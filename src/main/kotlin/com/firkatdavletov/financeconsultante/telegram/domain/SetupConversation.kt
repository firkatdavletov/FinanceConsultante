package com.firkatdavletov.financeconsultante.telegram.domain

import com.firkatdavletov.financeconsultante.shared.domain.Money
import java.time.DateTimeException
import java.time.LocalDate
import java.time.LocalTime

class SetupConversation {
    fun respond(
        text: String,
        dialog: SetupDialog,
        current: BudgetSettings?,
        hasEntries: Boolean,
        today: LocalDate,
    ): SetupDecision {
        val input = text.trim()
        return if (input.startsWith('/')) {
            command(input.substringBefore(' '), dialog, current, hasEntries)
        } else {
            answer(input, dialog, today)
        }
    }

    private fun command(
        command: String,
        dialog: SetupDialog,
        current: BudgetSettings?,
        hasEntries: Boolean,
    ): SetupDecision =
        when (command) {
            "/cancel" -> {
                SetupDecision(SetupDialog(), "Ввод отменён. Сохранённые настройки доступны: /settings.")
            }

            "/help" -> {
                SetupDecision(dialog, SetupMessages.HELP)
            }

            "/start" -> {
                start(dialog, current)
            }

            "/settings" -> {
                SetupDecision(
                    dialog,
                    SetupMessages.summary(current) + "\n" + SetupMessages.MENU + SetupMessages.pending(dialog),
                )
            }

            "/opening", "/report_time", "/reserve" -> {
                if (command == "/opening" && hasEntries) {
                    SetupDecision(SetupDialog(), "Начальный остаток и дату нельзя менять после появления проводок.")
                } else {
                    edit(command, current)
                }
            }

            "/currency", "/timezone" -> {
                SetupDecision(dialog, SetupMessages.FIXED_SETTINGS)
            }

            else -> {
                SetupDecision(dialog, "Неизвестная команда. /help" + SetupMessages.pending(dialog))
            }
        }

    private fun start(
        dialog: SetupDialog,
        current: BudgetSettings?,
    ): SetupDecision =
        when {
            dialog.step != SetupStep.IDLE -> {
                SetupDecision(dialog, "Продолжим настройку. " + SetupMessages.prompt(dialog.step))
            }

            current != null -> {
                SetupDecision(dialog, SetupMessages.summary(current) + "\n" + SetupMessages.MENU)
            }

            else -> {
                val next = SetupDialog(step = SetupStep.OPENING, initialSetup = true)
                SetupDecision(next, SetupMessages.INTRO + "\n" + SetupMessages.prompt(next.step))
            }
        }

    private fun edit(
        command: String,
        current: BudgetSettings?,
    ): SetupDecision {
        if (current == null) return SetupDecision(SetupDialog(), "Сначала завершите настройку: /start.")

        val step =
            when (command) {
                "/opening" -> SetupStep.OPENING
                "/report_time" -> SetupStep.REPORT_TIME
                else -> SetupStep.RESERVE
            }
        val next =
            SetupDialog(step, false, current.openingBalance, current.startedOn, current.reportTime, current.cashReserve)
        return SetupDecision(next, SetupMessages.prompt(step))
    }

    @Suppress("SwallowedException")
    private fun answer(
        input: String,
        dialog: SetupDialog,
        today: LocalDate,
    ): SetupDecision =
        try {
            when (dialog.step) {
                SetupStep.IDLE -> {
                    SetupDecision(
                        dialog,
                        "Для настройки используйте /start или /settings. Формат операций: /help.",
                    )
                }

                SetupStep.OPENING -> {
                    advance(dialog.copy(step = SetupStep.DATE, openingBalance = money(input)))
                }

                SetupStep.DATE -> {
                    date(input, dialog, today)
                }

                SetupStep.REPORT_TIME -> {
                    time(input, dialog)
                }

                SetupStep.RESERVE -> {
                    val reserve = money(input)
                    require(reserve.amount.signum() >= 0)
                    finish(dialog.copy(cashReserve = reserve))
                }
            }
        } catch (_: IllegalArgumentException) {
            SetupDecision(dialog, "Некорректное значение. " + SetupMessages.prompt(dialog.step))
        } catch (_: DateTimeException) {
            SetupDecision(dialog, "Некорректная дата или время. " + SetupMessages.prompt(dialog.step))
        }

    private fun date(
        input: String,
        dialog: SetupDialog,
        today: LocalDate,
    ): SetupDecision {
        require(Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}").matches(input))
        val date = LocalDate.parse(input)
        require(date.year >= 1 && date <= today)
        val next = dialog.copy(startedOn = date, step = SetupStep.REPORT_TIME)
        return if (dialog.initialSetup) advance(next) else finish(next)
    }

    private fun time(
        input: String,
        dialog: SetupDialog,
    ): SetupDecision {
        require(Regex("[0-9]{2}:[0-9]{2}").matches(input))
        val next = dialog.copy(reportTime = LocalTime.parse(input), step = SetupStep.RESERVE)
        return if (dialog.initialSetup) advance(next) else finish(next)
    }

    private fun money(input: String): Money {
        require(Regex("-?(?:[0-9]+|[0-9]{1,3}(?:[ \\u00a0\\u202f][0-9]{3})+)(?:[.,][0-9]{1,2})?").matches(input))
        val normalized = input.replace(Regex("[ \\u00a0\\u202f]"), "").replace(',', '.')
        return Money.of(normalized)
    }

    private fun advance(dialog: SetupDialog): SetupDecision = SetupDecision(dialog, SetupMessages.prompt(dialog.step))

    private fun finish(dialog: SetupDialog): SetupDecision {
        val settings = dialog.settings()
        return SetupDecision(SetupDialog(), "Настройки сохранены.\n" + SetupMessages.summary(settings), settings)
    }
}
