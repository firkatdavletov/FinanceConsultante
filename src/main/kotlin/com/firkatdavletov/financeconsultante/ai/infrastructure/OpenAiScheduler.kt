package com.firkatdavletov.financeconsultante.ai.infrastructure

import com.firkatdavletov.financeconsultante.ai.application.OpenAiRecognitionWorker
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled

class OpenAiScheduler(
    private val worker: OpenAiRecognitionWorker,
) {
    @Scheduled(fixedDelayString = "\${app.openai.poll-delay:PT1S}", scheduler = "openAiTaskScheduler")
    @Suppress("TooGenericExceptionCaught") // The scheduler logs no provider payload and remains alive.
    fun process() {
        try {
            worker.runOnce()
        } catch (failure: RuntimeException) {
            logger.error("OpenAI recognition tick failed ({})", failure.javaClass.simpleName)
        }
    }

    companion object {
        private val logger = LoggerFactory.getLogger(OpenAiScheduler::class.java)
    }
}
