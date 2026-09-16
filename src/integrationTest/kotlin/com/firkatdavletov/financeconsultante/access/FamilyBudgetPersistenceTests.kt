package com.firkatdavletov.financeconsultante.access

import com.firkatdavletov.financeconsultante.PostgresIntegrationTest
import com.firkatdavletov.financeconsultante.access.application.FamilyBudgetService
import com.firkatdavletov.financeconsultante.access.application.FamilyChange
import com.firkatdavletov.financeconsultante.access.domain.MemberRole
import com.firkatdavletov.financeconsultante.budget.application.DailyReportCoordinator
import com.firkatdavletov.financeconsultante.budget.application.DailyReportWorker
import com.firkatdavletov.financeconsultante.goals.application.GoalChange
import com.firkatdavletov.financeconsultante.goals.application.SavingsGoalService
import com.firkatdavletov.financeconsultante.ledger.application.AccountingService
import com.firkatdavletov.financeconsultante.ledger.application.CategoryManagement
import com.firkatdavletov.financeconsultante.ledger.application.CorrectionResult
import com.firkatdavletov.financeconsultante.ledger.application.EntryCorrections
import com.firkatdavletov.financeconsultante.ledger.application.EntryTarget
import com.firkatdavletov.financeconsultante.ledger.application.LedgerOperations
import com.firkatdavletov.financeconsultante.ledger.application.OwnedLedger
import com.firkatdavletov.financeconsultante.ledger.application.RecordEntry
import com.firkatdavletov.financeconsultante.ledger.domain.Account
import com.firkatdavletov.financeconsultante.ledger.domain.BudgetSetup
import com.firkatdavletov.financeconsultante.ledger.domain.Category
import com.firkatdavletov.financeconsultante.ledger.domain.CategoryKind
import com.firkatdavletov.financeconsultante.ledger.domain.Direction
import com.firkatdavletov.financeconsultante.ledger.domain.EntryChange
import com.firkatdavletov.financeconsultante.ledger.domain.Household
import com.firkatdavletov.financeconsultante.ledger.domain.Member
import com.firkatdavletov.financeconsultante.shared.domain.Money
import com.firkatdavletov.financeconsultante.telegram.TelegramTestConfiguration
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
import org.assertj.core.api.Assertions.assertThatThrownBy
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
import java.time.Duration
import java.time.LocalDate
import java.time.LocalTime
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@Import(TelegramTestConfiguration::class)
@TestPropertySource(properties = ["app.telegram.bot-token=123456:synthetic-token", "app.telegram.owner-id=555"])
@Suppress("MagicNumber", "LongMethod", "LongParameterList", "TooManyFunctions")
class FamilyBudgetPersistenceTests
    @Autowired
    constructor(
        private val polls: PollingStore,
        private val processor: TelegramInboxProcessor,
        private val setup: SetupStore,
        private val operations: LedgerOperations,
        private val categories: CategoryManagement,
        private val accounting: AccountingService,
        private val corrections: EntryCorrections,
        private val goals: SavingsGoalService,
        private val families: FamilyBudgetService,
        private val access: OwnerAccess,
        private val reportCoordinator: DailyReportCoordinator,
        private val jdbc: JdbcTemplate,
        private val clock: Clock,
        transactionManager: PlatformTransactionManager,
    ) : PostgresIntegrationTest() {
        private val tx = TransactionTemplate(transactionManager)
        private var updateId = 0L

        @BeforeEach
        fun reset() {
            truncate()
            tx.executeWithoutResult {
                setup.saveSettings(
                    OWNER,
                    BudgetSettings(
                        Money.of("0"),
                        LocalDate.parse("2026-09-01"),
                        LocalTime.of(8, 0),
                        Money.of("0"),
                    ),
                )
            }
            updateId = 0
        }

        @AfterEach
        fun cleanup() = truncate()

        @Test
        fun `owner invitation and member acceptance share one budget with durable authorship`() {
            receive(OWNER, "/family invite $MEMBER")
            assertThat(reply(OWNER)).contains("MEMBER", "/family accept", "Отчёты")
            receive(MEMBER, "/family accept")
            assertThat(reply(MEMBER)).contains("Приглашение принято", "меняет только свои")

            receive(OWNER, "зарплата 100")
            receive(MEMBER, "продукты 70")
            receive(MEMBER, "продукты 70")
            receive(OWNER, "/balance")

            assertThat(reply(OWNER)).contains("-40.00 KZT (отрицательный)")
            assertThat(jdbc.queryForObject("SELECT count(*) FROM ledger_entry", Long::class.java)).isEqualTo(3)
            assertThat(
                jdbc.queryForList(
                    "SELECT DISTINCT m.telegram_user_id FROM ledger_entry l JOIN member m ON m.id = l.actor_id " +
                        "ORDER BY m.telegram_user_id",
                    Long::class.java,
                ),
            ).containsExactly(OWNER, MEMBER)
            receive(MEMBER, "/history")
            assertThat(reply(MEMBER)).contains("Автор: Telegram ID 555", "Автор: Telegram ID 777")

            val owner = owned(OWNER)
            val member = owned(MEMBER)
            val expense =
                jdbc.queryForObject(
                    "SELECT id FROM ledger_entry WHERE actor_id = ? ORDER BY history_sequence LIMIT 1",
                    UUID::class.java,
                    member.memberId,
                ) ?: error("Missing member expense")
            assertThat(
                corrections.selected(EntryTarget(owner.householdId, owner.memberId, expense, 0, owner.role)),
            ).isNotNull()
            assertThat(
                corrections.selected(EntryTarget(member.householdId, member.memberId, expense, 0, member.role)),
            ).isNotNull()
        }

        @Test
        fun `member changes only own entry and cannot mutate shared goals`() {
            inviteAndAccept()
            assertThat(access.allows(MEMBER, MEMBER, "private", false)).isTrue()
            assertThat(access.allows(MEMBER, MEMBER, "group", false)).isFalse()
            receive(MEMBER, "/family invite 999")
            assertThat(reply(MEMBER)).contains("только OWNER")
            assertThat(families.configureReports(MEMBER, MEMBER, enabled = true))
                .isInstanceOf(FamilyChange.Rejected::class.java)
            receive(OWNER, "кофе 5")
            receive(OWNER, "продукты 5")
            val ownerDraft =
                checkNotNull(
                    jdbc.queryForObject(
                        "SELECT id || ' ' || version FROM transaction_draft WHERE member_id = ? AND state = 'READY'",
                        String::class.java,
                        owned(OWNER).memberId,
                    ),
                )
            receive(MEMBER, "/confirm $ownerDraft")
            assertThat(reply(MEMBER)).contains("Черновик недоступен")
            receive(OWNER, "продукты 10")
            val ownerEntry = lastEntry()
            receive(MEMBER, "продукты 20")
            val memberEntry = lastEntry()
            val owner = owned(OWNER)
            val member = owned(MEMBER)

            assertThat(
                corrections.change(
                    EntryTarget(member.householdId, member.memberId, ownerEntry, 0, member.role),
                    EntryChange.Void,
                    "synthetic:member:foreign",
                ),
            ).isInstanceOf(CorrectionResult.Rejected::class.java)
            assertThat(
                corrections.change(
                    EntryTarget(member.householdId, member.memberId, memberEntry, 0, member.role),
                    EntryChange.Void,
                    "synthetic:member:own",
                ),
            ).isInstanceOf(CorrectionResult.Updated::class.java)

            val created =
                goals.create(
                    owner,
                    "Общая цель",
                    Money.of("100"),
                    LocalDate.parse("2026-10-01"),
                    1,
                    LocalDate.parse("2026-09-06"),
                    "synthetic:family:goal",
                ) as GoalChange.Applied
            assertThat(
                goals.reserve(member, created.value.id, 0, Money.of("10"), "synthetic:member:goal"),
            ).isInstanceOf(GoalChange.Rejected::class.java)
            assertThat(goals.overview(member, LocalDate.parse("2026-09-06")).goals).hasSize(1)
            receive(MEMBER, "/settings")
            assertThat(reply(MEMBER)).contains("только OWNER")
            receive(MEMBER, "/category_add expense кафе")
            assertThat(reply(MEMBER)).contains("только OWNER")
            receive(MEMBER, "/goals add 10 2026-10-01 1 Личная")
            assertThat(reply(MEMBER)).contains("только OWNER")
        }

        @Test
        fun `two households stay isolated`() {
            inviteAndAccept()
            val firstMember = owned(MEMBER)
            val secondHousehold = Household(UUID.randomUUID())
            val secondOwner = Member(UUID.randomUUID(), secondHousehold.id, SECOND_OWNER)
            val secondCategory = Category(UUID.randomUUID(), secondHousehold.id, "Изолировано", CategoryKind.EXPENSE)
            val start = LocalDate.parse("2026-09-01").atStartOfDay(LedgerOperations.ZONE).toInstant()
            accounting.openBudget(
                BudgetSetup(
                    secondHousehold,
                    secondOwner,
                    Account(UUID.randomUUID(), secondHousehold.id, Money.of("100"), start),
                    listOf(secondCategory),
                ),
            )
            val secondAccess = OwnedLedger(secondHousehold.id, secondOwner.id, SECOND_OWNER, start)
            val foreign =
                accounting
                    .record(
                        RecordEntry(
                            secondAccess.householdId,
                            secondAccess.memberId,
                            secondCategory.id,
                            Direction.EXPENSE,
                            Money.of("25"),
                            LocalDate.parse("2026-09-06").atStartOfDay(LedgerOperations.ZONE).toInstant(),
                            "synthetic:second:expense",
                        ),
                    ).entry

            assertThatThrownBy {
                accounting.record(
                    RecordEntry(
                        secondHousehold.id,
                        firstMember.memberId,
                        secondCategory.id,
                        Direction.EXPENSE,
                        Money.of("10"),
                        foreign.occurredAt,
                        "synthetic:cross:expense",
                    ),
                )
            }.isInstanceOf(IllegalArgumentException::class.java)
            assertThat(
                corrections.selected(
                    EntryTarget(
                        firstMember.householdId,
                        firstMember.memberId,
                        foreign.id,
                        foreign.version,
                        firstMember.role,
                    ),
                ),
            ).isNull()
            assertThat(accounting.currentBalance(secondHousehold.id, secondOwner.id).amount).isEqualTo(Money.of("75"))
            assertThat(accounting.currentBalance(firstMember.householdId, firstMember.memberId).amount)
                .isEqualTo(Money.of("0"))
        }

        @Test
        fun `revoke blocks queued input old membership and queued reports while preserving history`() {
            inviteAndAccept()
            receive(MEMBER, "продукты 10")
            val oldMember = owned(MEMBER)
            tx.executeWithoutResult { families.configureReports(OWNER, MEMBER, enabled = true) }
            val worker = DailyReportWorker(BOT, OWNER, reportCoordinator, Duration.ofMinutes(2), Duration.ofSeconds(1))
            assertThat(worker.runOnce()).isTrue()
            assertThat(worker.runOnce()).isTrue()
            assertThat(
                jdbc.queryForObject(
                    "SELECT count(*) FROM telegram_outbox WHERE member_id = ? AND report_job_id IS NOT NULL " +
                        "AND status = 'PENDING'",
                    Long::class.java,
                    oldMember.memberId,
                ),
            ).isEqualTo(1)

            accept(MEMBER, "/balance")
            tx.executeWithoutResult { families.revoke(OWNER, MEMBER) }

            assertThat(processor.processOne(BOT)).isTrue()
            assertThat(
                jdbc.queryForObject("SELECT status FROM telegram_inbox ORDER BY id DESC LIMIT 1", String::class.java),
            ).isEqualTo("IGNORED")
            assertThat(
                jdbc.queryForObject(
                    "SELECT status FROM telegram_outbox WHERE member_id = ? AND report_job_id IS NOT NULL",
                    String::class.java,
                    oldMember.memberId,
                ),
            ).isEqualTo("FAILED")
            assertThat(jdbc.queryForObject("SELECT count(*) FROM ledger_entry", Long::class.java)).isEqualTo(1)
            assertThat(access.canDeliver(MEMBER, oldMember.memberId, report = false)).isFalse()

            receive(OWNER, "/family invite $MEMBER")
            receive(MEMBER, "/family accept")
            val newMember = owned(MEMBER)
            assertThat(newMember.memberId).isNotEqualTo(oldMember.memberId)
            assertThat(access.canDeliver(MEMBER, oldMember.memberId, report = false)).isFalse()
            assertThat(
                corrections.selected(
                    EntryTarget(newMember.householdId, newMember.memberId, lastEntry(), 0, newMember.role),
                ),
            ).isNull()
        }

        @Test
        fun `concurrent family expenses both persist and repeated source remains idempotent`() {
            inviteAndAccept()
            val owner = owned(OWNER)
            val member = owned(MEMBER)
            tx.executeWithoutResult { categories.categories(OWNER) }
            val category = categoryId()
            val executor = Executors.newFixedThreadPool(2)
            try {
                val results =
                    executor
                        .invokeAll(
                            listOf(
                                Callable { record(owner, category, "synthetic:family:owner") },
                                Callable { record(member, category, "synthetic:family:member") },
                            ),
                        ).map { it.get(10, TimeUnit.SECONDS) }
                assertThat(results).containsExactlyInAnyOrder(true, true)
            } finally {
                executor.shutdownNow()
            }
            assertThat(record(member, category, "synthetic:family:member")).isFalse()
            assertThat(jdbc.queryForObject("SELECT count(*) FROM ledger_entry", Long::class.java)).isEqualTo(2)
            assertThat(accounting.currentBalance(owner.householdId, owner.memberId).amount).isEqualTo(Money.of("-120"))
        }

        private fun record(
            member: OwnedLedger,
            category: UUID,
            source: String,
        ): Boolean =
            accounting
                .record(
                    RecordEntry(
                        member.householdId,
                        member.memberId,
                        category,
                        Direction.EXPENSE,
                        Money.of("60"),
                        LocalDate.parse("2026-09-06").atStartOfDay(LedgerOperations.ZONE).toInstant(),
                        source,
                    ),
                ).recorded

        private fun inviteAndAccept() {
            receive(OWNER, "/family invite $MEMBER")
            receive(MEMBER, "/family accept")
            assertThat(owned(MEMBER).role).isEqualTo(MemberRole.MEMBER)
        }

        private fun receive(
            userId: Long,
            text: String,
        ) {
            accept(userId, text)
            assertThat(processor.processOne(BOT)).isTrue()
        }

        private fun accept(
            userId: Long,
            text: String,
        ) {
            val update =
                IncomingUpdate(
                    ++updateId,
                    IncomingMessage(userId, userId, "private", false, text, clock.instant()),
                )
            assertThat(polls.accept(checkNotNull(polls.claim(BOT)), listOf(update), access)).isTrue()
        }

        private fun owned(userId: Long) = checkNotNull(tx.execute { operations.owned(userId) })

        private fun reply(userId: Long): String =
            checkNotNull(
                jdbc.queryForObject(
                    "SELECT text FROM telegram_outbox WHERE owner_id = ? ORDER BY id DESC LIMIT 1",
                    String::class.java,
                    userId,
                ),
            )

        private fun lastEntry(): UUID =
            checkNotNull(
                jdbc.queryForObject(
                    "SELECT id FROM ledger_entry ORDER BY history_sequence DESC LIMIT 1",
                    UUID::class.java,
                ),
            )

        private fun categoryId(): UUID =
            checkNotNull(
                jdbc.queryForObject(
                    "SELECT id FROM category WHERE dictionary_key = 'GROCERIES'",
                    UUID::class.java,
                ),
            )

        private fun truncate() {
            jdbc.execute(
                "TRUNCATE family_invitation, openai_recognition_job, openai_daily_usage, " +
                    "recurring_expense_candidate_evidence, recurring_expense_candidate, " +
                    "savings_goal_allocation, goal_contribution_plan, savings_goal, expected_income_match, " +
                    "expected_income, recurring_occurrence_payment, recurring_occurrence, recurring_rule, " +
                    "daily_budget_snapshot, telegram_outbox, daily_report_job, transaction_draft, " +
                    "telegram_inbox, telegram_polling_state, telegram_dialog, entry_revision, ledger_entry, " +
                    "category_rule, category, account, member, household RESTART IDENTITY CASCADE",
            )
        }

        companion object {
            private const val MEMBER = 777L
            private const val SECOND_OWNER = 888L
        }
    }
