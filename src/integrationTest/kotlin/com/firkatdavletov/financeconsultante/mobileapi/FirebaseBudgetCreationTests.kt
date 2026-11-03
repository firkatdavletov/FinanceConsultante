package com.firkatdavletov.financeconsultante.mobileapi

import com.firkatdavletov.financeconsultante.PostgresIntegrationTest
import com.firkatdavletov.financeconsultante.auth.application.FirebaseAuthenticationService
import com.firkatdavletov.financeconsultante.auth.application.InvalidFirebaseToken
import com.firkatdavletov.financeconsultante.auth.application.VerifiedFirebaseIdentity
import com.firkatdavletov.financeconsultante.auth.infrastructure.FirebaseAdminTokenVerifier
import com.firkatdavletov.financeconsultante.mobileapi.application.CreateFirebaseBudget
import com.firkatdavletov.financeconsultante.mobileapi.application.FirebaseBudgetCreation
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.TestPropertySource
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@AutoConfigureMockMvc
@TestPropertySource(
    properties = [
        "app.mobile-api.enabled=true",
        "app.mobile-api.telegram-user-id=0", "app.firebase-auth.enabled=true",
        "app.firebase-auth.project-id=synthetic-project", "FIREBASE_LEGACY_UID=", "FIREBASE_LEGACY_MEMBER_ID=",
    ],
)
@Suppress("MagicNumber", "LongMethod", "TooManyFunctions", "LongParameterList")
class FirebaseBudgetCreationTests
    @Autowired
    constructor(
        private val creation: FirebaseBudgetCreation,
        private val management: com.firkatdavletov.financeconsultante.mobileapi.infrastructure.FirebaseBudgetManagement,
        private val auth: FirebaseAuthenticationService,
        private val jdbc: JdbcTemplate,
        private val mvc: MockMvc,
        private val transactions: PlatformTransactionManager,
    ) : PostgresIntegrationTest() {
        @MockitoBean private lateinit var verifier: FirebaseAdminTokenVerifier
        private val uid = "synthetic-t06-owner"
        private val otherUid = "synthetic-t06-other"
        private val request =
            CreateFirebaseBudget(UUID.randomUUID(), "Первый бюджет", "1234.50", LocalDate.parse("2026-09-05"))

        @BeforeEach fun prepare() {
            Mockito.`when`(verifier.verify("t06-owner")).thenReturn(VerifiedFirebaseIdentity(uid, "Owner", null))
            Mockito.`when`(verifier.verify("t06-other")).thenReturn(VerifiedFirebaseIdentity(otherUid, "Other", null))
            Mockito.`when`(verifier.verify("invalid")).thenThrow(InvalidFirebaseToken())
            Mockito.`when`(verifier.verify("synthetic-t06-static-token-1234567890")).thenThrow(InvalidFirebaseToken())
            auth.authenticate("Bearer t06-owner")
            auth.authenticate("Bearer t06-other")
        }

        @AfterEach fun cleanup() {
            val ids =
                jdbc.queryForList(
                    "SELECT household_id FROM firebase_budget_access WHERE firebase_uid IN (?, ?)",
                    UUID::class.java,
                    uid,
                    otherUid,
                )
            ids.forEach { id ->
                jdbc.update("DELETE FROM firebase_budget_creation WHERE household_id = ?", id)
                jdbc.update("DELETE FROM firebase_budget_access WHERE household_id = ?", id)
                listOf("entry_revision", "ledger_entry", "category", "account", "member").forEach {
                    jdbc.update("DELETE FROM $it WHERE household_id = ?", id)
                }
                jdbc.update("DELETE FROM household WHERE id = ?", id)
            }
            jdbc.update("DELETE FROM firebase_user WHERE firebase_uid IN (?, ?)", uid, otherUid)
        }

        @Test fun `rename retries never overwrite a newer name and reject changed payload`() {
            val budget = creation.create(uid, request)
            val first =
                com.firkatdavletov.financeconsultante.mobileapi.infrastructure.RenameFirebaseBudget(
                    UUID.randomUUID(),
                    "Первое имя",
                )
            management.rename(uid, budget.id, first)
            management.rename(uid, budget.id, first.copy(requestId = UUID.randomUUID(), name = "Второе имя"))
            assertThat(management.rename(uid, budget.id, first).name).isEqualTo("Второе имя")
            assertThatThrownBy { management.rename(uid, budget.id, first.copy(name = "Подмена")) }
                .hasMessageContaining("idempotency_conflict")
            assertThat(
                jdbc.queryForObject(
                    "SELECT opening_balance FROM account WHERE household_id=?",
                    BigDecimal::class.java,
                    budget.id,
                ),
            ).isEqualByComparingTo("1234.50")
        }

        @Test fun `delete is replayable preserves other budget and prevents creation replay`() {
            val budget = creation.create(uid, request)
            val other = creation.create(uid, request.copy(requestId = UUID.randomUUID(), name = "Другой"))
            val deletion = UUID.randomUUID()
            management.delete(uid, budget.id, deletion)
            management.delete(uid, budget.id, deletion)
            assertThat(
                jdbc.queryForObject("SELECT count(*) FROM household WHERE id=?", Int::class.java, budget.id),
            ).isZero()
            assertThat(
                jdbc.queryForObject("SELECT count(*) FROM account WHERE household_id=?", Int::class.java, other.id),
            ).isEqualTo(1)
            assertThat(
                jdbc.queryForObject("SELECT count(*) FROM firebase_user WHERE firebase_uid=?", Int::class.java, uid),
            ).isEqualTo(1)
            assertThatThrownBy { creation.create(uid, request) }.hasMessageContaining("budget_deleted")
        }

        @Test fun `concurrent deletion retries commit once and both return successfully`() {
            val budget = creation.create(uid, request)
            val requestId = UUID.randomUUID()
            val ready = CountDownLatch(2)
            val start = CountDownLatch(1)
            val pool = Executors.newFixedThreadPool(2)
            try {
                val calls =
                    (1..2).map {
                        pool.submit {
                            ready.countDown()
                            check(start.await(10, TimeUnit.SECONDS))
                            management.delete(uid, budget.id, requestId)
                        }
                    }
                check(ready.await(10, TimeUnit.SECONDS))
                start.countDown()
                calls.forEach { it.get(10, TimeUnit.SECONDS) }
                assertThat(
                    jdbc.queryForObject(
                        "SELECT count(*) FROM firebase_budget_management WHERE request_id=?",
                        Int::class.java,
                        requestId,
                    ),
                ).isEqualTo(1)
                assertThat(
                    jdbc.queryForObject("SELECT count(*) FROM household WHERE id=?", Int::class.java, budget.id),
                ).isZero()
            } finally {
                start.countDown()
                pool.shutdownNow()
            }
        }

        @Test fun `HTTP management rejects foreign identity and validates name`() {
            val budget = creation.create(uid, request)
            mvc
                .perform(
                    org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .patch(
                            "/api/v4/budgets/${budget.id}",
                        ).header("Authorization", "Bearer t06-other")
                        .contentType("application/json")
                        .content("""{"requestId":"${UUID.randomUUID()}","name":"Чужой"}"""),
                ).andExpect(status().isForbidden)
            mvc
                .perform(
                    org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .patch(
                            "/api/v4/budgets/${budget.id}",
                        ).header("Authorization", "Bearer t06-owner")
                        .contentType("application/json")
                        .content("""{"requestId":"${UUID.randomUUID()}","name":" "}"""),
                ).andExpect(status().isBadRequest)
            mvc
                .perform(
                    org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .delete(
                            "/api/v4/budgets/${budget.id}",
                        ).header("Authorization", "Bearer t06-other")
                        .param("requestId", UUID.randomUUID().toString()),
                ).andExpect(status().isForbidden)
        }

        @Test fun `first budget is usable after another login and HTTP retry returns the same owner budget`() {
            val first =
                post(request)
                    .andExpect(status().isOk)
                    .andExpect(header().string("Cache-Control", "no-store"))
                    .andExpect(jsonPath("$.name").value(request.name))
                    .andExpect(jsonPath("$.role").value("OWNER"))
                    .andReturn()
                    .response.contentAsString
            assertThat(
                post(request)
                    .andExpect(status().isOk)
                    .andReturn()
                    .response.contentAsString,
            ).isEqualTo(first)
            val id = budgetId()
            val member =
                jdbc.queryForObject(
                    "SELECT member_id FROM firebase_budget_access WHERE household_id = ?",
                    UUID::class.java,
                    id,
                )
            val category =
                jdbc.queryForObject(
                    "SELECT id FROM category WHERE household_id = ? AND dictionary_key = 'GROCERIES'",
                    UUID::class.java,
                    id,
                )
            assertThat(
                jdbc.queryForObject(
                    "SELECT opening_balance FROM account WHERE household_id = ?",
                    BigDecimal::class.java,
                    id,
                ),
            ).isEqualByComparingTo("1234.50")
            assertThat(
                jdbc.queryForObject("SELECT count(*) FROM category WHERE household_id = ?", Int::class.java, id),
            ).isEqualTo(4)
            assertThat(
                jdbc.queryForObject(
                    "SELECT count(*) FROM mobile_budget_access WHERE household_id = ?",
                    Int::class.java,
                    id,
                ),
            ).isZero()
            mvc
                .perform(
                    post("/api/v4/budgets/$id/sync/commands")
                        .header("Authorization", "Bearer t06-owner")
                        .contentType(
                            "application/json",
                        ).content(
                            """{"requestId":"${UUID.randomUUID()}","householdId":"$id","memberId":"$member",
                        "type":"CREATE_OPERATION","entityId":"${UUID.randomUUID()}",
                        "authoredOn":"2026-09-05","occurredOn":"2026-09-05","kind":"EXPENSE",
                        "categoryId":"$category","amount":"12.34"}""",
                        ),
                ).andExpect(status().isOk)
            auth.authenticate("Bearer t06-owner")
            mvc
                .perform(get("/api/v4/budgets").header("Authorization", "Bearer t06-owner"))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$[0].id").value(id.toString()))
            mvc
                .perform(get("/api/v4/budgets/$id/sync/changes").header("Authorization", "Bearer t06-owner"))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.scope.memberId").value(member.toString()))
                .andExpect(jsonPath("$.changes[?(@.operation.amountKzt == '12.34')]").isNotEmpty)
            mvc
                .perform(
                    get("/api/v4/budgets/$id").header("Authorization", "Bearer t06-other"),
                ).andExpect(status().isForbidden)
        }

        @Test fun `concurrent retries commit exactly one set of budget data`() {
            val start = CountDownLatch(1)
            val executor = Executors.newFixedThreadPool(4)
            try {
                val futures =
                    (1..4).map {
                        executor
                            .submit<com.firkatdavletov.financeconsultante.mobileapi.application.FirebaseBudgetSummary> {
                                check(start.await(10, TimeUnit.SECONDS))
                                creation.create(uid, request)
                            }
                    }
                start.countDown()
                val budgets = futures.map { it.get(20, TimeUnit.SECONDS) }
                assertThat(budgets.distinct()).hasSize(1)
                assertThat(
                    jdbc.queryForObject(
                        "SELECT count(*) FROM firebase_budget_creation WHERE firebase_uid = ?",
                        Int::class.java,
                        uid,
                    ),
                ).isEqualTo(1)
                assertThat(
                    jdbc.queryForObject(
                        "SELECT count(*) FROM member WHERE household_id = ?",
                        Int::class.java,
                        budgets.first().id,
                    ),
                ).isEqualTo(1)
            } finally {
                executor.shutdownNow()
            }
        }

        @Test fun `request ID is scoped to UID and cannot replace an accepted payload`() {
            val first = creation.create(uid, request)
            post(request.copy(openingBalance = "999.00"))
                .andExpect(status().isConflict)
                .andExpect(jsonPath("$.code").value("idempotency_conflict"))
            val other = creation.create(otherUid, request)
            val additional = creation.create(uid, request.copy(requestId = UUID.randomUUID(), name = "Второй бюджет"))
            assertThat(setOf(first.id, other.id, additional.id)).hasSize(3)
            assertThat(
                jdbc.queryForObject(
                    "SELECT opening_balance FROM account WHERE household_id = ?",
                    BigDecimal::class.java,
                    first.id,
                ),
            ).isEqualByComparingTo("1234.50")
        }

        @Test fun `failed transaction rolls back owner account categories membership and receipt together`() {
            val count = jdbc.queryForObject("SELECT count(*) FROM household", Int::class.java)
            assertThatThrownBy {
                TransactionTemplate(transactions).executeWithoutResult {
                    creation.create(uid, request)
                    error("Synthetic failure before commit")
                }
            }.isInstanceOf(IllegalStateException::class.java)
            assertThat(jdbc.queryForObject("SELECT count(*) FROM household", Int::class.java)).isEqualTo(count)
            assertThat(
                jdbc.queryForObject(
                    "SELECT count(*) FROM firebase_budget_access WHERE firebase_uid = ?",
                    Int::class.java,
                    uid,
                ),
            ).isZero()
            assertThat(
                jdbc.queryForObject(
                    "SELECT count(*) FROM firebase_budget_creation WHERE firebase_uid = ?",
                    Int::class.java,
                    uid,
                ),
            ).isZero()
            post(request).andExpect(status().isOk)
        }

        @Test fun `invalid input and tokens never create partial budgets`() {
            listOf(
                request.copy(name = " "),
                request.copy(name = "a".repeat(81)),
                request.copy(openingBalance = "1.001"),
                request.copy(openingBalance = "100000000000000000.00"),
                request.copy(accountingStartedOn = LocalDate.parse("2026-09-07")),
                request.copy(currency = "RUB"),
            ).forEach { post(it).andExpect(status().isBadRequest) }
            post(request, "invalid").andExpect(status().isUnauthorized)
            post(request, "synthetic-t06-static-token-1234567890").andExpect(status().isUnauthorized)
            assertThat(
                jdbc.queryForObject(
                    "SELECT count(*) FROM firebase_budget_access WHERE firebase_uid = ?",
                    Int::class.java,
                    uid,
                ),
            ).isZero()
            post(request.copy(openingBalance = "-12.34")).andExpect(status().isOk)
        }

        private fun budgetId() =
            jdbc.queryForObject(
                "SELECT household_id FROM firebase_budget_creation WHERE firebase_uid = ? AND request_id = ?",
                UUID::class.java,
                uid,
                request.requestId,
            )!!

        private fun post(
            value: CreateFirebaseBudget,
            token: String = "t06-owner",
        ) = mvc.perform(
            post("/api/v4/budgets")
                .header("Authorization", "Bearer $token")
                .contentType("application/json")
                .content(
                    """{"requestId":"${value.requestId}","name":"${value.name}",
                    "openingBalance":"${value.openingBalance}",
                    "accountingStartedOn":"${value.accountingStartedOn}","currency":"${value.currency}"}""",
                ),
        )
    }
