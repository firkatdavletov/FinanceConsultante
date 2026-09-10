package com.firkatdavletov.financeconsultante.ai.infrastructure

import com.fasterxml.jackson.annotation.JsonPropertyDescription
import com.fasterxml.jackson.databind.ObjectMapper
import com.firkatdavletov.financeconsultante.ai.application.RecognitionObservation
import com.firkatdavletov.financeconsultante.ai.application.RecognitionResponse
import com.firkatdavletov.financeconsultante.ai.application.RecognitionUsage
import com.firkatdavletov.financeconsultante.ai.application.TransactionRecognitionProvider
import com.firkatdavletov.financeconsultante.ai.domain.RecognitionCandidate
import com.firkatdavletov.financeconsultante.ai.domain.RecognitionCurrency
import com.firkatdavletov.financeconsultante.ai.domain.RecognitionInput
import com.firkatdavletov.financeconsultante.ai.domain.RecognitionIntent
import com.firkatdavletov.financeconsultante.ai.domain.RecognitionOperationType
import com.openai.client.OpenAIClient
import com.openai.models.responses.Response
import com.openai.models.responses.ResponseCreateParams
import com.openai.models.responses.StructuredResponseCreateParams
import java.util.Locale
import java.util.concurrent.TimeUnit

class OpenAiTransactionRecognizer(
    private val client: OpenAIClient,
    private val properties: OpenAiProperties,
    private val prompt: String,
    private val mapper: ObjectMapper = ObjectMapper(),
) : TransactionRecognitionProvider {
    // SDK failures are reduced to safe codes; payload-bearing messages are never logged.
    @Suppress("TooGenericExceptionCaught", "ReturnCount")
    override fun recognize(input: RecognitionInput): RecognitionResponse {
        val started = System.nanoTime()
        return try {
            require(input.message.length <= MAX_MESSAGE_LENGTH)
            val params = request(input)
            client.responses().withRawResponse().create(params.rawParams).use { raw ->
                val response = raw.parse()
                val output = outputText(response) ?: return failure("incomplete_response", started)
                val contract =
                    runCatching { mapper.readValue(output, OpenAiRecognitionOutput::class.java) }
                        .getOrElse { return failure("invalid_json", started) }
                val usage = response.usage().orElse(null) ?: return failure("missing_usage", started)
                RecognitionResponse.Success(
                    RecognitionObservation(
                        candidate = contract.toCandidate(),
                        requestId = raw.requestId().orElse(null),
                        responseId = response.id(),
                        usage = RecognitionUsage(usage.inputTokens(), usage.outputTokens()),
                        latencyMillis = elapsedMillis(started),
                    ),
                )
            }
        } catch (failure: RuntimeException) {
            failure(classify(failure), started)
        }
    }

    private fun request(input: RecognitionInput): StructuredResponseCreateParams<OpenAiRecognitionOutput> =
        ResponseCreateParams
            .builder()
            .model(properties.model)
            .instructions(prompt)
            .input(renderInput(input))
            .maxOutputTokens(properties.maxOutputTokens)
            .store(false)
            .text(OpenAiRecognitionOutput::class.java)
            .build()

    private fun renderInput(input: RecognitionInput): String =
        mapper.writeValueAsString(
            mapOf(
                "message" to input.message,
                "today" to input.today.toString(),
                "allowedCategories" to
                    input.categories.map { category ->
                        mapOf(
                            "categoryId" to category.id.toString(),
                            "name" to category.name,
                            "operationType" to category.direction.name,
                        )
                    },
            ),
        )

    private fun outputText(response: Response): String? =
        response
            .output()
            .flatMap { item ->
                item
                    .message()
                    .map { message ->
                        message.content().mapNotNull { content -> content.outputText().orElse(null)?.text() }
                    }.orElse(emptyList())
            }.singleOrNull()

    private fun classify(failure: RuntimeException): String {
        val names =
            generateSequence<Throwable>(failure) { current -> current.cause }
                .map { it.javaClass.simpleName.lowercase(Locale.ROOT) }
                .toList()
        return when {
            names.any { "timeout" in it } -> "timeout"
            names.any { "ratelimit" in it } -> "rate_limit"
            names.any { "invaliddata" in it } -> "invalid_response"
            names.any { "json" in it || "parse" in it || "mapping" in it } -> "invalid_json"
            names.any { "permission" in it || "authentication" in it } -> "credentials"
            names.any { "connection" in it || it == "ioexception" || "socket" in it } -> "network"
            else -> "provider_error"
        }
    }

    private fun failure(
        code: String,
        started: Long,
    ) = RecognitionResponse.Failure(code, elapsedMillis(started))

    private fun elapsedMillis(started: Long): Long =
        TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started).coerceAtLeast(0)

    companion object {
        private const val MAX_MESSAGE_LENGTH = 512
    }
}

class OpenAiRecognitionOutput {
    @JvmField
    @JsonPropertyDescription("RECORD_TRANSACTION, PURCHASE_QUESTION, or UNKNOWN")
    var intent: RecognitionIntent = RecognitionIntent.UNKNOWN

    @JvmField
    @JsonPropertyDescription("INCOME, EXPENSE, or UNKNOWN")
    var operationType: RecognitionOperationType = RecognitionOperationType.UNKNOWN

    @JvmField
    @JsonPropertyDescription("Exact positive decimal string from message, or UNKNOWN")
    var amount: String = "UNKNOWN"

    @JvmField
    @JsonPropertyDescription("KZT, RUB, or UNKNOWN")
    var currency: RecognitionCurrency = RecognitionCurrency.UNKNOWN

    @JvmField
    @JsonPropertyDescription("One supplied categoryId, or UNKNOWN")
    var categoryId: String = "UNKNOWN"

    @JvmField
    @JsonPropertyDescription("ISO local date, or UNKNOWN")
    var occurredOn: String = "UNKNOWN"

    @JvmField
    @JsonPropertyDescription("Short neutral description; empty when unknown")
    var description: String = ""

    @JvmField
    @JsonPropertyDescription("True whenever any required field is unknown or ambiguous")
    var needsClarification: Boolean = true

    fun toCandidate() =
        RecognitionCandidate(
            intent,
            operationType,
            amount,
            currency,
            categoryId,
            occurredOn,
            description,
            needsClarification,
        )
}
