package com.firkatdavletov.financeconsultante.telegram.application

class TelegramMenuRegistrar(
    private val client: TelegramClient,
) {
    @Volatile
    private var registered = false

    fun registerOnce(): Boolean =
        synchronized(this) {
            if (registered) {
                false
            } else {
                client.configureMenu(COMMANDS)
                registered = true
                true
            }
        }

    companion object {
        val COMMANDS =
            listOf(
                BotCommand("balance", "Баланс и обороты"),
                BotCommand("today", "Лимит и оценка на сегодня"),
                BotCommand("recurring", "Регулярные платежи"),
                BotCommand("expected", "Ожидаемые доходы"),
            )
    }
}
