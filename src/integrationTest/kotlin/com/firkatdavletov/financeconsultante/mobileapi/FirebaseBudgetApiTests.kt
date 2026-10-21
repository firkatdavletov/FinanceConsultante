package com.firkatdavletov.financeconsultante.mobileapi

import com.firkatdavletov.financeconsultante.PostgresIntegrationTest
import com.firkatdavletov.financeconsultante.auth.application.InvalidFirebaseToken
import com.firkatdavletov.financeconsultante.auth.application.VerifiedFirebaseIdentity
import com.firkatdavletov.financeconsultante.auth.infrastructure.FirebaseAdminTokenVerifier
import com.firkatdavletov.financeconsultante.mobileapi.application.CloudBudgetCommand
import com.firkatdavletov.financeconsultante.mobileapi.application.CloudBudgetService
import com.firkatdavletov.financeconsultante.mobileapi.application.CloudSeedCategory
import com.firkatdavletov.financeconsultante.mobileapi.application.CreateCloudBudget
import com.firkatdavletov.financeconsultante.mobileapi.application.FirebaseBudgets
import org.assertj.core.api.Assertions.assertThat
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
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@AutoConfigureMockMvc
@TestPropertySource(
    properties = [
        "app.mobile-api.enabled=true", "app.mobile-api.token=synthetic-t05-static-token-1234567890",
        "app.mobile-api.telegram-user-id=0",
        "app.firebase-auth.enabled=true",
        "app.firebase-auth.project-id=synthetic-project",
        "FIREBASE_LEGACY_UID=synthetic-t05-owner", "FIREBASE_LEGACY_MEMBER_ID=10000000-0000-0000-0000-000000000005",
    ],
)
@Suppress("MagicNumber", "LongMethod", "TooManyFunctions")
class FirebaseBudgetApiTests
    @Autowired
    constructor(
        private val cloud: CloudBudgetService,
        private val budgets: FirebaseBudgets,
        private val jdbc: JdbcTemplate,
        private val mvc: MockMvc,
    ) : PostgresIntegrationTest() {
        @MockitoBean private lateinit var verifier: FirebaseAdminTokenVerifier
        private val uid = "synthetic-t05-owner"
        private val otherUid = "synthetic-t05-other"
        private val member = UUID.fromString("10000000-0000-0000-0000-000000000005")
        private val households = mutableListOf<UUID>()
        private lateinit var request: CreateCloudBudget

        @BeforeEach fun prepare() {
            Mockito
                .`when`(
                    verifier.verify("synthetic-owner"),
                ).thenReturn(VerifiedFirebaseIdentity(uid, "Owner", "same@example.test"))
            Mockito
                .`when`(
                    verifier.verify("synthetic-other"),
                ).thenReturn(VerifiedFirebaseIdentity(otherUid, "Other", "same@example.test"))
            Mockito.`when`(verifier.verify("invalid")).thenThrow(InvalidFirebaseToken())
            request = create(member)
        }

        @AfterEach fun cleanup() {
            households.forEach { id ->
                jdbc.update("DELETE FROM firebase_budget_access WHERE household_id = ?", id)
                jdbc.update("DELETE FROM mobile_budget_access WHERE household_id = ?", id)
                listOf(
                    "recurring_expense_candidate_evidence",
                    "recurring_expense_candidate",
                    "savings_goal",
                    "expected_income_match",
                    "recurring_occurrence_payment",
                    "expected_income",
                    "recurring_occurrence",
                    "recurring_rule",
                    "entry_revision",
                    "ledger_entry",
                    "category",
                    "account",
                    "member",
                ).forEach { jdbc.update("DELETE FROM $it WHERE household_id = ?", id) }
                jdbc.update("DELETE FROM household WHERE id = ?", id)
            }
            jdbc.update("DELETE FROM firebase_user WHERE firebase_uid IN (?, ?)", uid, otherUid)
        }

        @Test fun `only explicitly configured verified UID receives the original member and budget`() {
            list("synthetic-other").andExpect(jsonPath("$.length()").value(0))
            list("synthetic-owner")
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$[0].id").value(request.householdId.toString()))
                .andExpect(jsonPath("$[0].memberId").value(member.toString()))
                .andExpect(jsonPath("$[0].role").value("OWNER"))
            list("synthetic-owner").andExpect(jsonPath("$.length()").value(1))
            assertThat(
                jdbc.queryForObject(
                    "SELECT COUNT(*) FROM firebase_budget_access WHERE member_id = ?",
                    Int::class.java,
                    member,
                ),
            ).isEqualTo(1)
            mvc
                .perform(
                    get("/api/v4/budgets/${request.householdId}").header("Authorization", "Bearer synthetic-other"),
                ).andExpect(status().isForbidden)
            mvc
                .perform(
                    get("/api/v4/budgets/${request.householdId}").header("Authorization", "Bearer synthetic-owner"),
                ).andExpect(status().isOk)
                .andExpect(jsonPath("$.scope.memberId").value(member.toString()))
        }

        @Test fun `scoped v4 command keeps UUID receipt and amount across lost response retry`() {
            list("synthetic-owner")
            val command =
                CloudBudgetCommand(
                    UUID.randomUUID(),
                    request.householdId,
                    member,
                    "CREATE_OPERATION",
                    UUID.randomUUID().toString(),
                    LocalDate.parse("2026-09-05"),
                    categoryId = request.categories[0].id,
                    amount = "12.34",
                    kind = "EXPENSE",
                    occurredOn = LocalDate.parse("2026-09-05"),
                )
            val first = budgets.command(uid, request.householdId, command)
            assertThat(budgets.command(uid, request.householdId, command)).isEqualTo(first)
            val body = """{"requestId":"${command.requestId}",
            "householdId":"${command.householdId}","memberId":"$member",
            "type":"CREATE_OPERATION","entityId":"${command.entityId}","authoredOn":"2026-09-05",
            "categoryId":"${command.categoryId}","amount":"12.34","kind":"EXPENSE","occurredOn":"2026-09-05"}"""
            mvc
                .perform(
                    post(
                        "/api/v4/budgets/${request.householdId}/sync/commands",
                    ).header("Authorization", "Bearer synthetic-owner")
                        .contentType("application/json")
                        .content(body),
                ).andExpect(status().isOk)
                .andExpect(jsonPath("$.requestId").value(command.requestId.toString()))
            assertThat(
                jdbc.queryForObject(
                    "SELECT COUNT(*) FROM ledger_entry WHERE household_id = ?",
                    Int::class.java,
                    request.householdId,
                ),
            ).isEqualTo(1)
            assertThat(
                jdbc.queryForObject(
                    "SELECT amount_kzt FROM ledger_entry WHERE id = ?",
                    java.math.BigDecimal::class.java,
                    UUID.fromString(command.entityId),
                ),
            ).isEqualByComparingTo("12.34")
            mvc
                .perform(
                    get(
                        "/api/v4/budgets/${request.householdId}/sync/changes",
                    ).header("Authorization", "Bearer synthetic-owner"),
                ).andExpect(status().isOk)
                .andExpect(jsonPath("$.scope.householdId").value(request.householdId.toString()))
        }

        @Test fun `revoked member and wrong scope cannot exchange financial data`() {
            list("synthetic-owner")
            val body = """{"requestId":"${UUID.randomUUID()}","householdId":"${UUID.randomUUID()}","memberId":"$member",
            "type":"CREATE_OPERATION","entityId":"${UUID.randomUUID()}","authoredOn":"2026-09-05"}"""
            mvc
                .perform(
                    post(
                        "/api/v4/budgets/${request.householdId}/sync/commands",
                    ).header("Authorization", "Bearer synthetic-owner")
                        .contentType("application/json")
                        .content(body),
                ).andExpect(status().isConflict)
                .andExpect(jsonPath("$.code").value("scope_changed"))
            jdbc.update("UPDATE member SET role = 'MEMBER', revoked_at = CURRENT_TIMESTAMP WHERE id = ?", member)
            list("synthetic-owner").andExpect(jsonPath("$.length()").value(0))
            mvc
                .perform(
                    get(
                        "/api/v4/budgets/${request.householdId}/sync/changes",
                    ).header("Authorization", "Bearer synthetic-owner"),
                ).andExpect(status().isForbidden)
            assertThat(
                jdbc.queryForObject(
                    "SELECT COUNT(*) FROM ledger_entry WHERE household_id = ?",
                    Int::class.java,
                    request.householdId,
                ),
            ).isZero()
        }

        @Test fun `invalid token cannot list or create membership`() {
            mvc.perform(get("/api/v4/budgets")).andExpect(status().isUnauthorized)
            mvc
                .perform(
                    get("/api/v4/budgets").header("Authorization", "Bearer invalid"),
                ).andExpect(status().isUnauthorized)
            assertThat(
                jdbc.queryForObject(
                    "SELECT COUNT(*) FROM firebase_budget_access WHERE member_id = ?",
                    Int::class.java,
                    member,
                ),
            ).isZero()
        }

        @Test fun `one UID can list two distinct budgets and concurrent binding stays unique`() {
            list("synthetic-owner")
            val second = create(UUID.randomUUID())
            jdbc.update(
                "INSERT INTO firebase_budget_access(firebase_uid, household_id, member_id) VALUES (?, ?, ?)",
                uid,
                second.householdId,
                second.memberId,
            )
            val start = CountDownLatch(1)
            val pool = Executors.newFixedThreadPool(2)
            try {
                val futures =
                    (1..2).map {
                        pool.submit<List<UUID>> {
                            start.await()
                            budgets.list(uid).map { it.id }
                        }
                    }
                start.countDown()
                futures.forEach {
                    assertThat(
                        it.get(10, TimeUnit.SECONDS),
                    ).containsExactlyInAnyOrder(request.householdId, second.householdId)
                }
            } finally {
                pool.shutdownNow()
            }
            assertThat(
                jdbc.queryForObject(
                    "SELECT COUNT(*) FROM firebase_budget_access WHERE firebase_uid = ?",
                    Int::class.java,
                    uid,
                ),
            ).isEqualTo(2)
        }

        private fun list(token: String) =
            mvc.perform(get("/api/v4/budgets").header("Authorization", "Bearer $token")).andExpect(status().isOk)

        private fun create(id: UUID): CreateCloudBudget {
            val household = UUID.randomUUID()
            households += household
            val categories =
                listOf("GROCERIES", "HEALTH", "SALARY", "OTHER").map {
                    CloudSeedCategory(
                        UUID.randomUUID(),
                        it,
                        if (it == "SALARY") "INCOME" else "EXPENSE",
                        when (it) {
                            "GROCERIES" -> "FOOD"
                            "HEALTH" -> "HEALTH"
                            "SALARY" -> "INCOME"
                            else -> "OTHER"
                        },
                        it,
                    )
                }
            return CreateCloudBudget(
                UUID.randomUUID(),
                household,
                id,
                "100.00",
                LocalDate.parse("2026-09-01"),
                categories,
            ).also {
                cloud.create(
                    UUID
                        .randomUUID()
                        .toString()
                        .replace("-", "")
                        .repeat(2),
                    it,
                )
            }
        }
    }
