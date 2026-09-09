package com.firkatdavletov.financeconsultante.telegram

import com.firkatdavletov.financeconsultante.telegram.application.InboxStore
import com.firkatdavletov.financeconsultante.telegram.application.LedgerHandler
import com.firkatdavletov.financeconsultante.telegram.application.OwnerAccess
import com.firkatdavletov.financeconsultante.telegram.application.SetupStore
import com.firkatdavletov.financeconsultante.telegram.application.TelegramInboxProcessor
import com.firkatdavletov.financeconsultante.telegram.infrastructure.TelegramProperties
import com.firkatdavletov.financeconsultante.telegram.infrastructure.TelegramStorageConfiguration
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import java.time.Clock

@TestConfiguration(proxyBeanMethods = false)
@Import(TelegramStorageConfiguration::class)
@EnableConfigurationProperties(TelegramProperties::class)
class TelegramTestConfiguration {
    @Bean
    fun telegramTestProcessor(
        inbox: InboxStore,
        setup: SetupStore,
        clock: Clock,
        ledger: LedgerHandler,
    ): TelegramInboxProcessor = TelegramInboxProcessor(inbox, setup, OwnerAccess(OWNER), clock, ledger)

    companion object {
        const val OWNER = 555L
        const val BOT = 123456L
    }
}
