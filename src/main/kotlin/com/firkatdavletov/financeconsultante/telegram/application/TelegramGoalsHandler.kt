package com.firkatdavletov.financeconsultante.telegram.application

import com.firkatdavletov.financeconsultante.goals.application.GoalChange
import com.firkatdavletov.financeconsultante.goals.application.GoalSpendReceipt
import com.firkatdavletov.financeconsultante.goals.application.SavingsGoalService
import com.firkatdavletov.financeconsultante.goals.application.SavingsOverview
import com.firkatdavletov.financeconsultante.goals.domain.ContributionPlanStatus
import com.firkatdavletov.financeconsultante.goals.domain.GoalContributionPlan
import com.firkatdavletov.financeconsultante.goals.domain.SavingsGoal
import com.firkatdavletov.financeconsultante.goals.domain.SavingsGoalState
import com.firkatdavletov.financeconsultante.goals.domain.SavingsGoalView
import com.firkatdavletov.financeconsultante.ledger.application.OwnedLedger
import com.firkatdavletov.financeconsultante.ledger.domain.MessageParseResult.Command
import com.firkatdavletov.financeconsultante.shared.domain.Currency
import com.firkatdavletov.financeconsultante.shared.domain.Money
import com.firkatdavletov.financeconsultante.telegram.domain.ACCOUNTING_ZONE
import java.time.Clock
import java.time.DateTimeException
import java.time.LocalDate
import java.util.UUID

