package com.firkatdavletov.financeconsultante.telegram

import com.firkatdavletov.financeconsultante.PostgresIntegrationTest
import com.firkatdavletov.financeconsultante.ledger.application.AccountingService
import com.firkatdavletov.financeconsultante.ledger.application.CategoryManagement
import com.firkatdavletov.financeconsultante.ledger.application.EntryCorrections
import com.firkatdavletov.financeconsultante.ledger.application.LedgerOperations
import com.firkatdavletov.financeconsultante.ledger.application.TransactionDraftRepository
import com.firkatdavletov.financeconsultante.ledger.infrastructure.JdbcBudgetRepository
import com.firkatdavletov.financeconsultante.ledger.infrastructure.JdbcExchangeRateRepository
import com.firkatdavletov.financeconsultante.ledger.infrastructure.JdbcLedgerRepository
import com.firkatdavletov.financeconsultante.recurring.infrastructure.JdbcRecurringPlanRepository
import com.firkatdavletov.financeconsultante.shared.domain.Money
import com.firkatdavletov.financeconsultante.telegram.TelegramTestConfiguration.Companion.BOT
import com.firkatdavletov.financeconsultante.telegram.TelegramTestConfiguration.Companion.OWNER
import com.firkatdavletov.financeconsultante.telegram.application.InboxStore
import com.firkatdavletov.financeconsultante.telegram.application.IncomingMessage
import com.firkatdavletov.financeconsultante.telegram.application.IncomingUpdate
import com.firkatdavletov.financeconsultante.telegram.application.OutboxStore
import com.firkatdavletov.financeconsultante.telegram.application.OwnerAccess
import com.firkatdavletov.financeconsultante.telegram.application.PollingStore
import com.firkatdavletov.financeconsultante.telegram.application.SetupStore
import com.firkatdavletov.financeconsultante.telegram.application.TelegramCorrectionHandler
import com.firkatdavletov.financeconsultante.telegram.application.TelegramDeliveryWorker
import com.firkatdavletov.financeconsultante.telegram.application.TelegramInboxProcessor
import com.firkatdavletov.financeconsultante.telegram.application.TelegramLedgerHandler
import com.firkatdavletov.financeconsultante.telegram.application.TelegramPoller
import com.firkatdavletov.financeconsultante.telegram.domain.BudgetSettings
import com.firkatdavletov.financeconsultante.telegram.infrastructure.TelegramConfiguration
import com.firkatdavletov.financeconsultante.telegram.infrastructure.TelegramProperties
import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath
import com.github.tomakehurst.wiremock.client.WireMock.post
import com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor
import com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo
import com.github.tomakehurst.wiremock.core.WireMockConfiguration.options
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.test.context.TestPropertySource
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.time.Clock
import java.time.Instant
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
class TelegramLedgerTests
    @Autowired
    constructor(
        private val polls: PollingStore,
        private val outbox: OutboxStore,
        private val inbox: InboxStore,
        private val setup: SetupStore,
        private val processor: TelegramInboxProcessor,
        private val operations: LedgerOperations,
        private val drafts: TransactionDraftRepository,
        private val categories: CategoryManagement,
        private val corrections: EntryCorrections,
        private val clock: Clock,
        private val jdbc: JdbcTemplate,
        transactionManager: PlatformTransactionManager,
    ) : PostgresIntegrationTest() {
        private val tx = TransactionTemplate(transactionManager)
        private val access = OwnerAccess(OWNER)
        private var updateId = 0L

        @BeforeEach
        fun reset() {
            jdbc.execute(
                "TRUNCATE openai_recognition_job, openai_daily_usage, transaction_draft, telegram_outbox, " +
                    "telegram_inbox, telegram_polling_state, " +
                    "telegram_dialog RESTART IDENTITY",
            )
            val households = jdbc.queryForList("SELECT household_id FROM member WHERE telegram_user_id = ?", OWNER)
            households.forEach { row ->
                val id = row.getValue("household_id")
                for (table in listOf("entry_revision", "ledger_entry", "category", "account", "member")) {
                    jdbc.update("DELETE FROM $table WHERE household_id = ?", id)
                }
                jdbc.update("DELETE FROM household WHERE id = ?", id)
            }
            tx.executeWithoutResult {
                setup.saveSettings(
                    OWNER,
                    BudgetSettings(Money.of("0"), LocalDate.parse("2026-09-01"), LocalTime.of(8, 0), Money.of("0")),
                )
            }
            updateId = 0
        }

        @Test
        @Suppress("LongMethod") // Exercises the complete network and commit failure sequence.
        fun `control journal flows from fake Telegram through PostgreSQL and survives delivery failure`() {
            val server = WireMockServer(options().dynamicPort().http2PlainDisabled(true))
            server.start()
            try {
                val texts = listOf("зарплата 1300000", "супермаркет 15000", "продукты 700", "аптека 1300")
                val body =
                    texts
                        .mapIndexed { i, text ->
                            """{"update_id":${i + 1},"message":{"date":${clock.instant().epochSecond},
                "from":{"id":555,"is_bot":false},"chat":{"id":555,"type":"private"},"text":"$text"}}"""
                        }.joinToString(",")
                server.stubFor(
                    post(urlEqualTo(API + "getUpdates")).willReturn(json("""{"ok":true,"result":[$body]}""")),
                )
                server.stubFor(
                    post(urlEqualTo(API + "sendMessage")).willReturn(
                        json("""{"ok":false,"error_code":429,"parameters":{"retry_after":2}}""").withStatus(429),
                    ),
                )
                val client =
                    TelegramConfiguration().telegramClient(
                        TelegramProperties("123456:synthetic-token", OWNER, server.baseUrl()),
                    )
                val poller = TelegramPoller(BOT, access, client, polls)
                poller.pollOnce()
                repeat(4) { assertThat(processor.processOne(BOT)).isTrue() }
                val worker = TelegramDeliveryWorker(BOT, access, client, outbox)
                worker.deliverOne()
                assertThat(count("ledger_entry")).isEqualTo(4)
                assertThat(count("entry_revision")).isEqualTo(4)
                assertThat(
                    jdbc.queryForObject("SELECT count(*) FROM telegram_inbox WHERE text IS NOT NULL", Long::class.java),
                ).isZero()
                poller.pollOnce() // The fake API deliberately repeats the same batch.
                assertThat(processor.processOne(BOT)).isFalse()
                jdbc.update("UPDATE telegram_outbox SET next_attempt_at = clock_timestamp() - interval '1 second'")
                server.stubFor(
                    post(
                        urlEqualTo(API + "sendMessage"),
                    ).willReturn(json("""{"ok":true,"result":{"message_id":91}}""")),
                )
                worker.deliverOne()
                val abandoned = checkNotNull(outbox.claim(BOT))
                client.sendMessage(abandoned.ownerId, abandoned.text) // Crash before acknowledging delivery.
                jdbc.update(
                    "UPDATE telegram_outbox SET lease_until = clock_timestamp() - interval '1 second' WHERE " +
                        "status = 'SENDING'",
                )
                TelegramDeliveryWorker(BOT, access, client, outbox).deliverOne()
                assertThat(count("ledger_entry")).isEqualTo(4)
                updateId = 4
                receive("/balance")
                assertThat(reply()).contains("1283000.00 KZT", "Доходы: 1300000.00", "Расходы: 17000.00")
                assertThat(
                    jdbc.queryForObject(
                        "SELECT sum(l.amount_kzt)::text FROM ledger_entry l JOIN category c ON c.id = l.category_id " +
                            "JOIN member m ON m.household_id = l.household_id WHERE m.telegram_user_id = ? " +
                            "AND c.name = 'Продукты'",
                        String::class.java,
                        OWNER,
                    ),
                ).isEqualTo("15700.00")
                server.verify(
                    postRequestedFor(urlEqualTo(API + "sendMessage")).withRequestBody(matchingJsonPath("$.text")),
                )
            } finally {
                server.stop()
            }
        }

        @Test
        fun `persistent clarification requires confirmation and concurrent confirmations record once`() {
            receive("аптека 1.001")
            assertThat(count("ledger_entry")).isZero()
            receive("аптека 10,01")
            val confirm = confirmation()
            receive("/balance")
            assertThat(reply()).contains("Учётный баланс: 0.00 KZT")
            accept(listOf(update(confirm), update(confirm)))
            concurrentProcess()
            assertThat(count("ledger_entry")).isEqualTo(1)
            assertThat(count("entry_revision")).isEqualTo(1)
            assertThat(
                jdbc.queryForObject("SELECT state FROM transaction_draft", String::class.java),
            ).isEqualTo("CONFIRMED")
            receive(confirm)
            assertThat(reply()).contains("Уже записано")
            receive("/balance")
            assertThat(reply()).contains("-10.01 KZT (отрицательный)")
            assertThat(
                jdbc.queryForObject(
                    "SELECT source_key FROM ledger_entry l JOIN member m ON m.household_id = l.household_id " +
                        "WHERE m.telegram_user_id = ?",
                    String::class.java,
                    OWNER,
                ),
            ).isEqualTo("telegram:$BOT:inbox:1")
        }

        @Test
        fun `stale version cancellation and foreign draft cannot post`() {
            receive("продукты 10 2026-08-31")
            val stale = draftCommand()
            receive("продукты 20 2026-09-01")
            val latest = confirmation()
            receive(stale)
            assertThat(reply()).contains("устарело")
            receive("/confirm ${UUID.randomUUID()} 0")
            assertThat(reply()).contains("недоступен")
            receive("/cancel")
            receive(latest)
            assertThat(reply()).contains("уже отменён")
            assertThat(count("ledger_entry")).isZero()
            receive("продукты 30")
            assertThat(count("ledger_entry")).isEqualTo(1)
        }

        @Test
        fun `rollback after posting restores inbox draft audit and outbox then new processor recovers`() {
            receive("продукты 1.001")
            receive("продукты 10")
            accept(listOf(update(confirmation())))
            tx.executeWithoutResult {
                assertThat(processor.processOne(BOT)).isTrue()
                assertThat(count("ledger_entry")).isEqualTo(1)
                it.setRollbackOnly()
            }
            assertThat(count("ledger_entry")).isZero()
            assertThat(count("entry_revision")).isZero()
            assertThat(
                jdbc.queryForObject("SELECT state FROM transaction_draft", String::class.java),
            ).isEqualTo("READY")
            assertThat(jdbc.queryForObject("SELECT count(*) FROM telegram_outbox", Long::class.java)).isEqualTo(2)
            tx.executeWithoutResult {
                val fresh =
                    TelegramInboxProcessor(
                        inbox,
                        setup,
                        access,
                        clock,
                        TelegramLedgerHandler(
                            access,
                            operations,
                            drafts,
                            clock,
                            categories,
                            TelegramCorrectionHandler(
                                access,
                                operations,
                                corrections,
                                categories,
                                clock,
                            ),
                        ),
                    )
                assertThat(fresh.processOne(BOT)).isTrue()
            }
            assertThat(count("ledger_entry")).isEqualTo(1)
            assertThat(count("entry_revision")).isEqualTo(1)
            assertThat(jdbc.queryForObject("SELECT count(*) FROM telegram_outbox", Long::class.java)).isEqualTo(3)
        }

        @Test
        fun `outbox failure rolls back financial entry and audit`() {
            accept(listOf(update("продукты 10")))
            try {
                jdbc.execute(
                    "ALTER TABLE telegram_outbox ADD CONSTRAINT synthetic_outbox_failure CHECK (text = 'blocked')",
                )
                assertThatThrownBy { processor.processOne(BOT) }.isInstanceOf(RuntimeException::class.java)
                assertThat(count("ledger_entry")).isZero()
                assertThat(count("entry_revision")).isZero()
                assertThat(
                    jdbc.queryForObject("SELECT status FROM telegram_inbox", String::class.java),
                ).isEqualTo("RECEIVED")
            } finally {
                jdbc.execute("ALTER TABLE telegram_outbox DROP CONSTRAINT IF EXISTS synthetic_outbox_failure")
            }
            assertThat(processor.processOne(BOT)).isTrue()
            assertThat(count("ledger_entry")).isEqualTo(1)
        }

        @Test
        fun `delayed message keeps original local date and predating opening asks for decision`() {
            receive("продукты 10", Instant.parse("2026-09-04T18:59:59Z"))
            assertThat(
                jdbc.queryForObject(
                    "SELECT occurred_on::text FROM ledger_entry l JOIN member m ON m.household_id = " +
                        "l.household_id WHERE m.telegram_user_id = ?",
                    String::class.java,
                    OWNER,
                ),
            ).isEqualTo("2026-09-04")
            receive("аптека 20", Instant.parse("2026-09-04T19:00:00Z"))
            receive("продукты 30", Instant.parse("2026-08-31T18:00:00Z"))
            assertThat(reply()).contains("раньше начала учёта", "начальный остаток", "/cancel")
            assertThat(count("ledger_entry")).isEqualTo(2)
            receive("продукты 30 2026-09-01")
            receive(confirmation())
            assertThat(count("ledger_entry")).isEqualTo(3)
        }

        @Test
        fun `missing rate persists proposal and daily request then confirmation uses immutable rate`() {
            val date = "2026-09-03"
            jdbc.update("DELETE FROM exchange_rate_job WHERE rate_date = ?::date", date)
            jdbc.update("DELETE FROM daily_exchange_rate WHERE rate_date = ?::date", date)
            receive("аптека 1,01 RUB $date")
            assertThat(reply()).contains("Нет курса RUB/KZT", "Баланс не изменён")
            assertThat(count("ledger_entry")).isZero()
            assertThat(
                jdbc.queryForObject(
                    "SELECT count(*) FROM exchange_rate_job WHERE rate_date = ?::date",
                    Long::class.java,
                    date,
                ),
            ).isEqualTo(1)
            receive("/draft")
            receive(confirmation())
            assertThat(count("ledger_entry")).isZero()
            jdbc.update(
                "INSERT INTO daily_exchange_rate(rate_date, rub_kzt, source, fetched_at, quoted_at) VALUES " +
                    "(?::date, 5.12345678, 'SYNTHETIC', ?::timestamptz, ?::timestamptz)",
                date,
                clock.instant().toString(),
                clock.instant().toString(),
            )
            receive(confirmation())
            assertThat(count("ledger_entry")).isEqualTo(1)
            assertThat(reply()).contains("1.01 RUB = 5.17 KZT", "5.12345678")
            receive("/history")
            assertThat(reply()).contains("1.01 RUB = 5.17 KZT")
        }

        @Test
        fun `history is bounded and cursor excludes new and previous entries`() {
            repeat(12) { receive("продукты ${it + 1}") }
            receive("/history")
            val first = reply()
            assertThat(first.lines().count { it.contains("расход, Продукты:") }).isEqualTo(10)
            val next = first.substringAfter("Далее: ")
            receive("аптека 99 2026-09-01")
            receive(next)
            assertThat(reply().lines().count { it.contains("расход, Продукты:") }).isEqualTo(2)
            assertThat(reply()).contains("Конец истории").doesNotContain("99.00", "3.00 KZT")
            receive("/history -1")
            assertThat(reply()).contains("Формат")
            assertThat(first.length).isLessThanOrEqualTo(4096)
        }

        @Test
        fun `week and month report commands return bounded factual analytics`() {
            receive("зарплата 1000")
            receive("продукты 250")

            receive("/report week")
            assertThat(reply()).contains(
                "Текущий недельный отчёт",
                "Доходы: 1000.00 KZT",
                "Чистые расходы: 250.00 KZT",
                "Начальный остаток, черновики, ожидаемые доходы и планы не включены",
            )
            assertThat(reply().length).isLessThanOrEqualTo(4096)

            receive("/report month")
            assertThat(reply()).contains("Текущий месячный отчёт", "Продукты: 250.00 KZT")
            receive("/report year")
            assertThat(reply()).contains("Формат: /report week или /report month")
        }

        @Test
        fun `unauthorized input and direct handler access cannot read or change ledger`() {
            val forbidden =
                IncomingUpdate(++updateId, IncomingMessage(999, 999, "private", false, "зарплата 10", clock.instant()))
            accept(listOf(forbidden))
            assertThat(processor.processOne(BOT)).isFalse()
            assertThat(count("ledger_entry")).isZero()
            assertThat(jdbc.queryForObject("SELECT text FROM telegram_inbox", String::class.java)).isNull()
            assertThatThrownBy {
                TelegramLedgerHandler(
                    access,
                    operations,
                    drafts,
                    clock,
                    categories,
                    TelegramCorrectionHandler(
                        access,
                        operations,
                        corrections,
                        categories,
                        clock,
                    ),
                ).handle(
                    com.firkatdavletov.financeconsultante.telegram.application
                        .InboxMessage(1, 999, "/balance"),
                    BOT,
                )
            }.isInstanceOf(IllegalArgumentException::class.java)
        }

        @Test
        fun `questions future amounts and old inbox without date never post automatically`() {
            receive("можно купить телефон 100?")
            assertThat(jdbc.queryForObject("SELECT count(*) FROM transaction_draft", Long::class.java)).isZero()
            receive("продукты 1.001")
            assertThat(reply()).contains("Сумма")
            receive("продукты 10 завтра")
            assertThat(reply()).contains("Будущая")
            receive("/cancel")
            accept(listOf(IncomingUpdate(++updateId, IncomingMessage(OWNER, OWNER, "private", false, "продукты 10"))))
            processor.processOne(BOT)
            assertThat(reply()).contains("Дата исходного сообщения неизвестна")
            assertThat(count("ledger_entry")).isZero()
        }

        @Test
        fun `confirmation from another bot cannot consume its draft`() {
            val otherBot = BOT + 1
            val lease = checkNotNull(polls.claim(otherBot))
            polls.accept(lease, listOf(update("продукты 1.001"), update("продукты 10")), access)
            repeat(2) { processor.processOne(otherBot) }
            val otherConfirmation = confirmation()
            receive(otherConfirmation)
            assertThat(reply()).contains("недоступен")
            assertThat(count("ledger_entry")).isZero()
        }

        @Test
        fun `conversion outside money range and conversion rounding to zero remain drafts`() {
            val date = "2026-09-02"
            jdbc.update("DELETE FROM exchange_rate_job WHERE rate_date = ?::date", date)
            jdbc.update("DELETE FROM daily_exchange_rate WHERE rate_date = ?::date", date)
            jdbc.update(
                "INSERT INTO daily_exchange_rate(rate_date, rub_kzt, source, fetched_at, quoted_at) " +
                    "VALUES (?::date, 5, 'SYNTHETIC', ?::timestamptz, ?::timestamptz)",
                date,
                clock.instant().toString(),
                clock.instant().toString(),
            )
            receive("аптека 99999999999999999.99 RUB $date")
            assertThat(reply()).contains("вне допустимого диапазона")
            assertThat(count("ledger_entry")).isZero()
            receive("/cancel")
            jdbc.update("DELETE FROM daily_exchange_rate WHERE rate_date = ?::date", date)
            jdbc.update(
                "INSERT INTO daily_exchange_rate(rate_date, rub_kzt, source, fetched_at, quoted_at) " +
                    "VALUES (?::date, 0.01, 'SYNTHETIC', ?::timestamptz, ?::timestamptz)",
                date,
                clock.instant().toString(),
                clock.instant().toString(),
            )
            receive("аптека 0.01 RUB $date")
            assertThat(reply()).contains("меньше 0,01")
            assertThat(count("ledger_entry")).isZero()
        }

        @Test
        fun `archived dictionary category is not silently recreated`() {
            receive("аптека 10")
            jdbc.update(
                "UPDATE category SET archived = true WHERE dictionary_key = 'HEALTH' AND " +
                    "household_id = (SELECT household_id FROM member WHERE telegram_user_id = ?)",
                OWNER,
            )
            receive("аптека 20")
            assertThat(reply()).contains("Категория архивирована")
            assertThat(count("ledger_entry")).isEqualTo(1)
            assertThat(
                jdbc.queryForObject(
                    "SELECT count(*) FROM category WHERE dictionary_key = 'HEALTH' AND " +
                        "household_id = (SELECT household_id FROM member WHERE telegram_user_id = ?)",
                    Long::class.java,
                    OWNER,
                ),
            ).isEqualTo(1)
        }

        @Test
        fun `balance includes posted operation exactly at current midnight`() {
            val midnight = Clock.fixed(Instant.parse("2026-09-05T19:00:00Z"), clock.zone)
            receive("продукты 0.01", midnight.instant())
            val household = tx.execute { operations.owned(OWNER) } ?: error("Missing owner")
            val accounting =
                AccountingService(
                    JdbcBudgetRepository(JdbcClient.create(jdbc)),
                    JdbcLedgerRepository(JdbcClient.create(jdbc)),
                    JdbcExchangeRateRepository(JdbcClient.create(jdbc)),
                    midnight,
                    JdbcRecurringPlanRepository(JdbcClient.create(jdbc)),
                )
            assertThat(accounting.currentBalance(household.householdId, household.memberId).amount.amount)
                .isEqualByComparingTo("-0.01")
        }

        private fun concurrentProcess() {
            val barrier = CyclicBarrier(2)
            val executor = Executors.newFixedThreadPool(2)
            try {
                val results =
                    executor.invokeAll(
                        List(2) {
                            Callable {
                                barrier.await(5, TimeUnit.SECONDS)
                                processor.processOne(BOT)
                            }
                        },
                    )
                assertThat(results.map { it.get(10, TimeUnit.SECONDS) }).containsExactly(true, true)
            } finally {
                executor.shutdownNow()
            }
        }

        private fun receive(
            text: String,
            sent: Instant = clock.instant(),
        ) {
            accept(listOf(update(text, sent)))
            assertThat(processor.processOne(BOT)).isTrue()
        }

        private fun update(
            text: String,
            sent: Instant = clock.instant(),
        ): IncomingUpdate = IncomingUpdate(++updateId, IncomingMessage(OWNER, OWNER, "private", false, text, sent))

        private fun accept(updates: List<IncomingUpdate>) {
            assertThat(polls.accept(checkNotNull(polls.claim(BOT)), updates, access)).isTrue()
        }

        private fun reply(): String =
            checkNotNull(
                jdbc.queryForObject("SELECT text FROM telegram_outbox ORDER BY id DESC LIMIT 1", String::class.java),
            )

        private fun confirmation(): String =
            checkNotNull(
                jdbc.queryForObject(
                    "SELECT '/confirm ' || id || ' ' || version FROM transaction_draft WHERE state IN " +
                        "('READY', 'WAITING_RATE')",
                    String::class.java,
                ),
            )

        private fun draftCommand(): String =
            checkNotNull(
                jdbc.queryForObject(
                    "SELECT '/confirm ' || id || ' ' || version FROM transaction_draft WHERE state IN " +
                        "('NEEDS_INPUT', 'READY', 'WAITING_RATE')",
                    String::class.java,
                ),
            )

        private fun count(table: String): Long =
            checkNotNull(
                jdbc.queryForObject(
                    "SELECT count(*) FROM $table l JOIN member m ON m.household_id = l.household_id WHERE " +
                        "m.telegram_user_id = ?",
                    Long::class.java,
                    OWNER,
                ),
            )

        private fun json(body: String) = aResponse().withHeader("Content-Type", "application/json").withBody(body)

        companion object {
            private const val API = "/bot123456:synthetic-token/"
        }
    }
