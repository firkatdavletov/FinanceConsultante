package com.firkatdavletov.financeconsultante.telegram

import com.firkatdavletov.financeconsultante.PostgresIntegrationTest
import com.firkatdavletov.financeconsultante.ledger.AccountingFixtures
import com.firkatdavletov.financeconsultante.ledger.application.AccountingService
import com.firkatdavletov.financeconsultante.ledger.application.CorrectionResult
import com.firkatdavletov.financeconsultante.ledger.application.EntryCorrections
import com.firkatdavletov.financeconsultante.ledger.application.EntryTarget
import com.firkatdavletov.financeconsultante.ledger.application.LedgerOperations
import com.firkatdavletov.financeconsultante.ledger.domain.EntryChange
import com.firkatdavletov.financeconsultante.ledger.domain.LedgerEntry
import com.firkatdavletov.financeconsultante.shared.domain.Money
import com.firkatdavletov.financeconsultante.telegram.TelegramTestConfiguration.Companion.BOT
import com.firkatdavletov.financeconsultante.telegram.TelegramTestConfiguration.Companion.OWNER
import com.firkatdavletov.financeconsultante.telegram.application.IncomingKind
import com.firkatdavletov.financeconsultante.telegram.application.IncomingMessage
import com.firkatdavletov.financeconsultante.telegram.application.IncomingUpdate
import com.firkatdavletov.financeconsultante.telegram.application.OwnerAccess
import com.firkatdavletov.financeconsultante.telegram.application.PollingStore
import com.firkatdavletov.financeconsultante.telegram.application.SetupStore
import com.firkatdavletov.financeconsultante.telegram.application.TelegramInboxProcessor
import com.firkatdavletov.financeconsultante.telegram.domain.BudgetSettings
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.TestPropertySource
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.time.Clock
import java.time.LocalDate
import java.time.LocalTime
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@Import(TelegramTestConfiguration::class)
@TestPropertySource(properties = ["app.telegram.bot-token=123456:synthetic-token", "app.telegram.owner-id=555"])
@Suppress("MagicNumber", "TooManyFunctions", "LongParameterList")
class TelegramCorrectionsTests
    @Autowired
    constructor(
        private val polls: PollingStore,
        private val setup: SetupStore,
        private val processor: TelegramInboxProcessor,
        private val corrections: EntryCorrections,
        private val operations: LedgerOperations,
        private val accounting: AccountingService,
        private val jdbc: JdbcTemplate,
        private val clock: Clock,
        transactionManager: PlatformTransactionManager,
    ) : PostgresIntegrationTest() {
        private val tx = TransactionTemplate(transactionManager)
        private val access = OwnerAccess(OWNER)
        private var updateId = 0L

        @BeforeEach
        fun reset() {
            jdbc.execute(
                "TRUNCATE transaction_draft, telegram_outbox, telegram_inbox, telegram_polling_state, " +
                    "telegram_dialog RESTART IDENTITY",
            )
            val ids = jdbc.queryForList("SELECT household_id FROM member WHERE telegram_user_id = ?", OWNER)
            ids.forEach { row ->
                for (table in listOf(
                    "category_rule",
                    "entry_revision",
                    "ledger_entry",
                    "category",
                    "account",
                    "member",
                )) {
                    jdbc.update("DELETE FROM $table WHERE household_id = ?", row.getValue("household_id"))
                }
                jdbc.update("DELETE FROM household WHERE id = ?", row.getValue("household_id"))
            }
            tx.executeWithoutResult {
                setup.saveSettings(
                    OWNER,
                    BudgetSettings(
                        Money.of("0"),
                        LocalDate.parse("2026-09-01"),
                        LocalTime.of(8, 0),
                        Money.of("0"),
                    ),
                )
            }
            updateId = 0
        }

        @AfterEach
        fun removeRules() {
            jdbc.update(
                "DELETE FROM category_rule WHERE household_id = " +
                    "(SELECT household_id FROM member WHERE telegram_user_id = ?)",
                OWNER,
            )
        }

        @Test
        fun `amount date and category corrections retain one entry and complete audit`() {
            receive("аптека 1300 2026-09-02")
            val id = latest().id
            assertThat(buttons()).contains("amount:$id:0", "cat:$id:0", "date:$id:0", "void:$id:0")
            callback("amount:$id:0")
            assertThat(reply()).contains("/amount $id 0")
            receive("/amount $id 0 1000,01")
            receive("/date $id 1 2026-09-05")
            receive("/category_add expense транспорт")
            receive("/category $id 2 ${category("транспорт")}")
            val entry = latest()
            assertThat(entry.id).isEqualTo(id)
            assertThat(entry.version).isEqualTo(3)
            assertThat(entry.originalAmount).isEqualTo(Money.of("1000.01"))
            assertThat(
                entry.occurredAt.atZone(LedgerOperations.ZONE).toLocalDate(),
            ).isEqualTo(LocalDate.parse("2026-09-05"))
            assertThat(entry.sourceKey).isEqualTo("telegram:$BOT:inbox:1")
            assertThat(revisions(id)).isEqualTo(4)
            assertThat(
                jdbc.queryForObject(
                    "SELECT snapshot->>'original_amount' FROM entry_revision WHERE entry_id = ? AND version = 0",
                    String::class.java,
                    id,
                ),
            ).isEqualTo("1300.00")
            receive("/balance")
            assertThat(reply()).contains("-1000.01 KZT", "Доходы: 0.00")
            receive("/history")
            assertThat(reply()).contains("2026-09-05 расход, транспорт: 1000.01")
        }

        @Test
        fun `duplicate and stale cancellation never cancel a different entry`() {
            receive("аптека 1300")
            val first = latest().id
            receive("продукты 700")
            val second = latest().id
            callback("void:$first:0", query = "same-query")
            callback("void:$first:0", query = "same-query", processed = false)
            callback("void:$first:0")
            assertThat(reply()).contains("устарело")
            receive("/balance")
            assertThat(reply()).contains("-700.00 KZT")
            assertThat(revisions(first)).isEqualTo(2)
            assertThat(revisions(second)).isEqualTo(1)
            receive("/history")
            assertThat(reply()).contains("[отменена]")
        }

        @Test
        fun `partial refund lowers net expenses without increasing salary and repeated command is stale`() {
            receive("аптека 1300")
            val expense = latest().id
            receive("/refund $expense 0 300")
            val refund = latest()
            assertThat(refund.refundOf).isEqualTo(expense)
            receive("/refund $expense 0 300")
            assertThat(reply()).contains("устарело")
            receive("/balance")
            assertThat(reply()).contains("-1000.00 KZT", "Доходы: 0.00", "Возвраты: 300.00", "Чистые расходы: 1000.00")
            receive("/refund $expense 1 1001")
            assertThat(reply()).contains("превышает")
            receive("/amount ${refund.id} 0 1400")
            assertThat(reply()).contains("превышает")
            receive("/void $expense 1")
            assertThat(reply()).contains("Сначала отмените связанные возвраты")
            receive("/void ${refund.id} 0")
            receive("/void $expense 1")
            receive("/balance")
            assertThat(reply()).contains("Учётный баланс: 0.00", "Возвраты: 0.00")
        }

        @Test
        fun `concurrent corrections with separate transactions have one winner`() {
            receive("продукты 1300")
            val entry = latest()
            val target = target(entry)
            val barrier = CyclicBarrier(2)
            val executor = Executors.newFixedThreadPool(2)
            try {
                val futures =
                    executor.invokeAll(
                        listOf("100", "200").map { amount ->
                            Callable {
                                barrier.await(5, TimeUnit.SECONDS)
                                corrections.change(
                                    target,
                                    EntryChange.Amount(Money.of(amount)),
                                    "synthetic:correction:$amount",
                                )
                            }
                        },
                    )
                val results = futures.map { it.get(10, TimeUnit.SECONDS) }
                assertThat(results.count { it is CorrectionResult.Updated }).isEqualTo(1)
                assertThat(results.count { it is CorrectionResult.Rejected }).isEqualTo(1)
                assertThat(latest().originalAmount.amount.toPlainString()).isIn("100.00", "200.00")
                assertThat(revisions(entry.id)).isEqualTo(2)
            } finally {
                executor.shutdownNow()
            }
        }

        @Test
        fun `audit failure rolls back amount version inbox and reply then retry succeeds`() {
            receive("продукты 1300")
            val entry = latest()
            accept(update("/amount ${entry.id} 0 1000"))
            try {
                jdbc.execute(
                    "ALTER TABLE entry_revision ADD CONSTRAINT synthetic_revision_failure " +
                        "CHECK (reason <> 'AMOUNT_CHANGED') NOT VALID",
                )
                assertThatThrownBy { processor.processOne(BOT) }.isInstanceOf(RuntimeException::class.java)
                assertThat(latest().originalAmount).isEqualTo(Money.of("1300"))
                assertThat(latest().version).isZero()
                assertThat(revisions(entry.id)).isEqualTo(1)
                assertThat(jdbc.queryForObject("SELECT count(*) FROM telegram_outbox", Long::class.java)).isEqualTo(1)
            } finally {
                jdbc.execute("ALTER TABLE entry_revision DROP CONSTRAINT IF EXISTS synthetic_revision_failure")
            }
            assertThat(processor.processOne(BOT)).isTrue()
            assertThat(latest().originalAmount).isEqualTo(Money.of("1000"))
        }

        @Test
        fun `refund insert failure rolls back expense revision and financial source`() {
            receive("продукты 1300")
            val expense = latest()
            try {
                jdbc.execute(
                    "ALTER TABLE ledger_entry ADD CONSTRAINT synthetic_refund_failure " +
                        "CHECK (direction <> 'EXPENSE_REFUND') NOT VALID",
                )
                assertThatThrownBy {
                    corrections.refund(
                        target(expense),
                        Money.of("300"),
                        LocalDate.parse("2026-09-06"),
                        "synthetic:refund-failure",
                    )
                }.isInstanceOf(RuntimeException::class.java)
                assertThat(revisions(expense.id)).isEqualTo(1)
                assertThat(latest().version).isZero()
            } finally {
                jdbc.execute("ALTER TABLE ledger_entry DROP CONSTRAINT IF EXISTS synthetic_refund_failure")
            }
            receive("/refund ${expense.id} 0 300")
            assertThat(latest().refundOf).isEqualTo(expense.id)
        }

        @Test
        fun `foreign callback owner category and linked expense cannot cross household boundaries`() {
            val other = AccountingFixtures.budget()
            accounting.openBudget(other)
            val foreign = accounting.record(AccountingFixtures.command(other)).entry
            receive("продукты 50")
            val own = latest()
            callback("void:${foreign.id}:0")
            assertThat(reply()).contains("недоступна")
            callback("void:${own.id}:0", ownerId = 999, processed = false)
            receive("/category ${own.id} 0 ${foreign.categoryId}")
            assertThat(reply()).contains("недоступна")
            assertThat(
                corrections.change(
                    EntryTarget(other.household.id, target(own).memberId, foreign.id, 0),
                    EntryChange.Void,
                    "synthetic:foreign",
                ),
            ).isInstanceOf(CorrectionResult.Rejected::class.java)
            assertThatThrownBy {
                jdbc.update(
                    "UPDATE ledger_entry SET direction = 'EXPENSE_REFUND', refund_of = ? WHERE id = ?",
                    foreign.id,
                    own.id,
                )
            }.isInstanceOf(org.springframework.dao.DataIntegrityViolationException::class.java)
            assertThat(latest().version).isZero()
        }

        @Test
        fun `personal rule requires a category correction and archive preserves history and stops posting`() {
            receive("/category_add income продукты")
            assertThat(reply()).contains("уже существует")
            receive("аптека 30")
            val entry = latest().id
            receive("/rule $entry 0 аптека")
            assertThat(reply()).contains("не сохранено")
            receive("/category_add expense транспорт")
            val category = category("транспорт")
            receive("/category $entry 0 $category")
            receive("/rule $entry 1 аптека")
            assertThat(reply()).contains("Правило сохранено")
            receive("аптека 40")
            assertThat(latest().categoryId).isEqualTo(category)
            receive("/category_archive $category")
            receive("аптека 50")
            assertThat(reply()).contains("архивирована")
            receive("/history")
            assertThat(reply()).contains("транспорт: 40.00", "транспорт: 30.00")
            receive("/category_restore $category")
            receive("/cancel")
            receive("/category_rename $category поездки")
            receive("поездки 60")
            assertThat(latest().categoryId).isEqualTo(category)
            receive("/rule_delete аптека")
            receive("аптека 70")
            assertThat(latest().categoryId).isNotEqualTo(category)
        }

        @Test
        fun `custom category draft survives clarification and archived target cannot be confirmed`() {
            receive("/category_add expense транспорт")
            val id = category("транспорт")
            receive("неизвестно 10")
            receive("транспорт 20")
            val command =
                checkNotNull(
                    jdbc.queryForObject(
                        "SELECT '/confirm ' || id || ' ' || version FROM transaction_draft WHERE state = 'READY'",
                        String::class.java,
                    ),
                )
            receive("/category_archive $id")
            receive(command)
            assertThat(reply()).contains("архивирована")
            receive("/category_restore $id")
            receive("транспорт 20")
            val fresh =
                checkNotNull(
                    jdbc.queryForObject(
                        "SELECT '/confirm ' || id || ' ' || version FROM transaction_draft WHERE state = 'READY'",
                        String::class.java,
                    ),
                )
            receive(fresh)
            assertThat(latest().categoryId).isEqualTo(id)
        }

        @Test
        fun `edited source text never mutates ledger or becomes a new expense`() {
            receive("продукты 30")
            accept(update("продукты 3000", kind = IncomingKind.EDITED))
            assertThat(
                jdbc.queryForObject("SELECT text FROM telegram_inbox ORDER BY id DESC LIMIT 1", String::class.java),
            ).isEqualTo("edited_message")
            processor.processOne(BOT)
            assertThat(reply()).contains("не меняет учёт", "/history")
            assertThat(latest().originalAmount).isEqualTo(Money.of("30"))
            assertThat(revisions(latest().id)).isEqualTo(1)
        }

        @Test
        fun `RUB date correction requests a rate and preserves the old valuation`() {
            val day1 = "2026-09-04"
            val day2 = "2026-09-05"
            for (day in listOf(day1, day2)) {
                jdbc.update("DELETE FROM exchange_rate_job WHERE rate_date = ?::date", day)
                jdbc.update("DELETE FROM daily_exchange_rate WHERE rate_date = ?::date", day)
            }
            rate(day1, "5")
            receive("аптека 10 RUB $day1")
            val id = latest().id
            receive("/date $id 0 $day2")
            assertThat(reply()).contains("Нет курса", "Запрос курса сохранён")
            assertThat(latest().version).isZero()
            rate(day2, "6")
            receive("/date $id 0 $day2")
            assertThat(latest().amountKzt).isEqualTo(Money.of("60"))
            assertThat(
                jdbc.queryForObject(
                    "SELECT snapshot->>'amount_kzt' FROM entry_revision WHERE entry_id = ? AND version = 0",
                    String::class.java,
                    id,
                ),
            ).isEqualTo("50.00")
            receive("/refund $id 1 3 $day2")
            assertThat(latest().originalAmount.currency.name).isEqualTo("RUB")
            assertThat(latest().amountKzt).isEqualTo(Money.of("18"))
        }

        private fun rate(
            day: String,
            rate: String,
        ) {
            jdbc.update(
                "INSERT INTO daily_exchange_rate(rate_date, rub_kzt, source, fetched_at, quoted_at) " +
                    "VALUES (?::date, ?::numeric, 'SYNTHETIC', ?::timestamptz, ?::timestamptz)",
                day,
                rate,
                clock.instant().toString(),
                clock.instant().toString(),
            )
        }

        private fun receive(text: String) {
            accept(update(text))
            assertThat(processor.processOne(BOT)).isTrue()
        }

        private fun callback(
            data: String,
            query: String = "query-${updateId + 1}",
            ownerId: Long = OWNER,
            processed: Boolean = true,
        ) {
            accept(update(data, IncomingKind.CALLBACK, query, ownerId))
            assertThat(processor.processOne(BOT)).isEqualTo(processed)
        }

        private fun update(
            text: String,
            kind: IncomingKind = IncomingKind.MESSAGE,
            query: String? = null,
            ownerId: Long = OWNER,
        ) = IncomingUpdate(
            ++updateId,
            IncomingMessage(ownerId, ownerId, "private", false, text, clock.instant(), kind, query),
        )

        private fun accept(update: IncomingUpdate) {
            assertThat(polls.accept(checkNotNull(polls.claim(BOT)), listOf(update), access)).isTrue()
        }

        private fun reply(): String =
            checkNotNull(
                jdbc.queryForObject("SELECT text FROM telegram_outbox ORDER BY id DESC LIMIT 1", String::class.java),
            )

        private fun buttons(): String =
            checkNotNull(
                jdbc.queryForObject(
                    "SELECT buttons::text FROM telegram_outbox ORDER BY id DESC LIMIT 1",
                    String::class.java,
                ),
            )

        private fun latest(): LedgerEntry =
            tx.execute { operations.history(checkNotNull(operations.owned(OWNER)), Long.MAX_VALUE).first().entry }

        private fun target(entry: LedgerEntry): EntryTarget =
            tx.execute {
                val owner = checkNotNull(operations.owned(OWNER))
                EntryTarget(owner.householdId, owner.memberId, entry.id, entry.version)
            }

        private fun category(name: String): UUID =
            checkNotNull(
                jdbc.queryForObject(
                    "SELECT c.id FROM category c JOIN member m USING (household_id) " +
                        "WHERE m.telegram_user_id = ? AND c.name = ?",
                    UUID::class.java,
                    OWNER,
                    name,
                ),
            )

        private fun revisions(id: UUID): Long =
            checkNotNull(
                jdbc.queryForObject("SELECT count(*) FROM entry_revision WHERE entry_id = ?", Long::class.java, id),
            )
    }
