package com.firkatdavletov.financeconsultante.mobileapi

import com.firkatdavletov.financeconsultante.PostgresIntegrationTest
import com.firkatdavletov.financeconsultante.mobileapi.application.CloudBudgetCommand
import com.firkatdavletov.financeconsultante.mobileapi.application.CloudBudgetFailure
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
                    "SELECT count(*) FROM expected_income_match " +
                        "WHERE expected_income_id = ? AND ledger_entry_id = ?",
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

        @Test fun `details replace atomically preserve plan links and replay without another revision`() {
            val request = request()
            service.create(tokenHash, request)
            for (kind in listOf("EXPENSE", "INCOME")) {
                val originalCategory = request.categories.first { it.kind == kind }.id
                val category = UUID.randomUUID()
                service.command(
                    tokenHash,
                    command(
                        request,
                        "CREATE_CATEGORY",
                        category,
                    ).copy(name = if (kind == "INCOME") "Новый доход" else "Новый расход", kind = kind, icon = "HOME"),
                )
                val plan = UUID.randomUUID()
                if (kind == "INCOME") {
                    service.command(
                        tokenHash,
                        command(
                            request,
                            "CREATE_INCOME",
                            plan,
                        ).copy(categoryId = originalCategory, amount = "100.00", dueOn = authoredOn),
                    )
                }
                val id = UUID.randomUUID()
                service.command(
                    tokenHash,
                    command(request, "CREATE_OPERATION", id).copy(
                        kind = kind,
                        categoryId = originalCategory,
                        amount = "100.00",
                        occurredOn = authoredOn,
                        description = "Synthetic original",
                    ),
                )
                val before = jdbc.queryForMap("SELECT * FROM ledger_entry WHERE id = ?", id)
                val cursor = service.changes(tokenHash, null, 200).nextCursor
                val description =
                    command(request, "UPDATE_OPERATION", id).copy(
                        amount = "100.00",
                        categoryId = originalCategory,
                        description = "  Покупка 2 шт  ",
                        replaceDetails = true,
                    )
                val receipt = service.command(tokenHash, description)
                assertThat(service.command(tokenHash, description)).isEqualTo(receipt)
                var row = jdbc.queryForMap("SELECT * FROM ledger_entry WHERE id = ?", id)
                assertThat(row["normalized_description"]).isEqualTo("покупка 2 шт")
                assertThat(row["original_amount"]).isEqualTo(before["original_amount"])
                val categoryOnly =
                    description.copy(
                        requestId = UUID.randomUUID(),
                        categoryId = category,
                        description = "покупка 2 шт",
                    )
                service.command(tokenHash, categoryOnly)
                row = jdbc.queryForMap("SELECT * FROM ledger_entry WHERE id = ?", id)
                assertThat(row["category_id"]).isEqualTo(category)
                assertThat(row["normalized_description"]).isEqualTo("покупка 2 шт")
                val clear = categoryOnly.copy(requestId = UUID.randomUUID(), amount = "150.25", description = null)
                service.command(tokenHash, clear)
                assertThat(service.command(tokenHash, clear).entityToken).isEqualTo(3)
                row = jdbc.queryForMap("SELECT * FROM ledger_entry WHERE id = ?", id)
                assertThat(row["normalized_description"]).isNull()
                assertThat(row["original_amount"] as java.math.BigDecimal).isEqualByComparingTo("150.25")
                for (field in listOf(
                    "id",
                    "direction",
                    "actor_id",
                    "occurred_at",
                    "created_at",
                    "source_key",
                    "original_currency",
                )) {
                    assertThat(row[field]).describedAs(field).isEqualTo(before[field])
                }
                assertThat(
                    jdbc.queryForObject(
                        "SELECT count(*) FROM entry_revision WHERE entry_id = ? AND reason = 'DETAILS_CHANGED'",
                        Long::class.java,
                        id,
                    ),
                ).isEqualTo(3)
                if (kind == "INCOME") {
                    assertThat(
                        jdbc.queryForObject(
                            "SELECT count(*) FROM expected_income_match " +
                                "WHERE expected_income_id = ? AND ledger_entry_id = ?",
                            Long::class.java,
                            plan,
                            id,
                        ),
                    ).isEqualTo(1)
                }
                val changed =
                    service.changes(tokenHash, cursor, 200).changes.mapNotNull { it.operation }.last {
                        it.id ==
                            id
                    }
                assertThat(changed.categoryId).isEqualTo(category)
                assertThat(changed.description).isNull()
                assertThat(changed.amountKzt).isEqualTo("150.25")
                assertThatThrownBy {
                    service.command(tokenHash, clear.copy(replaceDetails = false))
                }.isInstanceOf(CloudBudgetFailure::class.java)
            }
        }

        @Test fun `invalid metadata rejects the entire correction and refund sources stay unchanged`() {
            val request = request()
            service.create(tokenHash, request)
            val category = request.categories.first { it.kind == "EXPENSE" }.id
            val id = UUID.randomUUID()
            service.command(
                tokenHash,
                command(request, "CREATE_OPERATION", id).copy(
                    kind = "EXPENSE",
                    categoryId = category,
                    amount = "100.00",
                    occurredOn = authoredOn,
                    description = "original",
                ),
            )
            val archived = request.categories.first { it.dictionaryKey == "HEALTH" }.id
            jdbc.update("UPDATE category SET archived = true WHERE id = ?", archived)
            val foreign = request()
            service.create(tokenHash.reversed(), foreign)
            val base =
                command(
                    request,
                    "UPDATE_OPERATION",
                    id,
                ).copy(amount = "150.25", categoryId = category, replaceDetails = true)
            val invalid =
                listOf(
                    base.copy(description = "я".repeat(161)),
                    base.copy(categoryId = request.categories.single { it.kind == "INCOME" }.id),
                    base.copy(categoryId = archived),
                    base.copy(categoryId = UUID.randomUUID()),
                    base.copy(categoryId = foreign.categories.first().id),
                    base.copy(amount = "0"),
                    base.copy(amount = "1.001"),
                    base.copy(currency = "RUB"),
                )
            for (bad in invalid) {
                assertThatThrownBy {
                    service.command(tokenHash, bad.copy(requestId = UUID.randomUUID()))
                }.isInstanceOf(IllegalArgumentException::class.java)
                val row = jdbc.queryForMap("SELECT * FROM ledger_entry WHERE id = ?", id)
                assertThat(row["original_amount"] as java.math.BigDecimal).isEqualByComparingTo("100.00")
                assertThat(row["normalized_description"]).isEqualTo("original")
                assertThat(row["version"]).isEqualTo(0L)
            }
            val atLimit = base.copy(requestId = UUID.randomUUID(), amount = "100.00", description = "я".repeat(160))
            service.command(tokenHash, atLimit)
            service.command(
                tokenHash,
                command(request, "REFUND_OPERATION", id).copy(amount = "1.00", refundId = UUID.randomUUID()),
            )
            assertThatThrownBy {
                service.command(tokenHash, base.copy(requestId = UUID.randomUUID(), description = "changed"))
            }.isInstanceOf(IllegalArgumentException::class.java)
            assertThat(
                jdbc.queryForObject(
                    "SELECT normalized_description FROM ledger_entry WHERE id = ?",
                    String::class.java,
                    id,
                ),
            ).isEqualTo("я".repeat(160))
            jdbc.update("DELETE FROM mobile_budget_access WHERE token_hash = ?", tokenHash.reversed())
        }

        @Test fun `legacy receipt fingerprint remains unchanged without the replacement flag`() {
            val request = request()
            service.create(tokenHash, request)
            val category = request.categories.first().id
            val id = UUID.randomUUID()
            service.command(
                tokenHash,
                command(
                    request,
                    "CREATE_OPERATION",
                    id,
                ).copy(
                    kind = "EXPENSE",
                    categoryId = category,
                    amount = "100.00",
                    occurredOn = authoredOn,
                    description = "original",
                ),
            )
            val c = command(request, "UPDATE_OPERATION", id).copy(amount = "120.00")
            val receipt = service.command(tokenHash, c)
            val oldFields =
                listOf(
                    c.requestId,
                    c.householdId,
                    c.memberId,
                    c.type,
                    c.entityId,
                    c.authoredOn,
                    c.expectedToken,
                    c.categoryId,
                    c.name,
                    c.kind,
                    c.icon,
                    c.amount,
                    c.frequency,
                    c.scheduleValue,
                    c.startsOn,
                    c.dueOn,
                    c.ruleId,
                    c.occurredOn,
                    c.description,
                    c.refundId,
                    c.currency,
                )
            val oldHash =
                MessageDigest
                    .getInstance("SHA-256")
                    .digest(
                        oldFields
                            .joinToString("") {
                                if (it == null) "-1:" else "${it.toString().length}:$it"
                            }.toByteArray(),
                    ).joinToString("") { "%02x".format(it) }
            assertThat(
                jdbc.queryForObject(
                    "SELECT payload_hash FROM mobile_sync_command WHERE request_id = ?",
                    String::class.java,
                    c.requestId,
                ),
            ).isEqualTo(oldHash)
            assertThat(service.command(tokenHash, c)).isEqualTo(receipt)
            assertThat(
                jdbc.queryForObject(
                    "SELECT normalized_description FROM ledger_entry WHERE id = ?",
                    String::class.java,
                    id,
                ),
            ).isEqualTo("original")
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
