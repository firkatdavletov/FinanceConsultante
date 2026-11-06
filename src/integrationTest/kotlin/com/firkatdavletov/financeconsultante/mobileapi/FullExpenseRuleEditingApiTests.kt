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
class FullExpenseRuleEditingApiTests
    @Autowired
    constructor(
        cloud: CloudBudgetService,
        jdbc: JdbcTemplate,
        mvc: MockMvc,
    ) : RuleEditingApiTestSupport(cloud, jdbc, mvc) {
        @Autowired private lateinit var transactionManager: org.springframework.transaction.PlatformTransactionManager

        @Autowired private lateinit var recurring:
            com.firkatdavletov.financeconsultante.recurring.application.RecurringPlanService

        @Test fun `Android creates named expense rules with replaceDetails and can replay the commands`() {
            list("synthetic-owner")
            val schedules = mapOf("MONTHLY" to 19, "WEEKLY" to 6, "ONCE" to null)
            for ((frequency, scheduleValue) in schedules) {
                val rule = UUID.randomUUID()
                val body =
                    command(
                        "CREATE_RULE",
                        rule,
                        fields =
                            mapOf(
                                "categoryId" to request.categories[0].id,
                                "amount" to "125.00",
                                "frequency" to frequency,
                                "scheduleValue" to scheduleValue,
                                "startsOn" to "2026-09-19",
                                "ruleName" to "Synthetic expense",
                                "replaceDetails" to true,
                            ),
                    )
                val receipt =
                    send(body)
                        .andExpect(status().isOk)
                        .andReturn()
                        .response.contentAsString
                val definition = snapshot("recurring_rule", rule)
                val record = record("RECURRING_RULE", rule)
                assertThat(record["ruleName"].asString()).isEqualTo("Synthetic expense")
                assertThat(record["frequency"].asString()).isEqualTo(frequency)
                assertThat(record["scheduleValue"]?.takeUnless { it.isNull }?.asInt()).isEqualTo(scheduleValue)
                val occurrence = expenseOccurrence(rule, "2026-09-19")
                val datesSql = "SELECT due_on FROM recurring_occurrence WHERE rule_id=? ORDER BY due_on"
                val dates = jdbc.queryForList(datesSql, rule)
                if (frequency == "ONCE") assertThat(dates).hasSize(1)

                assertThat(
                    send(body)
                        .andExpect(status().isOk)
                        .andReturn()
                        .response.contentAsString,
                ).isEqualTo(receipt)
                send(body.replace("\"replaceDetails\":true", "\"replaceDetails\":false")).andExpect(status().isConflict)
                assertThat(snapshot("recurring_rule", rule)).isEqualTo(definition)
                assertThat(expenseOccurrence(rule, "2026-09-19")).isEqualTo(occurrence)
                assertThat(jdbc.queryForList(datesSql, rule)).isEqualTo(dates)
            }
            assertThat(jdbc.queryForList("SELECT id FROM recurring_rule WHERE household_id=?", request.householdId))
                .hasSize(schedules.size)
            assertThat(jdbc.queryForList("SELECT id FROM ledger_entry WHERE household_id=?", request.householdId))
                .isEmpty()
        }

        @Test fun `saving an unsynced monthly rule again creates it under the original rule ID`() {
            list("synthetic-owner")
            val rule = UUID.randomUUID()
            val body =
                command(
                    "UPDATE_RULE",
                    rule,
                    fields =
                        mapOf(
                            "expectedToken" to 0,
                            "categoryId" to request.categories[0].id,
                            "amount" to "125.00",
                            "frequency" to "MONTHLY",
                            "scheduleValue" to 19,
                            "startsOn" to "2026-09-19",
                            "ruleName" to "Synthetic expense",
                            "replaceDetails" to true,
                        ),
                )
            send(body).andExpect(status().isOk)
            assertThat(record("RECURRING_RULE", rule)["ruleName"].asString()).isEqualTo("Synthetic expense")
            expenseOccurrence(rule, "2026-09-19")
        }

        @Test fun `create rule compatibility still rejects invalid schedules and unsupported commands`() {
            list("synthetic-owner")
            val fields =
                mapOf(
                    "categoryId" to request.categories[0].id,
                    "amount" to "125.00",
                    "startsOn" to "2026-09-19",
                    "replaceDetails" to true,
                )
            for ((frequency, scheduleValue) in mapOf("MONTHLY" to 32, "WEEKLY" to 8, "ONCE" to 1)) {
                val body =
                    command(
                        "CREATE_RULE",
                        UUID.randomUUID(),
                        fields = fields + mapOf("frequency" to frequency, "scheduleValue" to scheduleValue),
                    )
                send(body).andExpect(status().isBadRequest)
            }
            send(
                command(
                    "CREATE_OPERATION",
                    UUID.randomUUID(),
                    fields = fields + mapOf("kind" to "EXPENSE", "occurredOn" to "2026-09-06"),
                ),
            ).andExpect(status().isBadRequest)
            assertThat(jdbc.queryForList("SELECT id FROM recurring_rule WHERE household_id=?", request.householdId))
                .isEmpty()
        }

        @Test fun `generation skips definitions being edited and resumes after their transaction commits`() {
            list("synthetic-owner")
            val expense = UUID.randomUUID()
            val income = UUID.randomUUID()
            send(
                command(
                    "CREATE_RULE",
                    expense,
                    fields =
                        mapOf(
                            "categoryId" to request.categories[0].id,
                            "amount" to "100",
                            "frequency" to "MONTHLY",
                            "scheduleValue" to 31,
                            "startsOn" to "2026-09-06",
                        ),
                ),
            ).andExpect(status().isOk)
            send(
                command(
                    "CREATE_INCOME_RULE",
                    income,
                    fields =
                        mapOf(
                            "categoryId" to request.categories[2].id,
                            "amount" to "100",
                            "incomeName" to "Synthetic",
                            "incomeFrequency" to "MONTHLY",
                            "incomeActive" to true,
                            "dueOn" to "2026-09-30",
                        ),
                ),
            ).andExpect(status().isOk)
            val actor =
                com.firkatdavletov.financeconsultante.mobileapi.application.CloudBudgetActor(
                    request.householdId,
                    member,
                    request.accountingStartedOn.atStartOfDay(java.time.ZoneId.of("Asia/Almaty")).toInstant(),
                    com.firkatdavletov.financeconsultante.access.domain.MemberRole.OWNER,
                )
            val horizon = java.time.LocalDate.parse("2027-02-28")
            val pool = Executors.newSingleThreadExecutor()
            try {
                org.springframework.transaction.support.TransactionTemplate(transactionManager).executeWithoutResult {
                    jdbc.queryForList("SELECT id FROM account WHERE household_id=? FOR UPDATE", request.householdId)
                    jdbc.queryForList("SELECT id FROM recurring_rule WHERE id=? FOR UPDATE", expense)
                    jdbc.queryForList("SELECT id FROM income_rule WHERE id=? FOR UPDATE", income)
                    pool.submit { recurring.ensureOccurrences(actor, horizon) }.get(5, TimeUnit.SECONDS)
                    assertThat(
                        jdbc.queryForObject(
                            "SELECT count(*) FROM recurring_occurrence WHERE rule_id=? AND due_on=?",
                            Long::class.java,
                            expense,
                            horizon,
                        ),
                    ).isZero()
                    assertThat(
                        jdbc.queryForObject(
                            "SELECT count(*) FROM expected_income WHERE rule_id=? AND due_on=?",
                            Long::class.java,
                            income,
                            horizon,
                        ),
                    ).isZero()
                }
                recurring.ensureOccurrences(actor, horizon)
                expenseOccurrence(expense, "2027-02-28")
                incomeOccurrence(income, "2027-02-28")
            } finally {
                pool.shutdownNow()
            }
        }

        @Test fun `full expense editing preserves history links and matching IDs with HTTP round trip`() {
            list("synthetic-owner")
            val rule = UUID.randomUUID()
            val create =
                command(
                    "CREATE_RULE",
                    rule,
                    "2026-09-01",
                    mapOf(
                        "categoryId" to request.categories[0].id,
                        "amount" to "100.00",
                        "frequency" to "WEEKLY",
                        "scheduleValue" to 6,
                        "startsOn" to "2026-09-01",
                        "ruleName" to "  Synthetic rent  ",
                    ),
                )
            send(create).andExpect(status().isOk)
            assertThat(record("RECURRING_RULE", rule)["ruleName"].asString()).isEqualTo("Synthetic rent")
            val partial = expenseOccurrence(rule, "2026-09-12")
            val paid = expenseOccurrence(rule, "2026-09-19")
            val closed = expenseOccurrence(rule, "2026-09-26")
            val full = expenseOccurrence(rule, "2026-10-17")
            for ((id, amount) in listOf(partial to "40.00", paid to "100.00", full to "100.00")) {
                val ledger = UUID.randomUUID()
                send(
                    command(
                        "CREATE_OPERATION",
                        ledger,
                        fields =
                            mapOf(
                                "categoryId" to request.categories[0].id,
                                "amount" to amount,
                                "kind" to "EXPENSE",
                                "occurredOn" to "2026-09-05",
                            ),
                    ),
                ).andExpect(status().isOk)
                jdbc.update(
                    "INSERT INTO operation_plan_links(household_id,ledger_id,occurrence_id,created_at) " +
                        "VALUES (?,?,?,CURRENT_TIMESTAMP)",
                    request.householdId,
                    ledger,
                    id,
                )
            }
            jdbc.update("UPDATE recurring_occurrence SET status='PAID' WHERE id=?", paid)
            jdbc.update("UPDATE recurring_occurrence SET status='CANCELLED' WHERE id=?", closed)
            val protected =
                listOf(expenseOccurrence(rule, "2026-09-05"), partial, paid, closed, full)
                    .associateWith { snapshot("recurring_occurrence", it) }
            val matching = expenseOccurrence(rule, "2026-10-03")
            val disappeared = expenseOccurrence(rule, "2026-10-10")
            val update =
                command(
                    "UPDATE_RULE",
                    rule,
                    fields =
                        mapOf(
                            "replaceDetails" to true,
                            "categoryId" to request.categories[1].id,
                            "amount" to "200.00",
                            "frequency" to "MONTHLY",
                            "scheduleValue" to 3,
                            "startsOn" to "2026-09-01",
                            "ruleName" to "  Synthetic new rent  ",
                        ),
                )
            val receipt =
                send(update)
                    .andExpect(status().isOk)
                    .andReturn()
                    .response.contentAsString
            assertThat(
                send(update)
                    .andExpect(status().isOk)
                    .andReturn()
                    .response.contentAsString,
            ).isEqualTo(receipt)
            protected.forEach { (id, before) -> assertThat(snapshot("recurring_occurrence", id)).isEqualTo(before) }
            assertThat(expenseOccurrence(rule, "2026-10-03")).isEqualTo(matching)
            assertThat(
                jdbc.queryForMap("SELECT planned_amount,category_id FROM recurring_occurrence WHERE id=?", matching),
            ).containsEntry("category_id", request.categories[1].id)
                .containsEntry("planned_amount", "200.00".toBigDecimal())
            assertThat(
                jdbc.queryForObject(
                    "SELECT status FROM recurring_occurrence WHERE id=?",
                    String::class.java,
                    disappeared,
                ),
            ).isEqualTo("CANCELLED")
            assertThat(
                jdbc.queryForObject(
                    "SELECT count(*) FROM recurring_occurrence WHERE rule_id=? AND due_on='2026-09-03'",
                    Long::class.java,
                    rule,
                ),
            ).isZero()
            expenseOccurrence(rule, "2026-11-03")
            val result = record("RECURRING_RULE", rule)
            assertThat(result["ruleName"].asString()).isEqualTo("Synthetic new rent")
            assertThat(result["categoryId"].asString()).isEqualTo(request.categories[1].id.toString())
            assertThat(result["frequency"].asString()).isEqualTo("MONTHLY")
            assertThat(result["scheduleValue"].asInt()).isEqualTo(3)
            assertThat(
                record("RECURRING_OCCURRENCE", matching)["amount"].asString().toBigDecimal(),
            ).isEqualByComparingTo("200")
            assertThat(
                jdbc.queryForObject(
                    "SELECT count(*) FROM operation_plan_links WHERE household_id=?",
                    Long::class.java,
                    request.householdId,
                ),
            ).isEqualTo(3)
            // A later read must not regenerate dates under the new definition before authoredOn.
            record("RECURRING_RULE", rule)
            assertThat(
                jdbc.queryForObject(
                    "SELECT count(*) FROM recurring_occurrence WHERE rule_id=? AND due_on='2026-09-03'",
                    Long::class.java,
                    rule,
                ),
            ).isZero()
        }

        @Test fun `legacy amount update ignores full snapshot and full replacement validates atomically`() {
            list("synthetic-owner")
            val rule = UUID.randomUUID()
            val fields =
                mapOf(
                    "categoryId" to request.categories[0].id,
                    "amount" to "100.00",
                    "frequency" to "MONTHLY",
                    "scheduleValue" to 31,
                    "startsOn" to "2026-09-01",
                )
            send(command("CREATE_RULE", rule, "2026-09-01", fields)).andExpect(status().isOk)
            val legacy = record("RECURRING_RULE", rule)
            assertThat(legacy["ruleName"] == null || legacy["ruleName"].isNull).isTrue()
            send(
                command(
                    "UPDATE_RULE",
                    rule,
                    fields =
                        fields +
                            mapOf(
                                "amount" to "150.00",
                                "categoryId" to request.categories[2].id,
                                "frequency" to "ONCE",
                                "scheduleValue" to 99,
                                "startsOn" to "2026-08-01",
                                "ruleName" to "Ignored",
                            ),
                ),
            ).andExpect(status().isOk)
            val after = snapshot("recurring_rule", rule)
            val monthEnd = expenseOccurrence(rule, "2026-09-30")
            val full = fields + mapOf("replaceDetails" to true, "ruleName" to "Edited")
            val invalid =
                listOf(
                    full + ("ruleName" to "   "),
                    full + ("ruleName" to "x".repeat(161)),
                    full + ("categoryId" to request.categories[2].id),
                    full + ("categoryId" to UUID.randomUUID()),
                    full + ("startsOn" to "2026-09-05"),
                    full + ("startsOn" to "2026-08-31"),
                    full + ("scheduleValue" to 32),
                    full + ("frequency" to "WEEKLY") + ("scheduleValue" to 8),
                    full + ("frequency" to "ONCE"),
                    full + ("amount" to "1.001"),
                )
            invalid.forEach { send(command("UPDATE_RULE", rule, fields = it)).andExpect(status().isBadRequest) }
            assertThat(snapshot("recurring_rule", rule)).isEqualTo(after)
            val result = record("RECURRING_RULE", rule)
            assertThat(result["frequency"].asString()).isEqualTo("MONTHLY")
            assertThat(result["scheduleValue"].asInt()).isEqualTo(31)
            assertThat(result["categoryId"].asString()).isEqualTo(request.categories[0].id.toString())
            val once =
                command(
                    "UPDATE_RULE",
                    rule,
                    fields =
                        mapOf(
                            "replaceDetails" to true,
                            "categoryId" to request.categories[0].id,
                            "amount" to "160.00",
                            "frequency" to "ONCE",
                            "startsOn" to "2026-09-08",
                        ),
                )
            send(once).andExpect(status().isOk)
            assertThat(record("RECURRING_RULE", rule)["frequency"].asString()).isEqualTo("ONCE")
            expenseOccurrence(rule, "2026-09-08")
            assertThat(send(once.replace("160.00", "170.00")).andReturn().response.status).isEqualTo(409)
            send(command("UPDATE_RULE", rule, fields = full + ("startsOn" to "2026-09-08")))
                .andExpect(status().isOk)
            assertThat(expenseOccurrence(rule, "2026-09-30")).isEqualTo(monthEnd)
            assertThat(record("RECURRING_OCCURRENCE", monthEnd)["status"].asString()).isEqualTo("SCHEDULED")
        }

        @Test fun `concurrent full edit delivery commits one definition receipt and date set`() {
            list("synthetic-owner")
            val id = UUID.randomUUID()
            send(
                command(
                    "CREATE_RULE",
                    id,
                    fields =
                        mapOf(
                            "categoryId" to request.categories[0].id,
                            "amount" to "100",
                            "frequency" to "ONCE",
                            "startsOn" to "2026-09-06",
                        ),
                ),
            ).andExpect(status().isOk)
            val body =
                command(
                    "UPDATE_RULE",
                    id,
                    fields =
                        mapOf(
                            "replaceDetails" to true,
                            "categoryId" to request.categories[0].id,
                            "amount" to "200",
                            "frequency" to "MONTHLY",
                            "scheduleValue" to 31,
                            "startsOn" to "2026-09-06",
                            "ruleName" to "Concurrent",
                        ),
                )
            val ready = CountDownLatch(2)
            val start = CountDownLatch(1)
            val pool = Executors.newFixedThreadPool(2)
            try {
                val futures =
                    (1..2).map {
                        pool.submit<String> {
                            ready.countDown()
                            check(start.await(10, TimeUnit.SECONDS))
                            send(body)
                                .andExpect(status().isOk)
                                .andReturn()
                                .response.contentAsString
                        }
                    }
                check(ready.await(10, TimeUnit.SECONDS))
                start.countDown()
                assertThat(futures.map { it.get(20, TimeUnit.SECONDS) }.distinct()).hasSize(1)
                assertThat(
                    jdbc.queryForObject("SELECT version FROM recurring_rule WHERE id=?", Long::class.java, id),
                ).isEqualTo(1)
                assertThat(
                    jdbc.queryForObject(
                        "SELECT count(*) FROM recurring_occurrence WHERE rule_id=? AND status='SCHEDULED'",
                        Long::class.java,
                        id,
                    ),
                ).isEqualTo(3)
            } finally {
                pool.shutdownNow()
            }
        }
    }
