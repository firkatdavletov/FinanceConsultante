package com.firkatdavletov.financeconsultante.mobileapi

import com.firkatdavletov.financeconsultante.PostgresIntegrationTest
import com.firkatdavletov.financeconsultante.auth.application.FirebaseAuthenticationService
import com.firkatdavletov.financeconsultante.auth.application.InvalidFirebaseToken
import com.firkatdavletov.financeconsultante.auth.application.VerifiedFirebaseIdentity
import com.firkatdavletov.financeconsultante.auth.infrastructure.FirebaseAdminTokenVerifier
import com.firkatdavletov.financeconsultante.mobileapi.application.CreateFirebaseBudget
import com.firkatdavletov.financeconsultante.mobileapi.application.FirebaseBudgetCreation
import com.firkatdavletov.financeconsultante.mobileapi.application.FirebaseBudgetInvitations
import com.firkatdavletov.financeconsultante.mobileapi.application.FirebaseBudgetSummary
import com.firkatdavletov.financeconsultante.mobileapi.application.FirebaseInvitationCodes
import com.firkatdavletov.financeconsultante.mobileapi.application.IssuedFirebaseInvitation
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
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
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.time.Clock
import java.time.Duration
import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.sql.DataSource

@AutoConfigureMockMvc
@TestPropertySource(
    properties = [
        "app.mobile-api.enabled=true",
        "app.mobile-api.telegram-user-id=0", "app.firebase-auth.enabled=true",
        "app.firebase-auth.project-id=synthetic-project", "FIREBASE_LEGACY_UID=", "FIREBASE_LEGACY_MEMBER_ID=",
    ],
)
@Suppress("MagicNumber", "LongMethod", "LongParameterList", "TooManyFunctions")
class FirebaseBudgetInvitationTests
    @Autowired
    constructor(
        private val invitations: FirebaseBudgetInvitations,
        private val creation: FirebaseBudgetCreation,
        private val authentication: FirebaseAuthenticationService,
        private val codes: FirebaseInvitationCodes,
        private val jdbc: JdbcTemplate,
        private val mvc: MockMvc,
        private val clock: Clock,
        private val transactions: PlatformTransactionManager,
        private val dataSource: DataSource,
    ) : PostgresIntegrationTest() {
        @MockitoBean private lateinit var verifier: FirebaseAdminTokenVerifier
        private val uid = "synthetic-t09-owner"
        private val memberUid = "synthetic-t09-member"
        private val otherUid = "synthetic-t09-other"
        private val households = mutableListOf<UUID>()
        private lateinit var budget: FirebaseBudgetSummary

        @BeforeEach fun prepare() {
            listOf("owner" to uid, "member" to memberUid, "other" to otherUid).forEach { (token, identity) ->
                Mockito
                    .`when`(
                        verifier.verify("t09-$token"),
                    ).thenReturn(VerifiedFirebaseIdentity(identity, token, null))
                authentication.authenticate("Bearer t09-$token")
            }
            Mockito.`when`(verifier.verify("invalid")).thenThrow(InvalidFirebaseToken())
            Mockito.`when`(verifier.verify("synthetic-t09-static-token-1234567890")).thenThrow(InvalidFirebaseToken())
            budget = createBudget()
        }

        @AfterEach fun cleanup() {
            households.forEach { id ->
                jdbc.update("DELETE FROM firebase_budget_invitation WHERE household_id = ?", id)
                jdbc.update("DELETE FROM firebase_budget_creation WHERE household_id = ?", id)
                jdbc.update("DELETE FROM firebase_budget_access WHERE household_id = ?", id)
                listOf("entry_revision", "ledger_entry", "category", "account", "member").forEach {
                    jdbc.update("DELETE FROM $it WHERE household_id = ?", id)
                }
                jdbc.update("DELETE FROM household WHERE id = ?", id)
            }
            jdbc.update("DELETE FROM firebase_user WHERE firebase_uid IN (?, ?, ?)", uid, memberUid, otherUid)
        }

        @Test fun `owner creates a private hashed 24 hour code and replaces and revokes it without changing money`() {
            val account = jdbc.queryForMap("SELECT * FROM account WHERE household_id = ?", budget.id)
            mvc
                .perform(post(path()).header("Authorization", "Bearer t09-owner"))
                .andExpect(status().isOk)
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.budgetId").value(budget.id.toString()))
                .andExpect(jsonPath("$.code").isString)
            val first = invitations.current(uid, budget.id).current!!
            assertThat(Duration.between(clock.instant(), first.expiresAt)).isEqualTo(Duration.ofHours(24))
            mvc
                .perform(get("${path()}/current").header("Authorization", "Bearer t09-owner"))
                .andExpect(status().isOk)
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.current.code").doesNotExist())
            val second = invitations.create(uid, budget.id)
            assertThat(second.code).matches("[0-9A-HJKMNP-TV-Z]{5}(-[0-9A-HJKMNP-TV-Z]{5}){3}")
            assertThat(
                jdbc.queryForObject(
                    "SELECT code_hash FROM firebase_budget_invitation WHERE id = ?",
                    String::class.java,
                    second.id,
                ),
            ).isEqualTo(codes.hash(second.code))
                .doesNotContain(second.code)
            assertThat(jdbc.queryForMap("SELECT * FROM firebase_budget_invitation WHERE id = ?", second.id).values)
                .doesNotContain(second.code)
            assertThat(
                jdbc.queryForObject(
                    "SELECT revoked_at FROM firebase_budget_invitation WHERE id = ?",
                    OffsetDateTime::class.java,
                    first.id,
                ),
            ).isNotNull()
            repeat(2) {
                mvc
                    .perform(delete("${path()}/${second.id}").header("Authorization", "Bearer t09-owner"))
                    .andExpect(status().isNoContent)
                    .andExpect(header().string("Cache-Control", "no-store"))
            }
            assertThat(invitations.current(uid, budget.id).current).isNull()
            assertThat(jdbc.queryForMap("SELECT * FROM account WHERE household_id = ?", budget.id)).isEqualTo(account)
        }

        @Test fun `invitation endpoints enforce owner and budget scope with Firebase credentials`() {
            val memberId = UUID.randomUUID()
            jdbc.update("INSERT INTO member(id, household_id, role) VALUES (?, ?, 'MEMBER')", memberId, budget.id)
            jdbc.update("INSERT INTO firebase_budget_access VALUES (?, ?, ?)", memberUid, budget.id, memberId)
            val active = invitations.create(uid, budget.id)
            for (token in listOf("t09-member", "t09-other")) {
                mvc.perform(post(path()).header("Authorization", "Bearer $token")).andExpect(status().isForbidden)
                mvc
                    .perform(
                        get("${path()}/current").header("Authorization", "Bearer $token"),
                    ).andExpect(status().isForbidden)
                mvc
                    .perform(
                        delete("${path()}/${active.id}").header("Authorization", "Bearer $token"),
                    ).andExpect(status().isForbidden)
            }
            mvc.perform(post(path())).andExpect(status().isUnauthorized)
            for (token in listOf("invalid", "synthetic-t09-static-token-1234567890")) {
                mvc.perform(post(path()).header("Authorization", "Bearer $token")).andExpect(status().isUnauthorized)
            }
            val other = createBudget()
            mvc
                .perform(
                    delete(
                        "/api/v4/budgets/${other.id}/invitations/${active.id}",
                    ).header("Authorization", "Bearer t09-owner"),
                ).andExpect(status().isNoContent)
            assertThat(invitations.current(uid, budget.id).current!!.id).isEqualTo(active.id)
            assertThat(invitations.current(uid, other.id).current).isNull()
        }

        @Test fun `concurrent creation leaves exactly one unused code belonging to this budget`() {
            val start = CountDownLatch(1)
            val pool = Executors.newFixedThreadPool(4)
            val created: List<IssuedFirebaseInvitation>
            try {
                val futures =
                    (1..4).map {
                        pool.submit<IssuedFirebaseInvitation> {
                            start.await()
                            invitations.create(uid, budget.id)
                        }
                    }
                start.countDown()
                created = futures.map { it.get(10, TimeUnit.SECONDS) }
            } finally {
                pool.shutdownNow()
            }
            assertThat(created.map { it.code }.distinct()).hasSize(4)
            val active = invitations.current(uid, budget.id).current!!
            assertThat(created.map { it.id }).contains(active.id)
            assertThat(
                jdbc.queryForObject(
                    "SELECT count(*) FROM firebase_budget_invitation WHERE household_id = ? " +
                        "AND revoked_at IS NULL AND accepted_at IS NULL",
                    Int::class.java,
                    budget.id,
                ),
            ).isEqualTo(1)
            assertThat(
                jdbc.queryForObject(
                    "SELECT count(*) FROM firebase_budget_invitation WHERE household_id = ? AND revoked_at IS NOT NULL",
                    Int::class.java,
                    budget.id,
                ),
            ).isEqualTo(3)
        }

        @Test fun `expiration boundary and accepted state cannot appear as an active invitation`() {
            val expired = invitations.create(uid, budget.id)
            jdbc.update(
                "UPDATE firebase_budget_invitation SET created_at = ?, expires_at = ? WHERE id = ?",
                OffsetDateTime.now(clock).minusHours(24),
                OffsetDateTime.now(clock),
                expired.id,
            )
            assertThat(invitations.current(uid, budget.id).current).isNull()
            val replacement = invitations.create(uid, budget.id)
            jdbc.update(
                "UPDATE firebase_budget_invitation SET accepted_at = ? WHERE id = ?",
                OffsetDateTime.now(clock),
                replacement.id,
            )
            assertThat(invitations.current(uid, budget.id).current).isNull()
            invitations.revoke(uid, budget.id, replacement.id)
            assertThat(
                jdbc.queryForObject(
                    "SELECT revoked_at FROM firebase_budget_invitation WHERE id = ?",
                    OffsetDateTime::class.java,
                    replacement.id,
                ),
            ).isNull()
            invitations.create(uid, budget.id)
            assertThat(
                jdbc.queryForObject(
                    "SELECT accepted_at FROM firebase_budget_invitation WHERE id = ?",
                    OffsetDateTime::class.java,
                    replacement.id,
                ),
            ).isNotNull()
        }

        @Test fun `rollback of replacement keeps the previous code active`() {
            val original = invitations.create(uid, budget.id)
            assertThatThrownBy {
                TransactionTemplate(transactions).executeWithoutResult {
                    invitations.create(uid, budget.id)
                    error("Synthetic transaction failure")
                }
            }.isInstanceOf(IllegalStateException::class.java)
            assertThat(invitations.current(uid, budget.id).current!!.id).isEqualTo(original.id)
            assertThat(
                jdbc.queryForObject(
                    "SELECT count(*) FROM firebase_budget_invitation WHERE household_id = ?",
                    Int::class.java,
                    budget.id,
                ),
            ).isEqualTo(1)
        }

        @Test fun `V26 preserves V25 budget memberships balances and creation receipts`() {
            val schema = "firebase_invitation_${UUID.randomUUID().toString().replace("-", "")}"
            try {
                val config =
                    Flyway
                        .configure()
                        .dataSource(dataSource)
                        .schemas(schema)
                        .defaultSchema(schema)
                config.target("25").load().migrate()
                jdbc.update("INSERT INTO $schema.household(id) VALUES (?)", budget.id)
                jdbc.update(
                    "INSERT INTO $schema.member(id, household_id, role) VALUES (?, ?, 'OWNER')",
                    budget.memberId,
                    budget.id,
                )
                jdbc.update(
                    "INSERT INTO $schema.account(id, household_id, opening_balance, accounting_started_at) " +
                        "VALUES (?, ?, 123.45, '2026-09-01T00:00:00Z')",
                    UUID.randomUUID(),
                    budget.id,
                )
                jdbc.update("INSERT INTO $schema.firebase_user(id, firebase_uid) VALUES (?, ?)", UUID.randomUUID(), uid)
                jdbc.update(
                    "INSERT INTO $schema.firebase_budget_access VALUES (?, ?, ?)",
                    uid,
                    budget.id,
                    budget.memberId,
                )
                jdbc.update(
                    "INSERT INTO $schema.firebase_budget_creation VALUES (?, ?, ?, ?)",
                    uid,
                    UUID.randomUUID(),
                    "a".repeat(64),
                    budget.id,
                )
                val before =
                    listOf("account", "firebase_budget_access", "firebase_budget_creation").associateWith {
                        jdbc.queryForList("SELECT * FROM $schema.$it")
                    }
                val migrated = config.target("26").load()
                assertThat(migrated.migrate().migrationsExecuted).isEqualTo(1)
                migrated.validate()
                before.forEach { (table, rows) ->
                    assertThat(jdbc.queryForList("SELECT * FROM $schema.$table")).isEqualTo(rows)
                }
                assertThat(
                    jdbc.queryForObject("SELECT count(*) FROM $schema.firebase_budget_invitation", Int::class.java),
                ).isZero()
            } finally {
                jdbc.execute("DROP SCHEMA IF EXISTS $schema CASCADE")
            }
        }

        private fun createBudget(): FirebaseBudgetSummary =
            creation
                .create(
                    uid,
                    CreateFirebaseBudget(UUID.randomUUID(), "Семейный", "100.00", LocalDate.parse("2026-09-05")),
                ).also { households += it.id }

        private fun path() = "/api/v4/budgets/${budget.id}/invitations"
    }
