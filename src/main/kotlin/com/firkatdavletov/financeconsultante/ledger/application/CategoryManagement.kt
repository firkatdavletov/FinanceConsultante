package com.firkatdavletov.financeconsultante.ledger.application

import com.firkatdavletov.financeconsultante.ledger.domain.Category
import com.firkatdavletov.financeconsultante.ledger.domain.CategoryKind
import com.firkatdavletov.financeconsultante.ledger.domain.Direction
import com.firkatdavletov.financeconsultante.ledger.domain.TransactionCategory
import com.firkatdavletov.financeconsultante.ledger.domain.TransactionMessageParser
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.Locale
import java.util.UUID

data class CategoryRule(
    val keyword: String,
    val categoryId: UUID,
)

interface CategoryRepository {
    fun categories(householdId: UUID): List<Category>

    fun rules(householdId: UUID): List<CategoryRule>

    fun create(category: Category): Boolean

    fun archive(
        householdId: UUID,
        id: UUID,
        archived: Boolean,
    ): Boolean

    fun rename(
        householdId: UUID,
        id: UUID,
        name: String,
    ): Boolean

    fun saveRule(
        target: EntryTarget,
        keyword: String,
    ): Boolean

    fun deleteRule(
        householdId: UUID,
        keyword: String,
    )
}

@Service
@Transactional
class CategoryManagement(
    private val ownership: LedgerInteractionRepository,
    private val repository: CategoryRepository,
) {
    private fun owner(ownerId: Long) = requireNotNull(ownership.owned(ownerId)) { "Budget access denied" }

    fun categories(ownerId: Long): List<Category> {
        val owner = owner(ownerId)
        TransactionCategory.entries.forEach { ownership.resolveCategory(owner.householdId, it) }
        return repository.categories(owner.householdId)
    }

    fun name(
        ownerId: Long,
        categoryId: UUID,
    ): String = repository.categories(owner(ownerId).householdId).first { it.id == categoryId }.name

    fun dictionary(ownerId: Long): Map<String, TransactionCategory> {
        val owner = owner(ownerId)
        val categories = categories(ownerId)
        val byId =
            categories.associate { category ->
                category.id to
                    TransactionCategory(
                        category.id.toString(),
                        category.name,
                        if (category.kind ==
                            CategoryKind.INCOME
                        ) {
                            Direction.INCOME
                        } else {
                            Direction.EXPENSE
                        },
                        category.id,
                    )
            }
        return TransactionMessageParser.DEFAULT_CATEGORIES
            .mapValues { (_, reference) ->
                categories.find { it.dictionaryKey == reference.name }?.let { byId.getValue(it.id) } ?: reference
            }.toMutableMap()
            .apply {
                categories.groupBy { it.name.lowercase(Locale.ROOT) }.forEach { (name, matches) ->
                    if (matches.size == 1) put(name, byId.getValue(matches.single().id)) else remove(name)
                }
                repository.rules(owner.householdId).forEach { rule ->
                    byId[rule.categoryId]?.let { put(rule.keyword, it) }
                }
            }
    }

    @Suppress("ReturnCount") // Reject invalid names and duplicates before creating a category.
    fun add(
        ownerId: Long,
        name: String,
        kind: CategoryKind,
    ): String {
        val owner = owner(ownerId)
        if (!validName(name)) return NAME_HELP
        val categories = categories(ownerId)
        if (categories.size >= MAX_ITEMS) return "Достигнут предел 100 категорий. Используйте существующие."
        if (categories.any { it.name.equals(name, ignoreCase = true) }) return "Категория уже существует: /categories."
        val created = repository.create(Category(UUID.randomUUID(), owner.householdId, name, kind))
        return if (created) "Категория создана: $name. /categories" else "Категория уже существует. /categories"
    }

    fun archive(
        ownerId: Long,
        id: UUID,
        archived: Boolean,
    ): String {
        val changed = repository.archive(owner(ownerId).householdId, id, archived)
        return if (!changed) {
            "Категория недоступна."
        } else if (archived) {
            "Категория архивирована; история сохранена. Правила этой категории не проводят новые операции."
        } else {
            "Категория восстановлена."
        }
    }

    @Suppress("ReturnCount") // Validation leaves the existing category unchanged.
    fun rename(
        ownerId: Long,
        id: UUID,
        name: String,
    ): String {
        val owner = owner(ownerId)
        if (!validName(name)) return NAME_HELP
        if (categories(ownerId).any { it.id != id && it.name.equals(name, true) }) {
            return "Название уже используется."
        }
        return if (repository.rename(owner.householdId, id, name)) {
            "Категория переименована; её ID сохранён."
        } else {
            "Категория недоступна."
        }
    }

    @Suppress("ReturnCount") // A personal rule must refer to an explicit category correction.
    fun saveRule(
        ownerId: Long,
        entryId: UUID,
        version: Long,
        keyword: String,
    ): String {
        val owner = owner(ownerId)
        if (!validName(keyword)) return NAME_HELP
        val rules = repository.rules(owner.householdId)
        if (rules.size >= MAX_ITEMS && rules.none { it.keyword == keyword }) return "Достигнут предел 100 правил."
        return if (repository.saveRule(EntryTarget(owner.householdId, owner.memberId, entryId, version), keyword)) {
            "Правило сохранено: $keyword → исправленная категория. /rules"
        } else {
            "Правило не сохранено: нужна текущая версия после исправления категории. /history"
        }
    }

    fun rules(ownerId: Long): List<CategoryRule> = repository.rules(owner(ownerId).householdId)

    fun deleteRule(
        ownerId: Long,
        keyword: String,
    ): String {
        repository.deleteRule(owner(ownerId).householdId, keyword)
        return "Правило удалено. /rules"
    }

    private fun validName(name: String) = name.length in 1..MAX_NAME && NAME.matches(name) && name !in RESERVED

    companion object {
        private const val MAX_ITEMS = 100
        private const val MAX_NAME = 50
        private val NAME = Regex("[a-zа-яё]+(?:-[a-zа-яё]+)*")
        private val RESERVED =
            setOf("сегодня", "вчера", "завтра", "купить", "покупать", "покупку", "покупке", "покупки")
        private const val NAME_HELP =
            "Название: одно слово строчными русскими/латинскими буквами, " +
                "до 50 знаков; допустим дефис."
    }
}
