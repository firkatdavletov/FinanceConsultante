package com.firkatdavletov.financeconsultante.analytics

import com.firkatdavletov.financeconsultante.PostgresIntegrationTest
import com.firkatdavletov.financeconsultante.analytics.application.AnalyticsReportRepository
import com.firkatdavletov.financeconsultante.analytics.application.AnalyticsReportService
import com.firkatdavletov.financeconsultante.analytics.domain.AnalyticsPeriod
import com.firkatdavletov.financeconsultante.ledger.application.AccountingService
import com.firkatdavletov.financeconsultante.ledger.application.CorrectionResult
import com.firkatdavletov.financeconsultante.ledger.application.EntryCorrections
import com.firkatdavletov.financeconsultante.ledger.application.EntryTarget
import com.firkatdavletov.financeconsultante.ledger.application.OwnedLedger
import com.firkatdavletov.financeconsultante.ledger.application.RecordEntry
import com.firkatdavletov.financeconsultante.ledger.domain.Account
import com.firkatdavletov.financeconsultante.ledger.domain.BudgetSetup
import com.firkatdavletov.financeconsultante.ledger.domain.Category
import com.firkatdavletov.financeconsultante.ledger.domain.CategoryKind
import com.firkatdavletov.financeconsultante.ledger.domain.Direction
import com.firkatdavletov.financeconsultante.ledger.domain.EntryChange
import com.firkatdavletov.financeconsultante.ledger.domain.Household
import com.firkatdavletov.financeconsultante.ledger.domain.LedgerEntry
import com.firkatdavletov.financeconsultante.ledger.domain.Member
import com.firkatdavletov.financeconsultante.shared.domain.Money
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID

