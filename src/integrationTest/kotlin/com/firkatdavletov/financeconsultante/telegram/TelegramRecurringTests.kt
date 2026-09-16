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
                    "recurring_occurrence_payment, expected_income_match, recurring_occurrence, recurring_rule, " +
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

        @Test
        fun `today command returns deterministic budget estimate without OpenAI`() {
            receive("/today")

            assertThat(reply()).contains(
                "Бюджет на 2026-09-06",
                "Учётный баланс: 0.00 KZT",
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
