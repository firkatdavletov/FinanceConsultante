package com.firkatdavletov.financeconsultante.mobileapi

import com.firkatdavletov.financeconsultante.PostgresIntegrationTest
import com.firkatdavletov.financeconsultante.auth.application.FirebaseAuthenticationService
import com.firkatdavletov.financeconsultante.auth.application.VerifiedFirebaseIdentity
import com.firkatdavletov.financeconsultante.auth.infrastructure.FirebaseAdminTokenVerifier
import com.firkatdavletov.financeconsultante.auth.infrastructure.deletionHash
import com.firkatdavletov.financeconsultante.ledger.application.AccountingService
import com.firkatdavletov.financeconsultante.mobileapi.application.AcceptFirebaseInvitation
import com.firkatdavletov.financeconsultante.mobileapi.application.CloudBudgetCommand
import com.firkatdavletov.financeconsultante.mobileapi.application.CreateFirebaseBudget
import com.firkatdavletov.financeconsultante.mobileapi.application.FirebaseBudgetCreation
import com.firkatdavletov.financeconsultante.mobileapi.application.FirebaseBudgetInvitations
import com.firkatdavletov.financeconsultante.mobileapi.application.FirebaseBudgets
import com.firkatdavletov.financeconsultante.mobileapi.application.FirebaseFamilyBudgets
import com.firkatdavletov.financeconsultante.mobileapi.application.MobileApiAccessDenied
import com.firkatdavletov.financeconsultante.mobileapi.infrastructure.FirebaseBudgetManagement
import com.firkatdavletov.financeconsultante.recurring.application.RecurringPlanService
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.dao.PessimisticLockingFailureException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.TestPropertySource
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.TransactionTemplate
import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@AutoConfigureMockMvc
@TestPropertySource(
    properties = [
        "app.mobile-api.enabled=true",
        "app.mobile-api.telegram-user-id=0", "app.firebase-auth.enabled=true",
        "app.firebase-auth.project-id=synthetic-project", "FIREBASE_LEGACY_UID=", "FIREBASE_LEGACY_MEMBER_ID=",
    ],
)
@Suppress("MagicNumber", "LongMethod", "LongParameterList")
class FirebaseBudgetArchiveTests
    @Autowired
    constructor(
        private val creation: FirebaseBudgetCreation,
        private val management: FirebaseBudgetManagement,
        private val auth: FirebaseAuthenticationService,
        private val jdbc: JdbcTemplate,
        private val mvc: MockMvc,
        private val transactions: PlatformTransactionManager,
        private val budgets: FirebaseBudgets,
        private val invitations: FirebaseBudgetInvitations,
        private val family: FirebaseFamilyBudgets,
        private val accounting: AccountingService,
        private val recurring: RecurringPlanService,
    ) : PostgresIntegrationTest() {
        @MockitoBean private lateinit var verifier: FirebaseAdminTokenVerifier
        private val uid = "synthetic-budget-archive-owner"
        private val otherUid = "synthetic-budget-archive-other"
        private val request =
            CreateFirebaseBudget(UUID.randomUUID(), "Первый бюджет", "1234.50", LocalDate.parse("2026-09-05"))

        @BeforeEach fun prepare() {
            Mockito
                .`when`(verifier.verify("archive-owner"))
                .thenReturn(VerifiedFirebaseIdentity(uid, "Owner", null))
            Mockito
                .`when`(
                    verifier.verify("archive-other"),
                ).thenReturn(VerifiedFirebaseIdentity(otherUid, "Other", null))
            auth.authenticate("Bearer archive-owner")
            auth.authenticate("Bearer archive-other")
        }

        @AfterEach fun cleanup() {
            val ids =
                jdbc.queryForList(
                    "SELECT household_id FROM firebase_budget_access WHERE firebase_uid = ?",
                    UUID::class.java,
                    uid,
                )
            ids.forEach { id ->
                jdbc.queryForList("SELECT erase_firebase_budget(?)", id)
            }
            jdbc.update(
                "DELETE FROM firebase_budget_management WHERE uid_hash IN (?, ?)",
                deletionHash(uid),
                deletionHash(otherUid),
            )
            jdbc.update("DELETE FROM firebase_user WHERE firebase_uid IN (?, ?)", uid, otherUid)
        }

        @Test fun `support restores the original family budget and old delete receipts never archive it again`() {
            val budget = creation.create(uid, request)
            val formerMember = family.join(otherUid, AcceptFirebaseInvitation(invitations.create(uid, budget.id).code))
            family.revoke(uid, budget.id, formerMember.memberId)
            val member = family.join(otherUid, AcceptFirebaseInvitation(invitations.create(uid, budget.id).code))
            assertThatThrownBy {
                management.delete(otherUid, budget.id, UUID.randomUUID())
            }.isInstanceOf(MobileApiAccessDenied::class.java)
            val unusedCode = AcceptFirebaseInvitation(invitations.create(uid, budget.id).code)
            val category = category(budget.id, "GROCERIES")
            val date = request.accountingStartedOn
            val command =
                CloudBudgetCommand(
                    UUID.randomUUID(),
                    budget.id,
                    budget.memberId,
                    "CREATE_OPERATION",
                    UUID.randomUUID().toString(),
                    date,
                    categoryId = category,
                    kind = "EXPENSE",
                    amount = "12.34",
                    occurredOn = date,
                )
            val commandReceipt = budgets.command(uid, budget.id, command)
            val journal = budgets.changes(otherUid, budget.id, null, 200)
            val balance = accounting.currentBalance(budget.id, budget.memberId)
            assertThat(balance.amount.amount).isEqualByComparingTo("1222.16")
            val deletion = UUID.randomUUID()
            mvc
                .perform(
                    delete("/api/v4/budgets/${budget.id}")
                        .header("Authorization", "Bearer archive-owner")
                        .param("requestId", deletion.toString()),
                ).andExpect(status().isNoContent)
                .andExpect(header().string("Cache-Control", "no-store"))
            val archivedAt = archivedAt(budget.id)
            assertThat(budgets.list(uid)).isEmpty()
            assertThat(budgets.list(otherUid)).isEmpty()
            assertThatThrownBy {
                budgets.changes(
                    otherUid,
                    budget.id,
                    null,
                    200,
                )
            }.isInstanceOf(MobileApiAccessDenied::class.java)
            assertThatThrownBy {
                budgets.command(
                    uid,
                    budget.id,
                    command,
                )
            }.isInstanceOf(MobileApiAccessDenied::class.java)
            assertThatThrownBy { family.members(uid, budget.id) }.isInstanceOf(MobileApiAccessDenied::class.java)
            assertThatThrownBy { family.join(otherUid, unusedCode) }.hasMessageContaining("invalid_invitation")
            assertThat(restore(otherUid, budget.id, archivedAt)).isFalse()
            assertThat(restore(uid, budget.id, archivedAt.minusDays(1))).isFalse()
            assertThat(restore(uid, budget.id, archivedAt)).isTrue()
            assertThat(restore(uid, budget.id, archivedAt)).isFalse()
            management.delete(uid, budget.id, deletion)
            assertThat(budgets.list(uid)).containsExactly(budget)
            assertThat(budgets.list(otherUid)).containsExactly(member)
            assertThat(family.members(uid, budget.id).members.map { it.id })
                .containsExactlyInAnyOrder(budget.memberId, member.memberId)
            assertThat(budgets.changes(otherUid, budget.id, null, 200)).isEqualTo(journal)
            assertThat(accounting.currentBalance(budget.id, budget.memberId)).isEqualTo(balance)
            assertThat(budgets.command(uid, budget.id, command)).isEqualTo(commandReceipt)
            assertThatThrownBy { family.join(otherUid, unusedCode) }.hasMessageContaining("invalid_invitation")
            assertThat(creation.create(uid, request)).isEqualTo(budget)
            management.delete(uid, budget.id, UUID.randomUUID())
            assertThat(restore(uid, budget.id, archivedAt)).isFalse()
            assertThat(budgets.list(uid)).isEmpty()
        }

        @Test fun `scheduler only materializes expense and income plans after restore`() {
            val budget = creation.create(uid, request)
            val expense = category(budget.id, "GROCERIES")
            val income = category(budget.id, "SALARY")
            jdbc.update(
                """
                INSERT INTO recurring_rule(id, household_id, member_id, category_id, frequency, schedule_value,
                    amount, starts_on, created_at, updated_at)
                VALUES (?, ?, ?, ?, 'MONTHLY', 15, 10.00, ?, now(), now())
                """.trimIndent(),
                UUID.randomUUID(),
                budget.id,
                budget.memberId,
                expense,
                request.accountingStartedOn,
            )
            jdbc.update(
                """
                INSERT INTO income_rule(id, household_id, member_id, category_id, amount, due_on, income_name,
                    income_frequency, income_active, effective_on, created_at, updated_at)
                VALUES (?, ?, ?, ?, 25.00, ?, 'Синтетический доход', 'MONTHLY', TRUE, ?, now(), now())
                """.trimIndent(),
                UUID.randomUUID(),
                budget.id,
                budget.memberId,
                income,
                request.accountingStartedOn,
                request.accountingStartedOn,
            )
            management.delete(uid, budget.id, UUID.randomUUID())
            recurring.generateAll()
            val tables = listOf("recurring_occurrence", "expected_income")
            for (table in tables) {
                assertThat(
                    jdbc.queryForObject(
                        "SELECT count(*) FROM $table WHERE household_id = ?",
                        Int::class.java,
                        budget.id,
                    ),
                ).isZero()
            }
            assertThat(restore(uid, budget.id, archivedAt(budget.id))).isTrue()
            recurring.generateAll()
            for (table in tables) {
                assertThat(
                    jdbc.queryForObject(
                        "SELECT count(*) FROM $table WHERE household_id = ?",
                        Int::class.java,
                        budget.id,
                    ),
                ).isPositive()
            }
        }

        @Test fun `sync with an older database snapshot aborts after concurrent archival`() {
            val budget = creation.create(uid, request)
            val snapshotTaken = CountDownLatch(1)
            val archived = CountDownLatch(1)
            val pool = Executors.newSingleThreadExecutor()
            try {
                val reading =
                    pool.submit {
                        val tx = TransactionTemplate(transactions)
                        tx.isolationLevel = TransactionDefinition.ISOLATION_REPEATABLE_READ
                        assertThatThrownBy {
                            tx.executeWithoutResult {
                                assertThat(budgets.list(uid)).containsExactly(budget)
                                snapshotTaken.countDown()
                                check(archived.await(10, TimeUnit.SECONDS))
                                budgets.changes(uid, budget.id, null, 200)
                            }
                        }.isInstanceOf(PessimisticLockingFailureException::class.java)
                    }
                check(snapshotTaken.await(10, TimeUnit.SECONDS))
                management.delete(uid, budget.id, UUID.randomUUID())
                archived.countDown()
                reading.get(10, TimeUnit.SECONDS)
                assertThatThrownBy {
                    budgets.changes(
                        uid,
                        budget.id,
                        null,
                        200,
                    )
                }.isInstanceOf(MobileApiAccessDenied::class.java)
            } finally {
                archived.countDown()
                pool.shutdownNow()
            }
        }

        private fun category(
            budgetId: UUID,
            key: String,
        ): UUID =
            checkNotNull(
                jdbc.queryForObject(
                    "SELECT id FROM category WHERE household_id=? AND dictionary_key=?",
                    UUID::class.java,
                    budgetId,
                    key,
                ),
            )

        private fun archivedAt(id: UUID): OffsetDateTime =
            checkNotNull(
                jdbc.queryForObject("SELECT archived_at FROM household WHERE id=?", OffsetDateTime::class.java, id),
            )

        private fun restore(
            ownerUid: String,
            id: UUID,
            archivedAt: OffsetDateTime,
        ): Boolean =
            jdbc.queryForObject(
                "SELECT restore_firebase_budget(?, ?, ?)",
                Boolean::class.java,
                ownerUid,
                id,
                archivedAt,
            ) ==
                true
    }
