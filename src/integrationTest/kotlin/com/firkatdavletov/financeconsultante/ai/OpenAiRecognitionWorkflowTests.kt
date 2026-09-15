package com.firkatdavletov.financeconsultante.ai

import com.firkatdavletov.financeconsultante.PostgresIntegrationTest
import com.firkatdavletov.financeconsultante.ai.application.OpenAiRecognitionRouter
import com.firkatdavletov.financeconsultante.ai.application.OpenAiRecognitionWorker
import com.firkatdavletov.financeconsultante.ai.application.RecognitionJobRepository
import com.firkatdavletov.financeconsultante.ai.application.RecognitionObservation
import com.firkatdavletov.financeconsultante.ai.application.RecognitionResponse
import com.firkatdavletov.financeconsultante.ai.application.RecognitionRunResult
import com.firkatdavletov.financeconsultante.ai.application.RecognitionUsage
import com.firkatdavletov.financeconsultante.ai.application.TransactionRecognitionProvider
import com.firkatdavletov.financeconsultante.ai.domain.RecognitionCandidate
import com.firkatdavletov.financeconsultante.ai.domain.RecognitionCurrency
import com.firkatdavletov.financeconsultante.ai.domain.RecognitionIntent
import com.firkatdavletov.financeconsultante.ai.domain.RecognitionOperationType
import com.firkatdavletov.financeconsultante.ai.domain.RecognitionValidator
import com.firkatdavletov.financeconsultante.ai.infrastructure.OpenAiProperties
import com.firkatdavletov.financeconsultante.ledger.application.CategoryManagement
import com.firkatdavletov.financeconsultante.ledger.application.LedgerOperations
import com.firkatdavletov.financeconsultante.shared.domain.Money
import com.firkatdavletov.financeconsultante.telegram.TelegramTestConfiguration
import com.firkatdavletov.financeconsultante.telegram.TelegramTestConfiguration.Companion.BOT
import com.firkatdavletov.financeconsultante.telegram.TelegramTestConfiguration.Companion.OWNER
import com.firkatdavletov.financeconsultante.telegram.application.InboxStore
import com.firkatdavletov.financeconsultante.telegram.application.IncomingKind
import com.firkatdavletov.financeconsultante.telegram.application.IncomingMessage
import com.firkatdavletov.financeconsultante.telegram.application.IncomingUpdate
import com.firkatdavletov.financeconsultante.telegram.application.LedgerHandler
import com.firkatdavletov.financeconsultante.telegram.application.OwnerAccess
import com.firkatdavletov.financeconsultante.telegram.application.PollingStore
import com.firkatdavletov.financeconsultante.telegram.application.SetupStore
import com.firkatdavletov.financeconsultante.telegram.application.TelegramInboxProcessor
import com.firkatdavletov.financeconsultante.telegram.domain.BudgetSettings
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.ApplicationContext
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.TestPropertySource
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionSynchronizationManager
import org.springframework.transaction.support.TransactionTemplate
import java.time.Clock
import java.time.Duration
import java.time.LocalDate
import java.time.LocalTime
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

