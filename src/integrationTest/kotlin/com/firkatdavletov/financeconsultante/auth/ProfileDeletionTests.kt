package com.firkatdavletov.financeconsultante.auth

import com.firkatdavletov.financeconsultante.PostgresIntegrationTest
import com.firkatdavletov.financeconsultante.auth.application.FirebaseAccountDeleter
import com.firkatdavletov.financeconsultante.auth.application.FirebaseUserStore
import com.firkatdavletov.financeconsultante.auth.application.InvalidFirebaseToken
import com.firkatdavletov.financeconsultante.auth.application.VerifiedFirebaseIdentity
import com.firkatdavletov.financeconsultante.auth.infrastructure.FirebaseAdminTokenVerifier
import com.firkatdavletov.financeconsultante.auth.infrastructure.JdbcProfileDeletion
import com.firkatdavletov.financeconsultante.auth.infrastructure.ProfileDeletionWorker
import com.firkatdavletov.financeconsultante.auth.infrastructure.deletionHash
import com.firkatdavletov.financeconsultante.mobileapi.application.CreateFirebaseBudget
import com.firkatdavletov.financeconsultante.mobileapi.application.FirebaseBudgetCreation
import com.firkatdavletov.financeconsultante.mobileapi.application.FirebaseBudgets
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.TestPropertySource
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.Clock
import java.time.LocalDate
import java.util.Base64
import java.util.UUID

