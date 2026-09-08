package com.firkatdavletov.financeconsultante.telegram

import com.firkatdavletov.financeconsultante.PostgresIntegrationTest
import com.firkatdavletov.financeconsultante.telegram.TelegramTestConfiguration.Companion.BOT
import com.firkatdavletov.financeconsultante.telegram.TelegramTestConfiguration.Companion.OWNER
import com.firkatdavletov.financeconsultante.telegram.application.IncomingMessage
import com.firkatdavletov.financeconsultante.telegram.application.IncomingUpdate
import com.firkatdavletov.financeconsultante.telegram.application.OutboxStore
import com.firkatdavletov.financeconsultante.telegram.application.OwnerAccess
import com.firkatdavletov.financeconsultante.telegram.application.PollingStore
import com.firkatdavletov.financeconsultante.telegram.application.TelegramClient
import com.firkatdavletov.financeconsultante.telegram.application.TelegramDeliveryWorker
import com.firkatdavletov.financeconsultante.telegram.application.TelegramFailure
import com.firkatdavletov.financeconsultante.telegram.application.TelegramInboxProcessor
import com.firkatdavletov.financeconsultante.telegram.application.TelegramPoller
import com.firkatdavletov.financeconsultante.telegram.infrastructure.RestTelegramClient
import com.firkatdavletov.financeconsultante.telegram.infrastructure.TelegramConfiguration
import com.firkatdavletov.financeconsultante.telegram.infrastructure.TelegramProperties
import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock.aResponse
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
import org.springframework.test.context.TestPropertySource
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionSynchronizationManager
import org.springframework.transaction.support.TransactionTemplate
import java.time.Duration
import java.util.concurrent.Callable
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@Import(TelegramTestConfiguration::class)
@TestPropertySource(properties = ["app.telegram.bot-token=123456:synthetic-token", "app.telegram.owner-id=555"])
@Suppress("MagicNumber", "TooManyFunctions")
class TelegramPersistenceTests
    @Autowired
    constructor(
        private val polls: PollingStore,
        private val outbox: OutboxStore,
        private val processor: TelegramInboxProcessor,
        private val jdbc: JdbcTemplate,
        transactionManager: PlatformTransactionManager,
    ) : PostgresIntegrationTest() {
        private val tx = TransactionTemplate(transactionManager)
        private val access = OwnerAccess(OWNER)

        @BeforeEach
        fun reset() {
            jdbc.execute(
                "TRUNCATE telegram_outbox, telegram_inbox, telegram_polling_state, telegram_dialog RESTART IDENTITY",
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
        fun `unauthorized group and unsupported updates store no payload and advance offset`() {
            val updates =
                listOf(
                    update(1, "/settings", user = 999),
                    update(2, "/settings", chat = -123, type = "group"),
                    update(3, "/start", isBot = true),
                    IncomingUpdate(4),
                    IncomingUpdate(5, IncomingMessage(OWNER, OWNER, "private", false, null)),
                )
            accept(updates)
            assertThat(offset()).isEqualTo(6)
            assertThat(count("telegram_inbox")).isEqualTo(5)
            assertThat(
                jdbc.queryForObject(
                    "SELECT count(*) FROM telegram_inbox WHERE text IS NOT NULL OR owner_id IS NOT NULL",
                    Long::class.java,
                ),
            ).isZero()
            assertThat(processor.processOne(BOT)).isFalse()
            assertThat(count("telegram_dialog")).isZero()
            assertThat(count("telegram_outbox")).isZero()
        }

        @Test
        fun `failure before inbox commit leaves offset unchanged and redelivery is safe`() {
            val lease = checkNotNull(polls.claim(BOT))
            tx.executeWithoutResult { status ->
                assertThat(polls.accept(lease, listOf(update(10, "/start")), access)).isTrue()
                status.setRollbackOnly()
            }
            assertThat(offset()).isZero()
            assertThat(count("telegram_inbox")).isZero()
            assertThat(polls.accept(lease, listOf(update(10, "/start")), access)).isTrue()
            assertThat(offset()).isEqualTo(11)
            assertThat(processor.processOne(BOT)).isTrue()
        }

        @Test
        fun `committed inbox survives restart and duplicate updates cannot replay the dialogue`() {
            accept(listOf(update(10, "/start")))
            assertThat(
                jdbc.queryForObject("SELECT status FROM telegram_inbox", String::class.java),
            ).isEqualTo("RECEIVED")
            processor.processOne(BOT)
            receive(11, "1234,50")
            receive(12, "/start")
            assertThat(jdbc.queryForObject("SELECT step FROM telegram_dialog", String::class.java)).isEqualTo("DATE")
            accept(listOf(update(11, "9999"), update(12, "/start")))
            assertThat(processor.processOne(BOT)).isFalse()
            receive(13, "2026-03-01")
            receive(14, "07:45")
            assertThat(ownerBudgetCount()).isZero()
            receive(15, "200")
            assertThat(ownerBudgetCount()).isEqualTo(1)
            assertThat(
                jdbc.queryForObject(
                    "SELECT opening_balance FROM account a JOIN member m USING (household_id) WHERE " +
                        "telegram_user_id = ?",
                    String::class.java,
                    OWNER,
                ),
            ).isEqualTo("1234.50")
            assertThat(
                jdbc.queryForObject(
                    "SELECT (accounting_started_at AT TIME ZONE 'UTC')::text " +
                        "FROM account a JOIN member m USING (household_id) " +
                        "WHERE telegram_user_id = ?",
                    String::class.java,
                    OWNER,
                ),
            ).startsWith("2026-02-28 19:00:00")
            assertThat(
                jdbc.queryForObject(
                    "SELECT count(*) FROM ledger_entry e JOIN member m USING (household_id) WHERE telegram_user_id = ?",
                    Long::class.java,
                    OWNER,
                ),
            ).isZero()
            assertThat(
                jdbc.queryForObject("SELECT count(*) FROM telegram_inbox WHERE text IS NOT NULL", Long::class.java),
            ).isZero()
            assertThat(count("telegram_outbox")).isEqualTo(6)
        }

        @Test
        fun `settings inbox completion and response roll back together`() {
            onboardingUntilReserve()
            accept(listOf(update(5, "100")))
            tx.executeWithoutResult { status ->
                processor.processOne(BOT)
                assertThat(ownerBudgetCount()).isEqualTo(1)
                status.setRollbackOnly()
            }
            assertThat(ownerBudgetCount()).isZero()
            assertThat(jdbc.queryForObject("SELECT step FROM telegram_dialog", String::class.java)).isEqualTo("RESERVE")
            assertThat(count("telegram_outbox")).isEqualTo(4)
            assertThat(processor.processOne(BOT)).isTrue()
            assertThat(ownerBudgetCount()).isEqualTo(1)
            assertThat(count("telegram_outbox")).isEqualTo(5)
            accept(listOf(update(5, "900")))
            assertThat(processor.processOne(BOT)).isFalse()
        }

        @Test
        fun `concurrent pollers get one lease and expired worker cannot commit`() {
            val barrier = CyclicBarrier(2)
            val executor = Executors.newFixedThreadPool(2)
            try {
                val claims =
                    executor
                        .invokeAll(
                            List(2) {
                                Callable {
                                    barrier.await(5, TimeUnit.SECONDS)
                                    polls.claim(BOT)
                                }
                            },
                        ).map { it.get(5, TimeUnit.SECONDS) }
                assertThat(claims.count { it != null }).isEqualTo(1)
                val stale = claims.filterNotNull().single()
                jdbc.update("UPDATE telegram_polling_state SET lease_until = clock_timestamp() - interval '1 second'")
                val replacement = checkNotNull(polls.claim(BOT))
                assertThat(polls.accept(stale, listOf(update(99, "/start")), access)).isFalse()
                assertThat(offset()).isZero()
                assertThat(polls.accept(replacement, listOf(update(1, "/help")), access)).isTrue()
            } finally {
                executor.shutdownNow()
            }
        }

        @Test
        fun `two processors preserve input order and create one response per update`() {
            accept(listOf(update(1, "/start"), update(2, "100")))
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
                assertThat(results.map { it.get(5, TimeUnit.SECONDS) }).containsExactly(true, true)
                assertThat(
                    jdbc.queryForObject("SELECT step FROM telegram_dialog", String::class.java),
                ).isEqualTo("DATE")
                assertThat(count("telegram_outbox")).isEqualTo(2)
            } finally {
                executor.shutdownNow()
            }
        }

        @Test
        fun `outbox respects retry_after and stale completion cannot steal a reclaimed delivery`() {
            receive(1, "/help")
            val first = checkNotNull(outbox.claim(BOT))
            assertThat(outbox.claim(BOT)).isNull()
            outbox.failed(first, TelegramFailure("api_429", Duration.ofSeconds(41)))
            assertThat(outbox.claim(BOT)).isNull()
            jdbc.update("UPDATE telegram_outbox SET next_attempt_at = clock_timestamp() - interval '1 second'")
            val second = checkNotNull(outbox.claim(BOT))
            outbox.sent(first, 11)
            assertThat(
                jdbc.queryForObject("SELECT status FROM telegram_outbox", String::class.java),
            ).isEqualTo("SENDING")
            outbox.sent(second, 12)
            assertThat(
                jdbc.queryForObject("SELECT telegram_message_id FROM telegram_outbox", Long::class.java),
            ).isEqualTo(12)
        }

        @Test
        fun `ambiguous send can duplicate only the reply and uses the stored text`() {
            receive(1, "/help")
            val first = checkNotNull(outbox.claim(BOT))
            val sent = mutableListOf<String>()
            val client =
                object : TelegramClient {
                    override fun getUpdates(offset: Long): List<IncomingUpdate> = error("Unexpected polling")

                    override fun sendMessage(
                        chatId: Long,
                        text: String,
                    ): Long {
                        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse()
                        sent += text
                        return sent.size.toLong()
                    }
                }
            client.sendMessage(first.ownerId, first.text) // Process stops here, before SENT is committed.
            jdbc.update("UPDATE telegram_outbox SET lease_until = clock_timestamp() - interval '1 second'")
            TelegramDeliveryWorker(BOT, access, client, outbox).deliverOne()
            assertThat(sent).containsExactly(first.text, first.text)
            assertThat(count("telegram_outbox")).isEqualTo(1)
            assertThat(ownerBudgetCount()).isZero()
        }

        @Test
        fun `polling HTTP happens outside transaction and restart uses durable offset`() {
            val offsets = mutableListOf<Long>()
            val client =
                object : TelegramClient {
                    override fun getUpdates(offset: Long): List<IncomingUpdate> {
                        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse()
                        offsets += offset
                        return if (offset == 0L) listOf(update(10, "/help")) else emptyList()
                    }

                    override fun sendMessage(
                        chatId: Long,
                        text: String,
                    ): Long = error("Unexpected send")
                }
            TelegramPoller(BOT, access, client, polls).pollOnce()
            TelegramPoller(BOT, access, client, polls).pollOnce()
            assertThat(offsets).containsExactly(0, 11)
        }

        @Test
        fun `WireMock to database to reply completes without live API`() {
            val server = WireMockServer(options().dynamicPort().http2PlainDisabled(true))
            server.start()
            try {
                val path = "/bot123456:synthetic-token/"
                server.stubFor(
                    post(urlEqualTo(path + "getUpdates")).willReturn(
                        aResponse()
                            .withHeader("Content-Type", "application/json")
                            .withBody(
                                """
{
  "ok": true,
  "result": [
    {
      "update_id": 1,
      "message": {
        "from": {
          "id": 555,
          "is_bot": false
        },
        "chat": {
          "id": 555,
          "type": "private"
        },
        "text": "/start"
      }
    }
  ]
}
""",
                            ),
                    ),
                )
                server.stubFor(
                    post(urlEqualTo(path + "sendMessage")).willReturn(
                        aResponse()
                            .withHeader("Content-Type", "application/json")
                            .withBody("""{"ok":true,"result":{"message_id":91}}"""),
                    ),
                )
                val client =
                    TelegramConfiguration().telegramClient(
                        TelegramProperties("123456:synthetic-token", OWNER, server.baseUrl()),
                    )
                assertThat(client).isInstanceOf(RestTelegramClient::class.java)
                TelegramPoller(BOT, access, client, polls).pollOnce()
                processor.processOne(BOT)
                TelegramDeliveryWorker(BOT, access, client, outbox).deliverOne()
                assertThat(offset()).isEqualTo(2)
                assertThat(
                    jdbc.queryForObject("SELECT status FROM telegram_outbox", String::class.java),
                ).isEqualTo("SENT")
                server.verify(1, postRequestedFor(urlEqualTo(path + "sendMessage")))
            } finally {
                server.stop()
            }
        }

        @Test
        fun `opening cannot change after ledger even through direct SQL while reserve and report remain editable`() {
            onboardingUntilReserve()
            receive(5, "0")
            jdbc.update(
                """
INSERT INTO category(id, household_id, name, kind)
            SELECT gen_random_uuid(), household_id, 'Synthetic', 'EXPENSE' FROM member WHERE
telegram_user_id = ?
""",
                OWNER,
            )
            jdbc.update(
                """
INSERT INTO ledger_entry(id, household_id, account_id, accounting_started_at, category_id,
            direction, original_amount, original_currency, amount_kzt, occurred_at, actor_id,
created_at, source_key)
            SELECT gen_random_uuid(), m.household_id, a.id, a.accounting_started_at, c.id,
'EXPENSE', 10, 'KZT', 10,
                a.accounting_started_at, m.id, a.accounting_started_at, 'synthetic'
            FROM member m JOIN account a USING(household_id) JOIN category c USING(household_id)
            WHERE m.telegram_user_id = ?
""",
                OWNER,
            )
            receive(6, "/opening")
            assertThat(
                jdbc.queryForObject("SELECT text FROM telegram_outbox ORDER BY id DESC LIMIT 1", String::class.java),
            ).contains("нельзя")
            assertThatThrownBy {
                jdbc.update(
                    "UPDATE account SET opening_balance = 200 WHERE household_id = (SELECT household_id " +
                        "FROM member WHERE telegram_user_id = ?)",
                    OWNER,
                )
            }.isInstanceOf(org.springframework.dao.DataIntegrityViolationException::class.java)
            receive(7, "/reserve")
            receive(8, "25")
            receive(9, "/report_time")
            receive(10, "09:30")
            assertThat(
                jdbc.queryForObject(
                    "SELECT cash_reserve FROM household h JOIN member m ON m.household_id = h.id WHERE " +
                        "telegram_user_id = ?",
                    String::class.java,
                    OWNER,
                ),
            ).isEqualTo("25.00")
            assertThat(
                jdbc.queryForObject(
                    "SELECT opening_balance FROM account a JOIN member m USING(household_id) " +
                        "WHERE telegram_user_id = ?",
                    String::class.java,
                    OWNER,
                ),
            ).isEqualTo("100.00")
        }

        @Test
        fun `poll flood control persists cooldown without acknowledging an update`() {
            val client =
                object : TelegramClient {
                    override fun getUpdates(offset: Long): List<IncomingUpdate> =
                        throw TelegramFailure("api_429", Duration.ofSeconds(41))

                    override fun sendMessage(
                        chatId: Long,
                        text: String,
                    ): Long = error("Unexpected send")
                }
            TelegramPoller(BOT, access, client, polls).pollOnce()
            assertThat(offset()).isZero()
            assertThat(polls.claim(BOT)).isNull()
            assertThat(count("telegram_inbox")).isZero()
        }

        @Test
        fun `crashed delivery stops after the bounded number of attempts and unblocks the next reply`() {
            receive(1, "/help")
            receive(2, "/settings")
            repeat(5) {
                assertThat(checkNotNull(outbox.claim(BOT)).id).isEqualTo(1)
                jdbc.update(
                    "UPDATE telegram_outbox SET lease_until = clock_timestamp() - interval '1 second' WHERE id = 1",
                )
            }
            assertThat(checkNotNull(outbox.claim(BOT)).id).isEqualTo(2)
            assertThat(jdbc.queryForObject("SELECT status FROM telegram_outbox WHERE id = 1", String::class.java))
                .isEqualTo("FAILED")
        }

        @Test
        fun `cancelled onboarding leaves no budget and start begins a new persistent dialog`() {
            receive(1, "/start")
            receive(2, "100")
            receive(3, "/cancel")
            assertThat(ownerBudgetCount()).isZero()
            assertThat(jdbc.queryForObject("SELECT step FROM telegram_dialog", String::class.java)).isEqualTo("IDLE")
            assertThat(jdbc.queryForObject("SELECT opening_balance FROM telegram_dialog", String::class.java)).isNull()
            receive(4, "/start")
            assertThat(jdbc.queryForObject("SELECT step FROM telegram_dialog", String::class.java)).isEqualTo("OPENING")
        }

        private fun onboardingUntilReserve() {
            receive(1, "/start")
            receive(2, "100")
            receive(3, "2026-03-01")
            receive(4, "08:00")
        }

        private fun receive(
            id: Long,
            text: String,
        ) {
            accept(listOf(update(id, text)))
            assertThat(processor.processOne(BOT)).isTrue()
        }

        private fun accept(updates: List<IncomingUpdate>) {
            assertThat(polls.accept(checkNotNull(polls.claim(BOT)), updates, access)).isTrue()
        }

        private fun update(
            id: Long,
            text: String,
            user: Long = OWNER,
            chat: Long = OWNER,
            type: String = "private",
            isBot: Boolean = false,
        ): IncomingUpdate = IncomingUpdate(id, IncomingMessage(user, chat, type, isBot, text))

        private fun offset(): Long =
            checkNotNull(
                jdbc.queryForObject(
                    "SELECT next_offset FROM telegram_polling_state WHERE bot_id = ?",
                    Long::class.java,
                    BOT,
                ),
            )

        private fun count(table: String): Long =
            checkNotNull(jdbc.queryForObject("SELECT count(*) FROM $table", Long::class.java))

        private fun ownerBudgetCount(): Long =
            checkNotNull(
                jdbc.queryForObject("SELECT count(*) FROM member WHERE telegram_user_id = ?", Long::class.java, OWNER),
            )
    }
