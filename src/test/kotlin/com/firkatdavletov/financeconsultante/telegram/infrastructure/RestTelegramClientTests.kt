package com.firkatdavletov.financeconsultante.telegram.infrastructure

import com.firkatdavletov.financeconsultante.telegram.application.TelegramFailure
import com.firkatdavletov.financeconsultante.telegram.application.TelegramMenuRegistrar
import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.equalToJson
import com.github.tomakehurst.wiremock.client.WireMock.post
import com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor
import com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo
import com.github.tomakehurst.wiremock.core.WireMockConfiguration.options
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.http.client.JdkClientHttpRequestFactory
import org.springframework.web.client.RestClient
import java.time.Duration

@Suppress("MagicNumber", "TooManyFunctions")
class RestTelegramClientTests {
    private val server = WireMockServer(options().dynamicPort().http2PlainDisabled(true))
    private val token = "123456:synthetic-token"

    @BeforeEach
    fun start() {
        server.start()
    }

    @AfterEach
    fun stop() {
        server.stop()
    }

    @Test
    fun `poll request has committed offset and unknown update is ignorable`() {
        stub(
            "getUpdates",
            """
{
  "ok": true,
  "result": [
    {
      "update_id": 11,
      "message": {
        "message_id": 1,
        "date": 1788663600,
        "from": {
          "id": 555,
          "is_bot": false
        },
        "chat": {
          "id": 555,
          "type": "private"
        },
        "text": "/start"
      }
    },
    {
      "update_id": 12,
      "callback_query": {
        "id": "synthetic"
      }
    }
  ]
}
""",
        )
        val updates = client().getUpdates(11)
        assertThat(updates.map { it.updateId }).containsExactly(11, 12)
        assertThat(updates.first().message?.userId).isEqualTo(555)
        assertThat(updates.first().message?.sentAt).isEqualTo(java.time.Instant.ofEpochSecond(1788663600))
        assertThat(updates.last().message).isNull()
        server.verify(
            postRequestedFor(urlEqualTo("/bot$token/getUpdates"))
                .withRequestBody(
                    equalToJson(
                        """{"offset":11,"timeout":25,"limit":100,
                        "allowed_updates":["message","callback_query","edited_message"]}""",
                    ),
                ),
        )
    }

    @Test
    fun `sendMessage uses plain text and returns message id`() {
        stub(
            "sendMessage",
            """{"ok":true,"result":{"message_id":73,"chat":{"id":555,"type":"private"},"text":"ignored"}}""",
        )
        assertThat(client().sendMessage(555, "<b>Тест & текст</b>")).isEqualTo(73)
        server.verify(
            postRequestedFor(urlEqualTo("/bot$token/sendMessage"))
                .withRequestBody(equalToJson("""{"chat_id":555,"text":"<b>Тест & текст</b>"}""")),
        )
    }

    @Test
    fun `menu registration uses private command scope and commands button`() {
        stub("setMyCommands", """{"ok":true,"result":true}""")
        stub("setChatMenuButton", """{"ok":true,"result":true}""")

        client().configureMenu(TelegramMenuRegistrar.COMMANDS)

        server.verify(
            postRequestedFor(urlEqualTo("/bot$token/setMyCommands")).withRequestBody(
                equalToJson(
                    """
                    {
                      "commands": [
                        {"command":"balance","description":"Баланс и обороты"},
                        {"command":"today","description":"Лимит и оценка на сегодня"},
                        {"command":"recurring","description":"Регулярные платежи"},
                        {"command":"expected","description":"Ожидаемые доходы"}
                      ],
                      "scope":{"type":"all_private_chats"}
                    }
                    """,
                ),
            ),
        )
        server.verify(
            postRequestedFor(urlEqualTo("/bot$token/setChatMenuButton"))
                .withRequestBody(equalToJson("""{"menu_button":{"type":"commands"}}""")),
        )
    }

    @Test
    fun `429 respects retry_after and never retains provider descriptions or token`() {
        stub(
            "sendMessage",
            """{"ok":false,"error_code":429,"description":"private $token","parameters":{"retry_after":41}}""",
            429,
        )
        assertThatThrownBy { client().sendMessage(555, "test") }.isInstanceOfSatisfying(TelegramFailure::class.java) {
            assertThat(it.retryAfter).isEqualTo(Duration.ofSeconds(41))
            assertThat(it.permanent).isFalse()
            assertThat(it.message).isEqualTo("api_429")
            assertThat(it.cause).isNull()
        }
    }

