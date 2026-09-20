package com.firkatdavletov.financeconsultante.ledger.application

import com.firkatdavletov.financeconsultante.ledger.domain.Category
import com.firkatdavletov.financeconsultante.ledger.domain.CategoryAliasNormalizer
import com.firkatdavletov.financeconsultante.ledger.domain.CategoryIcon
import com.firkatdavletov.financeconsultante.ledger.domain.CategoryKind
import com.firkatdavletov.financeconsultante.ledger.domain.Direction
import com.firkatdavletov.financeconsultante.ledger.domain.TransactionCategory
import com.firkatdavletov.financeconsultante.ledger.domain.TransactionMessageParser
import com.firkatdavletov.financeconsultante.ledger.domain.revised
import com.firkatdavletov.financeconsultante.shared.domain.Money
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.ChronoUnit
import java.util.Locale
import java.util.UUID

data class CategoryRule(
    val keyword: String,
    val categoryId: UUID,
)

data class CategoryUsage(
    val monthAmount: Money,
    val monthOperations: Long,
    val totalOperations: Long,
)

data class CategoryDetails(
    val category: Category,
    val usage: CategoryUsage,
)

data class CategoryDeletion(
    val movedOperations: Int,
    val fallback: Category,
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

    fun changeIcon(
        householdId: UUID,
        id: UUID,
        icon: CategoryIcon,
    ): Boolean

    fun usage(
        householdId: UUID,
        id: UUID,
        startInclusive: LocalDate,
        endExclusive: LocalDate,
    ): CategoryUsage

    fun saveRule(
        target: EntryTarget,
        keyword: String,
    ): Boolean

    fun saveLearnedRule(
        target: EntryTarget,
        alias: String,
    ): Boolean

    fun deleteRule(
        householdId: UUID,
        keyword: String,
    )

    fun deleteRules(
        householdId: UUID,
        categoryId: UUID,
    )
}

