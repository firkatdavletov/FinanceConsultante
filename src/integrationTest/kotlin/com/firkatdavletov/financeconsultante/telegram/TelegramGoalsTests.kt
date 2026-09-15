package com.firkatdavletov.financeconsultante.telegram

import com.firkatdavletov.financeconsultante.PostgresIntegrationTest
import com.firkatdavletov.financeconsultante.shared.domain.Money
import com.firkatdavletov.financeconsultante.telegram.TelegramTestConfiguration.Companion.BOT
import com.firkatdavletov.financeconsultante.telegram.TelegramTestConfiguration.Companion.OWNER
import com.firkatdavletov.financeconsultante.telegram.application.IncomingMessage
import com.firkatdavletov.financeconsultante.telegram.application.IncomingUpdate
import com.firkatdavletov.financeconsultante.telegram.application.OwnerAccess
import com.firkatdavletov.financeconsultante.telegram.application.PollingStore
import com.firkatdavletov.financeconsultante.telegram.application.SetupStore
import com.firkatdavletov.financeconsultante.telegram.application.TelegramInboxProcessor
import com.firkatdavletov.financeconsultante.telegram.domain.BudgetSettings
import org.assertj.core.api.Assertions.assertThat
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

@Import(TelegramTestConfiguration::class)
@TestPropertySource(properties = ["app.telegram.bot-token=123456:synthetic-token", "app.telegram.owner-id=555"])
@Suppress("MagicNumber")
class TelegramGoalsTests
    @Autowired
    constructor(
        private val polls: PollingStore,
        private val setup: SetupStore,
        private val processor: TelegramInboxProcessor,
        private val clock: Clock,
        private val jdbc: JdbcTemplate,
        transactionManager: PlatformTransactionManager,
    ) : PostgresIntegrationTest() {
        private val tx = TransactionTemplate(transactionManager)
        private val access = OwnerAccess(OWNER)
        private var updateId = 0L

        @BeforeEach
        fun reset() {
            cleanupData()
            tx.executeWithoutResult {
                setup.saveSettings(
                    OWNER,
                    BudgetSettings(Money.of("300"), LocalDate.parse("2026-09-01"), LocalTime.of(8, 0), Money.of("50")),
                )
            }
            updateId = 0
        }

        @AfterEach
        fun cleanup() {
            cleanupData()
        }

        @Test
        fun `goals command separates allocations plans and goal funded expense`() {
            receive("/category_add expense покупка")
            val category = category()
            receive("/goals add 1000 2026-10-01 1 Отпуск")
            val goal = jdbc.queryForMap("SELECT id, version FROM savings_goal")
            assertThat(reply()).contains("Цель сохранена", "Баланс и статистика расходов не изменены")

            receive("/goals reserve ${goal["id"]} ${goal["version"]} 100")
            assertThat(reply()).contains("Выделено 100.00 KZT", "Баланс и статистика расходов не изменены")
            assertThat(balance()).contains("300.00 KZT", "Расходы: 0.00 KZT")

            receive("/goals")
            assertThat(reply()).contains("Уже выделено G: 100.00 KZT", "Требуемый темп")
            receive("/today")
            assertThat(reply()).contains("Цели: 100.00 KZT", "Доступно сейчас: 150.00 KZT")

            receive("/goals plan ${goal["id"]} 1 2026-09-20 25")
            val plan = jdbc.queryForMap("SELECT id, version FROM goal_contribution_plan")
            assertThat(reply()).contains("Будущий взнос запланирован", "не входит в B или G")
            receive("/goals plan_cancel ${plan["id"]} ${plan["version"]}")
            assertThat(reply()).contains("Будущий взнос отменён", "не входит в B или G")

            receive("/goals spend ${goal["id"]} 1 40 $category")
            assertThat(reply()).contains("B и G уменьшены атомарно", "осталось выделено 60.00 KZT")
            assertThat(balance()).contains("260.00 KZT", "Расходы: 40.00 KZT")
            receive("/today")
            assertThat(reply()).contains("Цели: 60.00 KZT", "Доступно сейчас: 150.00 KZT")
            assertThat(count("ledger_entry")).isEqualTo(1)
        }

        private fun cleanupData() {
            jdbc.execute(
                "TRUNCATE savings_goal_allocation, goal_contribution_plan, savings_goal, " +
                    "openai_recognition_job, openai_daily_usage, transaction_draft, telegram_outbox, " +
                    "telegram_inbox, telegram_polling_state, telegram_dialog RESTART IDENTITY",
            )
            val households = jdbc.queryForList("SELECT household_id FROM member WHERE telegram_user_id = ?", OWNER)
            households.forEach { row ->
                val id = row.getValue("household_id")
                val tables =
                    listOf(
                        "daily_budget_snapshot",
                        "entry_revision",
                        "ledger_entry",
                        "category",
                        "account",
                        "member",
                    )
                for (table in tables) {
                    jdbc.update("DELETE FROM $table WHERE household_id = ?", id)
                }
                jdbc.update("DELETE FROM household WHERE id = ?", id)
            }
        }

        private fun category(): UUID =
            checkNotNull(
                jdbc.queryForObject(
                    "SELECT c.id FROM category c JOIN member m ON m.household_id = c.household_id " +
                        "WHERE m.telegram_user_id = ? AND c.kind = 'EXPENSE' ORDER BY c.name LIMIT 1",
                    UUID::class.java,
                    OWNER,
                ),
            )

        private fun balance(): String {
            receive("/balance")
            return reply()
        }

        private fun receive(text: String) {
            val message = IncomingMessage(OWNER, OWNER, "private", false, text, clock.instant())
            val update = IncomingUpdate(++updateId, message)
            assertThat(polls.accept(checkNotNull(polls.claim(BOT)), listOf(update), access)).isTrue()
            assertThat(processor.processOne(BOT)).isTrue()
        }

        private fun reply(): String =
            checkNotNull(
                jdbc.queryForObject("SELECT text FROM telegram_outbox ORDER BY id DESC LIMIT 1", String::class.java),
            )

        private fun count(table: String): Long =
            checkNotNull(
                jdbc.queryForObject(
                    "SELECT count(*) FROM $table t JOIN member m ON m.household_id = t.household_id " +
                        "WHERE m.telegram_user_id = ?",
                    Long::class.java,
                    OWNER,
                ),
            )
    }
