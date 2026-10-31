package com.firkatdavletov.financeconsultante.mobileapi

import com.firkatdavletov.financeconsultante.PostgresIntegrationTest
import com.firkatdavletov.financeconsultante.access.domain.MemberRole
import com.firkatdavletov.financeconsultante.mobileapi.application.CloudBudgetActor
import com.firkatdavletov.financeconsultante.mobileapi.application.CloudBudgetCommand
import com.firkatdavletov.financeconsultante.mobileapi.application.CloudBudgetFailure
import com.firkatdavletov.financeconsultante.mobileapi.application.CloudBudgetService
import com.firkatdavletov.financeconsultante.mobileapi.application.CloudSeedCategory
import com.firkatdavletov.financeconsultante.mobileapi.application.CreateCloudBudget
import com.firkatdavletov.financeconsultante.mobileapi.application.PlanLinkTarget
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.TestPropertySource
import java.security.MessageDigest
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.sql.DataSource

@TestPropertySource(properties = ["app.mobile-api.enabled=true", "app.mobile-api.telegram-user-id=0"])
@Suppress("MagicNumber", "LongMethod")
class OperationPlanSyncTests
    @Autowired
    constructor(
        private val service: CloudBudgetService,
        private val jdbc: JdbcTemplate,
        private val dataSource: DataSource,
    ) : PostgresIntegrationTest() {
        private val households = mutableSetOf<UUID>()
        private val tokenHash =
            MessageDigest.getInstance("SHA-256").digest("synthetic-income-sync-token".toByteArray()).joinToString("") {
                "%02x".format(it)
            }
        private val authoredOn = LocalDate.parse("2026-09-05")

        @AfterEach fun cleanup() {
            jdbc.update("DELETE FROM mobile_budget_access WHERE token_hash IN (?, ?)", tokenHash, tokenHash.reversed())
            households.forEach { id ->
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
                ).forEach {
                    jdbc.update("DELETE FROM $it WHERE household_id = ?", id)
                }
                jdbc.update("DELETE FROM household WHERE id = ?", id)
            }
        }

        @Test fun `confirmed linking preserves receipt partial amounts and gates legacy requests`() {
            val request = request()
            val scope = service.create(tokenHash, request).scope
            val actor =
                CloudBudgetActor(
                    scope.householdId,
                    scope.memberId,
                    scope.accountingStartedAt,
                    MemberRole.OWNER,
                )
            val category = request.categories.single { it.kind == "INCOME" }.id
            val plan = UUID.randomUUID()
            val createPlan =
                command(
                    request,
                    "CREATE_INCOME",
                    plan,
                ).copy(categoryId = category, amount = "100.00", dueOn = authoredOn)
            val oldReceipt = service.command(tokenHash, createPlan)
            val operation = UUID.randomUUID()
            val create =
                command(
                    request,
                    "CREATE_OPERATION",
                    operation,
                ).copy(
                    categoryId = category,
                    amount = "40.00",
                    kind = "INCOME",
                    occurredOn = authoredOn,
                    planMatchingMode = "CONFIRM_REQUIRED",
                )
            val capability = "confirmedPlanLinkingV1"
            val receipt = service.command(actor, create, capability)
            assertThat(receipt.suggestedPlan!!.target.planId).isEqualTo(plan)
            assertThat(
                jdbc.queryForObject(
                    "SELECT count(*) FROM operation_plan_links WHERE household_id=?",
                    Long::class.java,
                    request.householdId,
                ),
            ).isZero()
            assertThat(service.command(actor, create)).isEqualTo(receipt)
            assertThat(service.command(actor, createPlan)).isEqualTo(oldReceipt)
            assertThatThrownBy { service.changes(actor, null, 200) }.hasMessage("client_upgrade_required")
            val link =
                command(
                    request,
                    "SET_OPERATION_PLAN_LINK",
                    operation,
                ).copy(
                    expectedToken = 0,
                    expectedPlanVersion = 0,
                    planTarget = requireNotNull(receipt.suggestedPlan).target,
                )
            val linked = service.command(actor, link, capability)
            assertThat(linked.entityToken).isEqualTo(1)
            assertThat(service.command(actor, link)).isEqualTo(linked)
            assertThat(
                jdbc.queryForObject(
                    "SELECT operation_plan_contribution(?,?)",
                    java.math.BigDecimal::class.java,
                    request.householdId,
                    operation,
                ),
            ).isEqualByComparingTo("40.00")
            assertThat(
                jdbc.queryForObject("SELECT version FROM expected_income WHERE id=?", Long::class.java, plan),
            ).isZero()
            val remove = command(request, "REMOVE_OPERATION_PLAN_LINK", operation).copy(expectedToken = 1)
            val removed = service.command(actor, remove, capability)
            assertThat(removed.entityToken).isEqualTo(2)
            assertThat(service.command(actor, remove)).isEqualTo(removed)
            assertThat(
                jdbc.queryForObject(
                    "SELECT count(*) FROM operation_plan_links WHERE household_id=?",
                    Long::class.java,
                    request.householdId,
                ),
            ).isZero()
            val page = service.changes(actor, null, 200, capability)
            assertThat(page.changes.filter { it.kind == "INCOME_MATCH" }).hasSize(2)
        }

        @Test fun `V29 preserves legacy links and receipts and refuses contradictory categories`() {
            val request = request()
            service.create(tokenHash, request)
            val category = request.categories.single { it.kind == "INCOME" }.id
            val other = UUID.randomUUID()
            service.command(
                tokenHash,
                command(
                    request,
                    "CREATE_CATEGORY",
                    other,
                ).copy(name = "Synthetic alternate", kind = "INCOME", icon = "INCOME"),
            )
            val plan = UUID.randomUUID()
            service.command(
                tokenHash,
                command(
                    request,
                    "CREATE_INCOME",
                    plan,
                ).copy(categoryId = category, amount = "100.00", dueOn = authoredOn),
            )
            val op = UUID.randomUUID()
            val original =
                command(
                    request,
                    "CREATE_OPERATION",
                    op,
                ).copy(categoryId = category, amount = "40.00", kind = "INCOME", occurredOn = authoredOn)
            service.command(tokenHash, original)
            val schema = "plan_migration_" + UUID.randomUUID().toString().replace("-", "")
            try {
                Flyway
                    .configure()
                    .dataSource(
                        dataSource,
                    ).schemas(schema)
                    .defaultSchema(schema)
                    .target("28")
                    .load()
                    .migrate()
                for (table in listOf(
                    "household",
                    "member",
                    "account",
                    "category",
                    "expected_income",
                    "ledger_entry",
                    "expected_income_match",
                    "mobile_sync_command",
                )) {
                    val columns =
                        jdbc
                            .queryForList(
                                "SELECT column_name FROM information_schema.columns " +
                                    "WHERE table_schema=? AND table_name=? " +
                                    "AND is_generated='NEVER' AND is_identity='NO' ORDER BY ordinal_position",
                                String::class.java,
                                schema,
                                table,
                            ).joinToString(",")
                    val key = if (table == "household") "id" else "household_id"
                    jdbc.update(
                        "INSERT INTO $schema.$table($columns) SELECT $columns FROM $table WHERE $key=?",
                        request.householdId,
                    )
                }
                jdbc.update("UPDATE $schema.expected_income SET category_id=? WHERE id=?", other, plan)
                val migration =
                    Flyway
                        .configure()
                        .dataSource(
                            dataSource,
                        ).schemas(schema)
                        .defaultSchema(schema)
                        .load()
                assertThatThrownBy { migration.migrate() }.hasStackTraceContaining("Invalid historical plan links")
                assertThat(
                    jdbc.queryForObject("SELECT count(*) FROM $schema.expected_income_match", Long::class.java),
                ).isEqualTo(1)
                jdbc.update("UPDATE $schema.expected_income SET category_id=? WHERE id=?", category, plan)
                migration.migrate()
                assertThat(
                    jdbc.queryForObject("SELECT ledger_id FROM $schema.operation_plan_links", UUID::class.java),
                ).isEqualTo(op)
                assertThat(
                    jdbc.queryForObject("SELECT count(*) FROM $schema.mobile_sync_command", Long::class.java),
                ).isEqualTo(3)
                assertThat(
                    jdbc.queryForObject(
                        "SELECT expected_income_id FROM $schema.operation_plan_links",
                        UUID::class.java,
                    ),
                ).isEqualTo(plan)
            } finally {
                jdbc.execute("DROP SCHEMA IF EXISTS $schema CASCADE")
            }
        }

        @Test fun `concurrent confirmations of one operation choose exactly one target`() {
            val request = request()
            val scope = service.create(tokenHash, request).scope
            val actor =
                CloudBudgetActor(
                    scope.householdId,
                    scope.memberId,
                    scope.accountingStartedAt,
                    MemberRole.OWNER,
                )
            val category = request.categories.single { it.kind == "INCOME" }.id
            val targets = List(2) { UUID.randomUUID() }
            targets.forEach {
                service.command(
                    actor,
                    command(
                        request,
                        "CREATE_INCOME",
                        it,
                    ).copy(categoryId = category, amount = "100.00", dueOn = authoredOn),
                )
            }
            val operation = UUID.randomUUID()
            val capability = "confirmedPlanLinkingV1"
            service.command(
                actor,
                command(
                    request,
                    "CREATE_OPERATION",
                    operation,
                ).copy(
                    categoryId = category,
                    amount = "40.00",
                    kind = "INCOME",
                    occurredOn = authoredOn,
                    planMatchingMode = "CONFIRM_REQUIRED",
                ),
                capability,
            )
            val barrier = CyclicBarrier(2)
            val executor =
                Executors
                    .newFixedThreadPool(2)
            try {
                val futures =
                    targets.map { target ->
                        executor.submit(
                            Callable {
                                barrier.await(10, TimeUnit.SECONDS)
                                runCatching {
                                    service.command(
                                        actor,
                                        command(
                                            request,
                                            "SET_OPERATION_PLAN_LINK",
                                            operation,
                                        ).copy(
                                            expectedToken = 0,
                                            expectedPlanVersion = 0,
                                            planTarget =
                                                com.firkatdavletov.financeconsultante.mobileapi.application
                                                    .PlanLinkTarget(
                                                        "INCOME",
                                                        target,
                                                        dueOn = authoredOn,
                                                    ),
                                        ),
                                        capability,
                                    )
                                }
                            },
                        )
                    }
                val results = futures.map { it.get(20, TimeUnit.SECONDS) }
                assertThat(results.count { it.isSuccess }).isEqualTo(1)
                assertThat(results.single { it.isFailure }.exceptionOrNull()).hasMessage("version_conflict")
                assertThat(
                    jdbc.queryForObject(
                        "SELECT count(*) FROM operation_plan_links WHERE household_id=?",
                        Long::class.java,
                        request.householdId,
                    ),
                ).isEqualTo(1)
            } finally {
                executor.shutdownNow()
            }
        }

        @Test fun `projected expense materializes with zero version and refunds restore its remainder`() {
            val request = request()
            val scope = service.create(tokenHash, request).scope
            val actor = CloudBudgetActor(scope.householdId, scope.memberId, scope.accountingStartedAt, MemberRole.OWNER)
            val category = request.categories.first().id
            val rule = UUID.randomUUID()
            service.command(
                actor,
                command(
                    request,
                    "CREATE_RULE",
                    rule,
                ).copy(
                    categoryId = category,
                    amount = "100.00",
                    frequency = "MONTHLY",
                    scheduleValue = 31,
                    startsOn = authoredOn,
                ),
            )
            val operation = UUID.randomUUID()
            val capability = "confirmedPlanLinkingV1"
            service.command(
                actor,
                command(
                    request,
                    "CREATE_OPERATION",
                    operation,
                ).copy(
                    categoryId = category,
                    amount = "40.00",
                    kind = "EXPENSE",
                    occurredOn = authoredOn,
                    planMatchingMode = "CONFIRM_REQUIRED",
                ),
                capability,
            )
            val due = LocalDate.parse("2030-02-28")
            val target = PlanLinkTarget("EXPENSE", ruleId = rule, dueOn = due)
            val link =
                command(
                    request,
                    "SET_OPERATION_PLAN_LINK",
                    operation,
                ).copy(expectedToken = 0, expectedPlanVersion = 0, planTarget = target)
            val linked = service.command(actor, link, capability)
            val occurrence =
                jdbc.queryForObject(
                    "SELECT occurrence_id FROM operation_plan_links WHERE ledger_id=?",
                    UUID::class.java,
                    operation,
                )
            assertThat(
                jdbc.queryForObject(
                    "SELECT version FROM recurring_occurrence WHERE id=?",
                    Long::class.java,
                    occurrence,
                ),
            ).isZero()
            service.command(
                actor,
                command(request, "REFUND_OPERATION", operation).copy(amount = "10.00", refundId = UUID.randomUUID()),
                capability,
            )
            assertThat(
                jdbc.queryForObject(
                    "SELECT operation_plan_contribution(?,?)",
                    java.math.BigDecimal::class.java,
                    request.householdId,
                    operation,
                ),
            ).isEqualByComparingTo("30.00")
            assertThat(service.command(actor, link)).isEqualTo(linked)
            val update = command(request, "UPDATE_RULE", rule).copy(amount = "120.00")
            service.command(actor, update, capability)
            // A linked definition stays stable; other projections were materialized before the rule changed.
            val different = target.copy(dueOn = LocalDate.parse("2030-03-31"))
            assertThatThrownBy {
                service.command(
                    actor,
                    link.copy(requestId = UUID.randomUUID(), expectedToken = 2, planTarget = different),
                    capability,
                )
            }.hasMessage("plan_changed")
            assertThat(
                jdbc.queryForObject(
                    "SELECT occurrence_id FROM operation_plan_links WHERE ledger_id=?",
                    UUID::class.java,
                    operation,
                ),
            ).isEqualTo(occurrence)
        }

        private fun request(): CreateCloudBudget {
            val id = UUID.randomUUID()
            households += id
            return CreateCloudBudget(
                UUID.randomUUID(),
                id,
                UUID.randomUUID(),
                "100.00",
                LocalDate.parse("2026-09-01"),
                listOf(
                    CloudSeedCategory(UUID.randomUUID(), "Продукты", "EXPENSE", "FOOD", "GROCERIES"),
                    CloudSeedCategory(UUID.randomUUID(), "Здоровье", "EXPENSE", "HEALTH", "HEALTH"),
                    CloudSeedCategory(UUID.randomUUID(), "Зарплата", "INCOME", "INCOME", "SALARY"),
                    CloudSeedCategory(UUID.randomUUID(), "Другое", "EXPENSE", "OTHER", "OTHER"),
                ),
            )
        }

        private fun command(
            request: CreateCloudBudget,
            type: String,
            id: UUID,
        ) = CloudBudgetCommand(
            UUID.randomUUID(),
            request.householdId,
            request.memberId,
            type,
            id.toString(),
            authoredOn,
        )
    }
