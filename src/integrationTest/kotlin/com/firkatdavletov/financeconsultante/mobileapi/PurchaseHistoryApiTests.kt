package com.firkatdavletov.financeconsultante.mobileapi

import com.firkatdavletov.financeconsultante.PostgresIntegrationTest
import com.firkatdavletov.financeconsultante.access.domain.MemberRole
import com.firkatdavletov.financeconsultante.ledger.application.AccountingService
import com.firkatdavletov.financeconsultante.ledger.application.CorrectionResult
import com.firkatdavletov.financeconsultante.ledger.application.EntryCorrections
import com.firkatdavletov.financeconsultante.ledger.application.EntryTarget
import com.firkatdavletov.financeconsultante.ledger.application.RecordEntry
import com.firkatdavletov.financeconsultante.ledger.domain.Account
import com.firkatdavletov.financeconsultante.ledger.domain.BudgetSetup
import com.firkatdavletov.financeconsultante.ledger.domain.Category
import com.firkatdavletov.financeconsultante.ledger.domain.CategoryKind
import com.firkatdavletov.financeconsultante.ledger.domain.Direction
import com.firkatdavletov.financeconsultante.ledger.domain.Household
import com.firkatdavletov.financeconsultante.ledger.domain.Member
import com.firkatdavletov.financeconsultante.shared.domain.Money
import org.assertj.core.api.Assertions.assertThat
import org.hamcrest.Matchers.nullValue
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.TestPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

