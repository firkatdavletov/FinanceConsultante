package com.firkatdavletov.financeconsultante.telegram.application

import com.firkatdavletov.financeconsultante.ledger.application.CategoryManagement
import com.firkatdavletov.financeconsultante.ledger.application.LedgerOperations
import com.firkatdavletov.financeconsultante.ledger.application.OwnedLedger
import com.firkatdavletov.financeconsultante.ledger.application.ProposalCheck
import com.firkatdavletov.financeconsultante.ledger.application.TransactionDraftRepository
import com.firkatdavletov.financeconsultante.ledger.domain.CategoryIcon
import com.firkatdavletov.financeconsultante.ledger.domain.CategoryKind
import com.firkatdavletov.financeconsultante.ledger.domain.Direction
import com.firkatdavletov.financeconsultante.ledger.domain.DraftState
import com.firkatdavletov.financeconsultante.ledger.domain.LedgerEntry
import com.firkatdavletov.financeconsultante.ledger.domain.MessageParseResult.RecordTransaction
import com.firkatdavletov.financeconsultante.ledger.domain.TransactionCategory
import com.firkatdavletov.financeconsultante.ledger.domain.TransactionDraft
import java.util.UUID

@Suppress("TooManyFunctions") // One bounded state machine owns draft commands, callbacks, and category selection.
internal class TelegramDraftHandler(
    private val operations: LedgerOperations,
    private val drafts: TransactionDraftRepository,
    private val categories: CategoryManagement,
    private val recordedReply: (OwnedLedger, LedgerEntry, String) -> TelegramReply =
        { _, entry, category -> LedgerMessages.recorded(entry, category) },
) {
    fun command(
        name: String,
        arguments: String,
        owner: OwnedLedger,
        botId: Long,
    ): TelegramReply? {
        val active = drafts.active(owner, botId)
        return when (name) {
            "/draft" -> {
                val page = draftPage(arguments)
                if (page == null) {
                    "Формат: /draft [страница].".reply()
                } else {
                    LedgerMessages.drafts(active, page)
                }
            }

            "/confirm" -> {
                confirm(arguments, owner, botId, active)
            }

            else -> {
                cancel(arguments, owner, botId, active)
            }
        }
    }

    @Suppress("ReturnCount") // Callback guards reject malformed, stale, or inaccessible draft actions.
    fun callback(
        message: InboxMessage,
        botId: Long,
    ): TelegramReply {
        val owner = operations.owned(message.ownerId) ?: return "Сначала завершите настройку: /start.".reply()
        DraftCategoryCallbacks.parsePage(message.text)?.let { target ->
            return categoryPage(message.ownerId, owner, botId, target)
        }
        DraftCategoryCallbacks.parseSelection(message.text)?.let { target ->
            return selectCategory(message.ownerId, owner, botId, target)
        }
        DraftCategoryCallbacks.parseNewCategory(message.text)?.let { target ->
            return beginNewCategory(owner, botId, target)
        }
        val match = DRAFT_CALLBACK.matchEntire(message.text) ?: return "Кнопка черновика недоступна. /draft".reply()
        val id = UUID.fromString(match.groupValues[2])
        val version = match.groupValues[3].toLongOrNull() ?: return "Кнопка черновика недоступна. /draft".reply()
        return when (match.groupValues[1]) {
            "dc" -> confirm(owner, botId, id, version)
            "dx" -> cancel(owner, botId, id, version)
            else -> show(owner, botId, id, version)
        }
    }

    @Suppress("ReturnCount") // Only the one persisted category-name dialog may consume ordinary text.
    fun categoryNameInput(
        message: InboxMessage,
        botId: Long,
    ): TelegramReply? {
        val owner = operations.owned(message.ownerId) ?: return null
        val draft =
            drafts.active(owner, botId).singleOrNull { it.state == DraftState.NEEDS_CATEGORY_NAME } ?: return null
        if (message.text.startsWith('/')) {
            if (message.text.trim().equals("/cancel", ignoreCase = true)) {
                drafts.save(owner, botId, draft.cancel())
                return "Операция отменена. Баланс не изменён.".reply()
            }
            drafts.save(
                owner,
                botId,
                draft.revise(draft.proposal, DraftState.NEEDS_CATEGORY, MANUAL_CATEGORY_REASON),
            )
            return null
        }
        val name = message.text.trim()
        categories.nameError(message.ownerId, name)?.let { error ->
            return newCategoryPrompt(draft, error)
        }
        val category =
            categories.createExpense(message.ownerId, name, CategoryIcon.OTHER)
                ?: return newCategoryPrompt(draft, "Не удалось создать категорию. Введите другое название.")
        val proposal = checkNotNull(draft.proposal)
        val categorized =
            proposal.copy(
                category = TransactionCategory(category.id.toString(), category.name, proposal.direction, category.id),
            )
        return recordSelection(owner, botId, draft, categorized)
    }

    @Suppress("ReturnCount") // The no-argument shortcut is valid only for one unambiguous active draft.
    private fun confirm(
        arguments: String,
        owner: OwnedLedger,
        botId: Long,
        active: List<TransactionDraft>,
    ): TelegramReply {
        if (arguments.isEmpty()) {
            return when (active.size) {
                0 -> "Незавершённых черновиков нет.".reply()
                1 -> confirm(owner, botId, active.single().id, active.single().version)
                else -> LedgerMessages.drafts(active, notice = "Выберите черновик кнопкой подтверждения.")
            }
        }
        val target =
            draftTarget(arguments)
                ?: return LedgerMessages.drafts(active, notice = "Не удалось распознать подтверждение.")
        return confirm(owner, botId, target.first, target.second)
    }

    @Suppress("ReturnCount") // Every terminal state protects the ledger from a stale confirmation.
    private fun confirm(
        owner: OwnedLedger,
        botId: Long,
        id: UUID,
        version: Long,
    ): TelegramReply {
        val draft = drafts.find(owner, botId, id) ?: return "Черновик недоступен. /draft".reply()
        if (draft.state == DraftState.CONFIRMED) {
            return "Уже записано: ${draft.entryId}. Повторного изменения баланса нет.".reply()
        }
        if (draft.state == DraftState.CANCELLED) return "Черновик уже отменён. Баланс не изменён.".reply()
        if (draft.version != version) return LedgerMessages.draft(draft, "Подтверждение устарело. Актуальные данные:")
        if (draft.state !in setOf(DraftState.READY, DraftState.WAITING_RATE)) {
            return LedgerMessages.draft(draft, "Черновик нужно уточнить перед записью.")
        }
        val proposal = checkNotNull(draft.proposal)
        return when (val checked = operations.check(owner, proposal, source(botId, draft.sourceInboxId))) {
            is ProposalCheck.Pending -> {
                val revised = draft.revise(proposal, checked.state, checked.reason)
                drafts.save(owner, botId, revised)
                LedgerMessages.draft(revised)
            }

            is ProposalCheck.Ready -> {
                val entry = operations.record(checked).entry
                val learned =
                    categories.learnRule(owner, entry.id, entry.version, draft.categoryAlias)
                drafts.save(owner, botId, draft.confirm(entry.id))
                recorded(owner, entry, proposal.category.displayName, draft.categoryAlias, learned)
            }
        }
    }

    @Suppress("LongMethod", "ReturnCount") // Validation and the two-column keyboard form one screen response.
    private fun categoryPage(
        ownerId: Long,
        owner: OwnedLedger,
        botId: Long,
        target: DraftCategoryPage,
    ): TelegramReply {
        var draft = drafts.find(owner, botId, target.draftId) ?: return "Черновик недоступен. /draft".reply()
        if (!draft.active || (!draft.recognizedByAi && !draft.requiresCategory)) {
            return "Выбор категории уже недоступен. /draft".reply()
        }
        if (draft.version != target.version) return LedgerMessages.draft(draft, "Кнопка устарела. Актуальные данные:")
        if (draft.state == DraftState.NEEDS_CATEGORY_NAME) {
            draft = draft.revise(draft.proposal, DraftState.NEEDS_CATEGORY, MANUAL_CATEGORY_REASON)
            drafts.save(owner, botId, draft)
        }
        val direction = checkNotNull(draft.proposal).direction
        val available =
            categories
                .activeCategories(ownerId)
                .filter { category -> category.kind == direction.categoryKind() }
        if (available.isEmpty()) return LedgerMessages.draft(draft, "Нет доступных категорий этого типа.")
        val pages = (available.size + CATEGORY_PAGE_SIZE - 1) / CATEGORY_PAGE_SIZE
        if (target.page !in 0 until pages) return LedgerMessages.draft(draft, "Страница категорий недоступна.")
        val visible = available.drop(target.page * CATEGORY_PAGE_SIZE).take(CATEGORY_PAGE_SIZE)
        val buttons =
            buildList {
                visible.forEachIndexed { index, category ->
                    add(
                        ReplyButton(
                            "${category.icon.emoji} ${category.name}",
                            DraftCategoryCallbacks.selection(draft.id, draft.version, category.id),
                            index / CATEGORY_COLUMNS,
                        ),
                    )
                }
                val navigationRow = (visible.size + CATEGORY_COLUMNS - 1) / CATEGORY_COLUMNS
                if (target.page > 0) {
                    add(
                        ReplyButton(
                            "◀️",
                            DraftCategoryCallbacks.page(draft.id, draft.version, target.page - 1),
                            navigationRow,
                        ),
                    )
                }
                if (target.page + 1 < pages) {
                    add(
                        ReplyButton(
                            "▶️",
                            DraftCategoryCallbacks.page(draft.id, draft.version, target.page + 1),
                            navigationRow,
                        ),
                    )
                }
                var actionRow = navigationRow + 1
                if (owner.canManageHousehold && direction == Direction.EXPENSE) {
                    add(
                        ReplyButton(
                            "➕ Новая категория",
                            DraftCategoryCallbacks.newCategory(draft.id, draft.version),
                            actionRow++,
                        ),
                    )
                }
                add(ReplyButton("❌ Отменить операцию", "dx:${draft.id}:${draft.version}", actionRow))
            }
        return TelegramReply(
            "Выберите категорию для операции. Страница ${target.page + 1} из $pages.",
            buttons,
        )
    }

    @Suppress("ReturnCount") // Category selection validates the draft version, scope, state, and direction.
    private fun selectCategory(
        ownerId: Long,
        owner: OwnedLedger,
        botId: Long,
        target: DraftCategorySelection,
    ): TelegramReply {
        val draft = drafts.find(owner, botId, target.draftId) ?: return "Черновик недоступен. /draft".reply()
        if (draft.state == DraftState.CONFIRMED) {
            return "Уже записано: ${draft.entryId}. Повторного изменения баланса нет.".reply()
        }
        if (draft.state == DraftState.CANCELLED) return "Черновик уже отменён. Баланс не изменён.".reply()
        if ((!draft.recognizedByAi && !draft.requiresCategory) || draft.version != target.version) {
            return LedgerMessages.draft(draft, "Выбор категории устарел. Актуальные данные:")
        }
        val selected =
            categories.category(ownerId, target.categoryId)
                ?: return LedgerMessages.draft(draft, "Категория недоступна.")
        val proposal = checkNotNull(draft.proposal)
        if (selected.kind != proposal.direction.categoryKind()) {
            return LedgerMessages.draft(draft, "Тип категории не соответствует операции.")
        }
        val revised =
            proposal.copy(
                category = TransactionCategory(selected.id.toString(), selected.name, proposal.direction, selected.id),
            )
        return recordSelection(owner, botId, draft, revised)
    }

    @Suppress("ReturnCount") // Creating a category is allowed only for an active owned expense draft.
    private fun beginNewCategory(
        owner: OwnedLedger,
        botId: Long,
        target: DraftNewCategory,
    ): TelegramReply {
        val draft = drafts.find(owner, botId, target.draftId) ?: return "Черновик недоступен. /draft".reply()
        val selectionUnavailable = !draft.active || (!draft.recognizedByAi && !draft.requiresCategory)
        if (selectionUnavailable || draft.version != target.version) {
            return LedgerMessages.draft(draft, "Кнопка устарела. Актуальные данные:")
        }
        val proposal = checkNotNull(draft.proposal)
        if (!owner.canManageHousehold || proposal.direction != Direction.EXPENSE) {
            return LedgerMessages.draft(draft, "Новые категории может создавать только OWNER.")
        }
        val revised = draft.revise(proposal, DraftState.NEEDS_CATEGORY_NAME, "Введите название новой категории.")
        drafts.save(owner, botId, revised)
        return newCategoryPrompt(revised)
    }

    private fun recordSelection(
        owner: OwnedLedger,
        botId: Long,
        draft: TransactionDraft,
        proposal: RecordTransaction,
    ): TelegramReply =
        when (val checked = operations.check(owner, proposal, source(botId, draft.sourceInboxId))) {
            is ProposalCheck.Pending -> {
                val revised = draft.revise(proposal, checked.state, checked.reason)
                drafts.save(owner, botId, revised)
                LedgerMessages.draft(revised)
            }

            is ProposalCheck.Ready -> {
                val entry = operations.record(checked).entry
                val learned = categories.learnRule(owner, entry.id, entry.version, draft.categoryAlias)
                drafts.save(owner, botId, draft.confirm(entry.id, proposal))
                recorded(owner, entry, proposal.category.displayName, draft.categoryAlias, learned)
            }
        }

    private fun newCategoryPrompt(
        draft: TransactionDraft,
        error: String? = null,
    ): TelegramReply =
        TelegramReply(
            listOfNotNull(
                error,
                "Введите название новой категории одним сообщением. После ввода категория и расход будут сохранены.",
            ).joinToString("\n\n"),
            listOf(
                ReplyButton(
                    "◀️ Выбрать существующую",
                    DraftCategoryCallbacks.page(draft.id, draft.version, 0),
                    0,
                ),
                ReplyButton("❌ Отменить операцию", "dx:${draft.id}:${draft.version}", 1),
            ),
        )

    private fun recorded(
        owner: OwnedLedger,
        entry: LedgerEntry,
        category: String,
        alias: String?,
        learned: Boolean,
    ): TelegramReply {
        val reply = recordedReply(owner, entry, category)
        return if (alias != null && learned) {
            reply.copy(text = "${reply.text}\nПравило запомнено: «$alias» → «$category».")
        } else {
            reply
        }
    }

    private fun Direction.categoryKind(): CategoryKind =
        if (this == Direction.INCOME) CategoryKind.INCOME else CategoryKind.EXPENSE

    @Suppress("ReturnCount") // A specific target is required when more than one draft is active.
    private fun cancel(
        arguments: String,
        owner: OwnedLedger,
        botId: Long,
        active: List<TransactionDraft>,
    ): TelegramReply? {
        if (arguments.isEmpty()) {
            return when (active.size) {
                0 -> null
                1 -> cancel(owner, botId, active.single().id, active.single().version)
                else -> LedgerMessages.drafts(active, notice = "Выберите черновик кнопкой «Отменить».")
            }
        }
        val target =
            draftTarget(arguments)
                ?: return LedgerMessages.drafts(active, notice = "Не удалось распознать отмену.")
        return cancel(owner, botId, target.first, target.second)
    }

    @Suppress("ReturnCount") // Terminal and stale states must remain idempotent.
    private fun cancel(
        owner: OwnedLedger,
        botId: Long,
        id: UUID,
        version: Long,
    ): TelegramReply {
        val draft = drafts.find(owner, botId, id) ?: return "Черновик недоступен. /draft".reply()
        if (draft.state == DraftState.CONFIRMED) {
            return "Уже записано: ${draft.entryId}. Для отмены записи используйте /history.".reply()
        }
        if (draft.state == DraftState.CANCELLED) return "Черновик уже отменён. Баланс не изменён.".reply()
        if (draft.version != version) return LedgerMessages.draft(draft, "Отмена устарела. Актуальные данные:")
        drafts.save(owner, botId, draft.cancel())
        return "Черновик отменён. Баланс не изменён. Остальные черновики: /draft".reply()
    }

    @Suppress("ReturnCount") // The selected callback version is checked before prompting for clarification.
    private fun show(
        owner: OwnedLedger,
        botId: Long,
        id: UUID,
        version: Long,
    ): TelegramReply {
        val draft = drafts.find(owner, botId, id) ?: return "Черновик недоступен. /draft".reply()
        if (!draft.active) return "Черновик уже завершён. Откройте /draft или /history.".reply()
        if (draft.version != version) return LedgerMessages.draft(draft, "Кнопка устарела. Актуальные данные:")
        if (draft.state == DraftState.NEEDS_INPUT) {
            return LedgerMessages.draft(draft, "Отправьте одну исправленную операцию целиком.")
        }
        drafts.needsInput(owner, botId)?.let {
            return LedgerMessages.draft(it, "Сначала завершите текущее уточнение.")
        }
        val selected =
            draft.revise(
                draft.proposal,
                DraftState.NEEDS_INPUT,
                "Отправьте одну исправленную операцию целиком.",
            )
        drafts.save(owner, botId, selected)
        return LedgerMessages.draft(selected, "Выбран черновик для уточнения.")
    }

    @Suppress("ReturnCount") // Both UUID and optimistic-lock version are mandatory for a text fallback.
    private fun draftTarget(arguments: String): Pair<UUID, Long>? {
        val match = DRAFT_TARGET.matchEntire(arguments) ?: return null
        val version = match.groupValues[2].toLongOrNull() ?: return null
        return UUID.fromString(match.groupValues[1]) to version
    }

    private fun draftPage(arguments: String): Int? =
        if (arguments.isEmpty()) 1 else arguments.toIntOrNull()?.takeIf { it > 0 }

    private fun source(
        botId: Long,
        inboxId: Long,
    ): String = "telegram:$botId:inbox:$inboxId"

    companion object {
        val COMMANDS = setOf("/draft", "/confirm", "/cancel")
        val CALLBACKS = setOf("dc", "dx", "du", "dcp", "dcs", "dcn")
        private const val MANUAL_CATEGORY_REASON = "Не удалось определить категорию автоматически."
        private const val CATEGORY_PAGE_SIZE = 10
        private const val CATEGORY_COLUMNS = 2
        private const val UUID_PATTERN = "[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}"
        private val DRAFT_TARGET = Regex("($UUID_PATTERN) ([0-9]+)")
        private val DRAFT_CALLBACK = Regex("(dc|dx|du):($UUID_PATTERN):([0-9]+)")
    }
}
