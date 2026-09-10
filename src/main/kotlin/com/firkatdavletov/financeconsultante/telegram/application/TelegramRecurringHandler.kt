package com.firkatdavletov.financeconsultante.telegram.application

import com.firkatdavletov.financeconsultante.ledger.application.OwnedLedger
import com.firkatdavletov.financeconsultante.ledger.domain.MessageParseResult.Command
import com.firkatdavletov.financeconsultante.recurring.application.IncomeReceipt
import com.firkatdavletov.financeconsultante.recurring.application.PaymentReceipt
import com.firkatdavletov.financeconsultante.recurring.application.PlanChange
import com.firkatdavletov.financeconsultante.recurring.application.RecurringOverview
import com.firkatdavletov.financeconsultante.recurring.application.RecurringPlanService
import com.firkatdavletov.financeconsultante.recurring.application.RecurringRuleView
import com.firkatdavletov.financeconsultante.recurring.domain.ExpectedIncomeStatus
import com.firkatdavletov.financeconsultante.recurring.domain.ExpectedIncomeView
import com.firkatdavletov.financeconsultante.recurring.domain.OccurrenceStatus
import com.firkatdavletov.financeconsultante.recurring.domain.RecurrenceFrequency
import com.firkatdavletov.financeconsultante.recurring.domain.RecurringOccurrenceView
import com.firkatdavletov.financeconsultante.shared.domain.Currency
import com.firkatdavletov.financeconsultante.shared.domain.Money
import java.time.Clock
import java.time.DateTimeException
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

