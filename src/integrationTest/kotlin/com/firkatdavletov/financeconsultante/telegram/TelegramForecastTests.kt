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

@Import(TelegramTestConfiguration::class)
@TestPropertySource(properties = ["app.telegram.bot-token=123456:synthetic-token", "app.telegram.owner-id=555"])
@Suppress("MagicNumber") // Synthetic dates, money, and Telegram's documented message limit are test controls.
class TelegramForecastTests
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
                    BudgetSettings(
                        Money.of("1000"),
                        LocalDate.parse("2026-09-01"),
                        LocalTime.of(8, 0),
                        Money.of("100"),
                    ),
                )
            }
        }

        @AfterEach
        fun cleanup() {
            cleanupData()
        }

        @Test
        fun `forecast command explains short history and accepts manual daily limit`() {
            receive("/forecast")
            assertThat(reply())
                .contains(
                    "Прогноз 2026-09-06–2026-10-05",
                    "история 5 дн., нужно минимум 14",
                    "/forecast <KZT в день>",
                    "Прогноз не меняет ledger",
                ).hasSizeLessThanOrEqualTo(4096)

            receive("/forecast 10")
            assertThat(reply())
                .contains(
                    "ручной лимит 10.00 KZT/день",
                    "обычные траты +20%",
                    "сдвинут на 3 дня",
                    "Базовый: итог 700.00 KZT",
                    "Осторожный: итог 640.00 KZT",
                ).hasSizeLessThanOrEqualTo(4096)
            assertThat(count("ledger_entry")).isZero()
        }

        @Test
        fun `invalid manual daily limit does not run forecast`() {
            receive("/forecast -1")

            assertThat(reply()).isEqualTo(
                "Формат: /forecast или /forecast " +
                    "<положительный лимит KZT в день>.",
            )
            assertThat(count("ledger_entry")).isZero()
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

        private fun cleanupData() {
            jdbc.execute(
                "TRUNCATE recurring_expense_candidate_evidence, recurring_expense_candidate, " +
                    "savings_goal_allocation, goal_contribution_plan, savings_goal, " +
                    "operation_plan_links, expected_income, recurring_occurrence, " +
                    "recurring_rule, openai_recognition_job, openai_daily_usage, transaction_draft, telegram_outbox, " +
                    "telegram_inbox, telegram_polling_state, telegram_dialog RESTART IDENTITY",
            )
            val households = jdbc.queryForList("SELECT household_id FROM member WHERE telegram_user_id = ?", OWNER)
            households.forEach { row ->
                val household = row.getValue("household_id")
                val tables =
                    listOf(
                        "daily_budget_snapshot",
                        "entry_revision",
                        "ledger_entry",
                        "category",
                        "account",
                        "member",
                    )
                for (table in tables) jdbc.update("DELETE FROM $table WHERE household_id = ?", household)
                jdbc.update("DELETE FROM household WHERE id = ?", household)
            }
        }
    }
