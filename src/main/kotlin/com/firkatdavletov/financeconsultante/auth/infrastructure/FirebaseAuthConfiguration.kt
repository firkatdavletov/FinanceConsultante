package com.firkatdavletov.financeconsultante.auth.infrastructure

import com.firkatdavletov.financeconsultante.auth.application.FirebaseAccountDeleter
import com.firkatdavletov.financeconsultante.auth.application.FirebaseAuthenticationService
import com.firkatdavletov.financeconsultante.auth.application.FirebaseIdTokenVerifier
import com.firkatdavletov.financeconsultante.auth.application.FirebaseUserStore
import com.google.auth.oauth2.GoogleCredentials
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.auth.FirebaseAuth
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Lazy
import java.util.UUID

@ConfigurationProperties("app.firebase-auth")
data class FirebaseAuthProperties(
    val projectId: String = "",
)

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(FirebaseAuthProperties::class)
@ConditionalOnProperty(prefix = "app.firebase-auth", name = ["enabled"], havingValue = "true")
class FirebaseAuthConfiguration {
    @Bean(destroyMethod = "close")
    @Lazy
    fun firebaseAdminSession(properties: FirebaseAuthProperties): FirebaseAdminSession {
        require(properties.projectId.isNotBlank()) { "Firebase project ID is required" }
        require(System.getenv("FIREBASE_AUTH_EMULATOR_HOST").isNullOrBlank()) {
            "Firebase Auth emulator must not be used by the server"
        }
        val options =
            FirebaseOptions
                .builder()
                .setProjectId(properties.projectId)
                .setCredentials(GoogleCredentials.getApplicationDefault())
                .setConnectTimeout(TIMEOUT_MILLIS)
                .setReadTimeout(TIMEOUT_MILLIS)
                .build()
        val app = FirebaseApp.initializeApp(options, "financeconsultante-auth-${UUID.randomUUID()}")
        return FirebaseAdminSession(FirebaseAuth.getInstance(app), app::delete)
    }

    @Bean
    @ConditionalOnMissingBean(FirebaseIdTokenVerifier::class)
    fun firebaseIdTokenVerifier(session: FirebaseAdminSession): FirebaseAdminTokenVerifier =
        FirebaseAdminTokenVerifier(session.auth)

    @Bean
    @ConditionalOnMissingBean(FirebaseAccountDeleter::class)
    fun firebaseAccountDeleter(session: ObjectProvider<FirebaseAdminSession>): FirebaseAccountDeleter =
        FirebaseAdminAccountDeleter { session.getObject().auth }

    @Bean
    fun firebaseAuthenticationService(
        verifier: FirebaseIdTokenVerifier,
        users: FirebaseUserStore,
    ) = FirebaseAuthenticationService(verifier, users)

    companion object {
        private const val TIMEOUT_MILLIS = 10000
    }
}

class FirebaseAdminSession(
    val auth: FirebaseAuth,
    private val shutdown: () -> Unit,
) : AutoCloseable {
    override fun close() = shutdown()
}
