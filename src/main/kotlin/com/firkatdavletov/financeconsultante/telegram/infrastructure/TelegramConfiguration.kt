package com.firkatdavletov.financeconsultante.telegram.infrastructure

import com.firkatdavletov.financeconsultante.access.application.FamilyBudgetAccess
import com.firkatdavletov.financeconsultante.access.application.FamilyBudgetService
import com.firkatdavletov.financeconsultante.advice.application.PurchaseAdviceService
import com.firkatdavletov.financeconsultante.ai.application.OpenAiRecognitionRouter
import com.firkatdavletov.financeconsultante.analytics.application.AnalyticsReportService
import com.firkatdavletov.financeconsultante.analytics.application.BudgetForecastService
import com.firkatdavletov.financeconsultante.budget.application.BudgetReportService
import com.firkatdavletov.financeconsultante.goals.application.SavingsGoalService
import com.firkatdavletov.financeconsultante.ledger.application.AccountingService
import com.firkatdavletov.financeconsultante.ledger.application.CategoryManagement
import com.firkatdavletov.financeconsultante.ledger.application.EntryCorrections
import com.firkatdavletov.financeconsultante.ledger.application.LedgerOperations
import com.firkatdavletov.financeconsultante.ledger.application.TransactionDraftRepository
import com.firkatdavletov.financeconsultante.recurring.application.RecurringExpenseAnalysisService
import com.firkatdavletov.financeconsultante.recurring.application.RecurringPlanService
import com.firkatdavletov.financeconsultante.telegram.application.CategoryDialogRepository
import com.firkatdavletov.financeconsultante.telegram.application.EntryCorrectionDialogRepository
import com.firkatdavletov.financeconsultante.telegram.application.InboxStore
import com.firkatdavletov.financeconsultante.telegram.application.LedgerHandler
import com.firkatdavletov.financeconsultante.telegram.application.OutboxStore
import com.firkatdavletov.financeconsultante.telegram.application.OwnerAccess
import com.firkatdavletov.financeconsultante.telegram.application.PollingStore
import com.firkatdavletov.financeconsultante.telegram.application.SetupStore
import com.firkatdavletov.financeconsultante.telegram.application.TelegramClient
import com.firkatdavletov.financeconsultante.telegram.application.TelegramCorrectionHandler
import com.firkatdavletov.financeconsultante.telegram.application.TelegramDeliveryWorker
import com.firkatdavletov.financeconsultante.telegram.application.TelegramFamilyHandler
import com.firkatdavletov.financeconsultante.telegram.application.TelegramForecastHandler
import com.firkatdavletov.financeconsultante.telegram.application.TelegramGoalsHandler
import com.firkatdavletov.financeconsultante.telegram.application.TelegramInboxProcessor
import com.firkatdavletov.financeconsultante.telegram.application.TelegramLedgerHandler
import com.firkatdavletov.financeconsultante.telegram.application.TelegramMenuRegistrar
import com.firkatdavletov.financeconsultante.telegram.application.TelegramPoller
import com.firkatdavletov.financeconsultante.telegram.application.TelegramPurchaseHandler
import com.firkatdavletov.financeconsultante.telegram.application.TelegramRecurringHandler
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Import
import org.springframework.http.client.JdkClientHttpRequestFactory
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.scheduling.annotation.EnableScheduling
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler
import org.springframework.web.client.RestClient
import java.net.http.HttpClient
import java.time.Clock
import java.time.Duration

@Configuration(proxyBeanMethods = false)
@EnableScheduling
@EnableConfigurationProperties(TelegramProperties::class)
@ConditionalOnProperty(prefix = "app.telegram", name = ["enabled"], havingValue = "true")
@Import(TelegramStorageConfiguration::class)
class TelegramConfiguration {
    @Bean
    fun telegramClient(properties: TelegramProperties): TelegramClient {
        properties.validateEnabled()
        val http =
            HttpClient
                .newBuilder()
                .connectTimeout(
                    Duration.ofSeconds(5),
                ).followRedirects(HttpClient.Redirect.NEVER)
                .build()
        val factory = JdkClientHttpRequestFactory(http)
        factory.setReadTimeout(properties.requestTimeout)
        // A dedicated client avoids exporting the credential-bearing Telegram URL through HTTP observations.
        val client =
            RestClient
                .builder()
                .baseUrl(properties.baseUrl)
                .requestFactory(factory)
                .build()
        return RestTelegramClient(client, properties)
    }

