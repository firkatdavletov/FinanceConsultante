@file:Suppress("MagicNumber")

package com.firkatdavletov.financeconsultante.ai.evaluation

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.firkatdavletov.financeconsultante.ai.application.RecognitionResponse
import com.firkatdavletov.financeconsultante.ai.domain.RecognitionCandidate
import com.firkatdavletov.financeconsultante.ai.domain.RecognitionCategory
import com.firkatdavletov.financeconsultante.ai.domain.RecognitionInput
import com.firkatdavletov.financeconsultante.ai.domain.RecognitionValidation
import com.firkatdavletov.financeconsultante.ai.domain.RecognitionValidator
import com.firkatdavletov.financeconsultante.ai.infrastructure.OpenAiClientFactory
import com.firkatdavletov.financeconsultante.ai.infrastructure.OpenAiProperties
import com.firkatdavletov.financeconsultante.ai.infrastructure.OpenAiTransactionRecognizer
import com.firkatdavletov.financeconsultante.ledger.domain.Direction
import java.math.BigDecimal
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import kotlin.math.ceil

private val categories =
    listOf(
        RecognitionCategory(UUID.fromString("10000000-0000-0000-0000-000000000001"), "Зарплата", Direction.INCOME),
        RecognitionCategory(UUID.fromString("10000000-0000-0000-0000-000000000002"), "Продукты", Direction.EXPENSE),
        RecognitionCategory(UUID.fromString("10000000-0000-0000-0000-000000000003"), "Здоровье", Direction.EXPENSE),
    )

fun main() {
    require(System.getenv("OPENAI_LIVE_EVALUATION") == "true") {
        "Set OPENAI_LIVE_EVALUATION=true to authorize the paid external evaluation"
    }
    val apiKey = System.getenv("OPENAI_API_KEY")?.trim().orEmpty()
    require(apiKey.isNotEmpty()) { "OPENAI_API_KEY is required for the live evaluation" }
    val model = System.getenv("OPENAI_MODEL")?.trim()?.takeIf(String::isNotEmpty) ?: OpenAiProperties.DEFAULT_MODEL
    val properties = OpenAiProperties(enabled = true, apiKey = apiKey, model = model)
    val configuration = OpenAiClientFactory()
    val client = configuration.openAiClient(properties)
    try {
        val mapper = ObjectMapper()
        val prompt = resourceText("/openai/transaction-recognition-prompt-v1.txt")
        val corpus = resourceJson(mapper, "/openai/recognition-corpus-v1.json")
        val recognizer = OpenAiTransactionRecognizer(client, properties, prompt, mapper)
        val validator = RecognitionValidator()
        val today = LocalDate.parse(corpus.required("today").textValue())
        val results =
            corpus.required("cases").map { case ->
                evaluateCase(case, today, recognizer, validator)
            }
        val report = evaluationReport(corpus.required("version").textValue(), model, results)
        val output = Path.of("build/reports/openai/model-evaluation.json")
        Files.createDirectories(output.parent)
        mapper.writerWithDefaultPrettyPrinter().writeValue(output.toFile(), report)
        printSummary(report, output)
        require(results.none { it.providerError != null }) { "The evaluation contains provider failures" }
    } finally {
        client.close()
    }
}

private fun evaluateCase(
    case: JsonNode,
    today: LocalDate,
    recognizer: OpenAiTransactionRecognizer,
    validator: RecognitionValidator,
): EvaluationCaseResult {
    val expected = expected(case)
    val input = RecognitionInput(case.required("message").textValue(), today, categories)
    return when (val response = recognizer.recognize(input)) {
        is RecognitionResponse.Failure -> {
            EvaluationCaseResult(
                id = case.required("id").textValue(),
                latencyMillis = response.latencyMillis,
                providerError = response.code,
            )
        }

        is RecognitionResponse.Success -> {
            val observation = response.observation
            val actual = actual(observation.candidate)
            val matches = score(expected, actual)
            val validation = validator.validate(input, observation.candidate)
            EvaluationCaseResult(
                id = case.required("id").textValue(),
                latencyMillis = observation.latencyMillis,
                inputTokens = observation.usage.inputTokens,
                outputTokens = observation.usage.outputTokens,
                expected = expected,
                actual = actual,
                matches = matches,
                safetyMatch =
                    if (expected.needsClarification) {
                        validation is RecognitionValidation.Invalid
                    } else {
                        validation is RecognitionValidation.Valid
                    },
            )
        }
    }
}

internal fun score(
    expected: EvaluationFields,
    actual: EvaluationFields,
): EvaluationMatches =
    EvaluationMatches(
        intent = expected.intent == actual.intent,
        operationType = expected.operationType == actual.operationType,
        amount = decimalEquals(expected.amount, actual.amount),
        currency = expected.currency == actual.currency,
        category = expected.category == actual.category,
        occurredOn = expected.occurredOn == actual.occurredOn,
        needsClarification = expected.needsClarification == actual.needsClarification,
    )

private fun expected(case: JsonNode) =
    EvaluationFields(
        intent = case.required("intent").textValue(),
        operationType = case.required("operationType").textValue(),
        amount = case.optionalText("amount"),
        currency = case.required("currency").textValue(),
        category = case.optionalText("category"),
        occurredOn = case.optionalText("occurredOn"),
        needsClarification = case.required("needsClarification").booleanValue(),
    )

