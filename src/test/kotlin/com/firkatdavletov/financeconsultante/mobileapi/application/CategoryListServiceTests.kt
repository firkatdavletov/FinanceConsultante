package com.firkatdavletov.financeconsultante.mobileapi.application

import com.firkatdavletov.financeconsultante.access.application.FamilyBudgetAccess
import com.firkatdavletov.financeconsultante.access.domain.FamilyInvitation
import com.firkatdavletov.financeconsultante.access.domain.FamilyMember
import com.firkatdavletov.financeconsultante.access.domain.MemberRole
import com.firkatdavletov.financeconsultante.ledger.application.CategoryRepository
import com.firkatdavletov.financeconsultante.ledger.application.CategoryRule
import com.firkatdavletov.financeconsultante.ledger.application.CategoryUsage
import com.firkatdavletov.financeconsultante.ledger.application.EntryTarget
import com.firkatdavletov.financeconsultante.ledger.domain.Category
import com.firkatdavletov.financeconsultante.ledger.domain.CategoryKind
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

class CategoryListServiceTests {
    @Test
    fun `returns only active categories from member household`() {
        val householdId = UUID.randomUUID()
        val active = category(householdId, "Продукты")
        val archived = category(householdId, "Архив", archived = true)
        val repository = FakeCategoryRepository(listOf(active, archived))
        val member = member(householdId)

        val result = CategoryListService(FakeAccess(member), repository).categories(member.telegramUserId)

        assertThat(result.items).containsExactly(active)
        assertThat(repository.requestedHouseholdId).isEqualTo(householdId)
    }

    @Test
    fun `rejects inactive membership before reading categories`() {
        val repository = FakeCategoryRepository(emptyList())

        assertThatThrownBy { CategoryListService(FakeAccess(null), repository).categories(API_USER_ID) }
            .isInstanceOf(MobileApiAccessDenied::class.java)
        assertThat(repository.requestedHouseholdId).isNull()
    }

    private fun category(
        householdId: UUID,
        name: String,
        archived: Boolean = false,
    ) = Category(
        UUID.randomUUID(),
        householdId,
        name,
        CategoryKind.EXPENSE,
        archived,
        icon = "FOOD",
    )

    private fun member(householdId: UUID) =
        FamilyMember(
            UUID.randomUUID(),
            householdId,
            API_USER_ID,
            MemberRole.MEMBER,
            reportsEnabled = false,
            joinedAt = Instant.parse("2026-10-01T00:00:00Z"),
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

    private class FakeCategoryRepository(
        private val items: List<Category>,
    ) : CategoryRepository {
        var requestedHouseholdId: UUID? = null

        override fun categories(householdId: UUID): List<Category> {
            requestedHouseholdId = householdId
            return items
        }

        override fun rules(householdId: UUID): List<CategoryRule> = error("Not used")

        override fun create(category: Category): Boolean = error("Not used")

        override fun archive(
            householdId: UUID,
            id: UUID,
            archived: Boolean,
        ): Boolean = error("Not used")

        override fun rename(
            householdId: UUID,
            id: UUID,
            name: String,
        ): Boolean = error("Not used")

        override fun changeIcon(
            householdId: UUID,
            id: UUID,
            icon: String,
        ): Boolean = error("Not used")

        override fun usage(
            householdId: UUID,
            id: UUID,
            startInclusive: LocalDate,
            endExclusive: LocalDate,
        ): CategoryUsage = error("Not used")

        override fun saveRule(
            target: EntryTarget,
            keyword: String,
        ): Boolean = error("Not used")

        override fun saveLearnedRule(
            target: EntryTarget,
            alias: String,
        ): Boolean = error("Not used")

        override fun deleteRule(
            householdId: UUID,
            keyword: String,
        ) = error("Not used")

        override fun deleteRules(
            householdId: UUID,
            categoryId: UUID,
        ) = error("Not used")
    }

    companion object {
        private const val API_USER_ID = 991002L
    }
}
