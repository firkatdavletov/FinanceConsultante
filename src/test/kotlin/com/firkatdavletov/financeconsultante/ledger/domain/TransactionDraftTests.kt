package com.firkatdavletov.financeconsultante.ledger.domain

import com.firkatdavletov.financeconsultante.ledger.domain.MessageParseResult.RecordTransaction
import com.firkatdavletov.financeconsultante.shared.domain.Money
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.util.UUID

class TransactionDraftTests {
    private val draft = TransactionDraft(UUID.randomUUID(), 1, DraftState.NEEDS_INPUT, 0, null, "Уточните категорию")
    private val proposal =
        RecordTransaction(Money.of("10.01"), TransactionCategory.GROCERIES, LocalDate.parse("2026-09-01"))

    @Test
    fun `revision retains financial source and changes confirmation version`() {
        val revised = draft.revise(proposal, DraftState.READY, "Подтвердите")
        assertThat(revised.sourceInboxId).isEqualTo(draft.sourceInboxId)
        assertThat(revised.id).isEqualTo(draft.id)
        assertThat(revised.version).isEqualTo(draft.version + 1)
        assertThat(revised.proposal).isEqualTo(proposal)
    }

    @Test
    fun `unresolved draft cannot be confirmed`() {
        assertThatThrownBy { draft.confirm(UUID.randomUUID()) }.isInstanceOf(IllegalStateException::class.java)
    }

    @Test
    fun `confirmed draft cannot be confirmed revised or cancelled again`() {
        val entryId = UUID.randomUUID()
        val confirmed = draft.revise(proposal, DraftState.READY, "Подтвердите").confirm(entryId)
        assertThat(confirmed.active).isFalse()
        assertThat(confirmed.entryId).isEqualTo(entryId)
        assertThatThrownBy { confirmed.confirm(UUID.randomUUID()) }.isInstanceOf(IllegalStateException::class.java)
        assertThatThrownBy {
            confirmed.revise(proposal, DraftState.READY, "Изменение")
        }.isInstanceOf(IllegalStateException::class.java)
        assertThatThrownBy { confirmed.cancel() }.isInstanceOf(IllegalStateException::class.java)
    }

    @Test
    fun `cancelled draft cannot become a new financial operation`() {
        val cancelled = draft.cancel()
        assertThat(cancelled.active).isFalse()
        assertThatThrownBy {
            cancelled.revise(proposal, DraftState.READY, "Изменение")
        }.isInstanceOf(IllegalStateException::class.java)
    }

    @Test
    fun `ready state requires a complete proposal and confirmed state requires entry`() {
        assertThatThrownBy { draft.copy(state = DraftState.READY) }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy {
            draft.copy(state = DraftState.CONFIRMED, proposal = proposal)
        }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy {
            draft.copy(
                entryId = UUID.randomUUID(),
            )
        }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `category alias belongs only to an AI recognized draft`() {
        assertThatThrownBy {
            draft.copy(categoryAlias = "автобус")
        }.isInstanceOf(IllegalArgumentException::class.java)

        val recognized =
            draft.copy(
                state = DraftState.READY,
                proposal = proposal,
                recognizedByAi = true,
                categoryAlias = "автобус",
            )
        assertThat(recognized.revise(proposal, DraftState.READY, "Подтвердите").categoryAlias)
            .isEqualTo("автобус")
    }
}
