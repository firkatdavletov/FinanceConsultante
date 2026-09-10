package com.firkatdavletov.financeconsultante.ai.application

import com.firkatdavletov.financeconsultante.ai.domain.RecognitionInput
import com.firkatdavletov.financeconsultante.ai.domain.RecognitionValidation
import com.firkatdavletov.financeconsultante.ai.domain.RecognitionValidator
import com.firkatdavletov.financeconsultante.ledger.application.CategoryManagement
import com.firkatdavletov.financeconsultante.ledger.application.LedgerOperations
import com.firkatdavletov.financeconsultante.ledger.domain.ClarificationReason
import com.firkatdavletov.financeconsultante.ledger.domain.MessageParseResult
import com.firkatdavletov.financeconsultante.ledger.domain.TransactionMessageParser
import com.firkatdavletov.financeconsultante.telegram.application.InboxMessage
import com.firkatdavletov.financeconsultante.telegram.application.InboxStore
import com.firkatdavletov.financeconsultante.telegram.application.LedgerHandler
import com.firkatdavletov.financeconsultante.telegram.application.OwnerAccess
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.time.Clock
import java.time.Duration

class OpenAiRecognitionRouter(
    private val jobs: RecognitionJobRepository,
    private val operations: LedgerOperations,
    private val categories: CategoryManagement,
    private val clock: Clock,
    private val modelId: String,
    private val promptVersion: String,
    private val schemaVersion: String,
) {
    @Suppress("ReturnCount") // Eligibility exits before any durable or external effect.
    fun enqueueIfEligible(
        message: InboxMessage,
        botId: Long,
    ): Boolean {
        val sentAt = message.sentAt ?: return false
        if (sentAt > clock.instant() || operations.owned(message.ownerId) == null) return false
        val today = sentAt.atZone(LedgerOperations.ZONE).toLocalDate()
        val parsed = TransactionMessageParser(categories.dictionary(message.ownerId)).parse(message.text, today)
        val reason = (parsed as? MessageParseResult.NeedsClarification)?.reason ?: return false
        if (reason !in ELIGIBLE_REASONS) return false
        return jobs.enqueue(message, botId, modelId, promptVersion, schemaVersion)
    }

    companion object {
        private val ELIGIBLE_REASONS =
            setOf(
                ClarificationReason.INVALID_FORMAT,
                ClarificationReason.UNKNOWN_CATEGORY,
            )
    }
}

enum class RecognitionRunResult {
    IDLE,
    COMPLETED,
    FALLBACK,
}

class OpenAiRecognitionWorker(
    private val jobs: RecognitionJobRepository,
    private val provider: TransactionRecognitionProvider,
    private val validator: RecognitionValidator,
    private val ledger: LedgerHandler,
    private val inbox: InboxStore,
    private val access: OwnerAccess,
    transactionManager: PlatformTransactionManager,
    private val clock: Clock,
    private val leaseDuration: Duration,
    private val requestUnits: Int,
    private val dailyRequestLimit: Int,
) {
    private val transactions = TransactionTemplate(transactionManager)

    fun runOnce(): RecognitionRunResult {
        val now = clock.instant()
        val job =
            jobs.claim(
                now,
                now.atZone(LedgerOperations.ZONE).toLocalDate(),
                leaseDuration,
                requestUnits,
                dailyRequestLimit,
            ) ?: return RecognitionRunResult.IDLE
        val response =
            if (!access.owns(job.message.ownerId)) {
                RecognitionResponse.Failure("access_revoked", 0)
            } else if (job.networkAllowed) {
                val sentAt = checkNotNull(job.message.sentAt)
                provider.recognize(
                    RecognitionInput(
                        job.message.text,
                        sentAt.atZone(LedgerOperations.ZONE).toLocalDate(),
                        job.categories,
                    ),
                )
            } else {
                RecognitionResponse.Failure("daily_budget_exhausted", 0)
            }
        return checkNotNull(transactions.execute { finish(job, response) })
    }

    private fun finish(
        job: RecognitionJob,
        response: RecognitionResponse,
    ): RecognitionRunResult {
        if (!access.owns(job.message.ownerId)) {
            inbox.complete(job.message, job.botId, null)
            jobs.complete(
                job,
                RecognitionCompletion(RecognitionJobStatus.FAILED, "access_revoked"),
                clock.instant(),
            )
            return RecognitionRunResult.FALLBACK
        }
        return when (response) {
            is RecognitionResponse.Failure -> fallback(job, response)
            is RecognitionResponse.Success -> finishSuccess(job, response.observation)
        }
    }

    private fun finishSuccess(
        job: RecognitionJob,
        observation: RecognitionObservation,
    ): RecognitionRunResult {
        val input =
            RecognitionInput(
                job.message.text,
                checkNotNull(job.message.sentAt).atZone(LedgerOperations.ZONE).toLocalDate(),
                job.categories,
            )
        val validation = validator.validate(input, observation.candidate)
        val reply =
            when (validation) {
                is RecognitionValidation.Valid -> ledger.recognized(job.message, job.botId, validation.proposal)
                is RecognitionValidation.Invalid -> fallbackReply(job)
            }
        inbox.completeReply(job.message, job.botId, reply)
        val invalidCode = (validation as? RecognitionValidation.Invalid)?.code
        jobs.complete(
            job,
            RecognitionCompletion(
                status = if (invalidCode == null) RecognitionJobStatus.COMPLETED else RecognitionJobStatus.FAILED,
                errorCode = invalidCode?.let { "validation_$it" },
                requestId = observation.requestId,
                responseId = observation.responseId,
                usage = observation.usage,
                latencyMillis = observation.latencyMillis,
            ),
            clock.instant(),
        )
        return if (invalidCode == null) RecognitionRunResult.COMPLETED else RecognitionRunResult.FALLBACK
    }

    private fun fallback(
        job: RecognitionJob,
        failure: RecognitionResponse.Failure,
    ): RecognitionRunResult {
        inbox.completeReply(job.message, job.botId, fallbackReply(job))
        jobs.complete(
            job,
            RecognitionCompletion(
                status =
                    if (failure.code == "daily_budget_exhausted") {
                        RecognitionJobStatus.BUDGET_EXHAUSTED
                    } else {
                        RecognitionJobStatus.FAILED
                    },
                errorCode = failure.code,
                latencyMillis = failure.latencyMillis,
            ),
            clock.instant(),
        )
        return RecognitionRunResult.FALLBACK
    }

    private fun fallbackReply(job: RecognitionJob) =
        ledger.handle(job.message, job.botId)
            ?: error("An OpenAI-routed message must have a local fallback")
}
