package com.firkatdavletov.financeconsultante.mobileapi

import com.firkatdavletov.financeconsultante.PostgresIntegrationTest
import com.firkatdavletov.financeconsultante.mobileapi.application.CloudBudgetCommand
import com.firkatdavletov.financeconsultante.mobileapi.application.CloudBudgetService
import com.firkatdavletov.financeconsultante.mobileapi.application.CloudSeedCategory
import com.firkatdavletov.financeconsultante.mobileapi.application.CreateCloudBudget
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.TestPropertySource
import java.security.MessageDigest
import java.time.LocalDate
import java.util.UUID

@TestPropertySource(properties = ["app.mobile-api.enabled=true", "app.mobile-api.telegram-user-id=0"])
@Suppress("MagicNumber", "LongMethod")
class IncomeOperationSyncTests
    @Autowired
    constructor(
        private val service: CloudBudgetService,
        private val jdbc: JdbcTemplate,
    ) : PostgresIntegrationTest() {
        private val households = mutableSetOf<UUID>()
        private val tokenHash =
            MessageDigest.getInstance("SHA-256").digest("synthetic-income-sync-token".toByteArray()).joinToString("") {
                "%02x".format(it)
            }
        private val authoredOn = LocalDate.parse("2026-09-05")

        @AfterEach fun cleanup() {
            jdbc.update("DELETE FROM mobile_budget_access WHERE token_hash = ?", tokenHash)
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

        @Test fun `income amount edit preserves plan metadata audit and replay`() {
            val request = request()
            service.create(tokenHash, request)
            val category = request.categories.single { it.kind == "INCOME" }.id
            val plan = UUID.randomUUID()
            service.command(
                tokenHash,
                command(
                    request,
                    "CREATE_INCOME",
                    plan,
                ).copy(categoryId = category, amount = "100.00", dueOn = authoredOn),
            )
            val id = UUID.randomUUID()
            service.command(
                tokenHash,
                command(request, "CREATE_OPERATION", id).copy(
                    categoryId = category,
                    kind = "INCOME",
                    amount = "100.00",
                    occurredOn = authoredOn,
                    description = "Synthetic advance",
                ),
            )
            val before = service.changes(tokenHash, null, 200)
            val update =
                command(
                    request,
                    "UPDATE_OPERATION",
                    id,
                ).copy(amount = "150.25", kind = "INCOME", expectedToken = 0)
            service.command(tokenHash, update.copy(requestId = UUID.randomUUID(), amount = "125.00"))
            val receipt = service.command(tokenHash, update)
            assertThat(service.command(tokenHash, update)).isEqualTo(receipt)
            assertThat(receipt.entityToken).isEqualTo(2)
            val entry = jdbc.queryForMap("SELECT * FROM ledger_entry WHERE id = ?", id)
            assertThat(entry["direction"]).isEqualTo("INCOME")
            assertThat(entry["category_id"]).isEqualTo(category)
            assertThat(entry["normalized_description"]).isEqualTo("synthetic advance")
            assertThat(entry["occurred_on"].toString()).isEqualTo(authoredOn.toString())
            assertThat(entry["original_amount"] as java.math.BigDecimal).isEqualByComparingTo("150.25")
            assertThat(entry["amount_kzt"] as java.math.BigDecimal).isEqualByComparingTo("150.25")
            assertThat(
                jdbc.queryForObject(
                    "SELECT count(*) FROM entry_revision WHERE entry_id = ? AND reason = 'AMOUNT_CHANGED'",
                    Long::class.java,
                    id,
                ),
            ).isEqualTo(2)
            assertThat(
                jdbc.queryForObject(
                    "SELECT count(*) FROM expected_income_match WHERE expected_income_id = ? AND ledger_entry_id = ?",
                    Long::class.java,
                    plan,
                    id,
                ),
            ).isEqualTo(1)
            assertThat(
                jdbc.queryForObject(
                    "SELECT count(*) FROM ledger_entry WHERE household_id = ?",
                    Long::class.java,
                    request.householdId,
                ),
            ).isEqualTo(1)
            val changed =
                service
                    .changes(
                        tokenHash,
                        before.nextCursor,
                        200,
                    ).changes
                    .mapNotNull { it.operation }
                    .last()
            assertThat(changed.type).isEqualTo("INCOME")
            assertThat(changed.description).isEqualTo("synthetic advance")
            assertThat(changed.originalAmount).isEqualTo("150.25")
            assertThat(changed.amountKzt).isEqualTo("150.25")
            assertThat(changed.amountKzt.toBigDecimal() - "100.00".toBigDecimal()).isEqualByComparingTo("50.25")
        }

        @Test fun `income updates restore missing and voided entries without changing direction`() {
            val request = request()
            service.create(tokenHash, request)
            val id = UUID.randomUUID()
            val restore =
                command(request, "UPDATE_OPERATION", id).copy(
                    categoryId = request.categories.single { it.kind == "INCOME" }.id,
                    kind = "INCOME",
                    occurredOn = authoredOn,
                    amount = "100.00",
                )
            val first = service.command(tokenHash, restore)
            assertThat(service.command(tokenHash, restore)).isEqualTo(first)
            jdbc.update("UPDATE ledger_entry SET status = 'VOIDED', version = version + 1 WHERE id = ?", id)
            service.command(tokenHash, restore.copy(requestId = UUID.randomUUID(), amount = "150.25", kind = "EXPENSE"))
            val entry = jdbc.queryForMap("SELECT direction, status, original_amount FROM ledger_entry WHERE id = ?", id)
            assertThat(entry["direction"]).isEqualTo("INCOME")
            assertThat(entry["status"]).isEqualTo("POSTED")
            assertThat(entry["original_amount"] as java.math.BigDecimal).isEqualByComparingTo("150.25")
            assertThat(
                jdbc.queryForObject(
                    "SELECT count(*) FROM entry_revision WHERE entry_id = ? AND reason = 'LOCAL_STATE_RESTORED'",
                    Long::class.java,
                    id,
                ),
            ).isEqualTo(1)
            val legacy =
                command(request, "UPDATE_OPERATION", UUID.randomUUID()).copy(
                    categoryId = request.categories.first().id,
                    amount = "10.00",
                    occurredOn = authoredOn,
                )
            service.command(tokenHash, legacy)
            assertThat(
                jdbc.queryForObject(
                    "SELECT direction FROM ledger_entry WHERE id = ?",
                    String::class.java,
                    UUID.fromString(legacy.entityId),
                ),
            ).isEqualTo("EXPENSE")
            assertThatThrownBy {
                service.command(
                    tokenHash,
                    legacy.copy(
                        requestId = UUID.randomUUID(),
                        entityId = UUID.randomUUID().toString(),
                        kind = "EXPENSE_REFUND",
                    ),
                )
            }.isInstanceOf(IllegalArgumentException::class.java)
        }

        @Test fun `income updates reject invalid amounts refunds and foreign scope atomically`() {
            val request = request()
            service.create(tokenHash, request)
            val id = UUID.randomUUID()
            service.command(
                tokenHash,
                command(request, "CREATE_OPERATION", id).copy(
                    categoryId = request.categories.single { it.kind == "INCOME" }.id,
                    kind = "INCOME",
                    amount = "100.00",
                    occurredOn = authoredOn,
                ),
            )
            for (amount in listOf("0", "-1", "1.001", "100000000000000000")) {
                assertThatThrownBy {
                    service.command(
                        tokenHash,
                        command(request, "UPDATE_OPERATION", id).copy(amount = amount),
                    )
                }.isInstanceOf(IllegalArgumentException::class.java)
            }
            assertThatThrownBy {
                service.command(
                    tokenHash,
                    command(request, "REFUND_OPERATION", id).copy(amount = "10.00", refundId = UUID.randomUUID()),
                )
            }.isInstanceOf(IllegalArgumentException::class.java)
            assertThatThrownBy {
                service.command(
                    tokenHash,
                    command(request, "UPDATE_OPERATION", id).copy(amount = "150.25", currency = "RUB"),
                )
            }.isInstanceOf(IllegalArgumentException::class.java)
            assertThatThrownBy {
                service.command(
                    tokenHash,
                    command(request, "UPDATE_OPERATION", id).copy(amount = "150.25", householdId = UUID.randomUUID()),
                )
            }.hasMessage("scope_changed")
            assertThat(
                jdbc.queryForObject(
                    "SELECT original_amount FROM ledger_entry WHERE id = ?",
                    java.math.BigDecimal::class.java,
                    id,
                ),
            ).isEqualByComparingTo("100.00")
            assertThat(
                jdbc.queryForObject("SELECT version FROM ledger_entry WHERE id = ?", Long::class.java, id),
            ).isZero()
            assertThat(
                jdbc.queryForObject(
                    "SELECT count(*) FROM ledger_entry WHERE household_id = ?",
                    Long::class.java,
                    request.householdId,
                ),
            ).isEqualTo(1)
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