private fun actual(candidate: RecognitionCandidate) =
    EvaluationFields(
        intent = candidate.intent.name,
        operationType = candidate.operationType.name,
        amount = candidate.amount.knownValue(),
        currency = candidate.currency.name,
        category =
            candidate.categoryId
                .knownValue()
                ?.let { id -> runCatching { UUID.fromString(id) }.getOrNull() }
                ?.let { id -> categories.singleOrNull { it.id == id }?.name ?: "INVALID_CATEGORY_ID" },
        occurredOn = candidate.occurredOn.knownValue(),
        needsClarification = candidate.needsClarification,
    )

private fun evaluationReport(
    corpusVersion: String,
    model: String,
    results: List<EvaluationCaseResult>,
): Map<String, Any> {
    val successful = results.filter { it.providerError == null }
    val latencies = results.map(EvaluationCaseResult::latencyMillis)
    return linkedMapOf(
        "executedAt" to Instant.now().toString(),
        "corpusVersion" to corpusVersion,
        "model" to model,
        "promptVersion" to OpenAiProperties.PROMPT_VERSION,
        "schemaVersion" to OpenAiProperties.SCHEMA_VERSION,
        "requestCount" to results.size,
        "providerFailureCount" to results.count { it.providerError != null },
        "quality" to
            linkedMapOf(
                "intent" to count(successful) { it.matches?.intent == true },
                "operationType" to count(successful) { it.matches?.operationType == true },
                "amount" to count(successful) { it.matches?.amount == true },
                "currency" to count(successful) { it.matches?.currency == true },
                "category" to count(successful) { it.matches?.category == true },
                "occurredOn" to count(successful) { it.matches?.occurredOn == true },
                "needsClarification" to count(successful) { it.matches?.needsClarification == true },
                "allFields" to count(successful) { it.matches?.all == true },
                "safetyBoundary" to count(successful) { it.safetyMatch == true },
            ),
        "latencyMillis" to
            linkedMapOf(
                "p50" to percentile(latencies, 0.50),
                "p95" to percentile(latencies, 0.95),
                "min" to latencies.minOrNull(),
                "max" to latencies.maxOrNull(),
            ),
        "usage" to
            linkedMapOf(
                "inputTokens" to results.sumOf(EvaluationCaseResult::inputTokens),
                "outputTokens" to results.sumOf(EvaluationCaseResult::outputTokens),
                "totalTokens" to results.sumOf { it.inputTokens + it.outputTokens },
            ),
        "cases" to results,
    )
}

private fun count(
    results: List<EvaluationCaseResult>,
    predicate: (EvaluationCaseResult) -> Boolean,
) = mapOf("matched" to results.count(predicate), "total" to results.size)

internal fun percentile(
    values: List<Long>,
    quantile: Double,
): Long? {
    if (values.isEmpty()) return null
    require(quantile > 0.0 && quantile <= 1.0)
    val sorted = values.sorted()
    val index = ceil(quantile * sorted.size).toInt().coerceAtLeast(1) - 1
    return sorted[index]
}

private fun decimalEquals(
    expected: String?,
    actual: String?,
): Boolean {
    if (expected == null || actual == null) return expected == actual
    return runCatching { BigDecimal(expected).compareTo(BigDecimal(actual)) == 0 }.getOrDefault(false)
}

private fun JsonNode.optionalText(field: String): String? = get(field)?.takeUnless(JsonNode::isNull)?.textValue()

private fun String.knownValue(): String? = takeUnless { it == "UNKNOWN" }

private fun resourceText(path: String): String =
    checkNotNull(OpenAiModelEvaluationMarker::class.java.getResourceAsStream(path)).use {
        it.bufferedReader().readText().trim()
    }

private fun resourceJson(
    mapper: ObjectMapper,
    path: String,
): JsonNode = checkNotNull(OpenAiModelEvaluationMarker::class.java.getResourceAsStream(path)).use(mapper::readTree)

private fun printSummary(
    report: Map<String, Any>,
    output: Path,
) {
    val mapper = ObjectMapper()
    println(mapper.writerWithDefaultPrettyPrinter().writeValueAsString(report.filterKeys { it != "cases" }))
    println("Safe case-level report: ${output.toAbsolutePath()}")
}

private object OpenAiModelEvaluationMarker

internal data class EvaluationFields(
    val intent: String,
    val operationType: String,
    val amount: String?,
    val currency: String,
    val category: String?,
    val occurredOn: String?,
    val needsClarification: Boolean,
)

internal data class EvaluationMatches(
    val intent: Boolean,
    val operationType: Boolean,
    val amount: Boolean,
    val currency: Boolean,
    val category: Boolean,
    val occurredOn: Boolean,
    val needsClarification: Boolean,
) {
    val all: Boolean
        get() = intent && operationType && amount && currency && category && occurredOn && needsClarification
}

private data class EvaluationCaseResult(
    val id: String,
    val latencyMillis: Long,
    val inputTokens: Long = 0,
    val outputTokens: Long = 0,
    val providerError: String? = null,
    val expected: EvaluationFields? = null,
    val actual: EvaluationFields? = null,
    val matches: EvaluationMatches? = null,
    val safetyMatch: Boolean? = null,
)