    @Bean
    fun telegramOwnerAccess(
        properties: TelegramProperties,
        families: FamilyBudgetAccess,
    ): OwnerAccess = OwnerAccess(properties.ownerId, families)

    @Bean
    fun telegramPoller(
        properties: TelegramProperties,
        access: OwnerAccess,
        client: TelegramClient,
        store: PollingStore,
    ): TelegramPoller = TelegramPoller(properties.botId, access, client, store)

    @Bean
    fun telegramInboxProcessor(
        inbox: InboxStore,
        setup: SetupStore,
        access: OwnerAccess,
        clock: Clock,
        ledger: LedgerHandler,
        recognition: ObjectProvider<OpenAiRecognitionRouter>,
    ): TelegramInboxProcessor = TelegramInboxProcessor(inbox, setup, access, clock, ledger, recognition.ifAvailable)

    @Bean
    fun telegramDeliveryWorker(
        properties: TelegramProperties,
        access: OwnerAccess,
        client: TelegramClient,
        store: OutboxStore,
    ): TelegramDeliveryWorker = TelegramDeliveryWorker(properties.botId, access, client, store)

    @Bean
    fun telegramMenuRegistrar(client: TelegramClient): TelegramMenuRegistrar = TelegramMenuRegistrar(client)

    @Bean
    fun telegramScheduler(
        properties: TelegramProperties,
        poller: TelegramPoller,
        processor: TelegramInboxProcessor,
        delivery: TelegramDeliveryWorker,
        menu: TelegramMenuRegistrar,
        inbox: InboxStore,
    ): TelegramScheduler = TelegramScheduler(properties.botId, poller, processor, delivery, menu, inbox)

    @Bean
    fun telegramTaskScheduler(): ThreadPoolTaskScheduler =
        ThreadPoolTaskScheduler().apply {
            poolSize = TELEGRAM_WORKERS
            setThreadNamePrefix("telegram-")
            setWaitForTasksToCompleteOnShutdown(true)
            setAwaitTerminationSeconds(SHUTDOWN_SECONDS)
        }

    companion object {
        private const val TELEGRAM_WORKERS = 3
        private const val SHUTDOWN_SECONDS = 40
    }
}

class TelegramStorageConfiguration {
    @Bean
    fun ledgerHandler(
        access: OwnerAccess,
        families: FamilyBudgetService,
        operations: LedgerOperations,
        drafts: TransactionDraftRepository,
        clock: Clock,
        categories: CategoryManagement,
        corrections: EntryCorrections,
        recurringPlans: RecurringPlanService,
        recurringAnalysis: RecurringExpenseAnalysisService,
        savingsGoals: SavingsGoalService,
        budgetReports: BudgetReportService,
        analyticsReports: AnalyticsReportService,
        forecasts: BudgetForecastService,
        purchaseAdvice: PurchaseAdviceService,
        categoryDialogs: CategoryDialogRepository,
        entryCorrectionDialogs: EntryCorrectionDialogRepository,
    ): LedgerHandler =
        TelegramLedgerHandler(
            access,
            operations,
            drafts,
            clock,
            categories,
            TelegramCorrectionHandler(
                access,
                operations,
                corrections,
                categories,
                clock,
                categoryDialogs,
                entryCorrectionDialogs,
            ),
            TelegramRecurringHandler(recurringPlans, clock, recurringAnalysis),
            TelegramGoalsHandler(savingsGoals, clock),
            budgetReports,
            analyticsReports,
            TelegramForecastHandler(forecasts),
            TelegramPurchaseHandler(purchaseAdvice),
            TelegramFamilyHandler(families),
        )

    @Bean
    fun pollingStore(
        jdbc: JdbcClient,
        properties: TelegramProperties,
    ): PollingStore = JdbcPollingStore(jdbc, properties.leaseDuration)

    @Bean
    fun inboxStore(jdbc: JdbcClient): InboxStore = JdbcInboxStore(jdbc)

    @Bean
    fun outboxStore(
        jdbc: JdbcClient,
        properties: TelegramProperties,
    ): OutboxStore = JdbcOutboxStore(jdbc, properties.leaseDuration)

    @Bean
    fun setupStore(
        jdbc: JdbcClient,
        accounting: AccountingService,
    ): SetupStore = JdbcSetupStore(jdbc, accounting)
}
