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
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
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
abstract class RuleEditingApiTestSupport
    @Autowired
    constructor(
        protected val cloud: CloudBudgetService,
        protected val jdbc: JdbcTemplate,
        protected val mvc: MockMvc,
    ) : PostgresIntegrationTest() {
        @MockitoBean protected lateinit var verifier: FirebaseAdminTokenVerifier
        protected val uid = "synthetic-t05-owner"
        protected val member = UUID.fromString("10000000-0000-0000-0000-000000000005")
        protected val households = mutableListOf<UUID>()
        protected lateinit var request: CreateCloudBudget

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
                    "income_rule",
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

        protected val mapper = JsonMapper.builder().build()

        protected fun command(
            type: String,
            id: UUID,
            authoredOn: String = "2026-09-06",
            fields: Map<String, Any?> = emptyMap(),
        ): String =
            mapper.writeValueAsString(
                mapOf(
                    "requestId" to UUID.randomUUID(),
                    "householdId" to request.householdId,
                    "memberId" to member,
                    "type" to type,
                    "entityId" to id,
                    "authoredOn" to authoredOn,
                ) + fields,
            )

        protected fun send(
            body: String,
            capabilities: String? = null,
        ) = mvc.perform(
            post("/api/v4/budgets/${request.householdId}/sync/commands")
                .header("Authorization", "Bearer synthetic-owner")
                .header(
                    "X-Fillin-Capabilities",
                    capabilities ?: if (body.contains("\"CREATE_OPERATION\"") &&
                        !body.contains("CONFIRM_REQUIRED")
                    ) {
                        ""
                    } else {
                        "confirmedPlanLinkingV1"
                    },
                ).contentType("application/json")
                .content(body),
        )

        protected fun record(
            kind: String,
            id: UUID,
            capabilities: String = "confirmedPlanLinkingV1",
        ): JsonNode {
            var cursor: String? = null
            var result: JsonNode? = null
            do {
                val response =
                    mvc
                        .perform(
                            get("/api/v4/budgets/${request.householdId}/sync/changes")
                                .header(
                                    "Authorization",
                                    "Bearer synthetic-owner",
                                ).header("X-Fillin-Capabilities", capabilities)
                                .apply { if (cursor != null) param("cursor", requireNotNull(cursor)) }
                                .param("limit", "200"),
                        ).andExpect(status().isOk)
                        .andReturn()
                        .response.contentAsString
                val page = mapper.readTree(response)
                page["changes"]
                    .filter { it["kind"].asString() == kind && it["record"]["id"].asString() == id.toString() }
                    .lastOrNull()
                    ?.let { result = it["record"] }
                cursor = page["nextCursor"].asString()
            } while (page["hasMore"].asBoolean())
            return requireNotNull(result)
        }

        protected fun expenseOccurrence(
            rule: UUID,
            date: String,
        ): UUID =
            requireNotNull(
                jdbc.queryForObject(
                    "SELECT id FROM recurring_occurrence WHERE rule_id=? AND due_on=?::date",
                    UUID::class.java,
                    rule,
                    date,
                ),
            )

        protected fun incomeOccurrence(
            rule: UUID,
            date: String,
        ): UUID =
            requireNotNull(
                jdbc.queryForObject(
                    "SELECT id FROM expected_income WHERE rule_id=? AND scheduled_on=?::date",
                    UUID::class.java,
                    rule,
                    date,
                ),
            )

        protected fun snapshot(
            table: String,
            id: UUID,
        ): String =
            requireNotNull(
                jdbc.queryForObject("SELECT to_jsonb(r)::text FROM $table r WHERE id=?", String::class.java, id),
            )

        protected fun list(token: String) =
            mvc.perform(get("/api/v4/budgets").header("Authorization", "Bearer $token")).andExpect(status().isOk)

        protected fun create(id: UUID): CreateCloudBudget {
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
