package com.firkatdavletov.financeconsultante.telegram.application

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class TelegramMenuRegistrarTests {
    @Test
    fun `registration retries after failure and stops after success`() {
        val received = mutableListOf<List<BotCommand>>()
        var attempts = 0
        var fail = true
        val client =
            object : TelegramClient {
                override fun configureMenu(commands: List<BotCommand>) {
                    attempts += 1
                    if (fail) throw TelegramFailure("transport")
                    received += commands
                }

                override fun getUpdates(offset: Long): List<IncomingUpdate> = error("Unexpected polling")

                override fun sendMessage(
                    chatId: Long,
                    text: String,
                    buttons: List<ReplyButton>,
                ): Long = error("Unexpected send")

                override fun answerCallback(queryId: String) = error("Unexpected callback")
            }
        val registrar = TelegramMenuRegistrar(client)

        assertThatThrownBy { registrar.registerOnce() }.isInstanceOf(TelegramFailure::class.java)
        fail = false
        assertThat(registrar.registerOnce()).isTrue()
        assertThat(registrar.registerOnce()).isFalse()

        assertThat(attempts).isEqualTo(2)
        assertThat(received.single())
            .containsExactly(
                BotCommand("balance", "Баланс и обороты"),
                BotCommand("today", "Лимит и оценка на сегодня"),
                BotCommand("recurring", "Регулярные платежи"),
                BotCommand("expected", "Ожидаемые доходы"),
            )
    }
}
