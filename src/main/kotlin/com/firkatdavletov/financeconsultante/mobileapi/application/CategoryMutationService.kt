package com.firkatdavletov.financeconsultante.mobileapi.application

import com.firkatdavletov.financeconsultante.access.application.FamilyBudgetAccess
import com.firkatdavletov.financeconsultante.access.domain.MemberRole
import com.firkatdavletov.financeconsultante.ledger.application.CategoryDeletion
import com.firkatdavletov.financeconsultante.ledger.application.CategoryManagement
import com.firkatdavletov.financeconsultante.ledger.domain.Category
import com.firkatdavletov.financeconsultante.ledger.domain.CategoryIcon
import com.firkatdavletov.financeconsultante.ledger.domain.CategoryKind
import com.firkatdavletov.financeconsultante.ledger.domain.TransactionCategory
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

class MobileApiOwnerRequired : IllegalStateException("Category management requires OWNER membership")

class InvalidCategoryRequest(
    message: String,
) : IllegalArgumentException(message)

class MobileApiCategoryNotFound : NoSuchElementException("Category is unavailable")

class MobileApiCategoryConflict(
    message: String,
    cause: Throwable? = null,
) : IllegalStateException(message, cause)

@Service
class CategoryMutationService(
    private val access: FamilyBudgetAccess,
    private val categories: CategoryManagement,
) {
    @Transactional
    fun create(
        telegramUserId: Long,
        name: String?,
        kind: String?,
        icon: String?,
    ): Category {
        requireOwner(telegramUserId)
        val categoryName = name ?: invalid("name is required")
        val categoryKind = parseKind(kind)
        val categoryIcon = parseIcon(icon)
        categories.nameError(telegramUserId, categoryName)?.let(::invalid)
        return try {
            categories.create(telegramUserId, categoryName, categoryKind, categoryIcon)
                ?: conflict("Category already exists or the category limit was reached")
        } catch (error: DataIntegrityViolationException) {
            conflict("Category already exists", error)
        }
    }

    @Transactional
    fun update(
        telegramUserId: Long,
        categoryId: UUID,
        name: String?,
        icon: String?,
    ): Category {
        requireOwner(telegramUserId)
        if (name == null && icon == null) invalid("name or icon is required")
        val categoryIcon = icon?.let(::parseIcon)
        val current = categories.category(telegramUserId, categoryId) ?: notFound()
        if (name != null) {
            if (current.dictionaryKey == TransactionCategory.OTHER.name) {
                conflict("The system OTHER category cannot be renamed")
            }
            categories.nameError(telegramUserId, name, categoryId)?.let(::invalid)
        }
        return try {
            var updated = current
            if (name != null) {
                updated =
                    categories.renameCategory(telegramUserId, categoryId, name)
                        ?: conflict("Category changed while it was being updated")
            }
            if (categoryIcon != null) {
                updated =
                    categories.changeIcon(telegramUserId, categoryId, categoryIcon)
                        ?: conflict("Category changed while it was being updated")
            }
            updated
        } catch (error: DataIntegrityViolationException) {
            conflict("Category name is already in use", error)
        }
    }

    @Transactional
    fun delete(
        telegramUserId: Long,
        categoryId: UUID,
    ): CategoryDeletion {
        requireOwner(telegramUserId)
        val current = categories.category(telegramUserId, categoryId) ?: notFound()
        if (current.dictionaryKey == TransactionCategory.OTHER.name) {
            conflict("The system OTHER category cannot be deleted")
        }
        if (current.kind != CategoryKind.EXPENSE) {
            conflict("Only expense categories can be deleted")
        }
        return try {
            categories.delete(telegramUserId, categoryId, "mobile-api:category:$categoryId:delete")
                ?: conflict("Category changed while it was being deleted")
        } catch (error: IllegalStateException) {
            if (error is MobileApiCategoryConflict) throw error
            conflict("Category changed while it was being deleted", error)
        }
    }

    private fun requireOwner(telegramUserId: Long) {
        val member = access.activeMember(telegramUserId) ?: throw MobileApiAccessDenied()
        if (member.role != MemberRole.OWNER) throw MobileApiOwnerRequired()
    }

    private fun parseKind(value: String?): CategoryKind =
        value?.let { runCatching { CategoryKind.valueOf(it) }.getOrNull() }
            ?: invalid("kind must be EXPENSE or INCOME")

    private fun parseIcon(value: String?): CategoryIcon =
        value?.let { runCatching { CategoryIcon.valueOf(it) }.getOrNull() }
            ?: invalid("icon must be a supported category icon code")

    private fun invalid(message: String): Nothing = throw InvalidCategoryRequest(message)

    private fun notFound(): Nothing = throw MobileApiCategoryNotFound()

    private fun conflict(
        message: String,
        cause: Throwable? = null,
    ): Nothing = throw MobileApiCategoryConflict(message, cause)
}
