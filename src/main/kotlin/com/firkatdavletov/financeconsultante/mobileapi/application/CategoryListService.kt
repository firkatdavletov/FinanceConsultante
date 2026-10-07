package com.firkatdavletov.financeconsultante.mobileapi.application

import com.firkatdavletov.financeconsultante.access.application.FamilyBudgetAccess
import com.firkatdavletov.financeconsultante.ledger.application.CategoryRepository
import com.firkatdavletov.financeconsultante.ledger.domain.Category
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Isolation
import org.springframework.transaction.annotation.Transactional

data class CategoryList(
    val items: List<Category>,
)

@Service
class CategoryListService(
    private val access: FamilyBudgetAccess,
    private val categories: CategoryRepository,
) {
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    fun categories(telegramUserId: Long): CategoryList {
        val member = access.activeMember(telegramUserId) ?: throw MobileApiAccessDenied()
        return CategoryList(categories.categories(member.householdId).filterNot(Category::archived))
    }
}
