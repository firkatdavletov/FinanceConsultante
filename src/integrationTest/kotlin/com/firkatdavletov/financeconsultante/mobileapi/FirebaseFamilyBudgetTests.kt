package com.firkatdavletov.financeconsultante.mobileapi

import com.firkatdavletov.financeconsultante.PostgresIntegrationTest
import com.firkatdavletov.financeconsultante.auth.application.FirebaseAuthenticationService
import com.firkatdavletov.financeconsultante.auth.application.InvalidFirebaseToken
import com.firkatdavletov.financeconsultante.auth.application.VerifiedFirebaseIdentity
import com.firkatdavletov.financeconsultante.auth.infrastructure.FirebaseAdminTokenVerifier
import com.firkatdavletov.financeconsultante.mobileapi.application.AcceptFirebaseInvitation
import com.firkatdavletov.financeconsultante.mobileapi.application.CloudBudgetCommand
import com.firkatdavletov.financeconsultante.mobileapi.application.CloudBudgetFailure
import com.firkatdavletov.financeconsultante.mobileapi.application.CreateFirebaseBudget
import com.firkatdavletov.financeconsultante.mobileapi.application.FirebaseBudgetCreation
import com.firkatdavletov.financeconsultante.mobileapi.application.FirebaseBudgetInvitations
import com.firkatdavletov.financeconsultante.mobileapi.application.FirebaseBudgetSummary
import com.firkatdavletov.financeconsultante.mobileapi.application.FirebaseBudgets
import com.firkatdavletov.financeconsultante.mobileapi.application.FirebaseFamilyBudgets
import com.firkatdavletov.financeconsultante.mobileapi.application.MobileApiAccessDenied
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
import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
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
class FirebaseFamilyBudgetTests
    @Autowired
    constructor(
        private val family: FirebaseFamilyBudgets,
        private val invitations: FirebaseBudgetInvitations,
        private val budgets: FirebaseBudgets,
        private val creation: FirebaseBudgetCreation,
        private val authentication: FirebaseAuthenticationService,
        private val jdbc: JdbcTemplate,
        private val mvc: MockMvc,
        private val clock: Clock,
        private val transactions: PlatformTransactionManager,
        private val dataSource: DataSource,
    ) : PostgresIntegrationTest() {
        @MockitoBean private lateinit var verifier: FirebaseAdminTokenVerifier
        private val owner = "synthetic-t10-owner"
        private val guest = "synthetic-t10-guest"
        private val other = "synthetic-t10-other"
        private val ids = mutableListOf<UUID>()
        private lateinit var shared: FirebaseBudgetSummary
        private lateinit var personal: FirebaseBudgetSummary

        @BeforeEach fun prepare() {
            listOf("owner" to owner, "guest" to guest, "other" to other).forEach { (token, uid) ->
                Mockito.`when`(verifier.verify("t10-$token")).thenReturn(VerifiedFirebaseIdentity(uid, token, null))
                authentication.authenticate("Bearer t10-$token")
            }
            Mockito.`when`(verifier.verify("invalid")).thenThrow(InvalidFirebaseToken())
            Mockito.`when`(verifier.verify("synthetic-t10-static-token-1234567890")).thenThrow(InvalidFirebaseToken())
            shared = create(owner)
            personal = create(guest)
        }

        @AfterEach fun cleanup() {
            ids.forEach { id ->
                jdbc.update("DELETE FROM firebase_budget_invitation WHERE household_id = ?", id)
                jdbc.update("DELETE FROM firebase_budget_creation WHERE household_id = ?", id)
                jdbc.update("DELETE FROM firebase_budget_access WHERE household_id = ?", id)
                listOf("entry_revision", "ledger_entry", "category", "account", "member").forEach {
                    jdbc.update("DELETE FROM $it WHERE household_id = ?", id)
                }
                jdbc.update("DELETE FROM household WHERE id = ?", id)
            }
            jdbc.update("DELETE FROM firebase_user WHERE firebase_uid IN (?, ?, ?)", owner, guest, other)
        }

        @Test fun `joining keeps personal budget and grants MEMBER income expense and participant access`() {
            val code = invitations.create(owner, shared.id).code
            joinHttp(code.lowercase().replace("-", " "))
                .andExpect(status().isOk)
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.role").value("MEMBER"))
            val member = family.join(guest, AcceptFirebaseInvitation(code))
            assertThat(family.join(guest, AcceptFirebaseInvitation(code))).isEqualTo(member)
            assertThat(budgets.list(guest).map { it.id }).containsExactlyInAnyOrder(shared.id, personal.id)
            val expense = command(member, "EXPENSE", "12.34")
            val income = command(member, "INCOME", "5.00")
            budgets.command(guest, shared.id, expense)
            budgets.command(guest, shared.id, income)
            budgets.command(guest, shared.id, expense)
            assertThat(
                jdbc.queryForObject(
                    "SELECT count(*) FROM ledger_entry WHERE household_id = ?",
                    Int::class.java,
                    shared.id,
                ),
            ).isEqualTo(2)
            assertThat(
                jdbc.queryForObject(
                    "SELECT opening_balance FROM account WHERE household_id = ?",
                    java.math.BigDecimal::class.java,
                    personal.id,
                ),
            ).isEqualByComparingTo("100.00")
            mvc
                .perform(get("/api/v4/budgets/${shared.id}/members").header("Authorization", "Bearer t10-guest"))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.members.length()").value(2))
                .andExpect(jsonPath("$.members[1].currentUser").value(true))
                .andExpect(jsonPath("$.members[0].firebaseUid").doesNotExist())
            assertThat(family.members(owner, shared.id).members.map { it.role }).containsExactly("OWNER", "MEMBER")
            val invalid = expense.copy(requestId = UUID.randomUUID(), type = "CREATE_CATEGORY", name = "Forbidden")
            assertThatThrownBy {
                budgets.command(
                    guest,
                    shared.id,
                    invalid,
                )
            }.isInstanceOf(MobileApiAccessDenied::class.java)
            assertThatThrownBy { invitations.create(guest, shared.id) }.isInstanceOf(MobileApiAccessDenied::class.java)
            mvc
                .perform(get("/api/v4/budgets/${shared.id}/sync/changes").header("Authorization", "Bearer t10-owner"))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.scope.memberId").value(shared.memberId.toString()))
            mvc
                .perform(get("/api/v4/budgets/${shared.id}/members").header("Authorization", "Bearer t10-other"))
                .andExpect(status().isForbidden)
        }

        @Test fun `member updates own income through scoped sync but cannot change another authors entry`() {
            val member = family.join(guest, AcceptFirebaseInvitation(invitations.create(owner, shared.id).code))
            val income = command(member, "INCOME", "100.00")
            budgets.command(guest, shared.id, income)
            val update = income.copy(requestId = UUID.randomUUID(), type = "UPDATE_OPERATION", amount = "150.25")
            val body =
                """
                {
                    "requestId":"${update.requestId}","householdId":"${shared.id}","memberId":"${member.memberId}",
                    "type":"UPDATE_OPERATION","entityId":"${income.entityId}","authoredOn":"${update.authoredOn}",
                    "amount":"150.25","kind":"INCOME","expectedToken":0
                }
                """.trimIndent()
            repeat(2) {
                mvc
                    .perform(
                        post("/api/v4/budgets/${shared.id}/sync/commands")
                            .header("Authorization", "Bearer t10-guest")
                            .contentType("application/json")
                            .content(body),
                    ).andExpect(status().isOk)
                    .andExpect(jsonPath("$.entityToken").value(1))
            }
            val id = UUID.fromString(income.entityId)
            assertThat(
                jdbc.queryForObject(
                    "SELECT original_amount FROM ledger_entry WHERE id = ?",
                    java.math.BigDecimal::class.java,
                    id,
                ),
            ).isEqualByComparingTo("150.25")
            assertThat(
                jdbc.queryForObject("SELECT version FROM ledger_entry WHERE id = ?", Long::class.java, id),
            ).isEqualTo(1)
            val ownerIncome = command(shared, "INCOME", "75.00")
            budgets.command(owner, shared.id, ownerIncome)
            assertThatThrownBy {
                budgets.command(
                    guest,
                    shared.id,
                    update.copy(requestId = UUID.randomUUID(), entityId = ownerIncome.entityId),
                )
            }.isInstanceOf(IllegalArgumentException::class.java)
            assertThatThrownBy {
                budgets.command(guest, personal.id, update.copy(requestId = UUID.randomUUID()))
            }.isInstanceOf(CloudBudgetFailure::class.java)
            mvc
                .perform(
                    post("/api/v4/budgets/${shared.id}/sync/commands")
                        .header("Authorization", "Bearer t10-other")
                        .contentType("application/json")
                        .content(body),
                ).andExpect(status().isForbidden)
            assertThat(
                jdbc.queryForObject(
                    "SELECT original_amount FROM ledger_entry WHERE id = ?",
                    java.math.BigDecimal::class.java,
                    UUID.fromString(ownerIncome.entityId),
                ),
            ).isEqualByComparingTo("75.00")
        }

        @Test fun `invalid expired revoked and consumed codes never grant a foreign membership`() {
            val revoked = invitations.create(owner, shared.id)
            invitations.revoke(owner, shared.id, revoked.id)
            joinHttp(
                revoked.code,
            ).andExpect(status().isConflict).andExpect(jsonPath("$.code").value("invalid_invitation"))
            val expired = invitations.create(owner, shared.id)
            jdbc.update(
                "UPDATE firebase_budget_invitation SET created_at = ?, expires_at = ? WHERE id = ?",
                OffsetDateTime.now(clock).minusHours(24),
                OffsetDateTime.now(clock),
                expired.id,
            )
            joinHttp(expired.code).andExpect(status().isConflict)
            val code = invitations.create(owner, shared.id).code
            val member = family.join(guest, AcceptFirebaseInvitation(code))
            assertThatThrownBy {
                family.join(
                    other,
                    AcceptFirebaseInvitation(code),
                )
            }.isInstanceOf(CloudBudgetFailure::class.java)
            assertThat(family.join(guest, AcceptFirebaseInvitation(code))).isEqualTo(member)
            joinHttp("not-a-code").andExpect(status().isConflict)
            mvc
                .perform(
                    post(
                        "/api/v4/budgets/invitations/accept",
                    ).contentType("application/json").content("{\"code\":\"$code\"}"),
                ).andExpect(status().isUnauthorized)
            for (token in listOf("invalid", "synthetic-t10-static-token-1234567890")) {
                mvc
                    .perform(
                        post("/api/v4/budgets/invitations/accept")
                            .header("Authorization", "Bearer $token")
                            .contentType("application/json")
                            .content("{\"code\":\"$code\"}"),
                    ).andExpect(status().isUnauthorized)
            }
        }

        @Test fun `concurrent repeats create one member and two accounts cannot consume the same code`() {
            val code = invitations.create(owner, shared.id).code
            val pool = Executors.newFixedThreadPool(4)
            try {
                val start = CountDownLatch(1)
                val repeated =
                    (1..4).map {
                        pool.submit<FirebaseBudgetSummary> {
                            start.await()
                            family.join(guest, AcceptFirebaseInvitation(code))
                        }
                    }
                start.countDown()
                assertThat(repeated.map { it.get(10, TimeUnit.SECONDS).memberId }.distinct()).hasSize(1)
                assertThat(family.members(owner, shared.id).members).hasSize(2)
                val another = create(owner)
                val competingCode = invitations.create(owner, another.id).code
                val ready = CountDownLatch(1)
                val race =
                    listOf(guest, other).map { uid ->
                        pool.submit<Boolean> {
                            ready.await()
                            runCatching { family.join(uid, AcceptFirebaseInvitation(competingCode)) }.isSuccess
                        }
                    }
                ready.countDown()
                assertThat(race.map { it.get(10, TimeUnit.SECONDS) }).containsExactlyInAnyOrder(true, false)
                assertThat(family.members(owner, another.id).members).hasSize(2)
            } finally {
                pool.shutdownNow()
            }
        }

        @Test fun `revocation blocks all new requests and reinvitation creates a new generation`() {
            val code = invitations.create(owner, shared.id).code
            val member = family.join(guest, AcceptFirebaseInvitation(code))
            val oldCommand = command(member, "EXPENSE", "3.00")
            budgets.command(guest, shared.id, oldCommand)
            mvc
                .perform(
                    delete(
                        "/api/v4/budgets/${shared.id}/members/${shared.memberId}",
                    ).header("Authorization", "Bearer t10-owner"),
                ).andExpect(status().isForbidden)
            mvc
                .perform(
                    delete(
                        "/api/v4/budgets/${shared.id}/members/${member.memberId}",
                    ).header("Authorization", "Bearer t10-guest"),
                ).andExpect(status().isForbidden)
            family.revoke(guest, personal.id, member.memberId)
            assertThat(family.members(owner, shared.id).members).hasSize(2)
            repeat(2) { family.revoke(owner, shared.id, member.memberId) }
            for (suffix in listOf("", "/members", "/sync/changes")) {
                mvc
                    .perform(
                        get("/api/v4/budgets/${shared.id}$suffix").header("Authorization", "Bearer t10-guest"),
                    ).andExpect(status().isForbidden)
            }
            assertThatThrownBy {
                budgets.command(
                    guest,
                    shared.id,
                    oldCommand,
                )
            }.isInstanceOf(MobileApiAccessDenied::class.java)
            joinHttp(code).andExpect(status().isConflict)
            val renewed = family.join(guest, AcceptFirebaseInvitation(invitations.create(owner, shared.id).code))
            assertThat(renewed.memberId).isNotEqualTo(member.memberId)
            assertThatThrownBy {
                budgets.command(guest, shared.id, oldCommand.copy(requestId = UUID.randomUUID()))
            }.isInstanceOf(CloudBudgetFailure::class.java)
            budgets.command(guest, shared.id, command(renewed, "INCOME", "5.00"))
            assertThat(
                jdbc.queryForObject(
                    "SELECT count(*) FROM ledger_entry WHERE household_id = ?",
                    Int::class.java,
                    shared.id,
                ),
            ).isEqualTo(2)
            assertThat(family.members(owner, shared.id).members).hasSize(2)
            assertThatThrownBy {
                family.join(
                    guest,
                    AcceptFirebaseInvitation(code),
                )
            }.isInstanceOf(CloudBudgetFailure::class.java)
        }

        @Test fun `revocation waits for an existing financial transaction before denying further writes`() {
            val member = family.join(guest, AcceptFirebaseInvitation(invitations.create(owner, shared.id).code))
            val committed = CountDownLatch(1)
            val release = CountDownLatch(1)
            val started = CountDownLatch(1)
            val pool = Executors.newFixedThreadPool(2)
            try {
                val write =
                    pool.submit {
                        TransactionTemplate(transactions).executeWithoutResult {
                            budgets.command(guest, shared.id, command(member, "EXPENSE", "7.00"))
                            committed.countDown()
                            check(release.await(10, TimeUnit.SECONDS))
                        }
                    }
                check(committed.await(10, TimeUnit.SECONDS))
                val revoke =
                    pool.submit {
                        started.countDown()
                        family.revoke(owner, shared.id, member.memberId)
                    }
                check(started.await(10, TimeUnit.SECONDS))
                assertThatThrownBy { revoke.get(200, TimeUnit.MILLISECONDS) }.isInstanceOf(TimeoutException::class.java)
                release.countDown()
                write.get(10, TimeUnit.SECONDS)
                revoke.get(10, TimeUnit.SECONDS)
                assertThatThrownBy {
                    budgets.command(guest, shared.id, command(member, "EXPENSE", "1.00"))
                }.isInstanceOf(MobileApiAccessDenied::class.java)
            } finally {
                release.countDown()
                pool.shutdownNow()
            }
        }

        @Test fun `V27 preserves an unused V26 invitation and its budget data without granting access`() {
            val schema = "firebase_family_${UUID.randomUUID().toString().replace("-", "")}"
            try {
                val config =
                    Flyway
                        .configure()
                        .dataSource(dataSource)
                        .schemas(schema)
                        .defaultSchema(schema)
                config.target("26").load().migrate()
                jdbc.update("INSERT INTO $schema.household(id) VALUES (?)", shared.id)
                jdbc.update(
                    "INSERT INTO $schema.member(id, household_id, role) VALUES (?, ?, 'OWNER')",
                    shared.memberId,
                    shared.id,
                )
                jdbc.update(
                    "INSERT INTO $schema.firebase_budget_invitation(id, household_id, invited_by, " +
                        "code_hash, created_at, expires_at)  " +
                        "VALUES (?, ?, ?, ?, '2026-09-06T00:00:00Z', '2026-09-07T00:00:00Z')",
                    UUID.randomUUID(),
                    shared.id,
                    shared.memberId,
                    "a".repeat(64),
                )
                val before =
                    jdbc.queryForList(
                        "SELECT id,household_id,code_hash,created_at,expires_at FROM " +
                            "$schema.firebase_budget_invitation",
                    )
                val migration = config.target("27").load()
                assertThat(migration.migrate().migrationsExecuted).isEqualTo(1)
                migration.validate()
                assertThat(
                    jdbc.queryForList(
                        "SELECT id,household_id,code_hash,created_at,expires_at FROM " +
                            "$schema.firebase_budget_invitation",
                    ),
                ).isEqualTo(before)
                assertThat(
                    jdbc.queryForObject(
                        "SELECT accepted_by_uid FROM $schema.firebase_budget_invitation",
                        String::class.java,
                    ),
                ).isNull()
            } finally {
                jdbc.execute("DROP SCHEMA IF EXISTS $schema CASCADE")
            }
        }

        private fun create(uid: String) =
            creation
                .create(
                    uid,
                    CreateFirebaseBudget(UUID.randomUUID(), "Тестовый", "100.00", LocalDate.parse("2026-09-05")),
                ).also {
                    ids +=
                        it.id
                }

        private fun joinHttp(code: String) =
            mvc.perform(
                post("/api/v4/budgets/invitations/accept")
                    .header("Authorization", "Bearer t10-guest")
                    .contentType("application/json")
                    .content("{\"code\":\"$code\"}"),
            )

        private fun command(
            member: FirebaseBudgetSummary,
            kind: String,
            amount: String,
        ): CloudBudgetCommand {
            val category =
                jdbc.queryForObject(
                    "SELECT id FROM category WHERE household_id = ? AND kind = ? ORDER BY id LIMIT 1",
                    UUID::class.java,
                    shared.id,
                    kind,
                )!!
            return CloudBudgetCommand(
                UUID.randomUUID(),
                shared.id,
                member.memberId,
                "CREATE_OPERATION",
                UUID.randomUUID().toString(),
                LocalDate.parse("2026-09-05"),
                categoryId = category,
                amount = amount,
                kind = kind,
                occurredOn = LocalDate.parse("2026-09-05"),
            )
        }
    }
