package com.firkatdavletov.financeconsultante.mobileapi

import com.firkatdavletov.financeconsultante.PostgresIntegrationTest
import com.firkatdavletov.financeconsultante.mobileapi.application.CloudBudgetCommand
import com.firkatdavletov.financeconsultante.mobileapi.application.CloudBudgetFailure
import com.firkatdavletov.financeconsultante.mobileapi.application.CloudBudgetService
import com.firkatdavletov.financeconsultante.mobileapi.application.CloudSeedCategory
import com.firkatdavletov.financeconsultante.mobileapi.application.CreateCloudBudget
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.TestPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.security.MessageDigest
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@AutoConfigureMockMvc
@TestPropertySource(
    properties = [
        "app.mobile-api.enabled=true",
        "app.mobile-api.telegram-user-id=0",
    ],
)
@Suppress("MagicNumber", "LongMethod", "TooManyFunctions")
class CloudBudgetApiTests
    @Autowired
    constructor(
        private val service: CloudBudgetService,
        private val jdbc: JdbcTemplate,
        private val mvc: MockMvc,
    ) : PostgresIntegrationTest() {
        private val households = mutableSetOf<UUID>()
        private val token = "synthetic-independent-budget-token-12345"
        private val tokenHash =
            MessageDigest.getInstance("SHA-256").digest(token.toByteArray()).joinToString("") {
                "%02x".format(it)
            }
        private val authoredOn = LocalDate.parse("2026-09-05")

        @AfterEach fun cleanup() {
            jdbc.update("DELETE FROM mobile_budget_access WHERE token_hash = ?", tokenHash)
            households.forEach { id ->
                listOf(
                    "recurring_expense_candidate_evidence",
                    "recurring_expense_candidate",
                    "savings_goal",
                    "expected_income_match",
                    "recurring_occurrence_payment",
                    "expected_income",
                    "recurring_occurrence",
                    "recurring_rule",
                    "entry_revision",
                    "ledger_entry",
                    "category",
                    "account",
                    "member",
                ).forEach {
                    jdbc.update("DELETE FROM $it WHERE household_id = ?", id)
                }
                jdbc.update("DELETE FROM household WHERE id = ?", id)
            }
        }

        @Test fun `retired static token never creates or opens a budget`() {
            mvc
                .perform(
                    get("/api/v4/budget").header("Authorization", "Bearer $token"),
                ).andExpect(status().isUnauthorized)
                .andExpect(jsonPath("$.code").value("unauthorized"))
            mvc
                .perform(
                    get("/api/v4/budget").header("Authorization", "Bearer wrong-token"),
                ).andExpect(status().isUnauthorized)
            assertThat(
                jdbc.queryForObject(
                    "SELECT COUNT(*) FROM mobile_budget_access WHERE token_hash = ?",
                    Int::class.java,
                    tokenHash,
                ),
            ).isZero()
        }

        @Test fun `mobile creation needs no telegram and is replayable after a lost response`() {
            val request = request()
            val first = service.create(tokenHash, request)
            assertThat(service.create(tokenHash, request)).isEqualTo(first)
            assertThat(service.find(tokenHash)).isEqualTo(first)
            assertThat(first.scope.householdId).isEqualTo(request.householdId)
            assertThat(
                jdbc.queryForObject(
                    "SELECT telegram_user_id FROM member WHERE id = ?",
                    Long::class.java,
                    request.memberId,
                ),
            ).isNull()
            assertThat(
                jdbc.queryForObject(
                    "SELECT reports_enabled FROM member WHERE id = ?",
                    Boolean::class.java,
                    request.memberId,
                ),
            ).isFalse()
            assertThat(
                jdbc.queryForList(
                    "SELECT id FROM category WHERE household_id = ?",
                    UUID::class.java,
                    request.householdId,
                ),
            ).containsExactlyInAnyOrderElementsOf(
                request.categories.map {
                    it.id
                },
            )
            assertThatThrownBy {
                service.create(tokenHash, request.copy(requestId = UUID.randomUUID()))
            }.isInstanceOf(CloudBudgetFailure::class.java).hasMessage("budget_already_exists")
            assertThatThrownBy {
                service.create(tokenHash, request.copy(openingBalance = "101.00"))
            }.hasMessage("idempotency_conflict")
        }

        @Test fun `concurrent creation for one token commits one budget`() {
            val requests = listOf(request(), request())
            val start = CountDownLatch(1)
            val pool = Executors.newFixedThreadPool(2)
            try {
                val futures =
                    requests.map { request ->
                        pool.submit<Boolean> {
                            start.await()
                            try {
                                service.create(tokenHash, request)
                                true
                            } catch (
                                _: CloudBudgetFailure,
                            ) {
                                false
                            }
                        }
                    }
                start.countDown()
                assertThat(futures.map { it.get(20, TimeUnit.SECONDS) }).containsExactlyInAnyOrder(true, false)
                assertThat(
                    jdbc.queryForObject(
                        "SELECT COUNT(*) FROM mobile_budget_access WHERE token_hash = ?",
                        Int::class.java,
                        tokenHash,
                    ),
                ).isEqualTo(1)
                val actual =
                    jdbc.queryForList(
                        "SELECT id FROM household WHERE id IN (?, ?)",
                        UUID::class.java,
                        requests[0].householdId,
                        requests[1].householdId,
                    )
                assertThat(actual).hasSize(1)
            } finally {
                pool.shutdownNow()
            }
        }

        @Test fun `local amount wins stale version and refund replay changes money once`() {
            val request = request()
            service.create(tokenHash, request)
            val operation = UUID.randomUUID()
            val create =
                command(
                    request,
                    "CREATE_OPERATION",
                    operation,
                ).copy(
                    categoryId = request.categories.first().id,
                    kind = "EXPENSE",
                    amount = "10.00",
                    occurredOn = authoredOn,
                )
            service.command(tokenHash, create)
            service.command(tokenHash, create)
            service.command(
                tokenHash,
                command(request, "UPDATE_OPERATION", operation).copy(amount = "30.00", expectedToken = 0),
            )
            val local = command(request, "UPDATE_OPERATION", operation).copy(amount = "20.00", expectedToken = 0)
            val receipt = service.command(tokenHash, local)
            assertThat(receipt.entityToken).isEqualTo(2)
            assertThat(
                jdbc.queryForObject(
                    "SELECT original_amount FROM ledger_entry WHERE id = ?",
                    java.math.BigDecimal::class.java,
                    operation,
                ),
            ).isEqualByComparingTo("20.00")
            val refundId = UUID.randomUUID()
            val refund =
                command(
                    request,
                    "REFUND_OPERATION",
                    operation,
                ).copy(amount = "20.00", refundId = refundId, expectedToken = 2)
            val first = service.command(tokenHash, refund)
            assertThat(service.command(tokenHash, refund)).isEqualTo(first)
            assertThat(
                jdbc.queryForObject(
                    "SELECT COUNT(*) FROM ledger_entry WHERE household_id = ?",
                    Int::class.java,
                    request.householdId,
                ),
            ).isEqualTo(2)
            assertThat(
                jdbc.queryForObject(
                    "SELECT occurred_on FROM ledger_entry WHERE id = ?",
                    LocalDate::class.java,
                    refundId,
                ),
            ).isEqualTo(authoredOn)
            assertThat(
                jdbc.queryForObject(
                    """
SELECT SUM(CASE WHEN direction = 'EXPENSE' THEN -amount_kzt ELSE amount_kzt END) FROM
ledger_entry WHERE household_id = ?
                    """.trimIndent(),
                    java.math.BigDecimal::class.java,
                    request.householdId,
                ),
            ).isEqualByComparingTo("0.00")
        }

        @Test fun `local category update and deletion win without manual resolution`() {
            val request = request()
            service.create(tokenHash, request)
            val id = UUID.randomUUID()
            service.command(
                tokenHash,
                command(request, "CREATE_CATEGORY", id).copy(name = "Техника", kind = "EXPENSE", icon = "HOME"),
            )
            service.command(tokenHash, command(request, "UPDATE_CATEGORY", id).copy(name = "Серверная", icon = "HOME"))
            service.command(
                tokenHash,
                command(request, "UPDATE_CATEGORY", id).copy(name = "Локальная", icon = "HOME", expectedToken = 0),
            )
            assertThat(
                jdbc.queryForObject("SELECT name FROM category WHERE id = ?", String::class.java, id),
            ).isEqualTo("Локальная")
            service.command(tokenHash, command(request, "DELETE_CATEGORY", id).copy(expectedToken = 0))
            assertThat(
                jdbc.queryForObject("SELECT archived FROM category WHERE id = ?", Boolean::class.java, id),
            ).isTrue()
            service.command(
                tokenHash,
                command(
                    request,
                    "UPDATE_CATEGORY",
                    id,
                ).copy(name = "Восстановлена", kind = "EXPENSE", icon = "HOME", expectedToken = 0),
            )
            assertThat(
                jdbc.queryForObject("SELECT archived FROM category WHERE id = ?", Boolean::class.java, id),
            ).isFalse()
        }

        @Test fun `plan updates use current server versions and slices stay pinned`() {
            val request = request()
            service.create(tokenHash, request)
            val rule = UUID.randomUUID()
            service.command(
                tokenHash,
                command(
                    request,
                    "CREATE_RULE",
                    rule,
                ).copy(
                    categoryId = request.categories.first().id,
                    amount = "50.00",
                    frequency = "MONTHLY",
                    scheduleValue = 5,
                    startsOn = authoredOn,
                ),
            )
            service.command(tokenHash, command(request, "UPDATE_RULE", rule).copy(amount = "75.00", expectedToken = 0))
            service.command(tokenHash, command(request, "UPDATE_RULE", rule).copy(amount = "60.00", expectedToken = 0))
            assertThat(
                jdbc.queryForObject(
                    "SELECT amount FROM recurring_rule WHERE id = ?",
                    java.math.BigDecimal::class.java,
                    rule,
                ),
            ).isEqualByComparingTo("60.00")
            val first = service.changes(tokenHash, null, 1)
            service.command(
                tokenHash,
                command(request, "UPDATE_CATEGORY", request.categories.first().id).copy(name = "Еда", icon = "FOOD"),
            )
            var page = first
            do {
                assertThat(page.snapshotRevision).isEqualTo(first.snapshotRevision)
                if (!page.hasMore) break
                page = service.changes(tokenHash, page.nextCursor, 1)
            } while (true)
            val after = service.changes(tokenHash, page.nextCursor, 200)
            assertThat(after.changes.mapNotNull { it.category?.name }).contains("Еда")
            assertThatThrownBy {
                service.command(tokenHash, command(request, "DELETE_RULE", rule).copy(householdId = UUID.randomUUID()))
            }.hasMessage("scope_changed")
        }

        @Test fun `retired unscoped v4 never opens financial data`() {
            val before = jdbc.queryForObject("SELECT count(*) FROM household", Long::class.java)
            mvc
                .perform(get("/api/v4/budget").header("Authorization", "Bearer $token"))
                .andExpect(status().isUnauthorized)
            mvc
                .perform(get("/api/v4/sync/changes").header("Authorization", "Bearer $token"))
                .andExpect(status().isUnauthorized)
            assertThat(jdbc.queryForObject("SELECT count(*) FROM household", Long::class.java)).isEqualTo(before)
        }

        @Test fun `new budgets and commands reject foreign currency without partial writes`() {
            val request = request()
            assertThatThrownBy { service.create(tokenHash, request.copy(currency = "RUB")) }
                .isInstanceOf(IllegalArgumentException::class.java)
            assertThatThrownBy { service.find(tokenHash) }.hasMessage("budget_not_found")
            service.create(tokenHash, request)
            assertThatThrownBy {
                service.command(
                    tokenHash,
                    command(request, "CREATE_OPERATION", UUID.randomUUID()).copy(currency = "RUB"),
                )
            }.isInstanceOf(IllegalArgumentException::class.java)
            assertThat(
                jdbc.queryForObject(
                    "SELECT COUNT(*) FROM ledger_entry WHERE household_id = ?",
                    Int::class.java,
                    request.householdId,
                ),
            ).isZero()
        }

        @Test fun `retirement preserves historical binding without granting HTTP access`() {
            val request = request()
            service.create(tokenHash, request)
            jdbc.update("UPDATE member SET telegram_user_id = ? WHERE id = ?", 98234501L, request.memberId)
            mvc
                .perform(get("/api/v3/sync/changes").header("Authorization", "Bearer $token"))
                .andExpect(status().isUnauthorized)
            assertThat(service.find(tokenHash).scope.householdId).isEqualTo(request.householdId)
        }

        @Test fun `local amount restores a remotely voided expense with an audited revision`() {
            val request = request()
            service.create(tokenHash, request)
            val operation = UUID.randomUUID()
            service.command(
                tokenHash,
                command(request, "CREATE_OPERATION", operation).copy(
                    categoryId = request.categories.first().id,
                    kind = "EXPENSE",
                    amount = "10.00",
                    occurredOn = authoredOn,
                ),
            )
            jdbc.update("UPDATE ledger_entry SET status = 'VOIDED', version = version + 1 WHERE id = ?", operation)
            service.command(
                tokenHash,
                command(request, "UPDATE_OPERATION", operation).copy(amount = "20.00", expectedToken = 0),
            )
            assertThat(
                jdbc.queryForObject("SELECT status FROM ledger_entry WHERE id = ?", String::class.java, operation),
            ).isEqualTo("POSTED")
            assertThat(
                jdbc.queryForObject(
                    "SELECT original_amount FROM ledger_entry WHERE id = ?",
                    java.math.BigDecimal::class.java,
                    operation,
                ),
            ).isEqualByComparingTo("20.00")
            assertThat(
                jdbc.queryForObject(
                    "SELECT COUNT(*) FROM entry_revision WHERE entry_id = ? AND reason = 'LOCAL_STATE_RESTORED'",
                    Int::class.java,
                    operation,
                ),
            ).isEqualTo(1)
        }

        private fun request(): CreateCloudBudget {
            val id = UUID.randomUUID()
            households += id
            return CreateCloudBudget(
                UUID.randomUUID(),
                id,
                UUID.randomUUID(),
                "100.00",
                LocalDate.parse("2026-09-01"),
                listOf(
                    CloudSeedCategory(UUID.randomUUID(), "Продукты", "EXPENSE", "FOOD", "GROCERIES"),
                    CloudSeedCategory(UUID.randomUUID(), "Здоровье", "EXPENSE", "HEALTH", "HEALTH"),
                    CloudSeedCategory(UUID.randomUUID(), "Зарплата", "INCOME", "INCOME", "SALARY"),
                    CloudSeedCategory(UUID.randomUUID(), "Другое", "EXPENSE", "OTHER", "OTHER"),
                ),
            )
        }

        private fun command(
            request: CreateCloudBudget,
            type: String,
            id: UUID,
        ) = CloudBudgetCommand(
            UUID.randomUUID(),
            request.householdId,
            request.memberId,
            type,
            id.toString(),
            authoredOn,
        )
    }