    @Test
    fun `poll timeout and send timeout are sanitized retryable failures`() {
        for (method in listOf("getUpdates", "sendMessage")) {
            server.stubFor(
                post(urlEqualTo("/bot$token/$method")).willReturn(
                    aResponse()
                        .withFixedDelay(500)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""{"ok":true,"result":[]}"""),
                ),
            )
        }
        val client = client(Duration.ofMillis(50))
        assertThatThrownBy {
            client.getUpdates(0)
        }.isInstanceOf(TelegramFailure::class.java).hasMessage("transport").hasNoCause()
        assertThatThrownBy {
            client.sendMessage(555, "test")
        }.isInstanceOf(TelegramFailure::class.java).hasMessage("transport").hasNoCause()
    }

    @Test
    fun `invalid payload and missing update id do not acknowledge the batch`() {
        stub("getUpdates", "not json")
        assertThatThrownBy {
            client().getUpdates(
                0,
            )
        }.isInstanceOf(TelegramFailure::class.java).hasMessage("invalid_payload")
        stub("getUpdates", """{"ok":true,"result":[{}]}""")
        assertThatThrownBy {
            client().getUpdates(
                0,
            )
        }.isInstanceOf(TelegramFailure::class.java).hasMessage("invalid_update_id")
    }

    @Test
    fun `permanent refusal and server failure are distinct`() {
        stub("sendMessage", """{"ok":false,"error_code":403}""", 403)
        assertThatThrownBy { client().sendMessage(555, "test") }.isInstanceOfSatisfying(TelegramFailure::class.java) {
            assertThat(it.permanent).isTrue()
        }
        stub("getUpdates", """{"ok":false,"error_code":500}""", 500)
        assertThatThrownBy { client().getUpdates(0) }.isInstanceOfSatisfying(TelegramFailure::class.java) {
            assertThat(it.permanent).isFalse()
        }
    }

    @Test
    fun `enabled configuration rejects missing owner token and nonlocal custom endpoints`() {
        assertThatThrownBy { TelegramProperties().validateEnabled() }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy {
            TelegramProperties(
                token,
                0,
            ).validateEnabled()
        }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy {
            TelegramProperties(token, 555, "https://example.com").validateEnabled()
        }.isInstanceOf(IllegalArgumentException::class.java)
        TelegramProperties(token, 555, server.baseUrl()).validateEnabled()
    }

    private fun stub(
        method: String,
        body: String,
        status: Int = 200,
    ) {
        server.stubFor(
            post(urlEqualTo("/bot$token/$method"))
                .willReturn(
                    aResponse().withStatus(status).withHeader("Content-Type", "application/json").withBody(body),
                ),
        )
    }

    @Test
    fun `callbacks use the pressing user and edited messages discard their text`() {
        stub(
            "getUpdates",
            """{"ok":true,"result":[
            {"update_id":1,"callback_query":{"id":"synthetic-query","from":{"id":555,"is_bot":false},
              "message":{"message_id":73,"date":1788663600,"from":{"id":123456,"is_bot":true},
                "chat":{"id":555,"type":"private"}},"data":"void:00000000-0000-0000-0000-000000000001:0"}},
            {"update_id":2,"edited_message":{"message_id":5,"date":1788663600,"from":{"id":555,"is_bot":false},
              "chat":{"id":555,"type":"private"},"text":"продукты 9000"}},
            {"update_id":3,"callback_query":{"id":"foreign-bot","from":{"id":555,"is_bot":false},
              "message":{"date":1788663600,"from":{"id":999,"is_bot":true},"chat":{"id":555,"type":"private"}},
              "data":"void:00000000-0000-0000-0000-000000000001:0"}}
        ]}""",
        )
        val updates = client().getUpdates(0)
        assertThat(updates[0].message?.userId).isEqualTo(555)
        assertThat(updates[0].message?.callbackQueryId).isEqualTo("synthetic-query")
        assertThat(
            updates[0].message?.kind,
        ).isEqualTo(com.firkatdavletov.financeconsultante.telegram.application.IncomingKind.CALLBACK)
        assertThat(updates[1].message?.text).isEqualTo("edited_message")
        assertThat(
            updates[1].message?.kind,
        ).isEqualTo(com.firkatdavletov.financeconsultante.telegram.application.IncomingKind.EDITED)
        assertThat(updates[2].message).isNull()
    }

    @Test
    fun `inline keyboard payload and callback acknowledgement follow Bot API contract`() {
        stub("sendMessage", """{"ok":true,"result":{"message_id":73}}""")
        stub("answerCallbackQuery", """{"ok":true,"result":true}""")
        val data = "cat:00000000-0000-0000-0000-000000000001:9223372036854775807"
        val button =
            com.firkatdavletov.financeconsultante.telegram.application
                .ReplyButton("Категория", data)
        client().sendMessage(555, "Выберите действие", listOf(button))
        client().answerCallback("synthetic-query")
        server.verify(
            postRequestedFor(urlEqualTo("/bot$token/sendMessage")).withRequestBody(
                equalToJson(
                    """{"chat_id":555,"text":"Выберите действие","reply_markup":{"inline_keyboard":[[
              {"text":"Категория","callback_data":"$data"}]]}}""",
                ),
            ),
        )
        server.verify(
            postRequestedFor(
                urlEqualTo("/bot$token/answerCallbackQuery"),
            ).withRequestBody(equalToJson("""{"callback_query_id":"synthetic-query"}""")),
        )
        assertThatThrownBy { client().sendMessage(555, "Действие", listOf(button.copy(data = "x".repeat(65)))) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    private fun client(timeout: Duration = Duration.ofSeconds(2)): RestTelegramClient {
        val factory = JdkClientHttpRequestFactory()
        factory.setReadTimeout(timeout)
        return RestTelegramClient(
            RestClient
                .builder()
                .baseUrl(server.baseUrl())
                .requestFactory(factory)
                .build(),
            TelegramProperties(token, 555, server.baseUrl()),
        )
    }
}
