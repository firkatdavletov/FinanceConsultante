package com.firkatdavletov.financeconsultante.telegram.domain

internal object SetupMessages {
    fun pending(dialog: SetupDialog): String =
        if (dialog.step == SetupStep.IDLE) "" else "\nТекущий шаг: " + prompt(dialog.step)

    fun prompt(step: SetupStep): String =
        when (step) {
            SetupStep.OPENING -> {
                "Введите начальный остаток в KZT (до двух знаков после запятой; отрицательный " +
                    "допустим). /cancel — отмена."
            }

            SetupStep.DATE -> {
                "Введите дату начала учёта ГГГГ-ММ-ДД, не позже сегодня. Остаток относится к началу " +
                    "этого дня в Asia/Almaty. /cancel — отмена."
            }

            SetupStep.REPORT_TIME -> {
                "Введите время отчётов ЧЧ:ММ в Asia/Almaty (по умолчанию 08:00). Отправьте 08:00, " +
                    "чтобы сохранить его. /cancel — отмена."
            }

            SetupStep.RESERVE -> {
                "Введите денежный резерв в KZT, не меньше нуля. Резерв не является расходом. /cancel — отмена."
            }

            SetupStep.IDLE -> {
                MENU
            }
        }

    fun summary(current: BudgetSettings?): String =
        if (current == null) {
            "Бюджет ещё не настроен. KZT/RUB; основная KZT; Asia/Almaty; отчёт 08:00. /start"
        } else {
            "Валюты: KZT и RUB. Основная: KZT. Часовой пояс: Asia/Almaty.\n" +
                "Начальный остаток: ${current.openingBalance.amount.toPlainString()} KZT; дата: " +
                "${current.startedOn}.\n" +
                "Время отчётов: ${current.reportTime}. Резерв: ${current.cashReserve.amount.toPlainString()} KZT.\n" +
                "Отправка отчётов появится на следующем этапе."
        }

    const val MENU = "/opening — остаток и дата; /report_time — время отчётов; /reserve — резерв; /cancel — отмена."
    const val INTRO =
        "Бот учитывает введённые операции и не знает банковский остаток. " +
            "Валюты: KZT/RUB, основная KZT, Asia/Almaty, отчёт 08:00. Настроим бюджет."
    const val FIXED_SETTINGS =
        "В MVP основная валюта KZT и часовой пояс Asia/Almaty фиксированы. " +
            "Суммы существующих операций нельзя просто переименовать в другую валюту. RUB " +
            "учитывается по дневному курсу."
    const val HELP =
        "/start — начать или продолжить настройку; /settings — показать настройки. " + MENU +
            "\nДоходы, расходы, исправления и отправка отчётов будут доступны на следующих этапах. " +
            FIXED_SETTINGS
}
