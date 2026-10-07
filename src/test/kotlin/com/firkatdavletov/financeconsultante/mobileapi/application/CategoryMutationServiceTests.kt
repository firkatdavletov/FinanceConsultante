package com.firkatdavletov.financeconsultante.mobileapi.application

import com.firkatdavletov.financeconsultante.access.application.FamilyBudgetAccess
import com.firkatdavletov.financeconsultante.access.domain.FamilyInvitation
import com.firkatdavletov.financeconsultante.access.domain.FamilyMember
import com.firkatdavletov.financeconsultante.access.domain.MemberRole
import com.firkatdavletov.financeconsultante.ledger.application.CategoryManagement
import com.firkatdavletov.financeconsultante.ledger.application.CategoryRepository
import com.firkatdavletov.financeconsultante.ledger.application.CategoryRule
import com.firkatdavletov.financeconsultante.ledger.application.CategoryUsage
import com.firkatdavletov.financeconsultante.ledger.application.EntryTarget
import com.firkatdavletov.financeconsultante.ledger.application.LedgerInteractionRepository
import com.firkatdavletov.financeconsultante.ledger.application.LedgerRepository
import com.firkatdavletov.financeconsultante.ledger.application.OwnedLedger
import com.firkatdavletov.financeconsultante.ledger.domain.Category
import com.firkatdavletov.financeconsultante.ledger.domain.CategoryIcon
import com.firkatdavletov.financeconsultante.ledger.domain.CategoryKind
import com.firkatdavletov.financeconsultante.ledger.domain.LedgerEntry
import com.firkatdavletov.financeconsultante.ledger.domain.TransactionCategory
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID

class CategoryMutationServiceTests {
    @Test
    fun `owner creates and updates category`() {
        val fixture = fixture(MemberRole.OWNER)

        val created = fixture.service.create(API_USER_ID, "Кафе", "EXPENSE", "ENTERTAINMENT")
        val updated = fixture.service.update(API_USER_ID, created.id, "Рестораны", "FOOD")

        assertThat(created.kind).isEqualTo(CategoryKind.EXPENSE)
        assertThat(created.icon).isEqualTo(CategoryIcon.ENTERTAINMENT)
        assertThat(updated.name).isEqualTo("Рестораны")
        assertThat(updated.icon).isEqualTo(CategoryIcon.FOOD)
        assertThat(fixture.repository.items).contains(updated)
    }

    @Test
    fun `member cannot mutate categories`() {
        val fixture = fixture(MemberRole.MEMBER)

        assertThatThrownBy {
            fixture.service.create(API_USER_ID, "Кафе", "EXPENSE", "FOOD")
        }.isInstanceOf(MobileApiOwnerRequired::class.java)
        assertThat(fixture.repository.reads).isZero()
    }

    @Test
    fun `delete archives expense category and keeps fallback active`() {
        val fixture = fixture(MemberRole.OWNER)
        val category = fixture.service.create(API_USER_ID, "Кафе", "EXPENSE", "FOOD")

        val result = fixture.service.delete(API_USER_ID, category.id)

        assertThat(result.movedOperations).isZero()
        assertThat(result.fallback.dictionaryKey).isEqualTo(TransactionCategory.OTHER.name)
        assertThat(
            fixture.repository.items
                .single { it.id == category.id }
                .archived,
        ).isTrue()
        assertThat(
            fixture.repository.items
                .single { it.id == result.fallback.id }
                .archived,
        ).isFalse()
    }

    @Test
    fun `invalid mutation is rejected before a write`() {
        val fixture = fixture(MemberRole.OWNER)

        assertThatThrownBy {
            fixture.service.create(API_USER_ID, "Кафе", "UNKNOWN", "FOOD")
        }.isInstanceOf(InvalidCategoryRequest::class.java)
        assertThatThrownBy {
            fixture.service.update(API_USER_ID, UUID.randomUUID(), null, null)
        }.isInstanceOf(InvalidCategoryRequest::class.java)
        assertThat(fixture.repository.writes).isZero()
    }

    private fun fixture(role: MemberRole): Fixture {
        val householdId = UUID.randomUUID()
        val memberId = UUID.randomUUID()
        val member =
            FamilyMember(
                memberId,
                householdId,
                API_USER_ID,
                role,
                reportsEnabled = false,
                joinedAt = NOW,
            )
        val owned = OwnedLedger(householdId, memberId, API_USER_ID, NOW.minusSeconds(3600), role)
        val repository = FakeCategoryRepository()
        val ownership = FakeLedgerInteractionRepository(owned, repository)
        val management = CategoryManagement(ownership, repository, EmptyLedgerRepository(), CLOCK)
        return Fixture(CategoryMutationService(FakeAccess(member), management), repository)
    }

