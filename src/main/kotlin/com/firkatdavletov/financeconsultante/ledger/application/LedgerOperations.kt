package com.firkatdavletov.financeconsultante.ledger.application

import com.firkatdavletov.financeconsultante.exchange.application.ExchangeRateJobRepository
import com.firkatdavletov.financeconsultante.ledger.domain.DraftState
import com.firkatdavletov.financeconsultante.ledger.domain.MessageParseResult.RecordTransaction
import com.firkatdavletov.financeconsultante.shared.domain.Currency
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.LocalTime
import java.time.ZoneId

sealed interface ProposalCheck {
    data class Ready(
        val command: RecordEntry,
    ) : ProposalCheck

    data class Pending(
        val state: DraftState,
        val reason: String,
    ) : ProposalCheck
}

@Service
@Transactional(propagation = Propagation.MANDATORY)
class LedgerOperations(
    private val repository: LedgerInteractionRepository,
    private val accounting: AccountingService,
    private val rates: ExchangeRateRepository,
    private val rateJobs: ExchangeRateJobRepository,
    private val clock: Clock,
) {
    fun owned(ownerId: Long): OwnedLedger? = repository.owned(ownerId)

    @Suppress("ReturnCount") // Independent validation failures stop before any ledger write.
    fun check(
        owner: OwnedLedger,
        proposal: RecordTransaction,
        source: String,
    ): ProposalCheck {
        val occurredAt = proposal.occurredOn.atStartOfDay(ZONE).toInstant()
        if (occurredAt < owner.startedAt) {
            return ProposalCheck.Pending(
                DraftState.NEEDS_INPUT,
                "Дата раньше начала учёта ${owner.startedAt.atZone(ZONE).toLocalDate()}. " +
                    "Если операция уже входит в начальный остаток, отмените черновик: /cancel. " +
                    "При ошибке даты отправьте операцию целиком с верной датой. " +
                    "Изменить начало можно через /opening только до первой проводки.",
            )
        }
        if (occurredAt > clock.instant()) {
            return ProposalCheck.Pending(DraftState.NEEDS_INPUT, "Будущая операция не является фактом. Уточните дату.")
        }
        if (proposal.amount.currency == Currency.RUB) {
            val rate = rates.find(proposal.occurredOn)
            if (rate == null) {
                rateJobs.enqueueDate(proposal.occurredOn, RATE_FETCH_TIME, ZONE, clock.instant())
                return ProposalCheck.Pending(
                    DraftState.WAITING_RATE,
                    "Нет курса RUB/KZT за ${proposal.occurredOn}; запрос сохранён. " +
                        "После загрузки курса повторите подтверждение из /draft. " +
                        "Загрузка требует включённого Currencylayer; пока баланс не изменён.",
                )
            }
            if (!validConversion { rate.convert(proposal.amount).requirePositive() }) {
                return ProposalCheck.Pending(
                    DraftState.NEEDS_INPUT,
                    "Оценка в KZT вне допустимого диапазона или меньше 0,01. Уточните сумму.",
                )
            }
        }
        val category = repository.resolveCategory(owner.householdId, proposal.category)
        if (category.archived || category.kind != proposal.direction.categoryKind) {
            return ProposalCheck.Pending(DraftState.NEEDS_INPUT, "Категория архивирована. Укажите доступную категорию.")
        }
        return ProposalCheck.Ready(
            RecordEntry(
                owner.householdId,
                owner.memberId,
                category.id,
                proposal.direction,
                proposal.amount,
                occurredAt,
                source,
            ),
        )
    }

    fun record(ready: ProposalCheck.Ready): RecordResult = accounting.record(ready.command)

    fun balance(owner: OwnedLedger) = accounting.currentBalance(owner.householdId, owner.memberId)

    fun history(
        owner: OwnedLedger,
        before: Long,
    ): List<HistoryItem> = repository.history(owner.householdId, before, HISTORY_PAGE_SIZE + 1)

    @Suppress("SwallowedException")
    private fun validConversion(convert: () -> Unit): Boolean =
        try {
            convert()
            true
        } catch (_: IllegalArgumentException) {
            false
        }

    companion object {
        val ZONE: ZoneId = ZoneId.of("Asia/Almaty")
        const val HISTORY_PAGE_SIZE = 10
        private val RATE_FETCH_TIME = LocalTime.of(7, 0)
    }
}
