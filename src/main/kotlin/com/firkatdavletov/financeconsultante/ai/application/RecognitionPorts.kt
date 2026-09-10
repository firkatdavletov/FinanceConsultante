package com.firkatdavletov.financeconsultante.ai.application

import com.firkatdavletov.financeconsultante.ai.domain.RecognitionCandidate
import com.firkatdavletov.financeconsultante.ai.domain.RecognitionCategory
import com.firkatdavletov.financeconsultante.ai.domain.RecognitionInput
import com.firkatdavletov.financeconsultante.telegram.application.InboxMessage
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

data class RecognitionUsage(
    val inputTokens: Long,
    val outputTokens: Long,
) {
    val totalTokens: Long get() = inputTokens + outputTokens

    init {
        require(inputTokens >= 0 && outputTokens >= 0)
    }
}

data class RecognitionObservation(
    val candidate: RecognitionCandidate,
    val requestId: String?,
    val responseId: String,
    val usage: RecognitionUsage,
    val latencyMillis: Long,
)

sealed interface RecognitionResponse {
    data class Success(
        val observation: RecognitionObservation,
    ) : RecognitionResponse

    data class Failure(
        val code: String,
        val latencyMillis: Long,
    ) : RecognitionResponse
}

fun interface TransactionRecognitionProvider {
    fun recognize(input: RecognitionInput): RecognitionResponse
}

data class RecognitionJob(
    val id: UUID,
    val message: InboxMessage,
    val botId: Long,
    val categories: List<RecognitionCategory>,
    val modelId: String,
    val promptVersion: String,
    val schemaVersion: String,
    val attemptCount: Int,
    val usageDate: LocalDate?,
    val leaseToken: UUID,
    val networkAllowed: Boolean,
)

data class RecognitionCompletion(
    val status: RecognitionJobStatus,
    val errorCode: String?,
    val requestId: String? = null,
    val responseId: String? = null,
    val usage: RecognitionUsage? = null,
    val latencyMillis: Long? = null,
)

enum class RecognitionJobStatus {
    COMPLETED,
    FAILED,
    BUDGET_EXHAUSTED,
}

interface RecognitionJobRepository {
    fun enqueue(
        message: InboxMessage,
        botId: Long,
        modelId: String,
        promptVersion: String,
        schemaVersion: String,
    ): Boolean

    fun claim(
        now: Instant,
        usageDate: LocalDate,
        leaseDuration: Duration,
        requestUnits: Int,
        dailyRequestLimit: Int,
    ): RecognitionJob?

    fun complete(
        job: RecognitionJob,
        completion: RecognitionCompletion,
        now: Instant,
    )
}
