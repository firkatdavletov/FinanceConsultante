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
@Suppress("MagicNumber", "TooManyFunctions") // End-to-end helpers keep each financial assertion concise.
class TelegramPurchaseTests
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
            settings("600000", "2026-08-01", "50000")
            updateId = 0
        }

        @AfterEach
        fun cleanup() {
            cleanupData()
        }

        @Test
        fun `natural purchase question returns calculations without recording expense`() {
            receive("можно купить ноутбук за 500000?")

            assertThat(reply())
                .contains(
                    "Анализ покупки 500000.00 KZT на 2026-09-06",
                    "дата не указана — принято сегодня",
                    "будущие обязательства R",
                    "лимит",
                    "Отложить",
                    "Анализ не записал расход и не изменил ledger",
                    "Необходимость не указана",
                ).hasSizeLessThanOrEqualTo(4096)
            assertThat(count("ledger_entry")).isZero()
        }

        @Test
        fun `purchase over free amount exposes protected reserve instead of declaring it affordable`() {
            receive("/purchase 560000 хочу")

            assertThat(reply()).contains(
                "доступно F -10000.00 KZT",
                "затрагивание R/E/G 2026-09-06",
                "Рекомендация: отложить",
                "после R/E/G не хватает 10000.00 KZT",
            )
            assertThat(count("ledger_entry")).isZero()
        }

        @Test
        fun `goal funded purchase decreases projected balance and goal reserve together`() {
            receive("/goals add 500000 2026-09-30 1 Ноутбук")
            val goal = jdbc.queryForMap("SELECT id, version FROM savings_goal")
            receive("/goals reserve ${goal["id"]} ${goal["version"]} 500000")

            receive("/purchase 500000 нужно goal=${goal["id"]}")

            assertThat(reply()).contains(
                "цели G 0.00 KZT",
                "доступно F 50000.00 KZT",
                "используется до 500000.00 KZT",
                "B и G уменьшаются вместе",
                "без двойного вычета",
            )
            assertThat(count("ledger_entry")).isZero()
        }

        @Test
        fun `recommendation waits for cautious salary date and does not spend expected income early`() {
            settings("100", "2026-08-01", "0")
            receive("/category_add income зарплата")
            val income = category("INCOME")
            receive("/expected add 2026-09-10 1000 $income")

            receive("/purchase 500 хочу")

            assertThat(reply()).contains(
                "денежный дефицит возникает 2026-09-06",
                "Рекомендация: отложить до 2026-09-13",
                "доходы на 3 дня позже",
            )
            assertThat(count("ledger_entry")).isZero()
        }

        @Test
        fun `short history requests explicit daily spending instead of confident advice`() {
            settings("1000", "2026-09-01", "0")

            receive("/purchase 100")

            assertThat(reply()).contains(
                "есть только 5 завершённых дней",
                "daily=<положительный лимит KZT в день>",
                "Формат: /purchase",
            )
            assertThat(count("ledger_entry")).isZero()
        }

        private fun settings(
            balance: String,
            startedOn: String,
            reserve: String,
        ) {
            tx.executeWithoutResult {
                setup.saveSettings(
                    OWNER,
                    BudgetSettings(
                        Money.of(balance),
                        LocalDate.parse(startedOn),
                        LocalTime.of(8, 0),
                        Money.of(reserve),
                    ),
                )
            }
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

        private fun category(kind: String): UUID =
            checkNotNull(
                jdbc.queryForObject(
                    "SELECT c.id FROM category c JOIN member m ON m.household_id = c.household_id " +
                        "WHERE m.telegram_user_id = ? AND c.kind = ? ORDER BY c.name LIMIT 1",
                    UUID::class.java,
                    OWNER,
                    kind,
                ),
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
                "TRUNCATE savings_goal_allocation, goal_contribution_plan, savings_goal, " +
                    "expected_income_match, expected_income, recurring_occurrence_payment, recurring_occurrence, " +
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