@Import(TelegramTestConfiguration::class)
@TestPropertySource(properties = ["app.telegram.bot-token=123456:synthetic-token", "app.telegram.owner-id=555"])
@Suppress("MagicNumber", "LongParameterList", "TooManyFunctions")
class OpenAiRecognitionWorkflowTests
    @Autowired
    constructor(
        private val polls: PollingStore,
        private val inbox: InboxStore,
        private val setup: SetupStore,
        private val ledger: LedgerHandler,
        private val operations: LedgerOperations,
        private val categories: CategoryManagement,
        private val jobs: RecognitionJobRepository,
        private val clock: Clock,
        private val jdbc: JdbcTemplate,
        private val context: ApplicationContext,
        private val transactionManager: PlatformTransactionManager,
    ) : PostgresIntegrationTest() {
        private val tx = TransactionTemplate(transactionManager)
        private val access = OwnerAccess(OWNER)
        private val router =
            OpenAiRecognitionRouter(
                jobs,
                operations,
                categories,
                clock,
                OpenAiProperties.DEFAULT_MODEL,
                OpenAiProperties.PROMPT_VERSION,
                OpenAiProperties.SCHEMA_VERSION,
            )
        private val processor = TelegramInboxProcessor(inbox, setup, access, clock, ledger, router)
        private var updateId = 0L

        @BeforeEach
        fun reset() {
            jdbc.execute(
                "TRUNCATE openai_recognition_job, openai_daily_usage, transaction_draft, telegram_outbox, " +
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
            tx.executeWithoutResult {
                setup.saveSettings(
                    OWNER,
                    BudgetSettings(Money.of("0"), LocalDate.parse("2026-09-01"), LocalTime.of(8, 0), Money.of("0")),
                )
            }
            updateId = 0
        }

        @Test
        fun `valid response creates a confirmed draft without changing ledger and stores usage metadata`() {
            receive("Вчера потратил 2500 на продукты")
            assertThat(value("SELECT status FROM telegram_inbox", String::class.java)).isEqualTo("OPENAI_PENDING")
            assertThat(count("ledger_entry")).isZero()

            val provider =
                TransactionRecognitionProvider { input ->
                    assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse()
                    val category = input.categories.single { it.name == "Продукты" }
                    RecognitionResponse.Success(
                        RecognitionObservation(
                            RecognitionCandidate(
                                RecognitionIntent.RECORD_TRANSACTION,
                                RecognitionOperationType.EXPENSE,
                                "2500",
                                RecognitionCurrency.KZT,
                                category.id.toString(),
                                "2026-09-05",
                                "Продукты",
                                false,
                            ),
                            "req_synthetic",
                            "resp_synthetic",
                            RecognitionUsage(81, 29),
                            42,
                        ),
                    )
                }

            assertThat(worker(provider).runOnce()).isEqualTo(RecognitionRunResult.COMPLETED)
            assertThat(count("ledger_entry")).isZero()
            assertThat(value("SELECT state FROM transaction_draft", String::class.java)).isEqualTo("READY")
            assertThat(
                jdbc.queryForMap(
                    "SELECT status, request_id, response_id, input_tokens, output_tokens, total_tokens, latency_ms " +
                        "FROM openai_recognition_job",
                ),
            ).containsEntry("status", "COMPLETED")
                .containsEntry("request_id", "req_synthetic")
                .containsEntry("response_id", "resp_synthetic")
                .containsEntry("input_tokens", 81L)
                .containsEntry("output_tokens", 29L)
                .containsEntry("total_tokens", 110L)
                .containsEntry("latency_ms", 42L)
            assertThat(
                jdbc.queryForObject("SELECT total_tokens FROM openai_daily_usage", Long::class.java),
            ).isEqualTo(110)
            assertThat(
                jdbc.queryForObject("SELECT count(*) FROM telegram_inbox WHERE text IS NOT NULL", Long::class.java),
            ).isZero()

            receive("/confirm")
            assertThat(count("ledger_entry")).isEqualTo(1)
            assertThat(worker(provider).runOnce()).isEqualTo(RecognitionRunResult.IDLE)
        }

        @Test
        @Suppress("LongMethod") // Reproduces the complete reported interaction and both callback outcomes.
        fun `independent AI expenses keep separate drafts with buttons while local expense records immediately`() {
            receive("/category_add expense отдых-и-развлечения")
            receive("/category_add expense кафе-и-рестораны")
            val provider =
                TransactionRecognitionProvider { input ->
                    val leisure = input.message.startsWith("Отдых", ignoreCase = true)
                    val categoryName = if (leisure) "отдых-и-развлечения" else "кафе-и-рестораны"
                    val amount = if (leisure) "1297.5" else "11900"
                    val category = input.categories.single { it.name == categoryName }
                    RecognitionResponse.Success(
                        RecognitionObservation(
                            RecognitionCandidate(
                                RecognitionIntent.RECORD_TRANSACTION,
                                RecognitionOperationType.EXPENSE,
                                amount,
                                RecognitionCurrency.KZT,
                                category.id.toString(),
                                "2026-09-06",
                                if (leisure) "отдых" else "кафе",
                                false,
                            ),
                            "req_${category.id}",
                            "resp_${category.id}",
                            RecognitionUsage(20, 10),
                            5,
                        ),
                    )
                }

            receive("Отдых 1297.5")
            assertThat(worker(provider).runOnce()).isEqualTo(RecognitionRunResult.COMPLETED)
            receive("Кафе 11900")
            assertThat(worker(provider).runOnce()).isEqualTo(RecognitionRunResult.COMPLETED)

            assertThat(count("transaction_draft")).isEqualTo(2)
            assertThat(
                jdbc.queryForList(
                    "SELECT category_name FROM transaction_draft WHERE state = 'READY' ORDER BY source_inbox_id",
                    String::class.java,
                ),
            ).containsExactly("отдых-и-развлечения", "кафе-и-рестораны")
            assertThat(
                jdbc.queryForObject("SELECT count(DISTINCT id) FROM transaction_draft", Long::class.java),
            ).isEqualTo(2)
            assertThat(jdbc.queryForObject("SELECT max(version) FROM transaction_draft", Long::class.java)).isZero()

            receive("/confirm")
            assertThat(reply()).contains("Выберите черновик", "Незавершённые черновики: 2")
            assertThat(count("ledger_entry")).isZero()

            receive("Продукты 6710")
            assertThat(count("ledger_entry")).isEqualTo(1)
            assertThat(
                jdbc.queryForObject("SELECT amount_kzt::text FROM ledger_entry", String::class.java),
            ).isEqualTo("6710.00")
            assertThat(count("transaction_draft")).isEqualTo(2)

            receive("/draft")
            assertThat(reply()).contains("отдых-и-развлечения", "кафе-и-рестораны")
            assertThat(
                jdbc.queryForObject(
                    "SELECT buttons::text FROM telegram_outbox ORDER BY id DESC LIMIT 1",
                    String::class.java,
                ),
            ).contains("dc:", "du:", "dx:", "✅ Записать 1", "✏️ Уточнить 2", "❌ Отменить 2")

            val confirmLeisure = draftAction("dc", "отдых-и-развлечения")
            receiveCallback(confirmLeisure)
            assertThat(reply()).contains("Записано", "1297.50 KZT")
            assertThat(count("ledger_entry")).isEqualTo(2)
            receiveCallback(confirmLeisure)
            assertThat(reply()).contains("Уже записано", "Повторного изменения баланса нет")
            assertThat(count("ledger_entry")).isEqualTo(2)

            receiveCallback(draftAction("du", "кафе-и-рестораны"))
            assertThat(reply()).contains("Выбран черновик для уточнения", "версия 1")
            receive("/expense 11900 кафе-и-рестораны")
            assertThat(reply()).contains("кафе-и-рестораны", "версия 2")
            assertThat(count("transaction_draft")).isEqualTo(2)
            assertThat(count("ledger_entry")).isEqualTo(2)

            receiveCallback(draftAction("dx", "кафе-и-рестораны"))
            assertThat(reply()).contains("Черновик отменён")
            assertThat(count("ledger_entry")).isEqualTo(2)
            receive("/draft")
            assertThat(reply()).contains("Незавершённых черновиков нет")
        }

        @Test
        fun `local format bypasses OpenAI and disabled configuration requires no provider`() {
            receive("продукты 100")

            assertThat(count("ledger_entry")).isEqualTo(1)
            assertThat(count("openai_recognition_job")).isZero()
            assertThat(context.getBeansOfType(TransactionRecognitionProvider::class.java)).isEmpty()
        }

        @Test
        fun `invalid model fields fall back without changing ledger`() {
            receive("Вчера потратил 700 на продукты")
            val unknownCategory = UUID.randomUUID().toString()
            val invalidProvider =
                TransactionRecognitionProvider {
                    RecognitionResponse.Success(
                        RecognitionObservation(
                            RecognitionCandidate(
                                RecognitionIntent.RECORD_TRANSACTION,
                                RecognitionOperationType.EXPENSE,
                                "700",
                                RecognitionCurrency.KZT,
                                unknownCategory,
                                "2026-09-05",
                                "Injected category",
                                false,
                            ),
                            "req_invalid",
                            "resp_invalid",
                            RecognitionUsage(20, 10),
                            9,
                        ),
                    )
                }
            assertThat(worker(invalidProvider).runOnce()).isEqualTo(RecognitionRunResult.FALLBACK)
            assertThat(count("ledger_entry")).isZero()
            assertThat(value("SELECT status FROM openai_recognition_job", String::class.java)).isEqualTo("FAILED")
            assertThat(value("SELECT last_error_code FROM openai_recognition_job", String::class.java))
                .isEqualTo("validation_category")
        }

        @Test
        fun `provider timeout falls back without changing ledger`() {
            receive("Оплатил кружок 800")

            assertThat(
                worker(TransactionRecognitionProvider { RecognitionResponse.Failure("timeout", 50) }).runOnce(),
            ).isEqualTo(RecognitionRunResult.FALLBACK)
            assertThat(count("ledger_entry")).isZero()
            assertThat(
                jdbc.queryForObject(
                    "SELECT count(*) FROM openai_recognition_job WHERE status = 'FAILED' " +
                        "AND last_error_code = 'timeout'",
                    Long::class.java,
                ),
            ).isEqualTo(1)
        }

        @Test
        fun `daily budget blocks the network call and leaves the ledger unchanged`() {
            receive("Вчера потратил 2000 на продукты")
            jdbc.update(
                "INSERT INTO openai_daily_usage(usage_date, request_units) VALUES (?, ?)",
                LocalDate.parse("2026-09-06"),
                1,
            )
            val calls = AtomicInteger()
            val provider =
                TransactionRecognitionProvider {
                    calls.incrementAndGet()
                    error("The provider must not be called after budget exhaustion")
                }

            assertThat(worker(provider, dailyLimit = 1).runOnce()).isEqualTo(RecognitionRunResult.FALLBACK)
            assertThat(calls).hasValue(0)
            assertThat(count("ledger_entry")).isZero()
            assertThat(value("SELECT status FROM openai_recognition_job", String::class.java))
                .isEqualTo("BUDGET_EXHAUSTED")
            assertThat(value("SELECT last_error_code FROM openai_recognition_job", String::class.java))
                .isEqualTo("daily_budget_exhausted")
        }

        @Test
        fun `revoked owner is rejected before the provider call`() {
            receive("Вчера потратил 2000 на продукты")
            val calls = AtomicInteger()
            val provider =
                TransactionRecognitionProvider {
                    calls.incrementAndGet()
                    error("The provider must not be called after access revocation")
                }

            assertThat(worker(provider, workerAccess = OwnerAccess(999)).runOnce())
                .isEqualTo(RecognitionRunResult.FALLBACK)
            assertThat(calls).hasValue(0)
            assertThat(count("ledger_entry")).isZero()
            assertThat(value("SELECT status FROM telegram_inbox", String::class.java)).isEqualTo("IGNORED")
            assertThat(value("SELECT last_error_code FROM openai_recognition_job", String::class.java))
                .isEqualTo("access_revoked")
        }

        private fun worker(
            provider: TransactionRecognitionProvider,
            dailyLimit: Int = 50,
            workerAccess: OwnerAccess = access,
        ) = OpenAiRecognitionWorker(
            jobs,
            provider,
            RecognitionValidator(),
            ledger,
            inbox,
            workerAccess,
            transactionManager,
            clock,
            Duration.ofMinutes(2),
            1,
            dailyLimit,
        )

        private fun receive(text: String) {
            val lease = checkNotNull(polls.claim(BOT))
            val message = IncomingMessage(OWNER, OWNER, "private", false, text, clock.instant())
            assertThat(polls.accept(lease, listOf(IncomingUpdate(++updateId, message)), access)).isTrue()
            assertThat(tx.execute { processor.processOne(BOT) }).isTrue()
        }

        private fun receiveCallback(data: String) {
            val lease = checkNotNull(polls.claim(BOT))
            val id = ++updateId
            val message =
                IncomingMessage(
                    OWNER,
                    OWNER,
                    "private",
                    false,
                    data,
                    kind = IncomingKind.CALLBACK,
                    callbackQueryId = "query_$id",
                    messageId = id,
                )
            assertThat(polls.accept(lease, listOf(IncomingUpdate(id, message)), access)).isTrue()
            assertThat(tx.execute { processor.processOne(BOT) }).isTrue()
        }

        private fun draftAction(
            action: String,
            category: String,
        ): String =
            checkNotNull(
                jdbc.queryForObject(
                    "SELECT ? || ':' || id || ':' || version FROM transaction_draft WHERE category_name = ?",
                    String::class.java,
                    action,
                    category,
                ),
            )

        private fun reply(): String =
            checkNotNull(
                jdbc.queryForObject("SELECT text FROM telegram_outbox ORDER BY id DESC LIMIT 1", String::class.java),
            )

        private fun count(table: String): Long =
            checkNotNull(jdbc.queryForObject("SELECT count(*) FROM $table", Long::class.java))

        private fun <T : Any> value(
            sql: String,
            type: Class<T>,
        ): T = checkNotNull(jdbc.queryForObject(sql, type))
    }