@AutoConfigureMockMvc
@TestPropertySource(
    properties = [
        "app.mobile-api.enabled=true",
        "app.mobile-api.token=synthetic-mobile-token-1234567890",
        "app.mobile-api.telegram-user-id=991002",
    ],
)
@Suppress("MagicNumber")
class PurchaseHistoryApiTests
    @Autowired
    constructor(
        private val mockMvc: MockMvc,
        private val accounting: AccountingService,
        private val corrections: EntryCorrections,
        private val jdbc: JdbcTemplate,
    ) : PostgresIntegrationTest() {
        private val households = mutableListOf<UUID>()

        @AfterEach
        fun cleanup() {
            households.forEach { household ->
                jdbc.update("DELETE FROM entry_revision WHERE household_id = ?", household)
                jdbc.update("DELETE FROM ledger_entry WHERE household_id = ?", household)
                jdbc.update("DELETE FROM category WHERE household_id = ?", household)
                jdbc.update("DELETE FROM account WHERE household_id = ?", household)
                jdbc.update("DELETE FROM member WHERE household_id = ?", household)
                jdbc.update("DELETE FROM household WHERE id = ?", household)
            }
        }

        @Test
        fun `returns only household purchases with a stable cursor and denies revoked member`() {
            val setup = openBudget(OWNER_ID)
            addMember(setup, API_USER_ID)
            val first = record(setup, "10.50", Direction.EXPENSE, "mobile:test:purchase:1")
            record(setup, "100", Direction.INCOME, "mobile:test:income")
            val second = record(setup, "20", Direction.EXPENSE, "mobile:test:purchase:2")
            val foreign = openBudget(FOREIGN_OWNER_ID)
            record(foreign, "999", Direction.EXPENSE, "mobile:test:foreign")

            val secondSequence = sequence(second.id)
            mockMvc
                .perform(get("/api/v1/purchases").header(HttpHeaders.AUTHORIZATION, AUTHORIZATION).param("limit", "1"))
                .andExpect(status().isOk)
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].id").value(second.id.toString()))
                .andExpect(jsonPath("$.items[0].sequence").value(secondSequence))
                .andExpect(jsonPath("$.items[0].category.name").value("Тестовый расход"))
                .andExpect(jsonPath("$.items[0].originalAmount.amount").value("20.00"))
                .andExpect(jsonPath("$.items[0].originalAmount.currency").value("KZT"))
                .andExpect(jsonPath("$.items[0].amountKzt.amount").value("20.00"))
                .andExpect(jsonPath("$.items[0].occurredOn").value("2026-09-02"))
                .andExpect(jsonPath("$.items[0].status").value("POSTED"))
                .andExpect(jsonPath("$.nextCursor").value(secondSequence))

            mockMvc
                .perform(
                    get("/api/v1/purchases")
                        .header(HttpHeaders.AUTHORIZATION, AUTHORIZATION)
                        .param("limit", "1")
                        .param("cursor", secondSequence.toString()),
                ).andExpect(status().isOk)
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].id").value(first.id.toString()))
                .andExpect(jsonPath("$.nextCursor").value(nullValue()))

            jdbc.update(
                "UPDATE member SET revoked_at = CURRENT_TIMESTAMP WHERE household_id = ? AND telegram_user_id = ?",
                setup.household.id,
                API_USER_ID,
            )
            mockMvc
                .perform(get("/api/v1/purchases").header(HttpHeaders.AUTHORIZATION, AUTHORIZATION))
                .andExpect(status().isForbidden)
                .andExpect(jsonPath("$.code").value("access_denied"))
        }

        @Test
        fun `requires bearer token and validates pagination`() {
            mockMvc
                .perform(get("/api/v1/purchases"))
                .andExpect(status().isUnauthorized)
                .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, "Bearer realm=\"mobile-api\""))
                .andExpect(jsonPath("$.code").value("unauthorized"))
            mockMvc
                .perform(get("/api/v1/purchases").header(HttpHeaders.AUTHORIZATION, "Bearer wrong"))
                .andExpect(status().isUnauthorized)
            mockMvc
                .perform(
                    get("/api/v1/purchases")
                        .header(HttpHeaders.AUTHORIZATION, AUTHORIZATION)
                        .param("limit", "51"),
                ).andExpect(status().isBadRequest)
                .andExpect(jsonPath("$.code").value("invalid_request"))
            mockMvc
                .perform(
                    get("/api/v1/purchases")
                        .header(HttpHeaders.AUTHORIZATION, AUTHORIZATION)
                        .param("cursor", "not-a-number"),
                ).andExpect(status().isBadRequest)
                .andExpect(jsonPath("$.code").value("invalid_request"))
            mockMvc
                .perform(get("/api/v1/purchases").header(HttpHeaders.AUTHORIZATION, AUTHORIZATION))
                .andExpect(status().isForbidden)
        }

        @Test
        @Suppress("LongMethod") // One contract scenario verifies correction, partial refund, delete, and retry.
        fun `member edits own purchase amount and deletes it through a full refund`() {
            val setup = openBudget(OWNER_ID)
            val memberId = addMember(setup, API_USER_ID)
            val purchase = record(setup, "1300", Direction.EXPENSE, "mobile:test:mutable", memberId)

            mockMvc
                .perform(
                    patch("/api/v1/purchases/{purchaseId}", purchase.id)
                        .header(HttpHeaders.AUTHORIZATION, AUTHORIZATION)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""{"version":0,"amount":"700.50"}"""),
                ).andExpect(status().isOk)
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(jsonPath("$.id").value(purchase.id.toString()))
                .andExpect(jsonPath("$.originalAmount.amount").value("700.50"))
                .andExpect(jsonPath("$.amountKzt.amount").value("700.50"))
                .andExpect(jsonPath("$.version").value(1))

            assertThat(
                jdbc.queryForObject(
                    "SELECT count(*) FROM entry_revision WHERE entry_id = ? AND reason = 'AMOUNT_CHANGED'",
                    Long::class.java,
                    purchase.id,
                ),
            ).isEqualTo(1)

            val partialRefund =
                corrections.refund(
                    EntryTarget(setup.household.id, memberId, purchase.id, 1, MemberRole.MEMBER),
                    Money.of("200"),
                    LocalDate.parse("2026-09-05"),
                    "mobile:test:partial-refund",
                )
            assertThat(partialRefund).isInstanceOf(CorrectionResult.Updated::class.java)

            mockMvc
                .perform(
                    delete("/api/v1/purchases/{purchaseId}", purchase.id)
                        .header(HttpHeaders.AUTHORIZATION, AUTHORIZATION)
                        .param("version", "2"),
                ).andExpect(status().isNoContent)
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))

            assertThat(
                jdbc.queryForObject(
                    "SELECT count(*) FROM ledger_entry WHERE household_id = ? AND refund_of = ? AND status = 'POSTED'",
                    Long::class.java,
                    setup.household.id,
                    purchase.id,
                ),
            ).isEqualTo(2)
            assertThat(
                jdbc.queryForObject(
                    "SELECT sum(original_amount) FROM ledger_entry WHERE household_id = ? AND refund_of = ? " +
                        "AND status = 'POSTED'",
                    BigDecimal::class.java,
                    setup.household.id,
                    purchase.id,
                ),
            ).isEqualByComparingTo("700.50")
            assertThat(
                jdbc.queryForObject("SELECT status FROM ledger_entry WHERE id = ?", String::class.java, purchase.id),
            ).isEqualTo("POSTED")
            assertThat(
                jdbc.queryForObject("SELECT version FROM ledger_entry WHERE id = ?", Long::class.java, purchase.id),
            ).isEqualTo(3)
            assertThat(accounting.currentBalance(setup.household.id, setup.owner.id).amount).isEqualTo(Money.of("0"))

            mockMvc
                .perform(
                    delete("/api/v1/purchases/{purchaseId}", purchase.id)
                        .header(HttpHeaders.AUTHORIZATION, AUTHORIZATION)
                        .param("version", "2"),
                ).andExpect(status().isConflict)
                .andExpect(jsonPath("$.code").value("purchase_conflict"))
            assertThat(
                jdbc.queryForObject(
                    "SELECT count(*) FROM ledger_entry WHERE household_id = ? AND refund_of = ?",
                    Long::class.java,
                    setup.household.id,
                    purchase.id,
                ),
            ).isEqualTo(2)
        }

        @Test
        fun `purchase mutations validate input version and member ownership`() {
            val setup = openBudget(OWNER_ID)
            addMember(setup, API_USER_ID)
            val ownerPurchase = record(setup, "100", Direction.EXPENSE, "mobile:test:owner-purchase")

            mockMvc
                .perform(
                    patch("/api/v1/purchases/{purchaseId}", ownerPurchase.id)
                        .header(HttpHeaders.AUTHORIZATION, AUTHORIZATION)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""{"version":0,"amount":"50"}"""),
                ).andExpect(status().isNotFound)
                .andExpect(jsonPath("$.code").value("purchase_not_found"))

            jdbc.update(
                "UPDATE member SET revoked_at = CURRENT_TIMESTAMP WHERE household_id = ? AND telegram_user_id = ?",
                setup.household.id,
                API_USER_ID,
            )
            val owned = openBudget(API_USER_ID)
            val purchase = record(owned, "100", Direction.EXPENSE, "mobile:test:validation")

            mockMvc
                .perform(
                    patch("/api/v1/purchases/{purchaseId}", purchase.id)
                        .header(HttpHeaders.AUTHORIZATION, AUTHORIZATION)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""{"version":0,"amount":"1.001"}"""),
                ).andExpect(status().isBadRequest)
                .andExpect(jsonPath("$.code").value("invalid_purchase"))

            mockMvc
                .perform(
                    delete("/api/v1/purchases/{purchaseId}", purchase.id)
                        .header(HttpHeaders.AUTHORIZATION, AUTHORIZATION),
                ).andExpect(status().isBadRequest)
                .andExpect(jsonPath("$.code").value("invalid_purchase"))
        }

        private fun openBudget(telegramUserId: Long): BudgetSetup {
            val household = Household(UUID.randomUUID())
            val setup =
                BudgetSetup(
                    household,
                    Member(UUID.randomUUID(), household.id, telegramUserId),
                    Account(UUID.randomUUID(), household.id, Money.of("0"), ACCOUNTING_START),
                    listOf(
                        Category(UUID.randomUUID(), household.id, "Тестовый доход", CategoryKind.INCOME),
                        Category(UUID.randomUUID(), household.id, "Тестовый расход", CategoryKind.EXPENSE),
                    ),
                )
            accounting.openBudget(setup)
            households += household.id
            return setup
        }

        private fun addMember(
            setup: BudgetSetup,
            telegramUserId: Long,
        ): UUID {
            val memberId = UUID.randomUUID()
            jdbc.update(
                "INSERT INTO member(id, household_id, telegram_user_id, role, joined_at) " +
                    "VALUES (?, ?, ?, 'MEMBER', CURRENT_TIMESTAMP)",
                memberId,
                setup.household.id,
                telegramUserId,
            )
            return memberId
        }

        private fun record(
            setup: BudgetSetup,
            amount: String,
            direction: Direction,
            source: String,
            memberId: UUID = setup.owner.id,
        ) = accounting
            .record(
                RecordEntry(
                    setup.household.id,
                    memberId,
                    setup.categories.single { it.kind == direction.categoryKind }.id,
                    direction,
                    Money.of(amount),
                    OCCURRED_AT,
                    source,
                ),
            ).entry

        private fun sequence(entryId: UUID): Long =
            checkNotNull(
                jdbc.queryForObject(
                    "SELECT history_sequence FROM ledger_entry WHERE id = ?",
                    Long::class.java,
                    entryId,
                ),
            )

        companion object {
            private const val API_USER_ID = 991002L
            private const val OWNER_ID = 991001L
            private const val FOREIGN_OWNER_ID = 991003L
            private const val AUTHORIZATION = "Bearer synthetic-mobile-token-1234567890"
            private val ACCOUNTING_START = Instant.parse("2026-09-01T00:00:00Z")
            private val OCCURRED_AT = Instant.parse("2026-09-02T00:00:00Z")
        }
    }
