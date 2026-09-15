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
@Suppress("MagicNumber", "TooManyFunctions") // End-to-end helpers keep T15 financial assertions explicit.
class TelegramRecurringAnalysisTests
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
                        Money.of("100000"),
                        LocalDate.parse("2026-06-01"),
                        LocalTime.of(8, 0),
                        Money.of("0"),
                    ),
                )
            }
            updateId = 0
        }

        @AfterEach
        fun cleanup() {
            cleanupData()
        }

        @Test
        fun `weekly candidate needs owner acceptance and removes accepted evidence from variable forecast`() {
            receive("/category_add expense стриминг")
            recordSeries("стриминг", listOf("2026-08-20", "2026-08-27", "2026-09-03"), "1000")

            receive("/recurring analyze")

            val candidate = jdbc.queryForMap("SELECT id, version, status FROM recurring_expense_candidate")
            assertThat(reply()).contains(
                "Кандидат ${candidate["id"]} v0",
                "примерно еженедельно",
                "2026-08-20 1000.00 KZT",
                "2026-08-27 1000.00 KZT",
                "2026-09-03 1000.00 KZT",
                "базовый 94000.00 → 97000.00 KZT",
                "не создал правило без команды accept",
            )
            assertThat(candidate["status"]).isEqualTo("PENDING")
            assertThat(count("recurring_rule")).isZero()
            assertThat(count("ledger_entry")).isEqualTo(3)

            val accept = "/recurring accept ${candidate["id"]} ${candidate["version"]}"
            receive(accept)

            assertThat(reply()).contains("Кандидат подтверждён владельцем", "без списания денег")
            assertThat(count("recurring_rule")).isEqualTo(1)
            assertThat(count("ledger_entry")).isEqualTo(3)
            assertThat(
                jdbc.queryForObject("SELECT status FROM recurring_expense_candidate", String::class.java),
            ).isEqualTo("ACCEPTED")

            receive(accept)
            assertThat(reply()).contains("изменилось, уже обработано или недоступно")
            assertThat(count("recurring_rule")).isEqualTo(1)

            receive("/forecast")
            assertThat(reply()).contains("Обычные траты: 0.00 KZT/день", "Базовый: итог 93000.00 KZT")
        }

        @Test
        fun `candidate is revalidated when evidence changes before acceptance`() {
            receive("/category_add expense облако")
            recordSeries("облако", listOf("2026-08-20", "2026-08-27", "2026-09-03"), "900")
            receive("/recurring analyze")
            val candidate = jdbc.queryForMap("SELECT id, version FROM recurring_expense_candidate")

            val changed = entry("облако", "2026-08-27")
            receive("/void ${changed["id"]} ${changed["version"]}")
            receive("/recurring accept ${candidate["id"]} ${candidate["version"]}")

            assertThat(reply()).contains("Подтверждающие операции изменились", "Предложение отклонено")
            assertThat(count("recurring_rule")).isZero()
            assertThat(
                jdbc.queryForObject("SELECT status FROM recurring_expense_candidate", String::class.java),
            ).isEqualTo("REJECTED")
        }

        @Test
        fun `rejected monthly candidate with a skipped month is not proposed again`() {
            receive("/category_add expense музыка")
            recordSeries("музыка", listOf("2026-06-01", "2026-07-01", "2026-09-01"), "1500")
            receive("/recurring analyze")
            val candidate = jdbc.queryForMap("SELECT id, version FROM recurring_expense_candidate")
            assertThat(reply()).contains("примерно ежемесячно", "интервалы 30/62 дней")

            receive("/recurring reject ${candidate["id"]} ${candidate["version"]}")
            assertThat(reply()).contains("не будет предлагаться повторно")

            receive("/recurring analyze")
            assertThat(reply()).doesNotContain("Кандидат ${candidate["id"]}")
            assertThat(count("recurring_expense_candidate")).isEqualTo(1)
            assertThat(
                jdbc.queryForObject("SELECT status FROM recurring_expense_candidate", String::class.java),
            ).isEqualTo("REJECTED")
            assertThat(count("recurring_rule")).isZero()
        }

        @Test
        fun `refund void random groceries and an existing matching rule do not become candidates`() {
            receive("/category_add expense сервис")
            val serviceCategory = category("сервис")
            receive("/recurring add weekly чт 1000 $serviceCategory")
            recordSeries("сервис", listOf("2026-08-20", "2026-08-27", "2026-09-03"), "1000")

            receive("/category_add expense возвратная")
            recordSeries("возвратная", listOf("2026-08-20", "2026-08-27", "2026-09-03"), "500")
            val refunded = entry("возвратная", "2026-08-27")
            receive("/refund ${refunded["id"]} ${refunded["version"]} 100 2026-09-04")

            receive("/category_add expense отмененная")
            recordSeries("отмененная", listOf("2026-08-20", "2026-08-27", "2026-09-03"), "700")
            val voided = entry("отмененная", "2026-08-27")
            receive("/void ${voided["id"]} ${voided["version"]}")

            receive("продукты 1000 2026-08-01")
            receive("продукты 1020 2026-08-10")
            receive("продукты 980 2026-08-28")
            receive("/recurring analyze")

            assertThat(reply()).contains("Новых объяснимых кандидатов", "Случайные совпадения не создают")
            assertThat(count("recurring_expense_candidate")).isZero()
            assertThat(count("recurring_rule")).isEqualTo(1)
        }

        @Test
        fun `growth of an existing payment shows exact update and cancellation forecasts`() {
            receive("/category_add expense связь")
            val category = category("связь")
            receive("/recurring add weekly чт 1000 $category")
            val rule = jdbc.queryForMap("SELECT id, version FROM recurring_rule")
            listOf("2026-08-20", "2026-08-27", "2026-09-03").forEach { dueOn ->
                jdbc.update(
                    """INSERT INTO recurring_occurrence(
                    id, household_id, rule_id, category_id, planned_amount, due_on, created_at, updated_at
                    ) SELECT ?, household_id, id, category_id, amount, ?::date,
                        '2026-09-06T03:00:00Z', '2026-09-06T03:00:00Z'
                    FROM recurring_rule WHERE id = ?""",
                    UUID.randomUUID(),
                    dueOn,
                    rule["id"],
                )
            }
            val occurrences =
                jdbc.queryForList(
                    "SELECT id, version FROM recurring_occurrence WHERE due_on < '2026-09-06' ORDER BY due_on",
                )
            listOf("1000", "1000", "1200").zip(occurrences).forEach { (amount, occurrence) ->
                receive("/recurring pay ${occurrence["id"]} ${occurrence["version"]} $amount")
            }

            receive("/recurring analyze")

            assertThat(reply()).contains(
                "Рост существующего правила ${rule["id"]} v${rule["version"]}",
                "1000.00 → 1200.00 KZT",
                "Если изменить будущую сумму: конец 30-дневного прогноза",
                "Если отключить будущие платежи: конец 30-дневного прогноза",
                "/recurring amount ${rule["id"]} ${rule["version"]} 1200.00",
                "/recurring deactivate ${rule["id"]} ${rule["version"]}",
            )
            assertThat(count("recurring_expense_candidate")).isZero()
        }

        private fun recordSeries(
            description: String,
            dates: List<String>,
            amount: String,
        ) {
            dates.forEach { receive("$description $amount $it") }
        }

        private fun entry(
            description: String,
            occurredOn: String,
        ): Map<String, Any?> =
            jdbc.queryForMap(
                "SELECT id, version FROM ledger_entry WHERE normalized_description = ? AND occurred_on = ?::date",
                description,
                occurredOn,
            )

        private fun category(name: String): UUID =
            checkNotNull(
                jdbc.queryForObject(
                    "SELECT c.id FROM category c JOIN member m ON m.household_id = c.household_id " +
                        "WHERE m.telegram_user_id = ? AND c.name = ?",
                    UUID::class.java,
                    OWNER,
                    name,
                ),
            )

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
                    "recurring_occurrence_payment, expected_income_match, recurring_occurrence, " +
                    "recurring_rule, " +
                    "expected_income, openai_recognition_job, openai_daily_usage, " +
                    "transaction_draft, telegram_outbox, " +
                    "telegram_inbox, telegram_polling_state, telegram_dialog RESTART IDENTITY",
            )
            val households = jdbc.queryForList("SELECT household_id FROM member WHERE telegram_user_id = ?", OWNER)
            households.forEach { row ->
                val household = row.getValue("household_id")
                listOf(
                    "daily_budget_snapshot",
                    "entry_revision",
                    "ledger_entry",
                    "category",
                    "account",
                    "member",
                ).forEach { table -> jdbc.update("DELETE FROM $table WHERE household_id = ?", household) }
                jdbc.update("DELETE FROM household WHERE id = ?", household)
            }
        }
    }