@Suppress(
    "ReturnCount",
    "TooManyFunctions",
    "CyclomaticComplexMethod",
    "MagicNumber",
) // Fixed command arities and guards prevent unintended plan or ledger mutations.
class TelegramRecurringHandler(
    private val service: RecurringPlanService,
    private val clock: Clock,
) {
    fun command(
        message: InboxMessage,
        command: Command,
        owner: OwnedLedger,
        botId: Long,
    ): TelegramReply? {
        if (command.name !in COMMANDS) return null
        val today =
            messageDate(message)
                ?: return (
                    "Дата сообщения неизвестна или ещё не наступила. " +
                        "Повторите команду."
                ).reply()
        val parts = command.arguments.split(' ').filter(String::isNotEmpty)
        return when (command.name) {
            "/recurring" -> recurring(parts, owner, today, message, botId)
            else -> expected(parts, owner, today, message, botId)
        }
    }

    private fun recurring(
        parts: List<String>,
        owner: OwnedLedger,
        today: LocalDate,
        message: InboxMessage,
        botId: Long,
    ): TelegramReply =
        when {
            parts.isEmpty() -> {
                recurringOverview(service.overview(owner, today), today)
            }

            parts.size == 5 && parts[0] == "add" && parts[1] == "monthly" -> {
                val day = parts[2].toIntOrNull()?.takeIf { it in 1..31 } ?: return RECURRING_HELP.reply()
                val amount = amount(parts[3]) ?: return AMOUNT_HELP.reply()
                val category = uuid(parts[4]) ?: return CATEGORY_HELP.reply()
                renderRule(service.createRule(owner, RecurrenceFrequency.MONTHLY, day, amount, category, today))
            }

            parts.size == 5 && parts[0] == "add" && parts[1] == "weekly" -> {
                val day = weekday(parts[2]) ?: return WEEKDAY_HELP.reply()
                val amount = amount(parts[3]) ?: return AMOUNT_HELP.reply()
                val category = uuid(parts[4]) ?: return CATEGORY_HELP.reply()
                renderRule(service.createRule(owner, RecurrenceFrequency.WEEKLY, day.value, amount, category, today))
            }

            parts.size == 4 && parts[0] == "amount" -> {
                val target = versioned(parts) ?: return RECURRING_HELP.reply()
                val amount = amount(parts[3]) ?: return AMOUNT_HELP.reply()
                renderRule(service.updateRuleAmount(owner, target.first, target.second, amount, today))
            }

            parts.size == 3 && parts[0] == "deactivate" -> {
                val target = versioned(parts) ?: return RECURRING_HELP.reply()
                renderRule(service.deactivateRule(owner, target.first, target.second, today))
            }

            parts.size == 3 && parts[0] == "cancel" -> {
                val target = versioned(parts) ?: return RECURRING_HELP.reply()
                renderOccurrence(service.cancelOccurrence(owner, target.first, target.second, today), today)
            }

            parts.size in 4..5 && parts[0] == "pay" -> {
                val target = versioned(parts) ?: return RECURRING_HELP.reply()
                val paid = amount(parts[3]) ?: return AMOUNT_HELP.reply()
                val paidOn = if (parts.size == 5) date(parts[4]) ?: return DATE_HELP.reply() else today
                renderPayment(
                    service.pay(owner, target.first, target.second, paid, paidOn, source(message, botId)),
                )
            }

            else -> {
                RECURRING_HELP.reply()
            }
        }

    private fun expected(
        parts: List<String>,
        owner: OwnedLedger,
        today: LocalDate,
        message: InboxMessage,
        botId: Long,
    ): TelegramReply =
        when {
            parts.isEmpty() -> {
                expectedOverview(service.expectedIncomes(owner))
            }

            parts.size == 4 && parts[0] == "add" -> {
                val dueOn = date(parts[1]) ?: return DATE_HELP.reply()
                val amount = amount(parts[2]) ?: return AMOUNT_HELP.reply()
                val category = uuid(parts[3]) ?: return CATEGORY_HELP.reply()
                renderExpected(service.createExpectedIncome(owner, amount, dueOn, category, today))
            }

            parts.size == 3 && parts[0] == "cancel" -> {
                val target = versioned(parts) ?: return EXPECTED_HELP.reply()
                renderExpected(service.cancelExpectedIncome(owner, target.first, target.second, today))
            }

            parts.size in 4..5 && parts[0] == "receive" -> {
                val target = versioned(parts) ?: return EXPECTED_HELP.reply()
                val received = amount(parts[3]) ?: return AMOUNT_HELP.reply()
                val receivedOn = if (parts.size == 5) date(parts[4]) ?: return DATE_HELP.reply() else today
                renderIncome(
                    service.receiveExpectedIncome(
                        owner,
                        target.first,
                        target.second,
                        received,
                        receivedOn,
                        source(message, botId),
                    ),
                )
            }

            parts.size == 4 && parts[0] == "match" -> {
                val target = versioned(parts) ?: return EXPECTED_HELP.reply()
                val entryId =
                    uuid(parts[3])
                        ?: return "Нужен UUID проведённого дохода из /history.".reply()
                renderExpected(service.matchExpectedIncome(owner, target.first, target.second, entryId))
            }

            else -> {
                EXPECTED_HELP.reply()
            }
        }

    private fun recurringOverview(
        overview: RecurringOverview,
        today: LocalDate,
    ): TelegramReply {
        val rules =
            if (overview.rules.isEmpty()) {
                "Правил пока нет."
            } else {
                overview.rules.joinToString("\n") { ruleLine(it) }
            }
        val occurrences =
            if (overview.occurrences.isEmpty()) {
                "Обязательств пока нет."
            } else {
                overview.occurrences.joinToString("\n") { occurrenceLine(it, today) }
            }
        return buildString {
            appendLine("Постоянные платежи:")
            appendLine(rules)
            appendLine("Ближайшие обязательства:")
            appendLine(occurrences)
            append(RECURRING_HELP)
        }.reply()
    }

    private fun expectedOverview(items: List<ExpectedIncomeView>): TelegramReply =
        (
            "Ожидаемые доходы:\n" +
                if (items.isEmpty()) {
                    "Планов пока нет.\n$EXPECTED_HELP"
                } else {
                    items.joinToString("\n") {
                        expectedLine(it)
                    } + "\n$EXPECTED_HELP"
                }
        ).reply()

    private fun renderRule(result: PlanChange<RecurringRuleView>): TelegramReply =
        when (result) {
            is PlanChange.Rejected -> {
                result.message.reply()
            }

            is PlanChange.Applied -> {
                (
                    "Правило сохранено: ${ruleLine(result.value)}\n" +
                        if (
                            result.value.rule.frequency == RecurrenceFrequency.MONTHLY &&
                            result.value.rule.scheduleValue >= SHORT_MONTH_DAY
                        ) {
                            "В коротком месяце срок переносится " +
                                "на его последний день. "
                        } else {
                            ""
                        } +
                        "Конкретные обязательства созданы " +
                        "без списания денег."
                ).reply()
            }
        }

    private fun renderOccurrence(
        result: PlanChange<RecurringOccurrenceView>,
        today: LocalDate,
    ): TelegramReply =
        when (result) {
            is PlanChange.Rejected -> {
                result.message.reply()
            }

            is PlanChange.Applied -> {
                (
                    "Обязательство обновлено: " +
                        occurrenceLine(result.value, today)
                ).reply()
            }
        }

    private fun renderExpected(result: PlanChange<ExpectedIncomeView>): TelegramReply =
        when (result) {
            is PlanChange.Rejected -> {
                result.message.reply()
            }

            is PlanChange.Applied -> {
                (
                    "План дохода сохранён: ${expectedLine(result.value)}\n" +
                        "Баланс не изменён."
                ).reply()
            }
        }

    private fun renderPayment(result: PlanChange<PaymentReceipt>): TelegramReply =
        when (result) {
            is PlanChange.Rejected -> {
                result.message.reply()
            }

            is PlanChange.Applied -> {
                val receipt = result.value
                TelegramReply(
                    "Оплата записана в ledger: " +
                        "${receipt.entry.originalAmount.amount.toPlainString()} KZT, " +
                        "операция ${receipt.entry.id}. Осталось по обязательству: " +
                        "${receipt.occurrence.remainingAmount.amount.toPlainString()} KZT. " +
                        "Версия обязательства ${receipt.occurrence.occurrence.version}.",
                    EntryButtons.actions(receipt.entry),
                )
            }
        }

    private fun renderIncome(result: PlanChange<IncomeReceipt>): TelegramReply =
        when (result) {
            is PlanChange.Rejected -> {
                result.message.reply()
            }

            is PlanChange.Applied -> {
                val receipt = result.value
                TelegramReply(
                    "Доход записан и сопоставлен с планом: " +
                        "${receipt.entry.originalAmount.amount.toPlainString()} KZT, " +
                        "операция ${receipt.entry.id}. " +
                        "Ожидание больше не входит в прогноз.",
                    EntryButtons.actions(receipt.entry),
                )
            }
        }

    private fun ruleLine(view: RecurringRuleView): String {
        val rule = view.rule
        val schedule =
            when (rule.frequency) {
                RecurrenceFrequency.MONTHLY -> {
                    "ежемесячно ${rule.scheduleValue}-го" +
                        if (rule.scheduleValue >= SHORT_MONTH_DAY) {
                            " (короткий месяц: последний день)"
                        } else {
                            ""
                        }
                }

                RecurrenceFrequency.WEEKLY -> {
                    "еженедельно, ${WEEKDAYS[rule.scheduleValue - 1]}"
                }
            }
        return "${rule.id} v${rule.version}: $schedule, ${rule.amount.amount.toPlainString()} KZT, " +
            "${view.categoryName}, ${if (rule.active) "активно" else "отключено"}."
    }

    private fun occurrenceLine(
        view: RecurringOccurrenceView,
        today: LocalDate,
    ): String {
        val occurrence = view.occurrence
        val state =
            when {
                occurrence.status == OccurrenceStatus.CANCELLED -> {
                    "отменено"
                }

                view.paidInFull -> {
                    "оплачено ${view.paidAmount.amount.toPlainString()} KZT"
                }

                view.overdue(today) -> {
                    "просрочено, осталось " +
                        "${view.remainingAmount.amount.toPlainString()} KZT"
                }

                else -> {
                    "осталось ${view.remainingAmount.amount.toPlainString()} KZT"
                }
            }
        return "${occurrence.id} v${occurrence.version}: ${occurrence.dueOn}, ${view.categoryName}, " +
            "план ${occurrence.plannedAmount.amount.toPlainString()} KZT, $state."
    }

    private fun expectedLine(view: ExpectedIncomeView): String {
        val expected = view.expectedIncome
        val state =
            when {
                expected.status == ExpectedIncomeStatus.CANCELLED -> "отменено"
                view.fulfilled -> "получено ${view.receivedAmount.amount.toPlainString()} KZT"
                else -> "ожидается; в баланс не включено"
            }
        return "${expected.id} v${expected.version}: ${expected.dueOn}, ${view.categoryName}, " +
            "${expected.plannedAmount.amount.toPlainString()} KZT, $state."
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

    private fun weekday(value: String): DayOfWeek? = WEEKDAY_INPUT[value]

    private fun uuid(value: String): UUID? = if (UUID_PATTERN.matches(value)) UUID.fromString(value) else null

    private fun source(
        message: InboxMessage,
        botId: Long,
    ): String = "telegram:$botId:inbox:${message.id}"

    companion object {
        val COMMANDS = setOf("/recurring", "/expected")
        private const val SHORT_MONTH_DAY = 29
        private val ACCOUNTING_ZONE = ZoneId.of("Asia/Almaty")
        private const val AMOUNT_HELP =
            "Нужна положительная сумма KZT без разделителей тысяч, " +
                "до двух дробных знаков."
        private const val DATE_HELP = "Укажите действительную дату ГГГГ-ММ-ДД."
        private const val CATEGORY_HELP = "Нужен UUID активной категории из /categories."
        private const val WEEKDAY_HELP = "День недели: пн, вт, ср, чт, пт, сб или вс."
        private const val RECURRING_HELP =
            "Добавить: /recurring add monthly <1..31> <сумма KZT> <ID категории> или " +
                "/recurring add weekly <пн..вс> <сумма KZT> <ID категории>. " +
                "Изменить сумму: /recurring amount <ID> <версия> <сумма>. " +
                "Отключить правило: /recurring deactivate <ID> <версия>. " +
                "Оплатить: /recurring pay <ID обязательства> <версия> " +
                "<сумма> [ГГГГ-ММ-ДД]. " +
                "Отменить будущее: /recurring cancel <ID обязательства> <версия>."
        private const val EXPECTED_HELP =
            "Добавить: /expected add <ГГГГ-ММ-ДД> <сумма KZT> " +
                "<ID категории дохода>. " +
                "Получить: /expected receive <ID> <версия> " +
                "<фактическая сумма> [ГГГГ-ММ-ДД]. " +
                "Связать уже записанный доход: /expected match <ID> " +
                "<версия> <ID проводки>. " +
                "Отменить будущий план: /expected cancel <ID> <версия>."
        private val AMOUNT = Regex("[0-9]+(?:[.,][0-9]{1,2})?")
        private val DATE = Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}")
        private val UUID_PATTERN = Regex("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}")
        private val WEEKDAYS =
            listOf(
                "понедельник",
                "вторник",
                "среда",
                "четверг",
                "пятница",
                "суббота",
                "воскресенье",
            )
        private val WEEKDAY_INPUT =
            mapOf(
                "пн" to DayOfWeek.MONDAY,
                "вт" to DayOfWeek.TUESDAY,
                "ср" to DayOfWeek.WEDNESDAY,
                "чт" to DayOfWeek.THURSDAY,
                "пт" to DayOfWeek.FRIDAY,
                "сб" to DayOfWeek.SATURDAY,
                "вс" to DayOfWeek.SUNDAY,
            )
    }
}