@Service
@Transactional
// Legacy commands and the button-driven workflow share one transaction boundary.
@Suppress("TooManyFunctions")
class CategoryManagement(
    private val ownership: LedgerInteractionRepository,
    private val repository: CategoryRepository,
    private val ledger: LedgerRepository,
    private val clock: Clock,
) {
    private fun owner(ownerId: Long) = requireNotNull(ownership.owned(ownerId)) { "Budget access denied" }

    fun categories(ownerId: Long): List<Category> {
        val owner = owner(ownerId)
        TransactionCategory.entries.forEach { ownership.resolveCategory(owner.householdId, it) }
        return repository.categories(owner.householdId)
    }

    fun activeCategories(ownerId: Long): List<Category> = categories(ownerId).filterNot { it.archived }

    fun category(
        ownerId: Long,
        categoryId: UUID,
    ): Category? = categories(ownerId).firstOrNull { it.id == categoryId && !it.archived }

    fun details(
        ownerId: Long,
        categoryId: UUID,
        month: YearMonth,
    ): CategoryDetails? {
        val owner = owner(ownerId)
        val category = categories(ownerId).firstOrNull { it.id == categoryId && !it.archived } ?: return null
        val start = month.atDay(1)
        return CategoryDetails(category, repository.usage(owner.householdId, categoryId, start, start.plusMonths(1)))
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
        val error = nameError(ownerId, name) ?: ownerOnlyError(ownerId)
        if (error != null) return error
        val owner = owner(ownerId)
        val created = repository.create(Category(UUID.randomUUID(), owner.householdId, name, kind))
        return if (created) "Категория создана: $name. /categories" else "Категория уже существует. /categories"
    }

    fun createExpense(
        ownerId: Long,
        name: String,
        icon: CategoryIcon,
    ): Category? {
        if (nameError(ownerId, name) != null || ownerOnlyError(ownerId) != null) return null
        val owner = owner(ownerId)
        val category = Category(UUID.randomUUID(), owner.householdId, name, CategoryKind.EXPENSE, icon = icon)
        return category.takeIf { repository.create(it) }
    }

    @Suppress("ReturnCount") // Name validation reports the first actionable error before a write.
    fun nameError(
        ownerId: Long,
        name: String,
        except: UUID? = null,
    ): String? {
        if (!validName(name)) return NAME_HELP
        val categories = categories(ownerId)
        if (categories.size >= MAX_ITEMS && except == null) {
            return "Достигнут предел 100 категорий. Используйте существующие."
        }
        return if (categories.any { it.id != except && it.name.equals(name, ignoreCase = true) }) {
            "Категория «$name» уже существует."
        } else {
            null
        }
    }

    @Suppress("ReturnCount") // Access and protected-category guards precede the legacy archive mutation.
    fun archive(
        ownerId: Long,
        id: UUID,
        archived: Boolean,
    ): String {
        val owner = owner(ownerId)
        if (!owner.canManageHousehold) return OWNER_ONLY
        val category = categories(ownerId).firstOrNull { it.id == id } ?: return "Категория недоступна."
        if (protected(category)) return "Системную категорию «Другое» нельзя архивировать."
        val changed = repository.archive(owner.householdId, id, archived)
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
        if (!owner.canManageHousehold) return OWNER_ONLY
        if (!validName(name)) return NAME_HELP
        val current = categories(ownerId).firstOrNull { it.id == id } ?: return "Категория недоступна."
        if (protected(current)) return "Системную категорию «Другое» нельзя переименовать."
        if (categories(ownerId).any { it.id != id && it.name.equals(name, true) }) {
            return "Название уже используется."
        }
        return if (repository.rename(owner.householdId, id, name)) {
            "Категория переименована; её ID сохранён."
        } else {
            "Категория недоступна."
        }
    }

    @Suppress("ReturnCount") // UI mutation rejects stale, protected, and invalid categories independently.
    fun renameCategory(
        ownerId: Long,
        id: UUID,
        name: String,
    ): Category? {
        val owner = owner(ownerId)
        val current = categories(ownerId).firstOrNull { it.id == id && !it.archived } ?: return null
        if (!owner.canManageHousehold || protected(current) || nameError(ownerId, name, id) != null) return null
        return current.copy(name = name).takeIf { repository.rename(owner.householdId, id, name) }
    }

    @Suppress("ReturnCount") // UI mutation rejects stale and unauthorized categories before writing.
    fun changeIcon(
        ownerId: Long,
        id: UUID,
        icon: CategoryIcon,
    ): Category? {
        val owner = owner(ownerId)
        val current = categories(ownerId).firstOrNull { it.id == id && !it.archived } ?: return null
        if (!owner.canManageHousehold) return null
        return current.copy(icon = icon).takeIf { repository.changeIcon(owner.householdId, id, icon) }
    }

    @Suppress("ReturnCount") // Deletion guards must complete before the audited bulk reassignment starts.
    fun delete(
        ownerId: Long,
        id: UUID,
        source: String,
    ): CategoryDeletion? {
        val owner = owner(ownerId)
        val current = categories(ownerId).firstOrNull { it.id == id && !it.archived } ?: return null
        if (!owner.canManageHousehold || protected(current) || current.kind != CategoryKind.EXPENSE) return null
        val fallback = ownership.resolveCategory(owner.householdId, TransactionCategory.OTHER)
        repository.archive(owner.householdId, fallback.id, false)
        val now = clock.instant().truncatedTo(ChronoUnit.MICROS)
        val entries = ledger.findByCategory(owner.householdId, current.id)
        entries.forEach { entry ->
            check(
                ledger.revise(
                    entry.revised(now).copy(categoryId = fallback.id),
                    owner.memberId,
                    "CATEGORY_DELETED",
                    "$source:${entry.id}",
                ),
            ) { "Category reassignment conflict" }
        }
        repository.deleteRules(owner.householdId, current.id)
        check(repository.archive(owner.householdId, current.id, true)) { "Category archive conflict" }
        return CategoryDeletion(entries.size, fallback.copy(archived = false))
    }

    @Suppress("ReturnCount") // A personal rule must refer to an explicit category correction.
    fun saveRule(
        ownerId: Long,
        entryId: UUID,
        version: Long,
        keyword: String,
    ): String {
        val owner = owner(ownerId)
        if (!owner.canManageHousehold) return OWNER_ONLY
        val normalized = normalizedAlias(keyword) ?: return RULE_HELP
        val rules = repository.rules(owner.householdId)
        if (rules.size >= MAX_ITEMS && rules.none { it.keyword == normalized }) return "Достигнут предел 100 правил."
        return if (repository.saveRule(EntryTarget(owner.householdId, owner.memberId, entryId, version), normalized)) {
            "Правило сохранено: $normalized → исправленная категория. /rules"
        } else {
            "Правило не сохранено: нужна текущая версия после исправления категории. /history"
        }
    }

    @Suppress("ReturnCount") // Invalid, exhausted, and persisted aliases are separate safe outcomes.
    fun learnRule(
        owner: OwnedLedger,
        entryId: UUID,
        version: Long,
        alias: String?,
    ): Boolean {
        val normalized = alias?.let(::normalizedAlias) ?: return false
        val rules = repository.rules(owner.householdId)
        if (rules.size >= MAX_ITEMS && rules.none { it.keyword == normalized }) return false
        return repository.saveLearnedRule(
            EntryTarget(owner.householdId, owner.memberId, entryId, version, owner.role),
            normalized,
        )
    }

    fun rules(ownerId: Long): List<CategoryRule> = repository.rules(owner(ownerId).householdId)

    @Suppress("ReturnCount") // Access and alias validation precede the delete independently.
    fun deleteRule(
        ownerId: Long,
        keyword: String,
    ): String {
        val owner = owner(ownerId)
        if (!owner.canManageHousehold) return OWNER_ONLY
        val normalized = normalizedAlias(keyword) ?: return RULE_HELP
        repository.deleteRule(owner.householdId, normalized)
        return "Правило удалено. /rules"
    }

    private fun validName(name: String) =
        name.length in 1..MAX_NAME &&
            CATEGORY_NAME.matches(name) &&
            name.split(' ').none { it.lowercase(Locale.ROOT) in RESERVED }

    private fun normalizedAlias(alias: String): String? =
        CategoryAliasNormalizer
            .normalize(alias)
            ?.takeIf { value -> value.split(' ').none { it in RESERVED } }

    private fun ownerOnlyError(ownerId: Long): String? = if (owner(ownerId).canManageHousehold) null else OWNER_ONLY

    private fun protected(category: Category): Boolean = category.dictionaryKey == TransactionCategory.OTHER.name

    companion object {
        private const val MAX_ITEMS = 100
        private const val MAX_NAME = 50
        private const val CATEGORY_WORD = "[A-Za-zА-Яа-яЁё]+(?:-[A-Za-zА-Яа-яЁё]+)*"
        private val CATEGORY_NAME = Regex("$CATEGORY_WORD(?: $CATEGORY_WORD)*")
        private val RESERVED =
            setOf("сегодня", "вчера", "завтра", "купить", "покупать", "покупку", "покупке", "покупки")
        private const val NAME_HELP =
            "Название: одно или несколько слов русскими/латинскими буквами, " +
                "до 50 знаков; допустим дефис."
        private const val RULE_HELP =
            "Правило: одно или несколько слов русскими/латинскими буквами, " +
                "до 100 знаков; допустим дефис."
        private const val OWNER_ONLY = "Категориями и правилами управляет только OWNER."
    }
}
