package com.firkatdavletov.financeconsultante.telegram.infrastructure

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonProperty
import com.firkatdavletov.financeconsultante.telegram.application.BotCommand
import com.firkatdavletov.financeconsultante.telegram.application.IncomingKind
import com.firkatdavletov.financeconsultante.telegram.application.IncomingMessage
import com.firkatdavletov.financeconsultante.telegram.application.IncomingUpdate
import com.firkatdavletov.financeconsultante.telegram.application.ReplyButton
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
    override fun configureMenu(commands: List<BotCommand>) {
        require(commands.size in 1..MAX_COMMANDS)
        expectTrue(
            "setMyCommands",
            mapOf(
                "commands" to commands,
                "scope" to mapOf("type" to "all_private_chats"),
            ),
        )
        expectTrue(
            "setChatMenuButton",
            mapOf("menu_button" to mapOf("type" to "commands")),
        )
    }

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
            IncomingUpdate(id, incoming(update))
        }
    }

    @Suppress("ReturnCount") // Distinguish callback, edited message and ordinary message transports.
    private fun incoming(update: BotUpdate): IncomingMessage? {
        update.callbackQuery?.let { return incomingCallback(it) }
        val message = update.editedMessage ?: update.message ?: return null
        val edited = update.editedMessage != null
        return IncomingMessage(
            message.from?.id,
            message.chat?.id,
            message.chat?.type,
            message.from?.isBot ?: true,
            if (edited) "edited_message" else message.text,
            message.date?.takeIf { it in 1..MAX_MESSAGE_TIMESTAMP }?.let(Instant::ofEpochSecond),
            if (edited) IncomingKind.EDITED else IncomingKind.MESSAGE,
            messageId = message.messageId,
        )
    }

    @Suppress("ReturnCount") // Invalid or inaccessible callback messages carry no authorized action.
    private fun incomingCallback(callback: BotCallback): IncomingMessage? {
        val message =
            callback.message?.takeIf {
                it.from?.id == properties.botId && it.from.isBot && (it.date ?: 0) > 0
            } ?: return null
        val id = callback.id?.takeIf { it.isNotBlank() && it.length <= MAX_QUERY_LENGTH } ?: return null
        val data =
            callback.data?.takeIf {
                it.isNotBlank() && it.toByteArray(Charsets.UTF_8).size <= MAX_CALLBACK_BYTES
            } ?: return null
        return IncomingMessage(
            callback.from?.id,
            message.chat?.id,
            message.chat?.type,
            callback.from?.isBot ?: true,
            data,
            null,
            IncomingKind.CALLBACK,
            id,
            message.messageId,
        )
    }

    override fun answerCallback(queryId: String) {
        require(queryId.isNotBlank() && queryId.length <= MAX_QUERY_LENGTH)
        request(
            "answerCallbackQuery",
            mapOf("callback_query_id" to queryId),
            object : ParameterizedTypeReference<BotResponse<Boolean>>() {},
        )
    }

    private fun expectTrue(
        method: String,
        payload: Any,
    ) {
        val accepted =
            request(
                method,
                payload,
                object : ParameterizedTypeReference<BotResponse<Boolean>>() {},
            )
        if (!accepted) throw TelegramFailure("rejected_result")
    }

    override fun sendMessage(
        chatId: Long,
        text: String,
        buttons: List<ReplyButton>,
    ): Long {
        require(chatId > 0 && text.isNotBlank() && text.length <= MAX_TEXT_LENGTH)
        require(buttons.all { it.data.toByteArray(Charsets.UTF_8).size in 1..MAX_CALLBACK_BYTES })
        val result =
            request(
                "sendMessage",
                SendMessageRequest(
                    chatId,
                    text,
                    keyboard(buttons),
                ),
                object : ParameterizedTypeReference<BotResponse<BotMessage>>() {},
            )
        return result.messageId?.takeIf { it > 0 } ?: throw TelegramFailure("invalid_message_id")
    }

    override fun editMessage(
        chatId: Long,
        messageId: Long,
        text: String,
        buttons: List<ReplyButton>,
    ): Long {
        require(chatId > 0 && messageId > 0 && text.isNotBlank() && text.length <= MAX_TEXT_LENGTH)
        require(buttons.all { it.data.toByteArray(Charsets.UTF_8).size in 1..MAX_CALLBACK_BYTES })
        val result =
            request(
                "editMessageText",
                EditMessageRequest(chatId, messageId, text, keyboard(buttons) ?: InlineKeyboard(emptyList())),
                object : ParameterizedTypeReference<BotResponse<BotMessage>>() {},
            )
        return result.messageId?.takeIf { it > 0 } ?: throw TelegramFailure("invalid_message_id")
    }

    private fun keyboard(buttons: List<ReplyButton>): InlineKeyboard? {
        if (buttons.isEmpty()) return null
        val rows =
            if (buttons.all { it.row == null }) {
                buttons.chunked(DEFAULT_BUTTONS_PER_ROW)
            } else {
                buttons.groupBy { it.row }.values.toList()
            }
        return InlineKeyboard(rows.map { row -> row.map { InlineButton(it.text, it.data) } })
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
        @param:JsonProperty("edited_message") val editedMessage: BotMessage? = null,
        @param:JsonProperty("callback_query") val callbackQuery: BotCallback? = null,
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    private data class BotCallback(
        val id: String? = null,
        val from: BotUser? = null,
        val message: BotMessage? = null,
        val data: String? = null,
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
        @get:JsonProperty("allowed_updates") val allowedUpdates: List<String> =
            listOf("message", "callback_query", "edited_message"),
    )

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private data class SendMessageRequest(
        @get:JsonProperty("chat_id") val chatId: Long,
        val text: String,
        @get:JsonProperty("reply_markup") val replyMarkup: InlineKeyboard? = null,
    )

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private data class EditMessageRequest(
        @get:JsonProperty("chat_id") val chatId: Long,
        @get:JsonProperty("message_id") val messageId: Long,
        val text: String,
        @get:JsonProperty("reply_markup") val replyMarkup: InlineKeyboard? = null,
    )

    private data class InlineKeyboard(
        @get:JsonProperty("inline_keyboard") val rows: List<List<InlineButton>>,
    )

    private data class InlineButton(
        val text: String,
        @get:JsonProperty("callback_data") val data: String,
    )

    companion object {
        private const val MAX_COMMANDS = 100
        private const val MAX_CALLBACK_BYTES = 64
        private const val MAX_QUERY_LENGTH = 256
        const val MAX_TEXT_LENGTH = 4096
        private const val MAX_MESSAGE_TIMESTAMP = 253402300799L
        private const val UPDATE_LIMIT = 100
        private const val DEFAULT_BUTTONS_PER_ROW = 2
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
