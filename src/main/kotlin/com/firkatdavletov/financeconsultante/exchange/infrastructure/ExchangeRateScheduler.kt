package com.firkatdavletov.financeconsultante.exchange.infrastructure

import com.firkatdavletov.financeconsultante.exchange.application.ExchangeRateRunResult
import com.firkatdavletov.financeconsultante.exchange.application.ExchangeRateWorker
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled

class ExchangeRateScheduler(
    private val worker: ExchangeRateWorker,
) {
    @Scheduled(
        initialDelayString = "\${app.exchange-rates.initial-delay:PT5S}",
        fixedDelayString = "\${app.exchange-rates.poll-delay:PT1M}",
    )
    fun tick() {
        when (worker.runOnce()) {
            ExchangeRateRunResult.IDLE -> Unit
            ExchangeRateRunResult.COMPLETED -> logger.info("Daily RUB/KZT rate fixed")
            ExchangeRateRunResult.RETRY_SCHEDULED -> logger.warn("Daily RUB/KZT rate retry scheduled")
        }
    }

    companion object {
        private val logger = LoggerFactory.getLogger(ExchangeRateScheduler::class.java)
    }
}
