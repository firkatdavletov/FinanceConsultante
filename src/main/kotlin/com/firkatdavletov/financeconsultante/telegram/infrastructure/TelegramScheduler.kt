package com.firkatdavletov.financeconsultante.telegram.infrastructure

import com.firkatdavletov.financeconsultante.telegram.application.InboxProcessingFailure
import com.firkatdavletov.financeconsultante.telegram.application.InboxStore
import com.firkatdavletov.financeconsultante.telegram.application.TelegramDeliveryWorker
import com.firkatdavletov.financeconsultante.telegram.application.TelegramInboxProcessor
import com.firkatdavletov.financeconsultante.telegram.application.TelegramPoller
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled

class TelegramScheduler(
    private val botId: Long,
    private val poller: TelegramPoller,
    private val processor: TelegramInboxProcessor,
    private val delivery: TelegramDeliveryWorker,
    private val inbox: InboxStore,
) {
    @Scheduled(fixedDelayString = "\${app.telegram.poll-delay:PT1S}", scheduler = "telegramTaskScheduler")
    fun poll() = safely("poll") { poller.pollOnce() }

    @Scheduled(fixedDelayString = "\${app.telegram.processing-delay:PT0.2S}", scheduler = "telegramTaskScheduler")
    fun process() =
        safely("inbox") {
            try {
                processor.processOne(botId)
            } catch (failure: InboxProcessingFailure) {
                inbox.failedAttempt(botId, failure.messageId)
                throw failure
            }
        }

    @Scheduled(fixedDelayString = "\${app.telegram.delivery-delay:PT1S}", scheduler = "telegramTaskScheduler")
    fun deliver() = safely("outbox") { delivery.deliverOne() }

    @Suppress("TooGenericExceptionCaught") // Scheduler boundary must sanitize all failures and keep running.
    private fun safely(
        stage: String,
        action: () -> Unit,
    ) {
        try {
            action()
        } catch (failure: RuntimeException) {
            // SQL/HTTP exception messages can contain private input. Log only a safe technical classification.
            logger.error("Telegram {} tick failed ({})", stage, failure.javaClass.simpleName)
        }
    }

    companion object {
        private val logger = LoggerFactory.getLogger(TelegramScheduler::class.java)
    }
}
