package com.firkatdavletov.financeconsultante.ledger.domain

import com.firkatdavletov.financeconsultante.ledger.domain.MessageParseResult.RecordTransaction
import com.firkatdavletov.financeconsultante.shared.domain.Currency
import java.util.UUID

enum class DraftState {
    NEEDS_INPUT,
    READY,
    WAITING_RATE,
    CONFIRMED,
    CANCELLED,
}

data class TransactionDraft(
    val id: UUID,
    val sourceInboxId: Long,
    val state: DraftState,
    val version: Long,
    val proposal: RecordTransaction?,
    val reason: String,
    val entryId: UUID? = null,
    val recognizedByAi: Boolean = false,
    val categoryAlias: String? = null,
) {
    val active: Boolean get() = state in setOf(DraftState.NEEDS_INPUT, DraftState.READY, DraftState.WAITING_RATE)

    init {
        require(sourceInboxId > 0 && version >= 0)
        require(reason.isNotBlank() && reason.length <= MAX_REASON_LENGTH)
        require(state !in setOf(DraftState.READY, DraftState.WAITING_RATE, DraftState.CONFIRMED) || proposal != null)
        require((state == DraftState.CONFIRMED) == (entryId != null))
        require(state != DraftState.WAITING_RATE || proposal?.amount?.currency == Currency.RUB)
        require(!recognizedByAi || proposal != null)
        require(categoryAlias == null || CategoryAliasNormalizer.normalize(categoryAlias) == categoryAlias)
        require(categoryAlias == null || recognizedByAi)
    }

    fun revise(
        proposal: RecordTransaction?,
        state: DraftState,
        reason: String,
    ): TransactionDraft {
        check(active && state in setOf(DraftState.NEEDS_INPUT, DraftState.READY, DraftState.WAITING_RATE))
        return copy(proposal = proposal, state = state, reason = reason, version = version + 1)
    }

    fun confirm(entryId: UUID): TransactionDraft {
        check(state == DraftState.READY || state == DraftState.WAITING_RATE)
        return copy(state = DraftState.CONFIRMED, entryId = entryId, version = version + 1)
    }

    fun cancel(): TransactionDraft {
        check(active)
        return copy(state = DraftState.CANCELLED, version = version + 1)
    }

    companion object {
        private const val MAX_REASON_LENGTH = 512
    }
}
