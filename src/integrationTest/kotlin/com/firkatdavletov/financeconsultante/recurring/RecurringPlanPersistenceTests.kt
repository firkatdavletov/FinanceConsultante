package com.firkatdavletov.financeconsultante.recurring

import com.firkatdavletov.financeconsultante.PostgresIntegrationTest
import com.firkatdavletov.financeconsultante.ledger.application.AccountingService
import com.firkatdavletov.financeconsultante.ledger.application.OwnedLedger
import com.firkatdavletov.financeconsultante.ledger.application.RecordEntry
import com.firkatdavletov.financeconsultante.ledger.domain.Account
import com.firkatdavletov.financeconsultante.ledger.domain.BudgetSetup
import com.firkatdavletov.financeconsultante.ledger.domain.Category
import com.firkatdavletov.financeconsultante.ledger.domain.CategoryKind
import com.firkatdavletov.financeconsultante.ledger.domain.Direction
import com.firkatdavletov.financeconsultante.ledger.domain.Household
import com.firkatdavletov.financeconsultante.ledger.domain.Member
import com.firkatdavletov.financeconsultante.recurring.application.PlanChange
import com.firkatdavletov.financeconsultante.recurring.application.RecurringPlanService
import com.firkatdavletov.financeconsultante.recurring.domain.RecurrenceFrequency
import com.firkatdavletov.financeconsultante.shared.domain.Currency
import com.firkatdavletov.financeconsultante.shared.domain.Money
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.JdbcTemplate
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@Suppress("MagicNumber", "TooManyFunctions")
class RecurringPlanPersistenceTests
    @Autowired
    constructor(
        private val service: RecurringPlanService,
        private val accounting: AccountingService,
        private val jdbc: JdbcTemplate,
    ) : PostgresIntegrationTest() {
        private lateinit var setup: BudgetSetup
        private lateinit var owner: OwnedLedger
        private val today = LocalDate.parse("2026-09-06")

        @BeforeEach
        fun createBudget() {
            val household = Household(UUID.randomUUID())
            val start = Instant.parse("2026-08-31T18:00:00Z")
            setup =
                BudgetSetup(
                    household,
                    Member(UUID.randomUUID(), household.id, positiveTelegramId()),
                    Account(UUID.randomUUID(), household.id, Money.of("0"), start),
                    listOf(
                        Category(UUID.randomUUID(), household.id, "Связь", CategoryKind.EXPENSE),
                        Category(UUID.randomUUID(), household.id, "Зарплата", CategoryKind.INCOME),
                    ),
                )
            accounting.openBudget(setup)
            owner = OwnedLedger(household.id, setup.owner.id, setup.owner.telegramUserId, start)
        }

        @AfterEach
        fun removeBudget() {
            if (!::setup.isInitialized) return
            val household = setup.household.id
            listOf("recurring_occurrence_payment", "expected_income_match").forEach {
                jdbc.update("DELETE FROM $it WHERE household_id = ?", household)
            }
            jdbc.update(
                "DELETE FROM entry_revision WHERE household_id = ?",
                household,
            )
            jdbc.update("DELETE FROM ledger_entry WHERE household_id = ?", household)
            jdbc.update("DELETE FROM recurring_occurrence WHERE household_id = ?", household)
            jdbc.update("DELETE FROM recurring_rule WHERE household_id = ?", household)
            jdbc.update("DELETE FROM expected_income WHERE household_id = ?", household)
            jdbc.update("DELETE FROM category WHERE household_id = ?", household)
            jdbc.update("DELETE FROM account WHERE household_id = ?", household)
            jdbc.update("DELETE FROM member WHERE household_id = ?", household)
            jdbc.update("DELETE FROM household WHERE id = ?", household)
        }

        @Test
        fun `repeated generation creates one occurrence per rule and date`() {
            val rule =
                applied(
                    service.createRule(
                        owner,
                        RecurrenceFrequency.MONTHLY,
                        31,
                        Money.of("100"),
                        expenseCategory(),
                        today,
                    ),
                ).rule

            service.overview(owner, today)
            service.overview(owner, today)

            assertThat(
                jdbc.queryForObject(
                    "SELECT count(*) FROM recurring_occurrence WHERE rule_id = ?",
                    Long::class.java,
                    rule.id,
                ),
            ).isEqualTo(3)
            assertThat(
                jdbc.queryForList(
                    "SELECT due_on::text FROM recurring_occurrence WHERE rule_id = ? ORDER BY due_on",
                    String::class.java,
                    rule.id,
                ),
            ).containsExactly("2026-09-30", "2026-10-31", "2026-11-30")
        }

        @Test
        fun `partial payments reduce R and actual amount above plan does not leave a remainder`() {
            service.createRule(owner, RecurrenceFrequency.MONTHLY, 6, Money.of("100"), expenseCategory(), today)
            val occurrence = service.overview(owner, today).occurrences.first { it.occurrence.dueOn == today }

            val first =
                applied(
                    service.pay(
                        owner,
                        occurrence.occurrence.id,
                        occurrence.occurrence.version,
                        Money.of("40"),
                        today,
                        "synthetic:recurring:first",
                    ),
                )
            assertThat(first.occurrence.remainingAmount).isEqualTo(Money.of("60"))
            assertThat(service.remainingObligations(owner, today)).isEqualTo(Money.of("60"))

            val stale =
                service.pay(
                    owner,
                    occurrence.occurrence.id,
                    occurrence.occurrence.version,
                    Money.of("40"),
                    today,
                    "synthetic:recurring:stale",
                )
            assertThat(stale).isInstanceOf(PlanChange.Rejected::class.java)

            val second =
                applied(
                    service.pay(
                        owner,
                        occurrence.occurrence.id,
                        first.occurrence.occurrence.version,
                        Money.of("70"),
                        today,
                        "synthetic:recurring:second",
                    ),
                )
            assertThat(second.occurrence.paidAmount).isEqualTo(Money.of("110"))
            assertThat(second.occurrence.remainingAmount).isEqualTo(Money.of("0"))
            assertThat(service.remainingObligations(owner, today)).isEqualTo(Money.of("0"))
            assertThat(accounting.currentBalance(owner.householdId, owner.memberId).amount).isEqualTo(Money.of("-110"))
            assertThat(linkCount("recurring_occurrence_payment")).isEqualTo(2)
        }

        @Test
        fun `existing posted expense can be matched without changing balance twice`() {
            service.createRule(owner, RecurrenceFrequency.MONTHLY, 6, Money.of("100"), expenseCategory(), today)
            val occurrence = service.overview(owner, today).occurrences.first { it.occurrence.dueOn == today }
            val actual =
                accounting
                    .record(
                        RecordEntry(
                            owner.householdId,
                            owner.memberId,
                            expenseCategory(),
                            Direction.EXPENSE,
                            Money.of("40"),
                            today.atStartOfDay(java.time.ZoneId.of("Asia/Almaty")).toInstant(),
                            "synthetic:existing:expense",
                        ),
                    ).entry
            val balanceBeforeMatch = accounting.currentBalance(owner.householdId, owner.memberId).amount

            val matched =
                applied(
                    service.matchPayment(
                        owner,
                        occurrence.occurrence.id,
                        occurrence.occurrence.version,
                        actual.id,
                    ),
                )

            assertThat(matched.paidAmount).isEqualTo(Money.of("40"))
            assertThat(matched.remainingAmount).isEqualTo(Money.of("60"))
            assertThat(accounting.currentBalance(owner.householdId, owner.memberId).amount)
                .isEqualTo(balanceBeforeMatch)
            assertThat(linkCount("recurring_occurrence_payment")).isEqualTo(1)
            assertThat(
                service.matchPayment(owner, matched.occurrence.id, matched.occurrence.version, actual.id),
            ).isInstanceOf(PlanChange.Rejected::class.java)
            assertThat(linkCount("recurring_occurrence_payment")).isEqualTo(1)
        }

        @Test
        fun `one existing expense cannot be concurrently matched to two obligations`() {
            repeat(2) {
                service.createRule(
                    owner,
                    RecurrenceFrequency.WEEKLY,
                    today.dayOfWeek.value,
                    Money.of("50"),
                    expenseCategory(),
                    today,
                )
            }
            val occurrences =
                service
                    .overview(owner, today)
                    .occurrences
                    .filter { it.occurrence.dueOn == today }
                    .map { it.occurrence }
            val actual =
                accounting
                    .record(
                        RecordEntry(
                            owner.householdId,
                            owner.memberId,
                            expenseCategory(),
                            Direction.EXPENSE,
                            Money.of("50"),
                            today.atStartOfDay(java.time.ZoneId.of("Asia/Almaty")).toInstant(),
                            "synthetic:concurrent:existing-expense",
                        ),
                    ).entry
            val executor = Executors.newFixedThreadPool(2)
            try {
                val results =
                    executor
                        .invokeAll(
                            occurrences.map { occurrence ->
                                Callable {
                                    service.matchPayment(owner, occurrence.id, occurrence.version, actual.id)
                                }
                            },
                        ).map { it.get(10, TimeUnit.SECONDS) }

                assertThat(results.count { it is PlanChange.Applied }).isEqualTo(1)
                assertThat(results.count { it is PlanChange.Rejected }).isEqualTo(1)
                assertThat(linkCount("recurring_occurrence_payment")).isEqualTo(1)
                assertThat(service.remainingObligations(owner, today)).isEqualTo(Money.of("50"))
                assertThat(accounting.currentBalance(owner.householdId, owner.memberId).amount)
                    .isEqualTo(Money.of("-50"))
            } finally {
                executor.shutdownNow()
            }
        }

        @Test
        fun `two concurrent full payment confirmations create one ledger entry`() {
            service.createRule(
                owner,
                RecurrenceFrequency.WEEKLY,
                today.dayOfWeek.value,
                Money.of("50"),
                expenseCategory(),
                today,
            )
            val occurrence =
                service
                    .overview(
                        owner,
                        today,
                    ).occurrences
                    .first { it.occurrence.dueOn == today }
                    .occurrence
            val executor = Executors.newFixedThreadPool(2)
            try {
                val results =
                    executor
                        .invokeAll(
                            listOf(
                                Callable {
                                    service.pay(
                                        owner,
                                        occurrence.id,
                                        0,
                                        Money.of("50"),
                                        today,
                                        "synthetic:concurrent:a",
                                    )
                                },
                                Callable {
                                    service.pay(
                                        owner,
                                        occurrence.id,
                                        0,
                                        Money.of("50"),
                                        today,
                                        "synthetic:concurrent:b",
                                    )
                                },
                            ),
                        ).map { it.get(10, TimeUnit.SECONDS) }

                assertThat(results.count { it is PlanChange.Applied }).isEqualTo(1)
                assertThat(results.count { it is PlanChange.Rejected }).isEqualTo(1)
                assertThat(linkCount("recurring_occurrence_payment")).isEqualTo(1)
                assertThat(
                    accounting.currentBalance(owner.householdId, owner.memberId).amount,
                ).isEqualTo(Money.of("-50"))
            } finally {
                executor.shutdownNow()
            }
        }

        @Test
        fun `amount change and cancellation affect only future occurrences while deactivation keeps history`() {
            val rule =
                applied(
                    service.createRule(
                        owner,
                        RecurrenceFrequency.MONTHLY,
                        1,
                        Money.of("100"),
                        expenseCategory(),
                        LocalDate.parse("2026-09-01"),
                    ),
                ).rule
            val before = service.overview(owner, today)
            assertThat(before.occurrences.first().overdue(today)).isTrue()

            val updatedRule =
                applied(
                    service.updateRuleAmount(owner, rule.id, rule.version, Money.of("150"), today),
                ).rule
            val changed = service.overview(owner, today)
            assertThat(
                changed.occurrences
                    .first {
                        it.occurrence.dueOn ==
                            LocalDate.parse(
                                "2026-09-01",
                            )
                    }.occurrence.plannedAmount,
            ).isEqualTo(Money.of("100"))
            val future = changed.occurrences.first { it.occurrence.dueOn == LocalDate.parse("2026-10-01") }
            assertThat(future.occurrence.plannedAmount).isEqualTo(Money.of("150"))
            service.cancelOccurrence(owner, future.occurrence.id, future.occurrence.version, today)
            service.deactivateRule(owner, rule.id, updatedRule.version, today)
            val after = service.overview(owner, today)

            assertThat(
                after.rules
                    .single()
                    .rule.active,
            ).isFalse()
            assertThat(after.occurrences.first { it.occurrence.dueOn == LocalDate.parse("2026-09-01") }.overdue(today))
                .isTrue()
            assertThat(after.occurrences.first { it.occurrence.dueOn == LocalDate.parse("2026-10-01") }.remainingAmount)
                .isEqualTo(Money.of("0"))
        }

        @Test
        fun `expected income does not change balance and fulfilled plan is not forecast twice`() {
            val expected =
                applied(
                    service.createExpectedIncome(
                        owner,
                        Money.of("100"),
                        LocalDate.parse("2026-09-10"),
                        incomeCategory(),
                        today,
                    ),
                ).expectedIncome
            assertThat(accounting.currentBalance(owner.householdId, owner.memberId).amount).isEqualTo(Money.of("0"))
            assertThat(service.expectedIncomeForecast(owner, LocalDate.parse("2026-09-30"))).hasSize(1)

            val receipt =
                applied(
                    service.receiveExpectedIncome(
                        owner,
                        expected.id,
                        expected.version,
                        Money.of("120"),
                        today,
                        "synthetic:expected:received",
                    ),
                )

            assertThat(receipt.expectedIncome.fulfilled).isTrue()
            assertThat(receipt.expectedIncome.receivedAmount).isEqualTo(Money.of("120"))
            assertThat(accounting.currentBalance(owner.householdId, owner.memberId).amount).isEqualTo(Money.of("120"))
            assertThat(service.expectedIncomeForecast(owner, LocalDate.parse("2026-09-30"))).isEmpty()
            assertThat(
                service.receiveExpectedIncome(
                    owner,
                    expected.id,
                    expected.version,
                    Money.of("120"),
                    today,
                    "synthetic:expected:duplicate",
                ),
            ).isInstanceOf(PlanChange.Rejected::class.java)
            assertThat(linkCount("expected_income_match")).isEqualTo(1)
        }

        @Test
        fun `existing actual income can be matched to a plan once`() {
            val expected =
                applied(
                    service.createExpectedIncome(
                        owner,
                        Money.of("100"),
                        LocalDate.parse("2026-09-10"),
                        incomeCategory(),
                        today,
                    ),
                ).expectedIncome
            val actual =
                accounting
                    .record(
                        RecordEntry(
                            owner.householdId,
                            owner.memberId,
                            incomeCategory(),
                            Direction.INCOME,
                            Money.of("130"),
                            today.atStartOfDay(java.time.ZoneId.of("Asia/Almaty")).toInstant(),
                            "synthetic:existing:income",
                        ),
                    ).entry

            val matched = applied(service.matchExpectedIncome(owner, expected.id, expected.version, actual.id))

            assertThat(matched.fulfilled).isTrue()
            assertThat(matched.receivedAmount).isEqualTo(Money.of("130"))
            assertThat(service.expectedIncomeForecast(owner, LocalDate.parse("2026-09-30"))).isEmpty()
            assertThat(linkCount("expected_income_match")).isEqualTo(1)
        }

        @Test
        fun `non KZT plan and linked fact amounts are rejected without ledger changes`() {
            val rubles = Money.of("100", Currency.RUB)
            assertThat(
                service.createRule(
                    owner,
                    RecurrenceFrequency.MONTHLY,
                    today.dayOfMonth,
                    rubles,
                    expenseCategory(),
                    today,
                ),
            ).isInstanceOf(PlanChange.Rejected::class.java)
            assertThat(
                service.createExpectedIncome(owner, rubles, today, incomeCategory(), today),
            ).isInstanceOf(PlanChange.Rejected::class.java)

            service.createRule(
                owner,
                RecurrenceFrequency.MONTHLY,
                today.dayOfMonth,
                Money.of("100"),
                expenseCategory(),
                today,
            )
            val occurrence = service.overview(owner, today).occurrences.single { it.occurrence.dueOn == today }
            assertThat(
                service.pay(
                    owner,
                    occurrence.occurrence.id,
                    occurrence.occurrence.version,
                    rubles,
                    today,
                    "synthetic:recurring:rub",
                ),
            ).isInstanceOf(PlanChange.Rejected::class.java)

            val expected =
                applied(
                    service.createExpectedIncome(owner, Money.of("100"), today, incomeCategory(), today),
                ).expectedIncome
            assertThat(
                service.receiveExpectedIncome(
                    owner,
                    expected.id,
                    expected.version,
                    rubles,
                    today,
                    "synthetic:expected:rub",
                ),
            ).isInstanceOf(PlanChange.Rejected::class.java)

            assertThat(accounting.currentBalance(owner.householdId, owner.memberId).amount).isEqualTo(Money.of("0"))
            assertThat(linkCount("recurring_occurrence_payment")).isZero()
            assertThat(linkCount("expected_income_match")).isZero()
        }

        private fun expenseCategory(): UUID = setup.categories.single { it.kind == CategoryKind.EXPENSE }.id

        private fun incomeCategory(): UUID = setup.categories.single { it.kind == CategoryKind.INCOME }.id

        private fun linkCount(table: String): Long =
            checkNotNull(
                jdbc.queryForObject(
                    "SELECT count(*) FROM $table WHERE household_id = ?",
                    Long::class.java,
                    owner.householdId,
                ),
            )

        private fun positiveTelegramId(): Long = UUID.randomUUID().mostSignificantBits ushr 1

        private fun <T> applied(result: PlanChange<T>): T {
            assertThat(result).isInstanceOf(PlanChange.Applied::class.java)
            return (result as PlanChange.Applied).value
        }
    }
