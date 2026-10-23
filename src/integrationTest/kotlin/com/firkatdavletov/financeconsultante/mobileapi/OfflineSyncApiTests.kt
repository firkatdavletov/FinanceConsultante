package com.firkatdavletov.financeconsultante.mobileapi

import com.firkatdavletov.financeconsultante.PostgresIntegrationTest
import com.firkatdavletov.financeconsultante.ledger.application.AccountingService
import com.firkatdavletov.financeconsultante.ledger.application.EntryCorrections
import com.firkatdavletov.financeconsultante.ledger.application.EntryTarget
import com.firkatdavletov.financeconsultante.ledger.application.RecordEntry
import com.firkatdavletov.financeconsultante.ledger.domain.Account
import com.firkatdavletov.financeconsultante.ledger.domain.BudgetSetup
import com.firkatdavletov.financeconsultante.ledger.domain.Category
import com.firkatdavletov.financeconsultante.ledger.domain.CategoryKind
import com.firkatdavletov.financeconsultante.ledger.domain.Direction
import com.firkatdavletov.financeconsultante.ledger.domain.EntryChange
import com.firkatdavletov.financeconsultante.ledger.domain.Household
import com.firkatdavletov.financeconsultante.ledger.domain.Member
import com.firkatdavletov.financeconsultante.mobileapi.application.CreateOfflineOperation
import com.firkatdavletov.financeconsultante.mobileapi.application.CreateOfflineOperationRequest
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
import org.springframework.transaction.support.TransactionTemplate
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
        "app.mobile-api.telegram-user-id=995002",
    ],
)
@Suppress("MagicNumber", "LongParameterList", "TooManyFunctions") // Shared fixtures exercise one sync contract.
class OfflineSyncApiTests
    @Autowired
    constructor(
        private val mvc: MockMvc,
        private val accounting: AccountingService,
        private val corrections: EntryCorrections,
        private val create: CreateOfflineOperation,
        private val sync: OfflineSyncService,
        private val jdbc: JdbcTemplate,
        private val transaction: TransactionTemplate,
    ) : PostgresIntegrationTest() {
        private val households = mutableListOf<UUID>()

        @AfterEach
        fun cleanup() {
            households.forEach { id ->
                jdbc.update("DELETE FROM entry_revision WHERE household_id = ?", id)
                jdbc.update("DELETE FROM ledger_entry WHERE household_id = ?", id)
                jdbc.update("DELETE FROM category WHERE household_id = ?", id)
                jdbc.update("DELETE FROM account WHERE household_id = ?", id)
                jdbc.update("DELETE FROM member WHERE household_id = ?", id)
                jdbc.update("DELETE FROM household WHERE id = ?", id)
            }
        }

        @Test
        fun `lost response retry returns one entry and current state after correction`() {
            val setup = budget()
            val request = request(setup)
            val first = create.create(USER, request)
            corrections.change(
                EntryTarget(setup.household.id, setup.owner.id, first.operation.id, 0),
                EntryChange.Amount(Money.of("15.01")),
                "test:offline:correct",
            )
            val retry = create.create(USER, request)
            assertThat(retry.operation.id).isEqualTo(first.operation.id)
            assertThat(retry.operation.amountKzt).isEqualTo("15.01")
            assertThat(retry.operation.version).isEqualTo(1)
            assertThat(count("ledger_entry", setup)).isEqualTo(1)
            assertThat(count("mobile_operation_request", setup)).isEqualTo(1)
            mvc
                .perform(postRequest(body(request.copy(amount = "20"))))
                .andExpect(status().isUnauthorized)
                .andExpect(jsonPath("$.code").value("unauthorized"))
            assertThatThrownBy { create.create(USER, request.copy(amount = "20")) }
                .isInstanceOf(OfflineSyncConflict::class.java)
            val events = drain().first.mapNotNull { it.operation }.filter { it.requestId == request.requestId }
            assertThat(events).hasSize(2)
            assertThat(events.last().amountKzt).isEqualTo("15.01")
        }

        @Test
        fun `simultaneous duplicate posts create exactly one financial operation`() {
            val setup = budget()
            val request = request(setup)
            val executor = Executors.newFixedThreadPool(2)
            val start = CountDownLatch(1)
            try {
                val results =
                    (1..2).map {
                        executor.submit<UUID> {
                            check(start.await(10, TimeUnit.SECONDS))
                            create.create(USER, request).operation.id
                        }
                    }
                start.countDown()
                assertThat(results.map { it.get(20, TimeUnit.SECONDS) }.distinct()).hasSize(1)
                assertThat(count("ledger_entry", setup)).isEqualTo(1)
                assertThat(count("entry_revision", setup)).isEqualTo(1)
            } finally {
                executor.shutdownNow()
            }
        }

        @Test
        fun `trusted creation validates financial input while retired HTTP rejects credentials`() {
            val setup = budget()
            val request = request(setup)
            val first = create.create(USER, request)
            assertThat(first.operation.amountKzt).isEqualTo("10.01")
            listOf(
                request.copy(requestId = UUID.randomUUID(), currency = "RUB"),
                request.copy(requestId = UUID.randomUUID(), amount = "1.001"),
                request.copy(requestId = UUID.randomUUID(), occurredOn = LocalDate.parse("2026-09-07")),
                request.copy(requestId = UUID.randomUUID(), categoryId = UUID.randomUUID()),
            ).forEach { invalid ->
                assertThatThrownBy { create.create(USER, invalid) }.isInstanceOf(InvalidOfflineRequest::class.java)
            }
            assertThatThrownBy { create.create(USER, request.copy(memberId = UUID.randomUUID())) }
                .isInstanceOf(OfflineSyncConflict::class.java)
            jdbc.update("UPDATE category SET archived = TRUE WHERE id = ?", request.categoryId)
            assertThatThrownBy { create.create(USER, request.copy(requestId = UUID.randomUUID())) }
                .isInstanceOf(InvalidOfflineRequest::class.java)
            assertThat(create.create(USER, request).operation.id).isEqualTo(first.operation.id)
            mvc.perform(postRequest(body(request))).andExpect(status().isUnauthorized)
            mvc.perform(get("/api/v1/sync/changes").header("Authorization", AUTH)).andExpect(status().isUnauthorized)
        }

        @Test
        fun `paged snapshot resumes and later writes are delivered by the next snapshot`() {
            val setup = budget()
            val first = sync.changes(USER, null, 1)
            assertThat(first.hasMore).isTrue()
            val posted = create.create(USER, request(setup)).operation
            val rest = drain(first.nextCursor, 1)
            assertThat((first.changes + rest.first).mapNotNull { it.operation }).isEmpty()
            val next = drain(rest.second, 1)
            assertThat(next.first.mapNotNull { it.operation }.map { it.id }).containsExactly(posted.id)
            assertThat(next.first.map { it.revision }).isSorted().doesNotHaveDuplicates()
            val other = budget(995003)
            accounting.record(
                RecordEntry(
                    other.household.id,
                    other.owner.id,
                    other.categories.first().id,
                    Direction.EXPENSE,
                    Money.of("2"),
                    Instant.parse("2026-09-02T00:00:00Z"),
                    "test:offline:foreign",
                ),
            )
            assertThat(drain(next.second).first).isEmpty()
        }

        @Test
        fun `journal contains Telegram style corrections voids refunds and category changes`() {
            val setup = budget()
            val initial = drain().second
            val expense =
                accounting
                    .record(
                        RecordEntry(
                            setup.household.id,
                            setup.owner.id,
                            setup.categories.first().id,
                            Direction.EXPENSE,
                            Money.of("10"),
                            Instant.parse("2026-09-02T00:00:00Z"),
                            "test:offline:telegram",
                        ),
                    ).entry
            corrections.refund(
                EntryTarget(setup.household.id, setup.owner.id, expense.id, 0),
                Money.of("2"),
                LocalDate.parse("2026-09-03"),
                "test:offline:refund",
            )
            jdbc.update("UPDATE category SET name = 'Новое имя' WHERE id = ?", setup.categories.first().id)
            val income =
                create.create(
                    USER,
                    request(setup).copy(type = "INCOME", categoryId = setup.categories.last().id),
                )
            corrections.change(
                EntryTarget(setup.household.id, setup.owner.id, income.operation.id, 0),
                EntryChange.Void,
                "test:offline:void",
            )
            val changes = drain(initial).first
            assertThat(changes.mapNotNull { it.operation }.map { it.type }).contains("EXPENSE_REFUND")
            assertThat(changes.mapNotNull { it.operation }.last().status).isEqualTo("VOIDED")
            assertThat(changes.mapNotNull { it.category }.last().name).isEqualTo("Новое имя")
            mvc
                .perform(get("/api/v1/sync/changes").header("Authorization", AUTH))
                .andExpect(status().isUnauthorized)
                .andExpect(jsonPath("$.code").value("unauthorized"))
        }

        @Test
        fun `revision lock prevents a later commit overtaking an earlier transaction and rolls back atomically`() {
            val setup = budget()
            val cursor = drain().second
            val locked = CountDownLatch(1)
            val release = CountDownLatch(1)
            val secondStarted = CountDownLatch(1)
            val executor = Executors.newFixedThreadPool(2)
            try {
                val first =
                    executor.submit {
                        transaction.executeWithoutResult {
                            jdbc.update("UPDATE category SET name = 'Первая' WHERE id = ?", setup.categories.first().id)
                            locked.countDown()
                            check(release.await(15, TimeUnit.SECONDS))
                        }
                    }
                check(locked.await(10, TimeUnit.SECONDS))
                val second =
                    executor.submit {
                        transaction.executeWithoutResult {
                            secondStarted.countDown()
                            jdbc.update("UPDATE category SET name = 'Вторая' WHERE id = ?", setup.categories.last().id)
                        }
                    }
                check(secondStarted.await(10, TimeUnit.SECONDS))
                assertThat(drain(cursor).first).isEmpty()
                release.countDown()
                first.get(20, TimeUnit.SECONDS)
                second.get(20, TimeUnit.SECONDS)
                val changes = drain(cursor)
                assertThat(changes.first.mapNotNull { it.category }.map { it.name }).containsExactly("Первая", "Вторая")
                transaction.executeWithoutResult { status ->
                    jdbc.update("UPDATE category SET name = 'Откат' WHERE id = ?", setup.categories.first().id)
                    status.setRollbackOnly()
                }
                assertThat(drain(changes.second).first).isEmpty()
            } finally {
                release.countDown()
                executor.shutdownNow()
            }
        }

        @Test
        fun `sync preserves maximum money precision without floating point conversion`() {
            val setup = budget()
            val posted = create.create(USER, request(setup).copy(amount = "99999999999999999.99"))
            assertThat(posted.operation.amountKzt).isEqualTo("99999999999999999.99")
            assertThat(
                drain()
                    .first
                    .mapNotNull { it.operation }
                    .single()
                    .amountKzt,
            ).isEqualTo("99999999999999999.99")
        }

        private fun drain(
            cursor: String? = null,
            limit: Int = 200,
        ): Pair<List<SyncChange>, String> {
            val events = mutableListOf<SyncChange>()
            var position = cursor
            do {
                val page = sync.changes(USER, position, limit)
                events += page.changes
                position = page.nextCursor
            } while (page.hasMore)
            return events to requireNotNull(position)
        }

        private fun budget(user: Long = USER): BudgetSetup {
            val household = Household(UUID.randomUUID())
            val setup =
                BudgetSetup(
                    household,
                    Member(UUID.randomUUID(), household.id, user),
                    Account(UUID.randomUUID(), household.id, Money.of("0"), Instant.parse("2026-09-01T00:00:00Z")),
                    listOf(
                        Category(UUID.randomUUID(), household.id, "Расход", CategoryKind.EXPENSE),
                        Category(UUID.randomUUID(), household.id, "Доход", CategoryKind.INCOME),
                    ),
                )
            accounting.openBudget(setup)
            households += household.id
            return setup
        }

        private fun request(setup: BudgetSetup) =
            CreateOfflineOperationRequest(
                UUID.randomUUID(),
                setup.household.id,
                setup.owner.id,
                "EXPENSE",
                setup.categories.first().id,
                "10.01",
                "KZT",
                LocalDate.parse("2026-09-02"),
            )

        private fun body(request: CreateOfflineOperationRequest) =
            """{
        "requestId":"${request.requestId}","householdId":"${request.householdId}","memberId":"${request.memberId}",
        "type":"${request.type}","categoryId":"${request.categoryId}","amount":"${request.amount}",
        "currency":"${request.currency}","occurredOn":"${request.occurredOn}"
    }"""

        private fun postRequest(body: String) =
            post("/api/v1/operations")
                .header("Authorization", AUTH)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)

        private fun count(
            table: String,
            setup: BudgetSetup,
        ) = jdbc.queryForObject(
            "SELECT count(*) FROM $table WHERE household_id = ?",
            Long::class.java,
            setup.household.id,
        )

        companion object {
            private const val USER = 995002L
            private const val AUTH = "Bearer synthetic-offline-token-1234567890"
        }
    }
