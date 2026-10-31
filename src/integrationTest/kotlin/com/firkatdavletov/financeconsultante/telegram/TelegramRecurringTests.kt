package com.firkatdavletov.financeconsultante.telegram

import com.firkatdavletov.financeconsultante.PostgresIntegrationTest
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
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.TestPropertySource
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.util.UUID

@Import(TelegramTestConfiguration::class)
@TestPropertySource(properties = ["app.telegram.bot-token=123456:synthetic-token", "app.telegram.owner-id=555"])
@Suppress("MagicNumber", "TooManyFunctions")
class TelegramRecurringTests
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
                    BudgetSettings(Money.of("0"), LocalDate.parse("2026-09-01"), LocalTime.of(8, 0), Money.of("0")),
                )
            }
            updateId = 0
        }

        @AfterEach
        fun cleanup() {
            cleanupData()
        }

        private fun cleanupData() {
            jdbc.execute(
                "TRUNCATE recurring_expense_candidate_evidence, recurring_expense_candidate, " +
                    "operation_plan_links, recurring_occurrence, recurring_rule, " +
                    "expected_income, openai_recognition_job, openai_daily_usage, transaction_draft, " +
                    "telegram_outbox, " +
                    "telegram_inbox, telegram_polling_state, telegram_dialog RESTART IDENTITY",
            )
            val households = jdbc.queryForList("SELECT household_id FROM member WHERE telegram_user_id = ?", OWNER)
            households.forEach { row ->
                val id = row.getValue("household_id")
                for (table in listOf("entry_revision", "ledger_entry", "category", "account", "member")) {
                    jdbc.update("DELETE FROM $table WHERE household_id = ?", id)
                }
                jdbc.update("DELETE FROM household WHERE id = ?", id)
            }
        }

        @Test
        fun `recurring and expected commands keep plans separate from ledger facts`() {
            receive("/category_add expense связь")
            receive("/category_add income зарплата")
            val expense = category("EXPENSE")
            val income = category("INCOME")
            receive("/recurring add monthly 31 100 $expense")
            assertThat(reply()).contains("последний день", "без списания денег")
            val occurrence =
                jdbc.queryForMap(
                    "SELECT id, version FROM recurring_occurrence WHERE due_on = '2026-09-30'::date",
                )
            assertThat(retrieveBalance()).contains("0.00 KZT")

            val payment = "/recurring pay ${occurrence["id"]} ${occurrence["version"]} 40"
            receive(payment)
            assertThat(reply()).contains("Осталось по обязательству: 60.00 KZT")
            receive(payment)
            assertThat(reply()).contains("изменилось")
            assertThat(count("ledger_entry")).isEqualTo(1)

            receive("/expected add 2026-09-10 100 $income")
            assertThat(reply()).contains("Баланс не изменён")
            val expected = jdbc.queryForMap("SELECT id, version FROM expected_income")
            assertThat(retrieveBalance()).contains("-40.00 KZT")
            receive("/expected receive ${expected["id"]} ${expected["version"]} 120")
            assertThat(reply()).contains("сопоставлен", "не входит в прогноз")
            assertThat(retrieveBalance()).contains("80.00 KZT", "Доходы: 120.00", "Расходы: 40.00")

            receive("подписка 25")
            assertThat(reply()).contains("Баланс не изменён")
            assertThat(count("recurring_rule")).isEqualTo(1)
            assertThat(count("ledger_entry")).isEqualTo(2)
        }

        @Test
        fun `existing expense can be matched to a recurring obligation without duplicate ledger entry`() {
            receive("/category_add expense связь")
            val expense = category("EXPENSE", "связь")
            receive("/recurring add monthly 6 100 $expense")
            val occurrence =
                jdbc.queryForMap(
                    "SELECT id, version FROM recurring_occurrence WHERE due_on = '2026-09-06'::date",
                )
            receive("/expense 40 связь")
            assertThat(reply()).contains("Записано")
            val entryId =
                checkNotNull(
                    jdbc.queryForObject(
                        "SELECT id FROM ledger_entry WHERE category_id = ?",
                        UUID::class.java,
                        expense,
                    ),
                )
            assertThat(count("ledger_entry")).isEqualTo(1)

            receive("/recurring match ${occurrence["id"]} ${occurrence["version"]} $entryId")

            assertThat(reply()).contains(
                "Расход $entryId связан с обязательством",
                "Новая проводка не создавалась",
                "Осталось по обязательству: 60.00 KZT",
            )
            assertThat(count("ledger_entry")).isEqualTo(1)
            assertThat(count("recurring_occurrence_payment")).isEqualTo(1)
            receive("/recurring")
            assertThat(reply()).contains("план 100.00 KZT, осталось 60.00 KZT")
        }

        @ParameterizedTest
        @ValueSource(booleans = [false, true])
        fun `recorded income automatically fulfills the nearest plan after direct input or draft confirmation`(
            throughDraft: Boolean,
        ) {
            receive("/category_add income Зарплата")
            val income = category("INCOME")
            receive("/expected add 2026-09-20 100 $income")
            receive("/expected add 2026-09-07 100 $income")
            if (throughDraft) receive("/income 1.001 Зарплата")

            receive("/income 130 Зарплата")
            if (throughDraft) {
                assertThat(count("ledger_entry")).isZero()
                receive("/confirm")
            }

            assertThat(reply()).contains("Записано")
            assertThat(count("ledger_entry")).isEqualTo(1)
            assertThat(count("expected_income_match")).isEqualTo(1)
            assertThat(
                jdbc.queryForObject(
                    "SELECT e.due_on::text FROM expected_income e " +
                        "JOIN expected_income_match m ON m.expected_income_id = e.id",
                    String::class.java,
                ),
            ).isEqualTo("2026-09-07")
            assertThat(retrieveBalance()).contains("130.00 KZT", "Доходы: 130.00")
        }

        @Test
        fun `recorded expense offers every matching plan date and callback links the selected occurrence`() {
            receive("/category_add expense связь")
            val expense = category("EXPENSE", "связь")
            receive("/recurring add monthly 10 100 $expense")
            receive("/recurring add monthly 20 100 $expense")

            receive("/expense 40 связь")

            assertThat(reply()).contains(
                "Записано",
                "Есть плановые расходы в категории «связь»",
                "Связать текущий расход с планом?",
            )
            assertThat(buttonTexts()).contains("📅 2026-09-10", "📅 2026-09-20")
            val entryId =
                checkNotNull(
                    jdbc.queryForObject(
                        "SELECT id FROM ledger_entry WHERE category_id = ?",
                        UUID::class.java,
                        expense,
                    ),
                )
            val callback = buttonData("📅 2026-09-20")

            receiveCallback(callback)

            val matched =
                jdbc.queryForMap(
                    """
                    SELECT o.due_on, p.ledger_entry_id
                    FROM recurring_occurrence_payment p
                    JOIN recurring_occurrence o ON o.id = p.occurrence_id
                    """.trimIndent(),
                )
            assertThat(matched["due_on"]).isEqualTo(java.sql.Date.valueOf("2026-09-20"))
            assertThat(matched["ledger_entry_id"]).isEqualTo(entryId)
            assertThat(reply()).contains("Новая проводка не создавалась", "Осталось по обязательству: 60.00 KZT")
            assertThat(count("ledger_entry")).isEqualTo(1)

            receiveCallback(callback)

            assertThat(reply()).contains("Обязательство изменилось или недоступно").doesNotContain("Новая проводка")
            assertThat(count("recurring_occurrence_payment")).isEqualTo(1)
            assertThat(count("ledger_entry")).isEqualTo(1)
        }

        @Test
        fun `today command returns deterministic budget estimate without OpenAI`() {
            receive("/today")

            assertThat(reply()).contains(
                "Бюджет на 6 сентября",
                "Баланс: 0.00 KZT",
                "Дневной лимит: 0.00 KZT",
                "Оценка: свободных средств нет",
                "ожидаемые доходы не считаются полученными",
            )
            assertThat(count("daily_budget_snapshot")).isEqualTo(1)

            receive("/today лишнее")
            assertThat(reply()).isEqualTo("Формат: /today")
        }

        @Test
        fun `today command marks unfinished setup as insufficient data`() {
            val household =
                jdbc.queryForObject(
                    "SELECT household_id FROM member WHERE telegram_user_id = ?",
                    UUID::class.java,
                    OWNER,
                )
            jdbc.update("DELETE FROM category WHERE household_id = ?", household)
            jdbc.update("DELETE FROM account WHERE household_id = ?", household)
            jdbc.update("DELETE FROM member WHERE household_id = ?", household)
            jdbc.update("DELETE FROM household WHERE id = ?", household)

            receive("/today")

            assertThat(reply()).contains("Данных недостаточно", "Лимит не рассчитан", "/start")
        }

        private fun retrieveBalance(): String {
            receive("/balance")
            return reply()
        }

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

        private fun category(
            kind: String,
            name: String,
        ): UUID =
            checkNotNull(
                jdbc.queryForObject(
                    "SELECT c.id FROM category c JOIN member m ON m.household_id = c.household_id " +
                        "WHERE m.telegram_user_id = ? AND c.kind = ? AND c.name = ?",
                    UUID::class.java,
                    OWNER,
                    kind,
                    name,
                ),
            )

        private fun receive(
            text: String,
            sent: Instant = clock.instant(),
        ) {
            val update = IncomingUpdate(++updateId, IncomingMessage(OWNER, OWNER, "private", false, text, sent))
            assertThat(polls.accept(checkNotNull(polls.claim(BOT)), listOf(update), access)).isTrue()
            assertThat(processor.processOne(BOT)).isTrue()
        }

        private fun receiveCallback(data: String) {
            val update =
                IncomingUpdate(
                    ++updateId,
                    IncomingMessage(
                        OWNER,
                        OWNER,
                        "private",
                        false,
                        data,
                        clock.instant(),
                        IncomingKind.CALLBACK,
                        "query-$updateId",
                        messageId = 73,
                    ),
                )
            assertThat(polls.accept(checkNotNull(polls.claim(BOT)), listOf(update), access)).isTrue()
            assertThat(processor.processOne(BOT)).isTrue()
        }

        private fun reply(): String =
            checkNotNull(
                jdbc.queryForObject("SELECT text FROM telegram_outbox ORDER BY id DESC LIMIT 1", String::class.java),
            )

        private fun buttonTexts(): List<String> =
            jdbc
                .queryForList(
                    """
                    SELECT item->>'text'
                    FROM telegram_outbox o
                    CROSS JOIN LATERAL jsonb_array_elements(o.buttons) item
                    WHERE o.id = (SELECT max(id) FROM telegram_outbox)
                    ORDER BY item->>'text'
                    """.trimIndent(),
                    String::class.java,
                ).filterNotNull()

        private fun buttonData(text: String): String =
            checkNotNull(
                jdbc.queryForObject(
                    """
                    SELECT item->>'data'
                    FROM telegram_outbox o
                    CROSS JOIN LATERAL jsonb_array_elements(o.buttons) item
                    WHERE o.id = (SELECT max(id) FROM telegram_outbox) AND item->>'text' = ?
                    """.trimIndent(),
                    String::class.java,
                    text,
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
    }
