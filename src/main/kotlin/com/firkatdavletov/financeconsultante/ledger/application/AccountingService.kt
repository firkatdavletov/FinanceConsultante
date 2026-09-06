package com.firkatdavletov.financeconsultante.ledger.application

import com.firkatdavletov.financeconsultante.ledger.domain.Account
import com.firkatdavletov.financeconsultante.ledger.domain.Balance
import com.firkatdavletov.financeconsultante.ledger.domain.BudgetSetup
import com.firkatdavletov.financeconsultante.ledger.domain.Direction
import com.firkatdavletov.financeconsultante.ledger.domain.LedgerEntry
import com.firkatdavletov.financeconsultante.shared.domain.Currency
import com.firkatdavletov.financeconsultante.shared.domain.Money
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Isolation
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.UUID

data class RecordEntry(
    val householdId: UUID,
    val memberId: UUID,
    val categoryId: UUID,
    val direction: Direction,
    val amount: Money,
    val occurredAt: Instant,
    val sourceKey: String,
)

data class RecordResult(
    val entry: LedgerEntry,
    val recorded: Boolean,
)

class MissingExchangeRate(
    val date: LocalDate,
) : IllegalStateException("Daily RUB/KZT rate is missing")

@Service
class AccountingService(
    private val budgets: BudgetRepository,
    private val ledger: LedgerRepository,
    private val rates: ExchangeRateRepository,
    private val clock: Clock,
) {
    @Transactional
    fun openBudget(setup: BudgetSetup) {
        require(setup.account.accountingStartedAt <= clock.instant()) { "Accounting cannot start in the future" }
        budgets.create(setup)
    }

    @Transactional
    fun record(command: RecordEntry): RecordResult {
        val account = ownedAccount(command.householdId, command.memberId)
        ledger.findBySource(command.householdId, command.sourceKey)?.let { return RecordResult(it, false) }
        command.amount.requirePositive()
        val now = clock.instant().truncatedTo(ChronoUnit.MICROS)
        require(command.occurredAt >= account.accountingStartedAt && command.occurredAt <= now) {
            "Outside accounting interval"
        }
        val category =
            requireNotNull(budgets.findCategory(command.householdId, command.categoryId)) {
                "Category is unavailable"
            }
        require(!category.archived && category.kind == command.direction.categoryKind) { "Category is unavailable" }
        val date = command.occurredAt.atZone(ZoneId.of("Asia/Almaty")).toLocalDate()
        val rate =
            when (command.amount.currency) {
                Currency.KZT -> null
                Currency.RUB -> rates.find(date) ?: throw MissingExchangeRate(date)
            }
        val entry =
            LedgerEntry(
                id = UUID.randomUUID(),
                householdId = command.householdId,
                accountId = account.id,
                categoryId = command.categoryId,
                actorId = command.memberId,
                direction = command.direction,
                originalAmount = command.amount,
                amountKzt = rate?.convert(command.amount) ?: command.amount,
                accountingStartedAt = account.accountingStartedAt,
                occurredAt = command.occurredAt,
                createdAt = now,
                sourceKey = command.sourceKey,
                exchangeRate = rate,
            )
        return if (ledger.insert(entry)) {
            RecordResult(entry, true)
        } else {
            RecordResult(checkNotNull(ledger.findBySource(command.householdId, command.sourceKey)), false)
        }
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    fun balance(
        householdId: UUID,
        memberId: UUID,
        untilExclusive: Instant,
    ): Balance {
        val account = ownedAccount(householdId, memberId)
        require(untilExclusive <= clock.instant()) { "Balance cutoff is in the future" }
        return Balance.calculate(account, ledger.findBefore(householdId, untilExclusive), untilExclusive)
    }

    private fun ownedAccount(
        householdId: UUID,
        memberId: UUID,
    ): Account = requireNotNull(budgets.findOwnedAccount(householdId, memberId)) { "Budget access denied" }
}
