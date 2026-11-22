package com.firkatdavletov.financeconsultante.mobileapi

import com.firkatdavletov.financeconsultante.PostgresIntegrationTest
import com.firkatdavletov.financeconsultante.auth.application.FirebaseAuthenticationService
import com.firkatdavletov.financeconsultante.auth.application.VerifiedFirebaseIdentity
import com.firkatdavletov.financeconsultante.auth.infrastructure.FirebaseAdminTokenVerifier
import com.firkatdavletov.financeconsultante.mobileapi.application.CloudBudgetCommand
import com.firkatdavletov.financeconsultante.mobileapi.application.CloudBudgetFailure
import com.firkatdavletov.financeconsultante.mobileapi.application.CloudSeedCategory
import com.firkatdavletov.financeconsultante.mobileapi.application.CreateCloudBudget
import com.firkatdavletov.financeconsultante.mobileapi.application.CreateFirebaseBudget
import com.firkatdavletov.financeconsultante.mobileapi.application.FirebaseBudgetCreation
import com.firkatdavletov.financeconsultante.mobileapi.application.FirebaseBudgetSummary
import com.firkatdavletov.financeconsultante.mobileapi.application.FirebaseBudgets
import com.firkatdavletov.financeconsultante.mobileapi.application.ImportFirebaseBudget
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
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
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
class FirebaseBudgetImportTests
    @Autowired
    constructor(
        private val creation: FirebaseBudgetCreation,
        private val budgets: FirebaseBudgets,
        private val auth: FirebaseAuthenticationService,
        private val jdbc: JdbcTemplate,
        private val mvc: MockMvc,
        private val transactions: PlatformTransactionManager,
    ) : PostgresIntegrationTest() {
        @MockitoBean private lateinit var verifier: FirebaseAdminTokenVerifier
        private val uid = "synthetic-t07-owner"
        private val otherUid = "synthetic-t07-other"
        private val date = LocalDate.parse("2026-09-05")
        private val bootstrap =
            CreateCloudBudget(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                "100.00",
                date,
                listOf(
                    seed("Продукты", "EXPENSE", "FOOD", "GROCERIES"),
                    seed("Здоровье", "EXPENSE", "HEALTH", "HEALTH"),
                    seed("Зарплата", "INCOME", "INCOME", "SALARY"),
                    seed("Другое", "EXPENSE", "OTHER", "OTHER"),
                ),
            )
        private val request = ImportFirebaseBudget("Локальный бюджет", bootstrap)

        @BeforeEach fun prepare() {
            Mockito.`when`(verifier.verify("t07-owner")).thenReturn(VerifiedFirebaseIdentity(uid, "Owner", null))
            Mockito.`when`(verifier.verify("t07-other")).thenReturn(VerifiedFirebaseIdentity(otherUid, "Other", null))
            auth.authenticate("Bearer t07-owner")
            auth.authenticate("Bearer t07-other")
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

        @Test fun `import adds another budget preserving all UUIDs and original operation receipts`() {
            val original = creation.create(uid, CreateFirebaseBudget(UUID.randomUUID(), "Облачный", "900.00", date))
            val first =
                postImport(request)
                    .andExpect(status().isOk)
                    .andExpect(jsonPath("$.id").value(bootstrap.householdId.toString()))
                    .andExpect(jsonPath("$.memberId").value(bootstrap.memberId.toString()))
                    .andReturn()
                    .response.contentAsString
            assertThat(
                postImport(request)
                    .andExpect(status().isOk)
                    .andReturn()
                    .response.contentAsString,
            ).isEqualTo(first)
            assertThat(budgets.list(uid).map { it.id }).containsExactlyInAnyOrder(original.id, bootstrap.householdId)
            assertThat(
                jdbc.queryForList(
                    "SELECT id FROM category WHERE household_id = ?",
                    UUID::class.java,
                    bootstrap.householdId,
                ),
            ).containsExactlyInAnyOrderElementsOf(bootstrap.categories.map { it.id })
            val operation =
                CloudBudgetCommand(
                    UUID.randomUUID(),
                    bootstrap.householdId,
                    bootstrap.memberId,
                    "CREATE_OPERATION",
                    UUID.randomUUID().toString(),
                    date,
                    categoryId = bootstrap.categories[0].id,
                    amount = "12.34",
                    kind = "EXPENSE",
                    occurredOn = date,
                )
            val receipt = budgets.command(uid, bootstrap.householdId, operation)
            assertThat(budgets.command(uid, bootstrap.householdId, operation)).isEqualTo(receipt)
            val refundId = UUID.randomUUID()
            val refund =
                operation.copy(
                    requestId = UUID.randomUUID(),
                    type = "REFUND_OPERATION",
                    amount = "2.00",
                    refundId = refundId,
                )
            budgets.command(uid, bootstrap.householdId, refund)
            budgets.command(uid, bootstrap.householdId, refund)
            val income =
                operation.copy(
                    requestId = UUID.randomUUID(),
                    entityId = UUID.randomUUID().toString(),
                    categoryId = bootstrap.categories.single { it.dictionaryKey == "SALARY" }.id,
                    kind = "INCOME",
                    amount = "7.00",
                )
            budgets.command(uid, bootstrap.householdId, income)
            budgets.command(uid, bootstrap.householdId, income)
            assertThat(
                jdbc.queryForList(
                    "SELECT id FROM ledger_entry WHERE household_id = ?",
                    UUID::class.java,
                    bootstrap.householdId,
                ),
            ).containsExactlyInAnyOrder(UUID.fromString(operation.entityId), refundId, UUID.fromString(income.entityId))
            val net =
                jdbc.queryForObject(
                    "SELECT sum(CASE WHEN direction = 'EXPENSE' THEN -amount_kzt ELSE amount_kzt END) " +
                        "FROM ledger_entry WHERE household_id = ? AND status = 'POSTED'",
                    BigDecimal::class.java,
                    bootstrap.householdId,
                )
            assertThat(BigDecimal(bootstrap.openingBalance) + requireNotNull(net)).isEqualByComparingTo("96.66")
            val changes = budgets.changes(uid, bootstrap.householdId, null, 200)
            assertThat(changes.changes.mapNotNull { it.budgetSettings?.openingBalance }).contains("100.00")
            assertThat(
                jdbc.queryForObject(
                    "SELECT opening_balance FROM account WHERE household_id = ?",
                    BigDecimal::class.java,
                    original.id,
                ),
            ).isEqualByComparingTo("900.00")
            assertThat(budgets.list(otherUid)).isEmpty()
            postImport(
                request,
                "t07-other",
            ).andExpect(status().isConflict).andExpect(jsonPath("$.code").value("budget_identity_conflict"))
            assertThat(budgets.list(uid)).hasSize(2)
        }

        @Test fun `accepted import rejects changed payload and cross endpoint request ID reuse`() {
            creation.importLocal(uid, request)
            postImport(request.copy(name = "Изменённый"))
                .andExpect(status().isConflict)
                .andExpect(jsonPath("$.code").value("idempotency_conflict"))
            postImport(request.copy(budget = bootstrap.copy(openingBalance = "999.00"))).andExpect(status().isConflict)
            assertThatThrownBy {
                creation.create(
                    uid,
                    CreateFirebaseBudget(bootstrap.requestId, request.name, "100.00", date),
                )
            }.isInstanceOf(CloudBudgetFailure::class.java)
            assertThat(
                jdbc.queryForObject(
                    "SELECT opening_balance FROM account WHERE household_id = ?",
                    BigDecimal::class.java,
                    bootstrap.householdId,
                ),
            ).isEqualByComparingTo("100.00")
        }

        @Test fun `archive reserves imported budget identities and rejects import replay`() {
            val budget = creation.importLocal(uid, request)
            mvc
                .perform(
                    org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .delete("/api/v4/budgets/${budget.id}")
                        .header("Authorization", "Bearer t07-owner")
                        .param("requestId", UUID.randomUUID().toString()),
                ).andExpect(status().isNoContent)
            postImport(request)
                .andExpect(status().isConflict)
                .andExpect(jsonPath("$.code").value("budget_deleted"))
            postImport(request.copy(budget = bootstrap.copy(requestId = UUID.randomUUID())), "t07-other")
                .andExpect(status().isConflict)
                .andExpect(jsonPath("$.code").value("budget_identity_conflict"))
        }

        @Test fun `imported budget accepts pending category usage with the original scope`() {
            postImport(request).andExpect(status().isOk)
            val id = UUID.randomUUID()
            val categoryId = bootstrap.categories.first().id
            val event = """{"id":"$id","operationId":"$id","categoryId":"$categoryId","usedAtMillis":0}"""
            mvc
                .perform(
                    post("/api/v4/category-usage/events")
                        .queryParam("budgetId", bootstrap.householdId.toString())
                        .header("Authorization", "Bearer t07-owner")
                        .contentType("application/json")
                        .content("""{"events":[$event]}"""),
                ).andExpect(status().isOk)
                .andExpect(jsonPath("$.scopeKey").value("${bootstrap.householdId}:${bootstrap.memberId}"))
                .andExpect(jsonPath("$.acceptedIds[0]").value(id.toString()))
        }

        @Test fun `concurrent retries import one budget and competing UIDs cannot claim the same local IDs`() {
            val start = CountDownLatch(1)
            val executor = Executors.newFixedThreadPool(4)
            try {
                val futures =
                    listOf(uid, uid, uid, otherUid).map { actor ->
                        executor.submit<Result<FirebaseBudgetSummary>> {
                            check(start.await(10, TimeUnit.SECONDS))
                            runCatching { creation.importLocal(actor, request) }
                        }
                    }
                start.countDown()
                val results = futures.map { it.get(20, TimeUnit.SECONDS) }
                val accepted = results.mapNotNull { it.getOrNull() }
                assertThat(accepted.map { it.id }.distinct()).containsExactly(bootstrap.householdId)
                assertThat(results.mapNotNull { it.exceptionOrNull() }).allSatisfy {
                    assertThat(it).isInstanceOf(CloudBudgetFailure::class.java)
                    assertThat((it as CloudBudgetFailure).code).isEqualTo("budget_identity_conflict")
                }
                assertThat(
                    jdbc.queryForObject(
                        "SELECT count(*) FROM firebase_budget_access WHERE household_id = ?",
                        Int::class.java,
                        bootstrap.householdId,
                    ),
                ).isEqualTo(1)
                assertThat(
                    jdbc.queryForObject(
                        "SELECT count(*) FROM category WHERE household_id = ?",
                        Int::class.java,
                        bootstrap.householdId,
                    ),
                ).isEqualTo(4)
            } finally {
                executor.shutdownNow()
            }
        }

        @Test fun `rollback leaves no partial local IDs and permits retry with the same bootstrap`() {
            assertThatThrownBy {
                TransactionTemplate(transactions).executeWithoutResult {
                    creation.importLocal(uid, request)
                    error("Synthetic interruption before commit")
                }
            }.isInstanceOf(IllegalStateException::class.java)
            assertThat(
                jdbc.queryForObject(
                    "SELECT count(*) FROM household WHERE id = ?",
                    Int::class.java,
                    bootstrap.householdId,
                ),
            ).isZero()
            assertThat(
                jdbc.queryForObject("SELECT count(*) FROM member WHERE id = ?", Int::class.java, bootstrap.memberId),
            ).isZero()
            postImport(request).andExpect(status().isOk)
        }

        @Test fun `import preserves the local budget denomination and request identity`() {
            val withCurrency = request.copy(budget = bootstrap.copy(currency = "EUR"))
            val imported = creation.importLocal(uid, withCurrency)
            assertThat(imported.id).isEqualTo(bootstrap.householdId)
            assertThat(imported.currency).isEqualTo("EUR")
            assertThat(creation.importLocal(uid, withCurrency)).isEqualTo(imported)
        }

        @Test fun `invalid bootstrap never changes existing data or creates a partial budget`() {
            listOf(
                bootstrap.copy(categories = bootstrap.categories.drop(1)),
                bootstrap.copy(openingBalance = "1.001"),
                bootstrap.copy(currency = "GBP"),
                bootstrap.copy(accountingStartedOn = date.plusDays(2)),
                bootstrap.copy(categories = bootstrap.categories.map { it.copy(id = bootstrap.categories[0].id) }),
            ).forEach { invalid ->
                assertThatThrownBy { creation.importLocal(uid, request.copy(budget = invalid)) }
                    .isInstanceOf(IllegalArgumentException::class.java)
            }
            assertThat(
                jdbc.queryForObject(
                    "SELECT count(*) FROM household WHERE id = ?",
                    Int::class.java,
                    bootstrap.householdId,
                ),
            ).isZero()
            postImport(request).andExpect(status().isOk)
        }

        private fun seed(
            name: String,
            kind: String,
            icon: String,
            key: String,
        ) = CloudSeedCategory(UUID.randomUUID(), name, kind, icon, key)

        @Test fun `import preserves opaque category icon codes`() {
            val code = " custom.icon-v2 "
            val imported =
                request.copy(
                    budget = bootstrap.copy(categories = bootstrap.categories.map { it.copy(icon = code) }),
                )
            postImport(imported).andExpect(status().isOk)
            assertThat(
                jdbc.queryForList(
                    "SELECT icon_code FROM category WHERE household_id = ?",
                    String::class.java,
                    bootstrap.householdId,
                ),
            ).hasSize(4).allMatch { it == code }
        }

        private fun postImport(
            value: ImportFirebaseBudget,
            token: String = "t07-owner",
        ): org.springframework.test.web.servlet.ResultActions {
            val b = value.budget
            val categories =
                b.categories.joinToString(",") { c ->
                    """{"id":"${c.id}","name":"${c.name}","kind":"${c.kind}",
            "icon":"${c.icon}","dictionaryKey":"${c.dictionaryKey}"}"""
                }
            val currency = b.currency?.let { ", \"currency\": \"$it\"" }.orEmpty()
            val body = """{"name":"${value.name}","budget":{"requestId":"${b.requestId}",
            "householdId":"${b.householdId}","memberId":"${b.memberId}","openingBalance":"${b.openingBalance}",
            "accountingStartedOn":"${b.accountingStartedOn}","categories":[$categories]$currency}}"""
            return mvc.perform(
                post("/api/v4/budgets/import")
                    .header("Authorization", "Bearer $token")
                    .contentType("application/json")
                    .content(body),
            )
        }
    }
