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

@Suppress("MagicNumber", "LongMethod")
class FullIncomeRuleEditingApiTests
    @Autowired
    constructor(
        cloud: CloudBudgetService,
        jdbc: JdbcTemplate,
        mvc: MockMvc,
    ) : RuleEditingApiTestSupport(cloud, jdbc, mvc) {
        @Test fun `standalone income is promoted with same ID and preserved paid occurrence through all frequencies`() {
            list("synthetic-owner")
            val id = UUID.randomUUID()
            val category = request.categories[2].id
            val fields =
                mapOf(
                    "categoryId" to category,
                    "amount" to "100.00",
                    "dueOn" to "2026-09-05",
                    "incomeName" to "  Synthetic salary  ",
                    "incomeFrequency" to "ONCE",
                    "incomeActive" to true,
                )
            send(command("CREATE_INCOME", id, "2026-09-01", fields)).andExpect(status().isOk)
            assertThat(record("EXPECTED_INCOME", id)["incomeName"].asString()).isEqualTo("Synthetic salary")
            val operation = UUID.randomUUID()
            send(
                command(
                    "CREATE_OPERATION",
                    operation,
                    fields =
                        mapOf(
                            "categoryId" to category,
                            "amount" to "40.00",
                            "kind" to "INCOME",
                            "occurredOn" to "2026-09-05",
                        ),
                ),
            ).andExpect(status().isOk)
            val before = jdbc.queryForMap("SELECT * FROM expected_income WHERE id=?", id)
            for (frequency in listOf("ONCE", "WEEKLY", "BIWEEKLY", "MONTHLY", "ONCE")) {
                val body =
                    command(
                        "UPDATE_INCOME_RULE",
                        id,
                        fields =
                            fields +
                                mapOf(
                                    "amount" to "200.00",
                                    "incomeName" to "New salary",
                                    "incomeFrequency" to frequency,
                                ),
                    )
                val receipt =
                    send(body)
                        .andExpect(status().isOk)
                        .andReturn()
                        .response.contentAsString
                assertThat(
                    send(body)
                        .andExpect(status().isOk)
                        .andReturn()
                        .response.contentAsString,
                ).isEqualTo(receipt)
                val original = jdbc.queryForMap("SELECT * FROM expected_income WHERE id=?", id)
                assertThat(original).containsAllEntriesOf(before.filterKeys { it !in setOf("rule_id", "scheduled_on") })
                assertThat(original["rule_id"]).isEqualTo(id)
                val rule = record("INCOME_RULE", id)
                assertThat(rule["incomeFrequency"].asString()).isEqualTo(frequency)
                assertThat(rule["incomeName"].asString()).isEqualTo("New salary")
                assertThat(rule["incomeActive"].asBoolean()).isTrue()
            }
            assertThat(
                jdbc.queryForObject(
                    "SELECT count(*) FROM operation_plan_links WHERE expected_income_id=? AND ledger_id=?",
                    Long::class.java,
                    id,
                    operation,
                ),
            ).isEqualTo(1)
            assertThat(
                jdbc.queryForObject(
                    "SELECT count(*) FROM ledger_entry WHERE household_id=?",
                    Long::class.java,
                    request.householdId,
                ),
            ).isEqualTo(1)
        }

        @Test fun `income rules reconcile future dates clamp month end and preserve linked future payment`() {
            list("synthetic-owner")
            val rule = UUID.randomUUID()
            val category = request.categories[2].id
            val fields =
                mapOf(
                    "categoryId" to category,
                    "amount" to "100.00",
                    "dueOn" to "2026-09-30",
                    "incomeName" to "Synthetic income",
                    "incomeFrequency" to "MONTHLY",
                    "incomeActive" to true,
                )
            send(command("CREATE_INCOME_RULE", rule, fields = fields)).andExpect(status().isOk)
            val original = incomeOccurrence(rule, "2026-09-30")
            val operation = UUID.randomUUID()
            send(
                command(
                    "CREATE_OPERATION",
                    operation,
                    fields =
                        mapOf(
                            "categoryId" to category,
                            "amount" to "40.00",
                            "kind" to "INCOME",
                            "occurredOn" to "2026-09-05",
                        ),
                ),
            ).andExpect(status().isOk)
            val protected = snapshot("expected_income", original)
            val october = incomeOccurrence(rule, "2026-10-30")
            send(
                command(
                    "UPDATE_INCOME_RULE",
                    rule,
                    fields =
                        fields + mapOf("amount" to "200.00", "incomeName" to "Updated"),
                ),
            ).andExpect(status().isOk)
            assertThat(snapshot("expected_income", original)).isEqualTo(protected)
            assertThat(incomeOccurrence(rule, "2026-10-30")).isEqualTo(october)
            assertThat(
                record("EXPECTED_INCOME", october)["amount"].asString().toBigDecimal(),
            ).isEqualByComparingTo("200")
            val change = fields + mapOf("dueOn" to "2026-10-31", "amount" to "300.00")
            send(command("UPDATE_INCOME_RULE", rule, fields = change)).andExpect(status().isOk)
            val newOctober = incomeOccurrence(rule, "2026-10-31")
            assertThat(snapshot("expected_income", original)).isEqualTo(protected)
            assertThat(record("EXPECTED_INCOME", october)["status"].asString()).isEqualTo("CANCELLED")
            val target = UUID.randomUUID()
            send(
                command(
                    "CREATE_OPERATION",
                    target,
                    fields =
                        mapOf(
                            "categoryId" to category,
                            "amount" to "10.00",
                            "kind" to "INCOME",
                            "occurredOn" to "2026-09-05",
                            "planMatchingMode" to "CONFIRM_REQUIRED",
                        ),
                ),
            ).andExpect(status().isOk)
            send(
                command(
                    "SET_OPERATION_PLAN_LINK",
                    target,
                    fields =
                        mapOf(
                            "expectedToken" to 0,
                            "expectedPlanVersion" to 0,
                            "planTarget" to mapOf("type" to "INCOME", "ruleId" to rule, "dueOn" to "2027-02-28"),
                        ),
                ),
            ).andExpect(status().isOk)
            val february = incomeOccurrence(rule, "2027-02-28")
            assertThat(record("EXPECTED_INCOME", february)["scheduledOn"].asString()).isEqualTo("2027-02-28")
            send(
                command("UPDATE_INCOME_RULE", rule, fields = change + ("incomeActive" to false)),
            ).andExpect(status().isOk)
            assertThat(record("INCOME_RULE", rule)["incomeActive"].asBoolean()).isFalse()
            assertThat(record("EXPECTED_INCOME", february)["status"].asString()).isEqualTo("SCHEDULED")
            send(command("UPDATE_INCOME_RULE", rule, fields = change + ("incomeActive" to true)))
                .andExpect(status().isOk)
            assertThat(incomeOccurrence(rule, "2026-10-31")).isEqualTo(newOctober)
            assertThat(record("EXPECTED_INCOME", newOctober)["status"].asString()).isEqualTo("SCHEDULED")
            for (invalid in listOf(
                change + ("incomeName" to " "),
                change + ("incomeFrequency" to "DAILY"),
                change + ("categoryId" to request.categories[0].id),
                change + ("dueOn" to "2026-09-04"),
            )) {
                send(command("UPDATE_INCOME_RULE", rule, fields = invalid)).andExpect(status().isBadRequest)
            }
        }
    }
