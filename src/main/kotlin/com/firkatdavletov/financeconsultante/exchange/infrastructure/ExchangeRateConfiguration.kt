package com.firkatdavletov.financeconsultante.exchange.infrastructure

import com.firkatdavletov.financeconsultante.exchange.application.ExchangeRateJobRepository
import com.firkatdavletov.financeconsultante.exchange.application.ExchangeRateWorker
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.client.SimpleClientHttpRequestFactory
import org.springframework.scheduling.annotation.EnableScheduling
import org.springframework.web.client.RestClient
import java.time.Clock

@Configuration(proxyBeanMethods = false)
@EnableScheduling
@EnableConfigurationProperties(CurrencyLayerProperties::class)
@ConditionalOnProperty(prefix = "app.exchange-rates", name = ["enabled"], havingValue = "true")
class ExchangeRateConfiguration {
    @Bean
    fun currencyLayerProvider(
        builder: RestClient.Builder,
        properties: CurrencyLayerProperties,
        clock: Clock,
    ): CurrencyLayerExchangeRateProvider {
        properties.validateEnabled()
        val requestFactory = SimpleClientHttpRequestFactory()
        requestFactory.setConnectTimeout(properties.connectTimeout)
        requestFactory.setReadTimeout(properties.readTimeout)
        val client =
            builder
                .clone()
                .baseUrl(properties.baseUrl)
                .requestFactory(requestFactory)
                .build()
        return CurrencyLayerExchangeRateProvider(client, properties, clock)
    }

    @Bean
    fun exchangeRateWorker(
        jobs: ExchangeRateJobRepository,
        provider: CurrencyLayerExchangeRateProvider,
        properties: CurrencyLayerProperties,
        clock: Clock,
    ): ExchangeRateWorker =
        ExchangeRateWorker(
            jobs,
            provider,
            clock,
            properties.zoneId,
            properties.fetchTime,
            properties.leaseDuration,
            properties.retryBaseDelay,
            properties.retryMaxDelay,
        )

    @Bean
    fun exchangeRateScheduler(worker: ExchangeRateWorker): ExchangeRateScheduler = ExchangeRateScheduler(worker)
}
