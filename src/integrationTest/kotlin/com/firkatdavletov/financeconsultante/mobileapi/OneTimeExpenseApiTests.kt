package com.firkatdavletov.financeconsultante.mobileapi

import com.firkatdavletov.financeconsultante.PostgresIntegrationTest
import com.firkatdavletov.financeconsultante.auth.application.VerifiedFirebaseIdentity
import com.firkatdavletov.financeconsultante.auth.infrastructure.FirebaseAdminTokenVerifier
import com.firkatdavletov.financeconsultante.mobileapi.application.CloudBudgetService
import com.firkatdavletov.financeconsultante.mobileapi.application.CloudSeedCategory
import com.firkatdavletov.financeconsultante.mobileapi.application.CreateCloudBudget
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
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@AutoConfigureMockMvc
@TestPropertySource(
    properties = [
        "app.mobile-api.enabled=true",
        "app.mobile-api.token=synthetic-retired-token-1234567890",
        "app.mobile-api.telegram-user-id=0",
        "app.firebase-auth.enabled=true",
        "app.firebase-auth.project-id=synthetic-project",
        "FIREBASE_LEGACY_UID=synthetic-t05-owner", "FIREBASE_LEGACY_MEMBER_ID=10000000-0000-0000-0000-000000000005",
    ],
)
@Suppress("MagicNumber", "LongMethod", "TooManyFunctions")
class OneTimeExpenseApiTests
    @Autowired
    constructor(
        private val cloud: CloudBudgetService,
        private val jdbc: JdbcTemplate,
        private val mvc: MockMvc,
    ) : PostgresIntegrationTest() {
        @MockitoBean private lateinit var verifier: FirebaseAdminTokenVerifier
        private val uid = "synthetic-t05-owner"
        private val member = UUID.fromString("10000000-0000-0000-0000-000000000005")
        private val households = mutableListOf<UUID>()
        private lateinit var request: CreateCloudBudget

        @BeforeEach fun prepare() {
            Mockito
                .`when`(
                    verifier.verify("synthetic-owner"),
                ).thenReturn(VerifiedFirebaseIdentity(uid, "Owner", "same@example.test"))
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
            jdbc.update("DELETE FROM firebase_user WHERE firebase_uid = ?", uid)
        }

        @Test fun `delayed one-time HTTP command accepts missing schedule and keeps its canonical occurrence`() {
            list("synthetic-owner")
            val rule = UUID.randomUUID()
            val body = oncePayload(UUID.randomUUID(), rule)
            val receipt =
                onceCommand(body)
                    .andExpect(status().isOk)
                    .andReturn()
                    .response.contentAsString
            val occurrence =
                jdbc.queryForObject(
                    "SELECT id FROM recurring_occurrence WHERE rule_id=?",
                    UUID::class.java,
                    rule,
                )
            assertThat(
                onceCommand(body)
                    .andExpect(status().isOk)
                    .andReturn()
                    .response.contentAsString,
            ).isEqualTo(receipt)
            val changes =
                mvc
                    .perform(
                        get("/api/v4/budgets/${request.householdId}/sync/changes")
                            .header("Authorization", "Bearer synthetic-owner"),
                    ).andExpect(status().isOk)
                    .andReturn()
                    .response.contentAsString
            val nodes =
                tools.jackson.databind.json.JsonMapper
                    .builder()
                    .build()
                    .readTree(changes)["changes"]
            val record = nodes.single { it["kind"].asString() == "RECURRING_RULE" }["record"]
            assertThat(record["id"].asString()).isEqualTo(rule.toString())
            assertThat(record["frequency"].asString()).isEqualTo("ONCE")
            assertThat(record["scheduleValue"] == null || record["scheduleValue"].isNull).isTrue()
            assertThat(record["startsOn"].asString()).isEqualTo("2026-09-05")
            val expense = nodes.single { it["kind"].asString() == "RECURRING_OCCURRENCE" }["record"]
            assertThat(expense["id"].asString()).isEqualTo(occurrence.toString())
            assertThat(expense["ruleId"].asString()).isEqualTo(rule.toString())
            assertThat(expense["dueOn"].asString()).isEqualTo("2026-09-05")
            val update =
                oncePayload(UUID.randomUUID(), rule)
                    .replace("CREATE_RULE", "UPDATE_RULE")
                    .replace("\"requestId\":", "\"expectedToken\":0,\"requestId\":")
                    .replace("100.00", "80.00")
                    .replace("\"authoredOn\":\"2026-09-04\"", "\"authoredOn\":\"2026-09-06\"")
            onceCommand(update).andExpect(status().isOk)
            onceCommand(body).andExpect(status().isOk)
            assertThat(
                jdbc.queryForObject(
                    "SELECT amount FROM recurring_rule WHERE id=?",
                    java.math.BigDecimal::class.java,
                    rule,
                ),
            ).isEqualByComparingTo("80.00")
            assertThat(
                jdbc.queryForObject(
                    "SELECT planned_amount FROM recurring_occurrence WHERE id=?",
                    java.math.BigDecimal::class.java,
                    occurrence,
                ),
            ).isEqualByComparingTo("100.00")
            assertThat(
                jdbc.queryForObject(
                    "SELECT count(*) FROM recurring_occurrence WHERE rule_id=?",
                    Long::class.java,
                    rule,
                ),
            ).isEqualTo(1)
        }

        @Test fun `one-time HTTP validates null schedule and creation dates`() {
            list("synthetic-owner")
            val body = oncePayload(UUID.randomUUID(), UUID.randomUUID(), ",\"scheduleValue\":null")
            onceCommand(body).andExpect(status().isOk)
            val invalid =
                listOf(
                    oncePayload(UUID.randomUUID(), UUID.randomUUID(), ",\"scheduleValue\":1"),
                    oncePayload(UUID.randomUUID(), UUID.randomUUID()).replace(
                        "\"startsOn\":\"2026-09-05\"",
                        "\"startsOn\":\"2026-09-03\"",
                    ),
                    oncePayload(UUID.randomUUID(), UUID.randomUUID()).replace("2026-09-05", "2026-08-31"),
                    oncePayload(UUID.randomUUID(), UUID.randomUUID()).replace("ONCE", "MONTHLY"),
                    oncePayload(
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        ",\"scheduleValue\":null",
                    ).replace("ONCE", "WEEKLY"),
                )
            invalid.forEach { onceCommand(it).andExpect(status().isBadRequest) }
            assertThat(
                jdbc.queryForObject(
                    "SELECT count(*) FROM recurring_rule WHERE household_id=?",
                    Long::class.java,
                    request.householdId,
                ),
            ).isEqualTo(1)
        }

        private fun oncePayload(
            requestId: UUID,
            rule: UUID,
            schedule: String = "",
        ) = """{"requestId":"$requestId","householdId":"${request.householdId}","memberId":"$member",
                "type":"CREATE_RULE","entityId":"$rule","authoredOn":"2026-09-04",
                "categoryId":"${request.categories[0].id}","amount":"100.00","frequency":"ONCE",
                "startsOn":"2026-09-05"$schedule}"""

        private fun onceCommand(body: String) =
            mvc.perform(
                post("/api/v4/budgets/${request.householdId}/sync/commands")
                    .header("Authorization", "Bearer synthetic-owner")
                    .contentType("application/json")
                    .content(body),
            )

        @Test fun `concurrent delivery of one-time command returns one receipt and occurrence`() {
            list("synthetic-owner")
            val rule = UUID.randomUUID()
            val body = oncePayload(UUID.randomUUID(), rule)
            val ready = CountDownLatch(2)
            val start = CountDownLatch(1)
            val pool = Executors.newFixedThreadPool(2)
            try {
                val futures =
                    (1..2).map {
                        pool.submit<String> {
                            ready.countDown()
                            check(start.await(10, TimeUnit.SECONDS))
                            onceCommand(body)
                                .andExpect(status().isOk)
                                .andReturn()
                                .response.contentAsString
                        }
                    }
                check(ready.await(10, TimeUnit.SECONDS))
                start.countDown()
                val receipts = futures.map { it.get(20, TimeUnit.SECONDS) }
                assertThat(receipts.distinct()).hasSize(1)
                assertThat(
                    jdbc.queryForObject(
                        "SELECT count(*) FROM recurring_occurrence WHERE rule_id=?",
                        Long::class.java,
                        rule,
                    ),
                ).isEqualTo(1)
            } finally {
                pool.shutdownNow()
            }
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
