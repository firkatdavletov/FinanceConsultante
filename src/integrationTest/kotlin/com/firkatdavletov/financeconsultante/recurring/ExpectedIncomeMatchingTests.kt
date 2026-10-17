package com.firkatdavletov.financeconsultante.recurring

import com.firkatdavletov.financeconsultante.PostgresIntegrationTest
import com.firkatdavletov.financeconsultante.ledger.application.AccountingService
import com.firkatdavletov.financeconsultante.ledger.application.EntryCorrections
import com.firkatdavletov.financeconsultante.ledger.application.EntryTarget
import com.firkatdavletov.financeconsultante.ledger.application.ExchangeRateRepository
import com.firkatdavletov.financeconsultante.ledger.application.OwnedLedger
import com.firkatdavletov.financeconsultante.ledger.application.RecordEntry
import com.firkatdavletov.financeconsultante.ledger.domain.Account
import com.firkatdavletov.financeconsultante.ledger.domain.BudgetSetup
import com.firkatdavletov.financeconsultante.ledger.domain.Category
import com.firkatdavletov.financeconsultante.ledger.domain.CategoryKind
import com.firkatdavletov.financeconsultante.ledger.domain.DailyExchangeRate
import com.firkatdavletov.financeconsultante.ledger.domain.Direction
import com.firkatdavletov.financeconsultante.ledger.domain.EntryChange
import com.firkatdavletov.financeconsultante.ledger.domain.Household
import com.firkatdavletov.financeconsultante.ledger.domain.Member
import com.firkatdavletov.financeconsultante.recurring.application.PlanChange
import com.firkatdavletov.financeconsultante.recurring.application.RecurringPlanService
import com.firkatdavletov.financeconsultante.recurring.domain.ExpectedIncome
import com.firkatdavletov.financeconsultante.shared.domain.Currency
import com.firkatdavletov.financeconsultante.shared.domain.Money
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@Suppress("MagicNumber", "TooManyFunctions")
class ExpectedIncomeMatchingTests
    @Autowired
    constructor(
        private val service: RecurringPlanService,
        private val accounting: AccountingService,
        private val corrections: EntryCorrections,
        private val rates: ExchangeRateRepository,
        private val jdbc: JdbcTemplate,
        transactionManager: PlatformTransactionManager,
    ) : PostgresIntegrationTest() {
        private val tx = TransactionTemplate(transactionManager)
        private lateinit var setup: BudgetSetup
        private lateinit var owner: OwnedLedger
        private val today = LocalDate.parse("2026-09-06")

        @BeforeEach
        fun createBudget() {
            val household = Household(UUID.randomUUID())
            val start = Instant.parse("2026-08-31T19:00:00Z")
            setup =
                BudgetSetup(
                    household,
                    Member(UUID.randomUUID(), household.id, UUID.randomUUID().mostSignificantBits ushr 1),
                    Account(UUID.randomUUID(), household.id, Money.of("0"), start),
                    listOf(Category(UUID.randomUUID(), household.id, "Зарплата", CategoryKind.INCOME)),
                )
            accounting.openBudget(setup)
            owner = OwnedLedger(household.id, setup.owner.id, setup.owner.telegramUserId, start)
        }

        @AfterEach
        fun removeBudget() {
            if (!::setup.isInitialized) return
            listOf(
                "expected_income_match",
                "entry_revision",
                "ledger_entry",
                "expected_income",
                "category",
                "account",
                "member",
                "household",
            ).forEach { table ->
                val column = if (table == "household") "id" else "household_id"
                jdbc.update("DELETE FROM $table WHERE $column = ?", setup.household.id)
            }
        }

        @Test
        fun `ordinary income automatically fulfills the nearest plan without changing the amount`() {
            val distant =
                applied(
                    service.createExpectedIncome(owner, Money.of("100"), today.plusDays(10), incomeCategory(), today),
                )
            val nearest =
                applied(
                    service.createExpectedIncome(owner, Money.of("100"), today.plusDays(1), incomeCategory(), today),
                )
            val command =
                RecordEntry(
                    owner.householdId,
                    owner.memberId,
                    incomeCategory(),
                    Direction.INCOME,
                    Money.of("130"),
                    today.atStartOfDay(java.time.ZoneId.of("Asia/Almaty")).toInstant(),
                    "synthetic:automatic:income",
                )

            val recorded = accounting.record(command)
            val repeated = accounting.record(command)

            assertThat(service.expectedIncome(owner, nearest.expectedIncome.id)?.fulfilled).isTrue()
            assertThat(
                service.expectedIncome(owner, nearest.expectedIncome.id)?.receivedAmount,
            ).isEqualTo(Money.of("130"))
            assertThat(service.expectedIncome(owner, distant.expectedIncome.id)?.fulfilled).isFalse()
            assertThat(
                service.plannedIncomes(owner).map { it.expectedIncome.id },
            ).containsExactly(distant.expectedIncome.id)
            assertThat(repeated.recorded).isFalse()
            assertThat(repeated.entry.id).isEqualTo(recorded.entry.id)
            assertThat(linkCount("expected_income_match")).isEqualTo(1)
            assertThat(accounting.currentBalance(owner.householdId, owner.memberId).amount).isEqualTo(Money.of("130"))
        }

        @Test
        fun `matching uses local operation date including overdue plans and prefers the earlier equal distance`() {
            val earlier = incomePlan(today.minusDays(4))
            val later = incomePlan(today.minusDays(2))

            accounting.record(
                incomeCommand("synthetic:past:income").copy(occurredAt = Instant.parse("2026-09-02T19:00:00Z")),
            )

            assertThat(service.expectedIncome(owner, earlier.id)?.fulfilled).isTrue()
            assertThat(service.expectedIncome(owner, later.id)?.fulfilled).isFalse()
        }

        @Test
        fun `cancelled fulfilled and other category plans are excluded from automatic matching`() {
            val cancelled = incomePlan(today)
            applied(service.cancelExpectedIncome(owner, cancelled.id, cancelled.version, today))
            val fulfilled = incomePlan(today)
            applied(
                service.receiveExpectedIncome(
                    owner,
                    fulfilled.id,
                    fulfilled.version,
                    Money.of("50"),
                    today,
                    "synthetic:already:received",
                ),
            )
            val otherCategory = UUID.randomUUID()
            jdbc.update(
                "INSERT INTO category(id, household_id, name, kind) VALUES (?, ?, ?, 'INCOME')",
                otherCategory,
                owner.householdId,
                "Премия",
            )
            val other = incomePlan(today, otherCategory)
            val available = incomePlan(today.plusDays(3))

            accounting.record(incomeCommand("synthetic:eligible:income"))

            assertThat(service.expectedIncome(owner, cancelled.id)?.fulfilled).isFalse()
            assertThat(service.expectedIncome(owner, fulfilled.id)?.receivedAmount).isEqualTo(Money.of("50"))
            assertThat(service.expectedIncome(owner, other.id)?.fulfilled).isFalse()
            assertThat(service.expectedIncome(owner, available.id)?.fulfilled).isTrue()
            assertThat(linkCount("expected_income_match")).isEqualTo(2)
        }

        @Test
        fun `income recorded by an active family member fulfills the household plan`() {
            val plan = incomePlan(today)
            val memberId = UUID.randomUUID()
            jdbc.update(
                "INSERT INTO member(id, household_id, telegram_user_id, role) VALUES (?, ?, ?, 'MEMBER')",
                memberId,
                owner.householdId,
                UUID.randomUUID().mostSignificantBits ushr 1,
            )

            val recorded = accounting.record(incomeCommand("synthetic:member:income").copy(memberId = memberId))

            assertThat(recorded.entry.actorId).isEqualTo(memberId)
            assertThat(service.expectedIncome(owner, plan.id)?.fulfilled).isTrue()
            assertThat(linkCount("expected_income_match")).isEqualTo(1)
        }

        @Test
        fun `RUB income is recorded without fulfilling a KZT income plan`() {
            tx.executeWithoutResult { transaction ->
                if (rates.find(today) == null) {
                    rates.fix(
                        DailyExchangeRate(today, BigDecimal("5.5"), "synthetic", Instant.parse("2026-09-06T03:00:00Z")),
                    )
                }
                val plan = incomePlan(today)

                val recorded =
                    accounting.record(incomeCommand("synthetic:rub:income").copy(amount = Money.of("10", Currency.RUB)))

                assertThat(recorded.recorded).isTrue()
                assertThat(recorded.entry.originalAmount.currency).isEqualTo(Currency.RUB)
                assertThat(service.expectedIncome(owner, plan.id)?.fulfilled).isFalse()
                assertThat(linkCount("expected_income_match")).isZero()
                transaction.setRollbackOnly()
            }
        }

        @Test
        fun `explicit receipt fulfills the selected plan even when another plan is nearer`() {
            val nearest = incomePlan(today)
            val selected = incomePlan(today.plusDays(10))

            val receipt =
                applied(
                    service.receiveExpectedIncome(
                        owner,
                        selected.id,
                        selected.version,
                        Money.of("80"),
                        today,
                        "synthetic:selected:income",
                    ),
                )

            assertThat(receipt.expectedIncome.expectedIncome.id).isEqualTo(selected.id)
            assertThat(receipt.expectedIncome.fulfilled).isTrue()
            assertThat(service.expectedIncome(owner, nearest.id)?.fulfilled).isFalse()
            assertThat(linkCount("expected_income_match")).isEqualTo(1)
        }

        @Test
        fun `reusing an income source cannot report another plan as received`() {
            val first = incomePlan(today)
            val command = incomeCommand("synthetic:reused:income")
            accounting.record(command)
            val second = incomePlan(today.plusDays(1))

            val result =
                service.receiveExpectedIncome(
                    owner,
                    second.id,
                    second.version,
                    Money.of("130"),
                    today,
                    command.sourceKey,
                )

            assertThat(result).isInstanceOf(PlanChange.Rejected::class.java)
            assertThat(service.expectedIncome(owner, first.id)?.fulfilled).isTrue()
            assertThat(service.expectedIncome(owner, second.id)?.fulfilled).isFalse()
            assertThat(linkCount("expected_income_match")).isEqualTo(1)
            assertThat(linkCount("ledger_entry")).isEqualTo(1)
        }

        @Test
        fun `voiding income reopens its plan and a fresh income can fulfill it again`() {
            val plan = incomePlan(today)
            val original = accounting.record(incomeCommand("synthetic:void:income")).entry

            corrections.change(
                EntryTarget(owner.householdId, owner.memberId, original.id, original.version),
                EntryChange.Void,
                "synthetic:void:correction",
            )

            assertThat(service.expectedIncome(owner, plan.id)?.fulfilled).isFalse()
            assertThat(service.plannedIncomes(owner).map { it.expectedIncome.id }).containsExactly(plan.id)
            accounting.record(incomeCommand("synthetic:void:income"))
            assertThat(service.expectedIncome(owner, plan.id)?.fulfilled).isFalse()
            accounting.record(incomeCommand("synthetic:replacement:income"))
            assertThat(service.expectedIncome(owner, plan.id)?.fulfilled).isTrue()
            assertThat(linkCount("expected_income_match")).isEqualTo(2)
            assertThat(accounting.currentBalance(owner.householdId, owner.memberId).amount).isEqualTo(Money.of("130"))
        }

        @Test
        fun `income plan link and version roll back together with the ledger and audit`() {
            val plan = incomePlan(today)

            assertThatThrownBy {
                tx.executeWithoutResult {
                    accounting.record(incomeCommand("synthetic:rollback:income"))
                    assertThat(service.expectedIncome(owner, plan.id)?.fulfilled).isTrue()
                    error("Synthetic rollback")
                }
            }.isInstanceOf(IllegalStateException::class.java)

            assertThat(service.expectedIncome(owner, plan.id)?.fulfilled).isFalse()
            assertThat(service.expectedIncome(owner, plan.id)?.expectedIncome?.version).isEqualTo(plan.version)
            assertThat(linkCount("expected_income_match")).isZero()
            assertThat(linkCount("entry_revision")).isZero()
            assertThat(linkCount("ledger_entry")).isZero()
        }

        @ParameterizedTest
        @ValueSource(booleans = [false, true])
        fun `concurrent income recording cannot fulfill the same plan twice`(sameSource: Boolean) {
            val plan = incomePlan(today)
            val barrier = CyclicBarrier(2)
            val executor = Executors.newFixedThreadPool(2)
            try {
                val results =
                    executor
                        .invokeAll(
                            List(2) { index ->
                                Callable {
                                    tx.execute {
                                        jdbc.queryForObject("SELECT 1", Int::class.java)
                                        barrier.await(5, TimeUnit.SECONDS)
                                        val source = if (sameSource) "same" else index.toString()
                                        accounting.record(incomeCommand("synthetic:concurrent:$source"))
                                    }
                                }
                            },
                        ).map { checkNotNull(it.get(10, TimeUnit.SECONDS)) }

                assertThat(results.count { it.recorded }).isEqualTo(if (sameSource) 1 else 2)
                assertThat(service.expectedIncome(owner, plan.id)?.expectedIncome?.version).isEqualTo(1)
                assertThat(service.expectedIncome(owner, plan.id)?.receivedAmount).isEqualTo(Money.of("130"))
                assertThat(linkCount("expected_income_match")).isEqualTo(1)
                assertThat(accounting.currentBalance(owner.householdId, owner.memberId).amount)
                    .isEqualTo(Money.of(if (sameSource) "130" else "260"))
            } finally {
                executor.shutdownNow()
            }
        }

        private fun incomePlan(
            dueOn: LocalDate,
            categoryId: UUID = incomeCategory(),
        ): ExpectedIncome =
            applied(
                service.createExpectedIncome(owner, Money.of("100"), dueOn, categoryId, LocalDate.parse("2026-09-01")),
            ).expectedIncome

        private fun incomeCommand(source: String): RecordEntry =
            RecordEntry(
                owner.householdId,
                owner.memberId,
                incomeCategory(),
                Direction.INCOME,
                Money.of("130"),
                today.atStartOfDay(java.time.ZoneId.of("Asia/Almaty")).toInstant(),
                source,
            )

        private fun incomeCategory(): UUID = setup.categories.single().id

        private fun linkCount(table: String): Long =
            checkNotNull(
                jdbc.queryForObject(
                    "SELECT count(*) FROM $table WHERE household_id = ?",
                    Long::class.java,
                    owner.householdId,
                ),
            )

        private fun <T> applied(result: PlanChange<T>): T {
            assertThat(result).isInstanceOf(PlanChange.Applied::class.java)
            return (result as PlanChange.Applied).value
        }
    }