@AutoConfigureMockMvc
@TestPropertySource(properties = ["app.firebase-auth.enabled=true", "app.firebase-auth.deletion-poll-delay=PT24H"])
@Suppress("MagicNumber", "LongMethod", "LongParameterList", "TooManyFunctions")
class ProfileDeletionTests
    @Autowired
    constructor(
        private val jdbc: JdbcTemplate,
        private val users: FirebaseUserStore,
        private val creation: FirebaseBudgetCreation,
        private val deletion: JdbcProfileDeletion,
        private val budgets: FirebaseBudgets,
        private val worker: ProfileDeletionWorker,
        private val mvc: MockMvc,
        private val clock: Clock,
        private val transactionManager: org.springframework.transaction.PlatformTransactionManager,
    ) : PostgresIntegrationTest() {
        @MockitoBean private lateinit var verifier: FirebaseAdminTokenVerifier

        @MockitoBean private lateinit var deleter: FirebaseAccountDeleter
        private val uid = "synthetic-delete-${UUID.randomUUID()}"
        private val other = "$uid-other"
        private val secret =
            Base64.getUrlEncoder().withoutPadding().encodeToString(
                java.security.SecureRandom().generateSeed(32),
            )

        @AfterEach fun cleanup() {
            // Exercise the public transaction for aggregate cleanup as well.
            for (user in listOf(uid, other)) {
                if (jdbc.queryForObject(
                        "SELECT count(*) FROM firebase_profile_deletion WHERE uid_hash=?",
                        Int::class.java,
                        deletionHash(user),
                    ) ==
                    0
                ) {
                    deletion.accept(user, deletionHash("cleanup-$user"))
                }
                jdbc.update("DELETE FROM firebase_profile_deletion WHERE uid_hash=?", deletionHash(user))
            }
        }

        @Test fun `deletion erases owned shared budgets journal and profile but preserves other accounts`() {
            val owned = budget(uid)
            val second = budget(uid)
            users.upsert(VerifiedFirebaseIdentity(other, "Synthetic", null))
            addMember(other, owned.id)
            addOperation(owned.id, owned.memberId)
            addDependentData(owned.id, owned.memberId)
            verified()
            mvc
                .perform(
                    delete(
                        "/api/v4/profile",
                    ).header("Authorization", "Bearer synthetic").header("X-Deletion-Token", secret),
                ).andExpect(status().isAccepted)
                .andExpect(jsonPath("$.status").value("PENDING"))
            for (id in listOf(owned.id, second.id)) {
                for (table in listOf(
                    "household",
                    "ledger_entry",
                    "entry_revision",
                    "category",
                    "mobile_sync_change",
                    "mobile_sync_state",
                    "firebase_budget_access",
                )) {
                    val key = if (table == "household") "id" else "household_id"
                    assertThat(
                        jdbc.queryForObject("SELECT count(*) FROM $table WHERE $key=?", Int::class.java, id),
                    ).isZero()
                }
            }
            assertThat(
                jdbc.queryForObject("SELECT count(*) FROM firebase_user WHERE firebase_uid=?", Int::class.java, other),
            ).isEqualTo(1)
            assertThatThrownBy {
                users.upsert(VerifiedFirebaseIdentity(uid, null, null))
            }.isInstanceOf(InvalidFirebaseToken::class.java)
            assertThatThrownBy { creation.create(uid, request()) }.isInstanceOf(InvalidFirebaseToken::class.java)
            mvc.perform(delete("/api/v4/profile").header("X-Deletion-Token", secret)).andExpect(status().isAccepted)
            worker.runOnce()
            Mockito.verify(deleter).delete(uid)
            mvc
                .perform(get("/api/v4/profile/deletion").header("X-Deletion-Token", secret))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.status").value("COMPLETED"))
            assertThat(
                jdbc.queryForObject(
                    "SELECT firebase_uid FROM firebase_profile_deletion WHERE uid_hash=?",
                    String::class.java,
                    deletionHash(uid),
                ),
            ).isNull()
        }

        @Test fun `foreign budget and financial authors survive deleting all member generations`() {
            val foreign = budget(other)
            users.upsert(VerifiedFirebaseIdentity(uid, "Synthetic", "person@example.test"))
            val old = addMember(uid, foreign.id)
            addOperation(foreign.id, old)
            jdbc.update("UPDATE member SET revoked_at=now() WHERE id=?", old)
            jdbc.update("DELETE FROM firebase_budget_access WHERE firebase_uid=?", uid)
            val current = addMember(uid, foreign.id)
            jdbc.update(
                """
                INSERT INTO firebase_budget_invitation(id, household_id, invited_by, code_hash, created_at, expires_at,
                    accepted_at, accepted_by_uid, accepted_member_id)
                    VALUES (?, ?, ?, ?, now(), now()+interval '1 day', now(), ?, ?)
                """.trimIndent(),
                UUID.randomUUID(),
                foreign.id,
                foreign.memberId,
                "a".repeat(64),
                uid,
                old,
            )
            val balance =
                jdbc.queryForObject(
                    "SELECT sum(amount_kzt) FROM ledger_entry WHERE household_id=?",
                    java.math.BigDecimal::class.java,
                    foreign.id,
                )
            deletion.accept(uid, deletionHash(secret))
            assertThat(budgets.budget(other, foreign.id).scope.memberId).isEqualTo(foreign.memberId)
            assertThat(
                jdbc.queryForObject(
                    "SELECT sum(amount_kzt) FROM ledger_entry WHERE household_id=?",
                    java.math.BigDecimal::class.java,
                    foreign.id,
                ),
            ).isEqualTo(balance)
            for (id in listOf(old, current)) {
                assertThat(
                    jdbc.queryForObject(
                        "SELECT revoked_at IS NOT NULL AND telegram_user_id IS NULL FROM member WHERE id=?",
                        Boolean::class.java,
                        id,
                    ),
                ).isTrue()
            }
            assertThat(
                jdbc.queryForObject(
                    "SELECT accepted_at IS NOT NULL AND accepted_by_uid IS NULL AND accepted_member_id IS NULL " +
                        "FROM firebase_budget_invitation WHERE household_id=?",
                    Boolean::class.java,
                    foreign.id,
                ),
            ).isTrue()
        }

        @Test fun `missing stale future and absent authentication times reject without writing`() {
            for (time in listOf(null, clock.instant().minusSeconds(301), clock.instant().plusSeconds(1))) {
                Mockito
                    .`when`(
                        verifier.verifyForDeletion("synthetic"),
                    ).thenReturn(VerifiedFirebaseIdentity(uid, null, null, time))
                mvc
                    .perform(
                        delete(
                            "/api/v4/profile",
                        ).header("Authorization", "Bearer synthetic").header("X-Deletion-Token", secret),
                    ).andExpect(status().isForbidden)
                    .andExpect(jsonPath("$.code").value("recent_login_required"))
            }
            mvc.perform(delete("/api/v4/profile").header("X-Deletion-Token", secret)).andExpect(status().isUnauthorized)
            mvc
                .perform(
                    get("/api/v4/profile/deletion").header("X-Deletion-Token", "bad"),
                ).andExpect(status().isBadRequest)
            mvc
                .perform(
                    get("/api/v4/profile/deletion").header("X-Deletion-Token", secret),
                ).andExpect(status().isNotFound)
        }

        @Test fun `account without server profile deletes and Firebase retry recovers expired lease`() {
            verified()
            mvc
                .perform(
                    delete(
                        "/api/v4/profile",
                    ).header("Authorization", "Bearer synthetic").header("X-Deletion-Token", secret),
                ).andExpect(status().isAccepted)
            Mockito.doThrow(IllegalStateException("synthetic outage")).`when`(deleter).delete(uid)
            assertThat(worker.runOnce()).isTrue()
            assertThat(deletion.status(deletionHash(secret))).isEqualTo("PENDING")
            jdbc.update(
                "UPDATE firebase_profile_deletion SET next_attempt_at=now(), lease_token=?, " +
                    "lease_until=now()-interval '1 minute' WHERE uid_hash=?",
                UUID.randomUUID(),
                deletionHash(uid),
            )
            Mockito.doNothing().`when`(deleter).delete(uid)
            assertThat(worker.runOnce()).isTrue()
            assertThat(deletion.status(deletionHash(secret))).isEqualTo("COMPLETED")
        }

        @Test fun `deletion waits for an in flight sync transaction and blocks later reads`() {
            val owned = budget(uid)
            val acquired = java.util.concurrent.CountDownLatch(1)
            val release = java.util.concurrent.CountDownLatch(1)
            val executor =
                java.util.concurrent.Executors
                    .newFixedThreadPool(2)
            try {
                val reading =
                    executor.submit {
                        org.springframework.transaction.support.TransactionTemplate(transactionManager).execute {
                            budgets.budget(uid, owned.id)
                            acquired.countDown()
                            check(release.await(10, java.util.concurrent.TimeUnit.SECONDS))
                        }
                    }
                check(acquired.await(10, java.util.concurrent.TimeUnit.SECONDS))
                val deleting = executor.submit<String> { deletion.accept(uid, deletionHash(secret)) }
                assertThatThrownBy { deleting.get(200, java.util.concurrent.TimeUnit.MILLISECONDS) }
                    .isInstanceOf(java.util.concurrent.TimeoutException::class.java)
                release.countDown()
                reading.get(10, java.util.concurrent.TimeUnit.SECONDS)
                assertThat(deleting.get(10, java.util.concurrent.TimeUnit.SECONDS)).isEqualTo("PENDING")
                assertThatThrownBy { budgets.budget(uid, owned.id) }.isInstanceOf(InvalidFirebaseToken::class.java)
            } finally {
                release.countDown()
                executor.shutdownNow()
            }
        }

        @Test fun `concurrent deletion requests share one receipt and different secret conflicts`() {
            budget(uid)
            val start = java.util.concurrent.CountDownLatch(1)
            val executor =
                java.util.concurrent.Executors
                    .newFixedThreadPool(2)
            try {
                val calls =
                    (1..2).map {
                        executor.submit<String> {
                            start.await(10, java.util.concurrent.TimeUnit.SECONDS)
                            deletion.accept(uid, deletionHash(secret))
                        }
                    }
                start.countDown()
                assertThat(calls.map { it.get(10, java.util.concurrent.TimeUnit.SECONDS) })
                    .containsExactly("PENDING", "PENDING")
                assertThatThrownBy { deletion.accept(uid, deletionHash("different")) }
                    .isInstanceOf(
                        com.firkatdavletov.financeconsultante.auth.application.ProfileDeletionFailure::class.java,
                    )
            } finally {
                executor.shutdownNow()
            }
        }

        @Test fun `failure after erasure rolls back budget profile and receipt together`() {
            val owned = budget(uid)
            val before = jdbc.queryForList("SELECT * FROM mobile_sync_change WHERE household_id=?", owned.id)
            assertThatThrownBy {
                org.springframework.transaction.support.TransactionTemplate(transactionManager).execute {
                    deletion.accept(uid, deletionHash(secret))
                    error("synthetic transaction failure")
                }
            }.isInstanceOf(IllegalStateException::class.java)
            assertThat(budgets.budget(uid, owned.id).scope.memberId).isEqualTo(owned.memberId)
            assertThat(deletion.status(deletionHash(secret))).isNull()
            assertThat(
                jdbc.queryForList("SELECT * FROM mobile_sync_change WHERE household_id=?", owned.id),
            ).isEqualTo(before)
        }

        private fun verified() {
            Mockito
                .`when`(
                    verifier.verifyForDeletion("synthetic"),
                ).thenReturn(VerifiedFirebaseIdentity(uid, null, null, clock.instant()))
        }

        private fun request() =
            CreateFirebaseBudget(UUID.randomUUID(), "Синтетический", "100.00", LocalDate.of(2026, 9, 1))

        private fun budget(
            user: String,
        ): com.firkatdavletov.financeconsultante.mobileapi.application.FirebaseBudgetSummary {
            users.upsert(VerifiedFirebaseIdentity(user, null, null))
            return creation.create(user, request())
        }

        private fun addMember(
            user: String,
            budget: UUID,
        ): UUID {
            val id = UUID.randomUUID()
            jdbc.update("INSERT INTO member(id, household_id, role) VALUES (?, ?, 'MEMBER')", id, budget)
            jdbc.update("INSERT INTO firebase_budget_access VALUES (?, ?, ?)", user, budget, id)
            return id
        }

        private fun addDependentData(
            budget: UUID,
            member: UUID,
        ) {
            val params = mutableMapOf<String, Any>("budget" to budget, "member" to member)
            for (key in listOf("rule", "occurrence", "income", "goal", "candidate")) params[key] = UUID.randomUUID()
            params["entry"] =
                jdbc.queryForObject(
                    "SELECT id FROM ledger_entry WHERE household_id=?",
                    UUID::class.java,
                    budget,
                )!!
            params["category"] =
                jdbc.queryForObject(
                    "SELECT category_id FROM ledger_entry WHERE household_id=?",
                    UUID::class.java,
                    budget,
                )!!
            val sql =
                """
                INSERT INTO entry_revision(household_id, entry_id, version, actor_id, action, snapshot, created_at)
                    VALUES (:budget, :entry, 0, :member, 'CREATED', '{}', now());
                INSERT INTO category_rule(household_id, keyword, category_id, corrected_entry_id, corrected_version)
                    VALUES (:budget, 'synthetic', :category, :entry, 0);
                INSERT INTO recurring_rule(id, household_id, member_id, category_id, frequency,
                    schedule_value, amount, starts_on, created_at, updated_at)
                    VALUES (:rule, :budget, :member, :category, 'MONTHLY', 1, 10, '2026-09-01', now(), now());
                INSERT INTO recurring_occurrence(id, household_id, rule_id, category_id, planned_amount,
                    due_on, created_at, updated_at)
                    VALUES (:occurrence, :budget, :rule, :category, 10, '2026-09-01', now(), now());
                INSERT INTO operation_plan_links(household_id, ledger_id, occurrence_id, created_at)
                    VALUES (:budget, :entry, :occurrence, now());
                INSERT INTO expected_income(id, household_id, member_id, category_id, planned_amount,
                    due_on, created_at, updated_at)
                    SELECT :income, :budget, :member, id, 20, '2026-09-01', now(), now()
                    FROM category WHERE household_id=:budget AND kind='INCOME';
                INSERT INTO savings_goal(id, household_id, member_id, name, target_amount, deadline,
                    priority, source_key, created_at, updated_at)
                    VALUES (:goal, :budget, :member, 'Synthetic', 100, '2027-01-01', 1, 'synthetic-goal', now(), now());
                INSERT INTO savings_goal_allocation(household_id, goal_id, action, amount, source_key, created_at)
                    VALUES (:budget, :goal, 'RESERVED', 10, 'synthetic-allocation', now());
                INSERT INTO goal_contribution_plan(id, household_id, goal_id, amount, due_on,
                    source_key, created_at, updated_at)
                    VALUES (:income, :budget, :goal, 10, '2026-10-01', 'synthetic-plan', now(), now());
                INSERT INTO recurring_expense_candidate(id, household_id, member_id, category_id,
                    normalized_description, frequency, schedule_value, suggested_amount, created_at, updated_at)
                    VALUES (:candidate, :budget, :member, :category, 'synthetic', 'MONTHLY', 1, 10, now(), now());
                INSERT INTO recurring_expense_candidate_evidence(household_id, candidate_id, ledger_entry_id, created_at)
                    VALUES (:budget, :candidate, :entry, now());
                """.trimIndent()
            val named =
                org.springframework.jdbc.core.namedparam
                    .NamedParameterJdbcTemplate(jdbc)
            sql.split(';').filter { it.isNotBlank() }.forEach { named.update(it, params) }
        }

        private fun addOperation(
            budget: UUID,
            member: UUID,
        ) {
            val category =
                jdbc.queryForObject(
                    "SELECT id FROM category WHERE household_id=? AND dictionary_key='GROCERIES'",
                    UUID::class.java,
                    budget,
                )
            jdbc.update(
                """
                INSERT INTO ledger_entry(id, household_id, account_id, accounting_started_at, category_id, direction,
                    original_amount, original_currency, amount_kzt, occurred_at, created_at, source_key, actor_id)
                SELECT ?, household_id, id, accounting_started_at, ?, 'EXPENSE', 12.34, 'KZT', 12.34,
                    '2026-09-05T00:00:00Z', '2026-09-05T00:00:00Z', ?, ? FROM account WHERE household_id=?
                """.trimIndent(),
                UUID.randomUUID(),
                category,
                "synthetic:${UUID.randomUUID()}",
                member,
                budget,
            )
        }
    }