    private data class Fixture(
        val service: CategoryMutationService,
        val repository: FakeCategoryRepository,
    )

    private class FakeAccess(
        private val member: FamilyMember?,
    ) : FamilyBudgetAccess {
        override fun canReceive(telegramUserId: Long) = false

        override fun canProcess(
            telegramUserId: Long,
            memberId: UUID?,
        ) = false

        override fun canDeliver(
            telegramUserId: Long,
            memberId: UUID,
            report: Boolean,
        ) = false

        override fun activeMember(
            telegramUserId: Long,
            memberId: UUID?,
        ) = member

        override fun pendingInvitation(telegramUserId: Long): FamilyInvitation? = null
    }

    private class FakeLedgerInteractionRepository(
        private val owned: OwnedLedger,
        private val categories: FakeCategoryRepository,
    ) : LedgerInteractionRepository {
        override fun owned(ownerId: Long): OwnedLedger? = owned.takeIf { it.ownerId == ownerId }

        override fun resolveCategory(
            householdId: UUID,
            key: TransactionCategory,
        ): Category =
            categories.items.firstOrNull { it.dictionaryKey == key.name }
                ?: Category(
                    UUID.randomUUID(),
                    householdId,
                    key.displayName,
                    key.direction.categoryKind,
                    dictionaryKey = key.name,
                    icon = CategoryIcon.defaultFor(key.name),
                ).also { categories.items += it }

        override fun history(
            householdId: UUID,
            before: Long,
            limit: Int,
        ) = error("Not used")

        override fun purchaseHistory(
            householdId: UUID,
            before: Long,
            limit: Int,
        ) = error("Not used")
    }

    private class FakeCategoryRepository : CategoryRepository {
        val items = mutableListOf<Category>()
        var reads = 0
        var writes = 0

        override fun categories(householdId: UUID): List<Category> {
            reads++
            return items.filter { it.householdId == householdId }.sortedWith(compareBy(Category::name, Category::id))
        }

        override fun rules(householdId: UUID): List<CategoryRule> = emptyList()

        override fun create(category: Category): Boolean {
            writes++
            if (items.any { it.householdId == category.householdId && it.name == category.name }) return false
            items += category
            return true
        }

        override fun archive(
            householdId: UUID,
            id: UUID,
            archived: Boolean,
        ): Boolean = replace(householdId, id) { it.copy(archived = archived) }

        override fun rename(
            householdId: UUID,
            id: UUID,
            name: String,
        ): Boolean = replace(householdId, id) { it.copy(name = name) }

        override fun changeIcon(
            householdId: UUID,
            id: UUID,
            icon: CategoryIcon,
        ): Boolean = replace(householdId, id) { it.copy(icon = icon) }

        override fun usage(
            householdId: UUID,
            id: UUID,
            startInclusive: LocalDate,
            endExclusive: LocalDate,
        ): CategoryUsage = error("Not used")

        override fun saveRule(
            target: EntryTarget,
            keyword: String,
        ) = error("Not used")

        override fun saveLearnedRule(
            target: EntryTarget,
            alias: String,
        ) = error("Not used")

        override fun deleteRule(
            householdId: UUID,
            keyword: String,
        ) = error("Not used")

        override fun deleteRules(
            householdId: UUID,
            categoryId: UUID,
        ) {
            writes++
        }

        private fun replace(
            householdId: UUID,
            id: UUID,
            replacement: (Category) -> Category,
        ): Boolean {
            val index = items.indexOfFirst { it.householdId == householdId && it.id == id }
            if (index < 0) return false
            writes++
            items[index] = replacement(items[index])
            return true
        }
    }

    private class EmptyLedgerRepository : LedgerRepository {
        override fun find(
            householdId: UUID,
            entryId: UUID,
        ): LedgerEntry? = null

        override fun refunds(
            householdId: UUID,
            entryId: UUID,
        ): List<LedgerEntry> = emptyList()

        override fun findByCategory(
            householdId: UUID,
            categoryId: UUID,
        ): List<LedgerEntry> = emptyList()

        override fun revise(
            entry: LedgerEntry,
            actorId: UUID,
            reason: String,
            source: String,
        ) = error("Not used")

        override fun findBySource(
            householdId: UUID,
            sourceKey: String,
        ): LedgerEntry? = null

        override fun insert(entry: LedgerEntry) = error("Not used")

        override fun findBefore(
            householdId: UUID,
            untilExclusive: Instant,
        ): List<LedgerEntry> = emptyList()
    }

    companion object {
        private const val API_USER_ID = 991002L
        private val NOW = Instant.parse("2026-10-07T00:00:00Z")
        private val CLOCK = Clock.fixed(NOW, ZoneOffset.UTC)
    }
}
