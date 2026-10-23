package com.firkatdavletov.financeconsultante.mobileapi

import com.firkatdavletov.financeconsultante.PostgresIntegrationTest
import com.firkatdavletov.financeconsultante.ledger.application.AccountingService
import com.firkatdavletov.financeconsultante.ledger.application.RecordEntry
import com.firkatdavletov.financeconsultante.ledger.domain.Account
import com.firkatdavletov.financeconsultante.ledger.domain.BudgetSetup
import com.firkatdavletov.financeconsultante.ledger.domain.Category
import com.firkatdavletov.financeconsultante.ledger.domain.CategoryKind
import com.firkatdavletov.financeconsultante.ledger.domain.Direction
import com.firkatdavletov.financeconsultante.ledger.domain.Household
import com.firkatdavletov.financeconsultante.ledger.domain.Member
import com.firkatdavletov.financeconsultante.mobileapi.application.BudgetCommandReceipt
import com.firkatdavletov.financeconsultante.mobileapi.application.BudgetSyncCommand
import com.firkatdavletov.financeconsultante.mobileapi.application.BudgetSyncCommands
import com.firkatdavletov.financeconsultante.mobileapi.application.InvalidOfflineRequest
import com.firkatdavletov.financeconsultante.mobileapi.application.OfflineSyncConflict
import com.firkatdavletov.financeconsultante.mobileapi.application.OfflineSyncService
import com.firkatdavletov.financeconsultante.mobileapi.application.SyncChange
import com.firkatdavletov.financeconsultante.shared.domain.Money
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.TestPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@AutoConfigureMockMvc
@TestPropertySource(
    properties = [
        "app.mobile-api.enabled=true",
        "app.mobile-api.telegram-user-id=995082",
    ],
)
@Suppress("MagicNumber", "TooManyFunctions", "LongParameterList")
class BudgetSyncApiTests
    @Autowired
    constructor(
        private val commands: BudgetSyncCommands,
        private val sync: OfflineSyncService,
        private val accounting: AccountingService,
        private val jdbc: JdbcTemplate,
        private val mvc: MockMvc,
    ) : PostgresIntegrationTest() {
        private val households = mutableListOf<UUID>()

        @AfterEach fun cleanup() {
            households.forEach { id ->
                listOf(
                    "recurring_occurrence_payment",
                    "expected_income_match",
                    "recurring_occurrence",
                    "recurring_rule",
                    "expected_income",
                    "entry_revision",
                    "ledger_entry",
                    "mobile_sync_command",
                    "category",
                    "account",
                    "member",
                ).forEach { table ->
                    jdbc.update("DELETE FROM $table WHERE household_id = ?", id)
                }
                jdbc.update("DELETE FROM household WHERE id = ?", id)
            }
        }

        @Test fun `client IDs and stable receipts survive response loss and reject changed payload`() {
            val setup = budget()
            val c = command(setup, "CREATE_CATEGORY").copy(name = "Тестовая", kind = "EXPENSE", icon = "HOME")
            val first = commands.execute(USER, c)
            assertThat(commands.execute(USER, c)).isEqualTo(first)
            assertThat(
                jdbc.queryForObject("SELECT count(*) FROM category WHERE id = ?", Long::class.java, c.entityId),
            ).isEqualTo(1)
            assertThatThrownBy {
                commands.execute(
                    USER,
                    c.copy(name = "Другая"),
                )
            }.isInstanceOf(OfflineSyncConflict::class.java)
            val rule =
                command(setup, "CREATE_RULE").copy(
                    categoryId = c.entityId,
                    amount = "123.45",
                    frequency = "MONTHLY",
                    scheduleValue = 31,
                    startsOn = LocalDate.parse("2026-09-02"),
                )
            val receipt = commands.execute(USER, rule)
            assertThat(receipt.entityToken).isZero()
            val changes = drain()
            assertThat(
                changes.any { it.record?.id == rule.entityId.toString() && it.kind == "RECURRING_RULE" },
            ).isTrue()
            assertThat(
                changes.filter { it.kind == "RECURRING_OCCURRENCE" }.map { it.record!!.amount },
            ).contains("123.45")
            assertThat(sync.changes(USER, null, 200).changes.all { it.kind in setOf("LEDGER", "CATEGORY") }).isTrue()
            mvc
                .perform(get("/api/v2/sync/changes").header("Authorization", AUTH))
                .andExpect(status().isUnauthorized)
                .andExpect(jsonPath("$.code").value("unauthorized"))
        }

        @Test fun `delayed income commands validate against authored date and keep optimistic versions`() {
            val setup = budget()
            val create =
                command(setup, "CREATE_INCOME").copy(
                    categoryId = setup.categories.last().id,
                    amount = "99.01",
                    dueOn = LocalDate.parse("2026-09-03"),
                )
            commands.execute(USER, create)
            val edit =
                command(setup, "UPDATE_INCOME", create.entityId).copy(
                    expectedToken = 0,
                    amount = "100.02",
                    dueOn = LocalDate.parse("2026-09-04"),
                    authoredOn = LocalDate.parse("2026-09-03"),
                )
            assertThat(commands.execute(USER, edit).entityToken).isEqualTo(1)
            assertThatThrownBy {
                commands.execute(USER, edit.copy(requestId = UUID.randomUUID()))
            }.isInstanceOf(OfflineSyncConflict::class.java)
            val cancel = command(setup, "DELETE_INCOME", create.entityId).copy(expectedToken = 1)
            commands.execute(USER, cancel)
            assertThat(
                jdbc.queryForObject(
                    "SELECT status FROM expected_income WHERE id = ?",
                    String::class.java,
                    create.entityId,
                ),
            ).isEqualTo("CANCELLED")
        }

        @Test fun `concurrent retries create one category and one receipt`() {
            val setup = budget()
            val c = command(setup, "CREATE_CATEGORY").copy(name = "Тестовая", kind = "EXPENSE", icon = "HOME")
            val pool = Executors.newFixedThreadPool(2)
            val start = CountDownLatch(1)
            try {
                val results =
                    (1..2).map {
                        pool.submit<BudgetCommandReceipt> {
                            check(start.await(10, TimeUnit.SECONDS))
                            commands.execute(USER, c)
                        }
                    }
                start.countDown()
                assertThat(results.map { it.get(20, TimeUnit.SECONDS) }.distinct()).hasSize(1)
                assertThat(
                    jdbc.queryForObject(
                        "SELECT count(*) FROM mobile_sync_command WHERE request_id = ?",
                        Long::class.java,
                        c.requestId,
                    ),
                ).isEqualTo(1)
            } finally {
                pool.shutdownNow()
            }
        }

        @Test fun `category revision catches Telegram edits and scope check precedes writes`() {
            val setup = budget()
            val category = setup.categories.first()
            val revision = drain().last { it.category?.id == category.id }.revision
            jdbc.update("UPDATE category SET name = 'Изменена' WHERE id = ?", category.id)
            val c = command(setup, "UPDATE_CATEGORY", category.id).copy(expectedToken = revision, name = "Моя правка")
            assertThatThrownBy { commands.execute(USER, c) }.isInstanceOf(OfflineSyncConflict::class.java)
            assertThatThrownBy {
                commands.execute(USER, c.copy(householdId = UUID.randomUUID()))
            }.isInstanceOf(OfflineSyncConflict::class.java)
            assertThat(
                jdbc.queryForObject("SELECT name FROM category WHERE id = ?", String::class.java, category.id),
            ).isEqualTo("Изменена")
        }

        @Test fun `retired HTTP commands are denied while trusted commands reject future authorship`() {
            val setup = budget()
            val c = command(setup, "CREATE_INCOME")
            val body = """{"requestId":"${c.requestId}","householdId":"${c.householdId}","memberId":"${c.memberId}",
            "entityId":"${c.entityId}","type":"CREATE_INCOME","authoredOn":"2026-09-02",
            "categoryId":"${setup.categories.last().id}","amount":"10.01","dueOn":"2026-09-03"}"""
            mvc
                .perform(
                    post(
                        "/api/v2/sync/commands",
                    ).header("Authorization", AUTH).contentType(MediaType.APPLICATION_JSON).content(body),
                ).andExpect(status().isUnauthorized)
                .andExpect(jsonPath("$.code").value("unauthorized"))
            assertThatThrownBy {
                commands.execute(
                    USER,
                    c.copy(requestId = UUID.randomUUID(), authoredOn = LocalDate.parse("2026-09-07")),
                )
            }.isInstanceOf(InvalidOfflineRequest::class.java)
        }

        @Test
        @Suppress("LongMethod") // One vertical scenario proves that closure never creates another ledger entry.
        fun `rule edits closure and deletion preserve posted payments and sync their links`() {
            val setup = budget()
            val rule =
                command(setup, "CREATE_RULE").copy(
                    categoryId = setup.categories.first().id,
                    amount = "10.00",
                    frequency = "WEEKLY",
                    scheduleValue = 3,
                    startsOn = LocalDate.parse("2026-09-02"),
                )
            commands.execute(USER, rule)
            commands.execute(
                USER,
                command(setup, "UPDATE_RULE", rule.entityId).copy(expectedToken = 0, amount = "20.00"),
            )
            val occurrence =
                jdbc.queryForMap(
                    "SELECT id, version FROM recurring_occurrence WHERE rule_id = ? ORDER BY due_on LIMIT 1",
                    rule.entityId,
                )
            val entry =
                accounting
                    .record(
                        RecordEntry(
                            setup.household.id,
                            setup.owner.id,
                            setup.categories.first().id,
                            Direction.EXPENSE,
                            Money.of("5.00"),
                            Instant.parse("2026-09-02T00:00:00Z"),
                            "test:budget:payment",
                        ),
                    ).entry
            jdbc.update(
                "INSERT INTO recurring_occurrence_payment(household_id, occurrence_id, ledger_entry_id, created_at) " +
                    "VALUES (?, ?, ?, now())",
                setup.household.id,
                occurrence["id"],
                entry.id,
            )
            val close =
                command(
                    setup,
                    "CLOSE_EXPENSE",
                    occurrence["id"] as UUID,
                ).copy(expectedToken = (occurrence["version"] as Number).toLong())
            val receipt = commands.execute(USER, close)
            assertThat(commands.execute(USER, close)).isEqualTo(receipt)
            commands.execute(USER, command(setup, "DELETE_RULE", rule.entityId).copy(expectedToken = 1))
            assertThat(
                jdbc.queryForObject(
                    "SELECT status FROM recurring_occurrence WHERE id = ?",
                    String::class.java,
                    occurrence["id"],
                ),
            ).isEqualTo("PAID")
            assertThat(
                jdbc.queryForObject(
                    "SELECT count(*) FROM ledger_entry WHERE household_id = ?",
                    Long::class.java,
                    setup.household.id,
                ),
            ).isEqualTo(1)
            assertThat(
                drain().any { it.kind == "OCCURRENCE_PAYMENT" && it.record?.ledgerId == entry.id.toString() },
            ).isTrue()
        }

        private fun drain(): List<SyncChange> {
            val changes = mutableListOf<SyncChange>()
            var cursor: String? = null
            do {
                val page = sync.changes(USER, cursor, 1, 2)
                changes += page.changes
                cursor = page.nextCursor
            } while (page.hasMore)
            return changes
        }

        private fun command(
            s: BudgetSetup,
            type: String,
            id: UUID = UUID.randomUUID(),
        ) = BudgetSyncCommand(UUID.randomUUID(), s.household.id, s.owner.id, type, id, LocalDate.parse("2026-09-02"))

        private fun budget(): BudgetSetup {
            val h = Household(UUID.randomUUID())
            val s =
                BudgetSetup(
                    h,
                    Member(UUID.randomUUID(), h.id, USER),
                    Account(UUID.randomUUID(), h.id, Money.of("0"), Instant.parse("2026-09-01T00:00:00Z")),
                    listOf(
                        Category(UUID.randomUUID(), h.id, "Расход", CategoryKind.EXPENSE),
                        Category(UUID.randomUUID(), h.id, "Доход", CategoryKind.INCOME),
                    ),
                )
            accounting.openBudget(s)
            households += h.id
            return s
        }

        companion object {
            private const val USER = 995082L
            private const val AUTH = "Bearer synthetic-budget-token-1234567890"
        }
    }