@Suppress(
    "ReturnCount",
    "CyclomaticComplexMethod",
    "MagicNumber",
    "LongMethod",
    "TooManyFunctions",
) // Fixed command forms stop malformed input before goal or ledger changes.
class TelegramGoalsHandler(
    private val service: SavingsGoalService,
    private val clock: Clock,
) {
    fun command(
        message: InboxMessage,
        command: Command,
        owner: OwnedLedger,
        botId: Long,
    ): TelegramReply? {
        if (command.name !in COMMANDS) return null
        val today = messageDate(message) ?: return "Дата сообщения неизвестна. Повторите команду.".reply()
        val parts = command.arguments.split(' ').filter(String::isNotEmpty)
        return when {
            parts.isEmpty() -> {
                renderOverview(service.overview(owner, today))
            }

            parts.size >= 5 && parts[0] == "add" -> {
                val amount = amount(parts[1]) ?: return AMOUNT_HELP.reply()
                val deadline = date(parts[2]) ?: return DATE_HELP.reply()
                val priority = parts[3].toIntOrNull() ?: return PRIORITY_HELP.reply()
                val name = parts.drop(4).joinToString(" ")
                renderGoal(service.create(owner, name, amount, deadline, priority, today, source(message, botId)))
            }

            parts.size == 4 && parts[0] == "reserve" -> {
                val target = versioned(parts) ?: return GOALS_HELP.reply()
                val amount = amount(parts[3]) ?: return AMOUNT_HELP.reply()
                renderGoal(service.reserve(owner, target.first, target.second, amount, source(message, botId)))
            }

            parts.size == 4 && parts[0] == "release" -> {
                val target = versioned(parts) ?: return GOALS_HELP.reply()
                val amount = amount(parts[3]) ?: return AMOUNT_HELP.reply()
                renderGoal(service.release(owner, target.first, target.second, amount, source(message, botId)))
            }

            parts.size == 5 && parts[0] == "plan" -> {
                val target = versioned(parts) ?: return GOALS_HELP.reply()
                val dueOn = date(parts[3]) ?: return DATE_HELP.reply()
                val amount = amount(parts[4]) ?: return AMOUNT_HELP.reply()
                renderPlan(
                    service.planContribution(
                        owner,
                        target.first,
                        target.second,
                        amount,
                        dueOn,
                        today,
                        source(message, botId),
                    ),
                )
            }

            parts.size == 3 && parts[0] == "plan_cancel" -> {
                val target = versioned(parts) ?: return GOALS_HELP.reply()
                renderPlan(service.cancelContribution(owner, target.first, target.second, source(message, botId)))
            }

            parts.size in 5..6 && parts[0] == "spend" -> {
                val target = versioned(parts) ?: return GOALS_HELP.reply()
                val amount = amount(parts[3]) ?: return AMOUNT_HELP.reply()
                val category = uuid(parts[4]) ?: return CATEGORY_HELP.reply()
                val spentOn = if (parts.size == 6) date(parts[5]) ?: return DATE_HELP.reply() else today
                renderSpend(
                    service.spend(
                        owner,
                        target.first,
                        target.second,
                        amount,
                        category,
                        spentOn,
                        source(message, botId),
                    ),
                )
            }

            else -> {
                GOALS_HELP.reply()
            }
        }
    }

    private fun renderOverview(overview: SavingsOverview): TelegramReply =
        buildString {
            append("Цели накоплений. Уже выделено G: ${money(overview.totalAllocated)}. ")
            append("Эти деньги остаются в учётном балансе B, но уменьшают доступную сумму.\n")
            if (overview.goals.isEmpty()) {
                append("Целей пока нет.\n")
            } else {
                overview.goals.forEach { append("\n${goalLine(it, overview.asOf)}\n") }
            }
            append("\nБудущие планы взносов не входят в G до команды reserve.\n")
            append(GOALS_HELP)
        }.reply()

    private fun renderGoal(result: GoalChange<SavingsGoal>): TelegramReply =
        when (result) {
            is GoalChange.Rejected -> {
                result.message.reply()
            }

            is GoalChange.Applied -> {
                val goal = result.value
                (
                    "Цель сохранена: ${goal.name}; ID ${goal.id}, версия ${goal.version}. " +
                        "Выделено ${money(goal.allocatedAmount)} из ${money(goal.targetAmount)}. " +
                        "Баланс и статистика расходов не изменены."
                ).reply()
            }
        }

    private fun renderPlan(result: GoalChange<GoalContributionPlan>): TelegramReply =
        when (result) {
            is GoalChange.Rejected -> {
                result.message.reply()
            }

            is GoalChange.Applied -> {
                val plan = result.value
                val state = if (plan.status == ContributionPlanStatus.SCHEDULED) "запланирован" else "отменён"
                (
                    "Будущий взнос $state: ${plan.id} v${plan.version}, ${plan.dueOn}, ${money(plan.amount)}. " +
                        "Он не входит в B или G."
                ).reply()
            }
        }

    private fun renderSpend(result: GoalChange<GoalSpendReceipt>): TelegramReply =
        when (result) {
            is GoalChange.Rejected -> {
                result.message.reply()
            }

            is GoalChange.Applied -> {
                val receipt = result.value
                TelegramReply(
                    "Расход из цели записан: ${money(receipt.entry.amountKzt)}; операция ${receipt.entry.id}. " +
                        "В цели «${receipt.goal.name}» осталось выделено ${money(receipt.goal.allocatedAmount)}. " +
                        "B и G уменьшены атомарно без двойного вычета.",
                    EntryButtons.actions(receipt.entry),
                )
            }
        }

    private fun goalLine(
        view: SavingsGoalView,
        asOf: LocalDate,
    ): String {
        val goal = view.goal
        val state =
            when (goal.state(asOf)) {
                SavingsGoalState.SAVING -> "копим"
                SavingsGoalState.ACHIEVED -> "достигнута"
                SavingsGoalState.OVERDUE -> "срок прошёл, не хватает ${money(goal.remainingAmount)}"
            }
        val pace =
            goal.requiredPace(asOf)?.let {
                " Требуемый темп: ${money(it.amountPerDay)} в день, дней с сегодняшним: ${it.daysRemaining}."
            } ?: ""
        val plans =
            view.plannedContributions
                .filter { it.status == ContributionPlanStatus.SCHEDULED }
                .joinToString("; ") { "${it.id} v${it.version}: ${it.dueOn} ${money(it.amount)}" }
                .ifEmpty { "нет" }
        return "${goal.id} v${goal.version}, приоритет ${goal.priority}: «${goal.name}», $state. " +
            "Выделено ${money(goal.allocatedAmount)} из ${money(goal.targetAmount)}, срок ${goal.deadline}." +
            pace + " Будущие планы: $plans."
    }

    private fun versioned(parts: List<String>): Pair<UUID, Long>? {
        if (parts.size < 3) return null
        val id = uuid(parts[1]) ?: return null
        val version = parts[2].toLongOrNull()?.takeIf { it >= 0 } ?: return null
        return id to version
    }

    @Suppress("SwallowedException")
    private fun amount(text: String): Money? =
        try {
            if (!AMOUNT.matches(text)) null else Money.of(text.replace(',', '.'), Currency.KZT).requirePositive()
        } catch (_: IllegalArgumentException) {
            null
        }

    @Suppress("SwallowedException")
    private fun date(text: String): LocalDate? =
        try {
            if (!DATE.matches(text)) null else LocalDate.parse(text)
        } catch (_: DateTimeException) {
            null
        }

    private fun messageDate(message: InboxMessage): LocalDate? {
        val sentAt = message.sentAt ?: return null
        if (sentAt > clock.instant()) return null
        return sentAt.atZone(ACCOUNTING_ZONE).toLocalDate()
    }

    private fun uuid(value: String): UUID? = if (UUID_PATTERN.matches(value)) UUID.fromString(value) else null

    private fun source(
        message: InboxMessage,
        botId: Long,
    ): String = "telegram:$botId:inbox:${message.id}"

    private fun money(value: Money): String = "${value.amount.toPlainString()} ${value.currency}"

    companion object {
        val COMMANDS = setOf("/goals")
        private const val AMOUNT_HELP = "Нужна положительная сумма KZT без разделителей тысяч, до двух знаков."
        private const val DATE_HELP = "Укажите действительную дату ГГГГ-ММ-ДД."
        private const val PRIORITY_HELP = "Приоритет: целое число от 1 (самый высокий) до 5."
        private const val CATEGORY_HELP = "Нужен UUID активной категории расхода из /categories."
        private const val GOALS_HELP =
            "Добавить: /goals add <сумма> <срок ГГГГ-ММ-ДД> <приоритет 1..5> <название>. " +
                "Выделить: /goals reserve <ID цели> <версия> <сумма>. " +
                "Вернуть в доступные: /goals release <ID цели> <версия> <сумма>. " +
                "План без резервирования: /goals plan <ID цели> <версия> <дата> <сумма>; " +
                "отмена: /goals plan_cancel <ID плана> <версия>. " +
                "Расход: /goals spend <ID цели> <версия> <сумма> <ID категории> [дата]."
        private val AMOUNT = Regex("[0-9]+(?:[.,][0-9]{1,2})?")
        private val DATE = Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}")
        private val UUID_PATTERN = Regex("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}")
    }
}
