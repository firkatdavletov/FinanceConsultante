package com.firkatdavletov.financeconsultante.recurring.infrastructure

import com.firkatdavletov.financeconsultante.recurring.domain.RecurrenceDetectionPolicy
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(RecurringAnalysisProperties::class)
class RecurringAnalysisConfiguration {
    @Bean
    fun recurrenceDetectionPolicy(properties: RecurringAnalysisProperties): RecurrenceDetectionPolicy =
        properties.toPolicy()
}

@ConfigurationProperties("app.recurring.analysis")
data class RecurringAnalysisProperties(
    val minimumOccurrences: Int = 3,
    val lookbackDays: Int = 400,
    val weeklyIntervalDays: Int = 7,
    val weeklyToleranceDays: Int = 1,
    val monthlyIntervalDays: Int = 30,
    val monthlyToleranceDays: Int = 4,
    val amountTolerancePercent: Int = 20,
    val priceIncreasePercent: Int = 5,
    val allowedSkippedPeriods: Int = 1,
) {
    fun toPolicy() =
        RecurrenceDetectionPolicy(
            minimumOccurrences,
            lookbackDays,
            weeklyIntervalDays,
            weeklyToleranceDays,
            monthlyIntervalDays,
            monthlyToleranceDays,
            amountTolerancePercent,
            priceIncreasePercent,
            allowedSkippedPeriods,
        )
}
