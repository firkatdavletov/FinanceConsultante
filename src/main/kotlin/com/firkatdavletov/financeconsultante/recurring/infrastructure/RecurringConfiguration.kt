package com.firkatdavletov.financeconsultante.recurring.infrastructure

import com.firkatdavletov.financeconsultante.recurring.application.RecurringPlanService
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.annotation.EnableScheduling
import org.springframework.scheduling.annotation.Scheduled

@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnProperty(
    prefix = "app.recurring",
    name = ["scheduler-enabled"],
    havingValue = "true",
    matchIfMissing = true,
)
class RecurringConfiguration {
    @Bean
    fun recurringOccurrenceScheduler(service: RecurringPlanService): RecurringOccurrenceScheduler =
        RecurringOccurrenceScheduler(service)
}

class RecurringOccurrenceScheduler(
    private val service: RecurringPlanService,
) {
    @Scheduled(
        initialDelayString = "\${app.recurring.initial-delay:PT5S}",
        fixedDelayString = "\${app.recurring.poll-delay:PT1H}",
    )
    @Suppress("TooGenericExceptionCaught") // A failed database tick is retried without leaking financial data.
    fun generate() {
        try {
            service.generateAll()
        } catch (failure: RuntimeException) {
            logger.error("Recurring occurrence generation failed ({})", failure.javaClass.simpleName)
        }
    }

    companion object {
        private val logger = LoggerFactory.getLogger(RecurringOccurrenceScheduler::class.java)
    }
}
