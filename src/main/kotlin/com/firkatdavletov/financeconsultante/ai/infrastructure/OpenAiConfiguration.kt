package com.firkatdavletov.financeconsultante.ai.infrastructure

import com.firkatdavletov.financeconsultante.ai.application.OpenAiRecognitionRouter
import com.firkatdavletov.financeconsultante.ai.application.OpenAiRecognitionWorker
import com.firkatdavletov.financeconsultante.ai.application.RecognitionJobRepository
import com.firkatdavletov.financeconsultante.ai.domain.RecognitionValidator
import com.firkatdavletov.financeconsultante.ledger.application.CategoryManagement
import com.firkatdavletov.financeconsultante.ledger.application.LedgerOperations
import com.firkatdavletov.financeconsultante.telegram.application.InboxStore
import com.firkatdavletov.financeconsultante.telegram.application.LedgerHandler
import com.firkatdavletov.financeconsultante.telegram.application.OwnerAccess
import com.openai.client.OpenAIClient
import com.openai.client.okhttp.OpenAIOkHttpClient
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.io.ClassPathResource
import org.springframework.scheduling.annotation.EnableScheduling
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler
import org.springframework.transaction.PlatformTransactionManager
import java.time.Clock

@Configuration(proxyBeanMethods = false)
@EnableScheduling
@EnableConfigurationProperties(OpenAiProperties::class)
@ConditionalOnProperty(prefix = "app.openai", name = ["enabled"], havingValue = "true")
@ConditionalOnProperty(prefix = "app.telegram", name = ["enabled"], havingValue = "true")
class OpenAiConfiguration {
    @Bean(destroyMethod = "close")
    fun openAiClient(properties: OpenAiProperties): OpenAIClient {
        properties.validateEnabled()
        return OpenAIOkHttpClient
            .builder()
            .apiKey(properties.apiKey)
            .baseUrl(properties.baseUrl)
            .timeout(properties.requestTimeout)
            .maxRetries(properties.maxRetries)
            .responseValidation(true)
            .build()
    }

    @Bean
    fun transactionRecognizer(
        client: OpenAIClient,
        properties: OpenAiProperties,
    ): OpenAiTransactionRecognizer = OpenAiTransactionRecognizer(client, properties, recognitionPrompt())

    @Bean
    fun openAiRecognitionRouter(
        jobs: RecognitionJobRepository,
        operations: LedgerOperations,
        categories: CategoryManagement,
        clock: Clock,
        properties: OpenAiProperties,
    ): OpenAiRecognitionRouter =
        OpenAiRecognitionRouter(
            jobs,
            operations,
            categories,
            clock,
            properties.model,
            OpenAiProperties.PROMPT_VERSION,
            OpenAiProperties.SCHEMA_VERSION,
        )

    @Bean
    fun openAiRecognitionWorker(
        jobs: RecognitionJobRepository,
        provider: OpenAiTransactionRecognizer,
        ledger: LedgerHandler,
        inbox: InboxStore,
        access: OwnerAccess,
        transactionManager: PlatformTransactionManager,
        clock: Clock,
        properties: OpenAiProperties,
    ): OpenAiRecognitionWorker =
        OpenAiRecognitionWorker(
            jobs,
            provider,
            RecognitionValidator(),
            ledger,
            inbox,
            access,
            transactionManager,
            clock,
            properties.leaseDuration,
            properties.requestUnits,
            properties.dailyRequestLimit,
        )

    @Bean
    fun openAiScheduler(worker: OpenAiRecognitionWorker): OpenAiScheduler = OpenAiScheduler(worker)

    @Bean
    fun openAiTaskScheduler(): ThreadPoolTaskScheduler =
        ThreadPoolTaskScheduler().apply {
            poolSize = 1
            setThreadNamePrefix("openai-")
            setWaitForTasksToCompleteOnShutdown(true)
            setAwaitTerminationSeconds(SCHEDULER_SHUTDOWN_SECONDS)
        }

    private fun recognitionPrompt(): String =
        ClassPathResource("openai/transaction-recognition-prompt-v1.txt")
            .inputStream
            .bufferedReader()
            .use { it.readText().trim() }

    companion object {
        private const val SCHEDULER_SHUTDOWN_SECONDS = 30
    }
}
