package com.firkatdavletov.financeconsultante.mobileapi.infrastructure

import com.firkatdavletov.financeconsultante.mobileapi.application.LegacyFirebaseBinding
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.util.UUID

@Configuration(proxyBeanMethods = false)
class LegacyFirebaseBindingConfiguration {
    @Bean
    fun legacyFirebaseBinding(
        @Value("\${FIREBASE_LEGACY_UID:}") uid: String,
        @Value("\${FIREBASE_LEGACY_MEMBER_ID:}") memberId: String,
    ) = LegacyFirebaseBinding(uid, memberId.takeIf(String::isNotBlank)?.let(UUID::fromString))
}
