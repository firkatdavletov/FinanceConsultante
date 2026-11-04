package com.firkatdavletov.financeconsultante.ai.application

import com.firkatdavletov.financeconsultante.ai.domain.RecognitionCandidate
import com.firkatdavletov.financeconsultante.ai.domain.RecognitionInput

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
