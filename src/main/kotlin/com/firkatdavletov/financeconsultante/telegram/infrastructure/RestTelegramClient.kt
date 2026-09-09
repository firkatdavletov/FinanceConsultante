package com.firkatdavletov.financeconsultante.telegram.infrastructure

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import com.firkatdavletov.financeconsultante.telegram.application.IncomingMessage
import com.firkatdavletov.financeconsultante.telegram.application.IncomingUpdate
import com.firkatdavletov.financeconsultante.telegram.application.TelegramClient
import com.firkatdavletov.financeconsultante.telegram.application.TelegramFailure
import org.springframework.core.ParameterizedTypeReference
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.web.client.ResourceAccessException
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientException
import java.time.Duration
import java.time.Instant

class RestTelegramClient(
    private val client: RestClient,
    private val properties: TelegramProperties,
) : TelegramClient {
    override fun getUpdates(offset: Long): List<IncomingUpdate> {
        val updates =
            request(
                "getUpdates",
                GetUpdatesRequest(offset, properties.pollTimeoutSeconds),
                object : ParameterizedTypeReference<BotResponse<List<BotUpdate>>>() {},
            )
        return updates.map { update ->
            val id =
                update.updateId?.takeIf { it >= 0 && it < Long.MAX_VALUE }
                    ?: throw TelegramFailure("invalid_update_id")
            IncomingUpdate(
                id,
                update.message?.let { message ->
                    IncomingMessage(
                        message.from?.id,
                        message.chat?.id,
                        message.chat?.type,
                        message.from?.isBot ?: true,
                        message.text,
                        message.date?.takeIf { it in 1..MAX_MESSAGE_TIMESTAMP }?.let(Instant::ofEpochSecond),
                    )
                },
            )
        }
    }

    override fun sendMessage(
        chatId: Long,
        text: String,
    ): Long {
        require(chatId > 0 && text.isNotBlank() && text.length <= MAX_TEXT_LENGTH)
        val result =
            request(
                "sendMessage",
                SendMessageRequest(chatId, text),
                object : ParameterizedTypeReference<BotResponse<BotMessage>>() {},
            )
        return result.messageId?.takeIf { it > 0 } ?: throw TelegramFailure("invalid_message_id")
    }

    @Suppress("SwallowedException")
    private fun <T : Any> request(
        method: String,
        payload: Any,
        type: ParameterizedTypeReference<BotResponse<T>>,
    ): T =
        try {
            client
                .post()
                .uri("/bot${properties.botToken}/$method")
                .contentType(MediaType.APPLICATION_JSON)
                .body(payload)
                .exchange { _, response ->
                    val envelope = response.bodyTo(type)
                    if (!response.statusCode.is2xxSuccessful || envelope?.ok != true) {
                        val code = envelope?.errorCode ?: response.statusCode.value()
                        val delay =
                            envelope
                                ?.parameters
                                ?.retryAfter
                                ?.takeIf { it in 1..MAX_RETRY_SECONDS }
                                ?.let(Duration::ofSeconds) ?: Duration.ofSeconds(DEFAULT_RETRY_SECONDS)
                        throw TelegramFailure("api_$code", delay, code in PERMANENT_ERRORS)
                    }
                    envelope.result ?: throw TelegramFailure("empty_result")
                }
        } catch (_: ResourceAccessException) {
            // Transport exceptions may contain the bot token in the URL. Never retain their cause.
            throw TelegramFailure("transport")
        } catch (_: RestClientException) {
            throw TelegramFailure("invalid_payload")
        }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private data class BotResponse<T>(
        val ok: Boolean = false,
        val result: T? = null,
        @param:JsonProperty("error_code") val errorCode: Int? = null,
        val parameters: ResponseParameters? = null,
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    private data class ResponseParameters(
        @param:JsonProperty("retry_after") val retryAfter: Long? = null,
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    private data class BotUpdate(
        @param:JsonProperty("update_id") val updateId: Long? = null,
        val message: BotMessage? = null,
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    private data class BotMessage(
        @param:JsonProperty("message_id") val messageId: Long? = null,
        val from: BotUser? = null,
        val chat: BotChat? = null,
        val text: String? = null,
        val date: Long? = null,
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    private data class BotUser(
        val id: Long? = null,
        @param:JsonProperty("is_bot") val isBot: Boolean = true,
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    private data class BotChat(
        val id: Long? = null,
        val type: String? = null,
    )

    private data class GetUpdatesRequest(
        val offset: Long,
        val timeout: Int,
        val limit: Int = UPDATE_LIMIT,
        @get:JsonProperty("allowed_updates") val allowedUpdates: List<String> = listOf("message"),
    )

    private data class SendMessageRequest(
        @get:JsonProperty("chat_id") val chatId: Long,
        val text: String,
    )

    companion object {
        const val MAX_TEXT_LENGTH = 4096
        private const val MAX_MESSAGE_TIMESTAMP = 253402300799L
        private const val UPDATE_LIMIT = 100
        private val PERMANENT_ERRORS =
            setOf(
                HttpStatus.BAD_REQUEST,
                HttpStatus.UNAUTHORIZED,
                HttpStatus.FORBIDDEN,
                HttpStatus.NOT_FOUND,
            ).map {
                it.value()
            }
        private const val MAX_RETRY_SECONDS = 604800L
        private const val DEFAULT_RETRY_SECONDS = 30L
    }
}
