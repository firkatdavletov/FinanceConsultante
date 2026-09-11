package com.firkatdavletov.financeconsultante.budget.infrastructure

import com.firkatdavletov.financeconsultante.budget.application.DailyReportCoordinator
import com.firkatdavletov.financeconsultante.budget.application.DailyReportWorker
import com.firkatdavletov.financeconsultante.telegram.infrastructure.TelegramProperties
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.annotation.EnableScheduling
import org.springframework.scheduling.annotation.Scheduled
import java.time.Duration

private val DEFAULT_LEASE_DURATION: Duration = Duration.ofMinutes(2)
private val DEFAULT_RETRY_DELAY: Duration = Duration.ofSeconds(30)
private val MIN_LEASE_DURATION: Duration = Duration.ofSeconds(30)
private val MIN_RETRY_DELAY: Duration = Duration.ofSeconds(1)
private val MAX_DURATION: Duration = Duration.ofMinutes(10)

@ConfigurationProperties("app.notifications")
data class NotificationProperties(
    val enabled: Boolean = false,
    val leaseDuration: Duration = DEFAULT_LEASE_DURATION,
    val retryDelay: Duration = DEFAULT_RETRY_DELAY,
) {
    init {
        require(leaseDuration in MIN_LEASE_DURATION..MAX_DURATION)
        require(retryDelay in MIN_RETRY_DELAY..MAX_DURATION)
    }
}

@Configuration(proxyBeanMethods = false)
@EnableScheduling
@EnableConfigurationProperties(NotificationProperties::class, TelegramProperties::class)
@ConditionalOnProperty(
    prefix = "app",
    name = ["notifications.enabled", "telegram.enabled"],
    havingValue = "true",
)
class BudgetReportConfiguration {
    @Bean
    fun dailyReportWorker(
        telegram: TelegramProperties,
        notifications: NotificationProperties,
        coordinator: DailyReportCoordinator,
    ): DailyReportWorker {
        telegram.validateEnabled()
        return DailyReportWorker(
            telegram.botId,
            telegram.ownerId,
            coordinator,
            notifications.leaseDuration,
            notifications.retryDelay,
        )
    }

    @Bean
    fun dailyReportScheduler(worker: DailyReportWorker): DailyReportScheduler = DailyReportScheduler(worker)
}

class DailyReportScheduler(
    private val worker: DailyReportWorker,
) {
    @Scheduled(
        initialDelayString = "\${app.notifications.initial-delay:PT5S}",
        fixedDelayString = "\${app.notifications.poll-delay:PT1M}",
    )
    @Suppress("TooGenericExceptionCaught") // A durable job is retried without logging financial report text.
    fun tick() {
        try {
            worker.runOnce()
        } catch (failure: RuntimeException) {
            logger.error("Daily report tick failed ({})", failure.javaClass.simpleName)
        }
    }

    companion object {
        private val logger = LoggerFactory.getLogger(DailyReportScheduler::class.java)
    }
}