@Suppress("MagicNumber", "LongParameterList", "TooManyFunctions") // Helpers keep financial fixtures explicit.
class AnalyticsReportPersistenceTests
    @Autowired
    constructor(
        private val accounting: AccountingService,
        private val corrections: EntryCorrections,
        private val reports: AnalyticsReportService,
        private val reportRepository: AnalyticsReportRepository,
        private val jdbc: JdbcTemplate,
        transactionManager: PlatformTransactionManager,
    ) : PostgresIntegrationTest() {
        private val tx = TransactionTemplate(transactionManager)
        private lateinit var setup: BudgetSetup
        private lateinit var owner: OwnedLedger

        @BeforeEach
        fun createBudget() {
            cleanupOwner()
            val household = Household(UUID.randomUUID(), reportTime = LocalTime.of(8, 0))
            val start = LocalDate.parse("2026-01-01").atStartOfDay(ZONE).toInstant()
            setup =
                BudgetSetup(
                    household,
                    Member(UUID.randomUUID(), household.id, OWNER),
                    Account(UUID.randomUUID(), household.id, Money.of("9000"), start),
                    listOf(
                        Category(UUID.randomUUID(), household.id, "Продукты", CategoryKind.EXPENSE),
                        Category(UUID.randomUUID(), household.id, "Здоровье", CategoryKind.EXPENSE),
                        Category(UUID.randomUUID(), household.id, "Зарплата", CategoryKind.INCOME),
                    ),
                )
            accounting.openBudget(setup)
            owner = OwnedLedger(household.id, setup.owner.id, OWNER, start)
        }

        @AfterEach
        fun cleanup() {
            cleanupOwner()
        }

        @Test
        fun `SQL aggregates posted facts and a correction changes a new report`() {
            val previous = record(Direction.EXPENSE, "100", expense("Продукты"), "previous", "2026-08-24")
            record(Direction.INCOME, "500", income(), "income", "2026-08-31")
            val food = record(Direction.EXPENSE, "200", expense("Продукты"), "food", "2026-09-01")
            record(Direction.EXPENSE, "50", expense("Здоровье"), "health", "2026-09-02")
            val voided = record(Direction.EXPENSE, "999", expense("Здоровье"), "voided", "2026-09-03")
            assertThat(
                corrections.refund(
                    EntryTarget(owner.householdId, owner.memberId, food.id, food.version),
                    Money.of("40"),
                    LocalDate.parse("2026-09-04"),
                    "synthetic:t10:refund",
                ),
            ).isInstanceOf(CorrectionResult.Updated::class.java)
            assertThat(
                corrections.change(
                    EntryTarget(owner.householdId, owner.memberId, voided.id, voided.version),
                    EntryChange.Void,
                    "synthetic:t10:void",
                ),
            ).isInstanceOf(CorrectionResult.Updated::class.java)
            insertExpectedIncome("7000")

            val first = tx.execute { reports.current(owner, AnalyticsPeriod.WEEK) }

            assertThat(first.current.income).isEqualTo(Money.of("500"))
            assertThat(first.current.expenses).isEqualTo(Money.of("250"))
            assertThat(first.current.refunds).isEqualTo(Money.of("40"))
            assertThat(first.current.netExpenses).isEqualTo(Money.of("210"))
            assertThat(first.current.categories.sumOf { it.netExpenses.amount }).isEqualByComparingTo("210.00")
            assertThat(first.previous.netExpenses).isEqualTo(Money.of("100"))
            assertThat(first.netExpenseChange().percentage).isEqualByComparingTo("110.0")

            assertThat(
                corrections.change(
                    EntryTarget(owner.householdId, owner.memberId, previous.id, previous.version),
                    EntryChange.Amount(Money.of("80")),
                    "synthetic:t10:old-correction",
                ),
            ).isInstanceOf(CorrectionResult.Updated::class.java)
            val refreshed = tx.execute { reports.current(owner, AnalyticsPeriod.WEEK) }
            assertThat(refreshed.previous.netExpenses).isEqualTo(Money.of("80"))
            assertThat(refreshed.netExpenseChange().percentage).isEqualByComparingTo("162.5")
        }

        @Test
        fun `local midnight defines week and month SQL boundaries and foreign owner is denied`() {
            val weekStart = LocalDate.parse("2026-08-31").atStartOfDay(ZONE).toInstant()
            val monthStart = LocalDate.parse("2026-09-01").atStartOfDay(ZONE).toInstant()
            recordAt("1", "before-week", weekStart.minusSeconds(1))
            recordAt("2", "week-start", weekStart)
            recordAt("3", "before-month", monthStart.minusSeconds(1))
            recordAt("4", "month-start", monthStart)

            val week = tx.execute { reports.current(owner, AnalyticsPeriod.WEEK) }
            val month = tx.execute { reports.current(owner, AnalyticsPeriod.MONTH) }

            assertThat(week.current.expenses).isEqualTo(Money.of("9"))
            assertThat(week.previous.expenses).isEqualTo(Money.of("1"))
            assertThat(month.current.expenses).isEqualTo(Money.of("4"))
            assertThat(month.previous.expenses).isEqualTo(Money.of("0"))
            assertThatThrownBy {
                tx.execute { reports.current(owner.copy(memberId = UUID.randomUUID()), AnalyticsPeriod.WEEK) }
            }.isInstanceOf(IllegalArgumentException::class.java)
                .hasMessage("Budget access denied")
        }

        private fun record(
            direction: Direction,
            amount: String,
            categoryId: UUID,
            source: String,
            date: String,
        ): LedgerEntry =
            accounting
                .record(
                    RecordEntry(
                        owner.householdId,
                        owner.memberId,
                        categoryId,
                        direction,
                        Money.of(amount),
                        LocalDate.parse(date).atStartOfDay(ZONE).toInstant(),
                        "synthetic:t10:$source",
                    ),
                ).entry

        private fun recordAt(
            amount: String,
            source: String,
            occurredAt: Instant,
        ): LedgerEntry =
            accounting
                .record(
                    RecordEntry(
                        owner.householdId,
                        owner.memberId,
                        expense("Продукты"),
                        Direction.EXPENSE,
                        Money.of(amount),
                        occurredAt,
                        "synthetic:t10:$source",
                    ),
                ).entry

        private fun insertExpectedIncome(amount: String) {
            jdbc.update(
                """
                INSERT INTO expected_income(
                    id, household_id, member_id, category_id, planned_amount, due_on, created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, '2026-09-04', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """.trimIndent(),
                UUID.randomUUID(),
                owner.householdId,
                owner.memberId,
                income(),
                java.math.BigDecimal(amount),
            )
        }

        private fun expense(name: String): UUID =
            setup.categories.single { it.kind == CategoryKind.EXPENSE && it.name == name }.id

        private fun income(): UUID = setup.categories.single { it.kind == CategoryKind.INCOME }.id

        private fun cleanupOwner() {
            val households = jdbc.queryForList("SELECT household_id FROM member WHERE telegram_user_id = ?", OWNER)
            households.forEach { row ->
                val household = row.getValue("household_id")
                val tables =
                    listOf(
                        "daily_report_job",
                        "expected_income_match",
                        "expected_income",
                        "recurring_occurrence_payment",
                        "recurring_occurrence",
                        "recurring_rule",
                        "entry_revision",
                        "ledger_entry",
                        "category",
                        "account",
                        "member",
                    )
                for (table in tables) {
                    jdbc.update("DELETE FROM $table WHERE household_id = ?", household)
                }
                jdbc.update("DELETE FROM household WHERE id = ?", household)
            }
        }

        companion object {
            private const val OWNER = 717171L
            private val ZONE: ZoneId = ZoneId.of("Asia/Almaty")
        }
    }
