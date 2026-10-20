package com.firkatdavletov.financeconsultante.auth

import com.firkatdavletov.financeconsultante.PostgresIntegrationTest
import com.firkatdavletov.financeconsultante.auth.application.FirebaseAuthenticationService
import com.firkatdavletov.financeconsultante.auth.application.FirebaseAuthenticationUnavailable
import com.firkatdavletov.financeconsultante.auth.application.InvalidFirebaseToken
import com.firkatdavletov.financeconsultante.auth.application.VerifiedFirebaseIdentity
import com.firkatdavletov.financeconsultante.auth.infrastructure.FirebaseAdminTokenVerifier
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.TestPropertySource
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@AutoConfigureMockMvc
@TestPropertySource(properties = ["app.firebase-auth.enabled=true", "app.firebase-auth.project-id=synthetic-project"])
@Suppress("MagicNumber", "LongMethod")
class FirebaseAuthenticationApiTests
    @Autowired
    constructor(
        private val service: FirebaseAuthenticationService,
        private val jdbc: JdbcTemplate,
        private val mvc: MockMvc,
    ) : PostgresIntegrationTest() {
        @MockitoBean private lateinit var verifier: FirebaseAdminTokenVerifier
        private val uid = "synthetic-${UUID.randomUUID()}"
        private val token = "synthetic-profile-token"

        @AfterEach fun cleanup() {
            jdbc.update("DELETE FROM firebase_user WHERE firebase_uid IN (?, ?)", uid, "$uid-second")
        }

        @Test fun `post returns a stable user across retries and refreshed tokens without creating a budget`() {
            val before = jdbc.queryForObject("SELECT count(*) FROM member", Long::class.java)
            Mockito
                .`when`(
                    verifier.verify(token),
                ).thenReturn(VerifiedFirebaseIdentity(uid, "First", "user@example.test"))
            val first = service.authenticate("Bearer $token")
            Mockito
                .`when`(
                    verifier.verify("synthetic-refreshed"),
                ).thenReturn(VerifiedFirebaseIdentity(uid, "Updated", null))
            mvc
                .perform(
                    post("/api/v4/auth/firebase")
                        .header("Authorization", "Bearer synthetic-refreshed")
                        .contentType("application/json")
                        .content("""{"uid":"ignored-client-uid"}"""),
                ).andExpect(status().isOk)
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.id").value(first.id.toString()))
                .andExpect(jsonPath("$.firebaseUid").value(uid))
                .andExpect(jsonPath("$.displayName").value("Updated"))
            assertThat(count()).isEqualTo(1)
            assertThat(jdbc.queryForObject("SELECT count(*) FROM member", Long::class.java)).isEqualTo(before)
            Mockito
                .`when`(
                    verifier.verify("synthetic-second"),
                ).thenReturn(VerifiedFirebaseIdentity("$uid-second", null, null))
            assertThat(service.authenticate("Bearer synthetic-second").id).isNotEqualTo(first.id)
        }

        @Test fun `missing static and revoked credentials return 401 and do not create users`() {
            mvc.perform(post("/api/v4/auth/firebase")).andExpect(status().isUnauthorized)
            Mockito.`when`(verifier.verify(token)).thenThrow(InvalidFirebaseToken())
            mvc
                .perform(post("/api/v4/auth/firebase").header("Authorization", "Bearer $token"))
                .andExpect(status().isUnauthorized)
                .andExpect(header().string("WWW-Authenticate", "Bearer"))
                .andExpect(jsonPath("$.code").value("unauthorized"))
            Mockito.`when`(verifier.verify("synthetic-static-token")).thenThrow(InvalidFirebaseToken())
            mvc
                .perform(post("/api/v4/auth/firebase").header("Authorization", "Bearer synthetic-static-token"))
                .andExpect(status().isUnauthorized)
            assertThat(count()).isZero()
        }

        @Test fun `Firebase outage returns a safe retryable 503 without a user record`() {
            Mockito.`when`(verifier.verify(token)).thenThrow(FirebaseAuthenticationUnavailable())
            mvc
                .perform(post("/api/v4/auth/firebase").header("Authorization", "Bearer $token"))
                .andExpect(status().isServiceUnavailable)
                .andExpect(jsonPath("$.code").value("auth_unavailable"))
            assertThat(count()).isZero()
        }

        @Test fun `concurrent first authentication creates exactly one committed user`() {
            Mockito.`when`(verifier.verify(token)).thenReturn(VerifiedFirebaseIdentity(uid, null, null))
            val start = CountDownLatch(1)
            val workers = Executors.newFixedThreadPool(2)
            try {
                val results =
                    (1..2).map {
                        workers.submit<UUID> {
                            start.await(5, TimeUnit.SECONDS)
                            service.authenticate("Bearer $token").id
                        }
                    }
                start.countDown()
                assertThat(results.map { it.get(10, TimeUnit.SECONDS) }.distinct()).hasSize(1)
                assertThat(count()).isEqualTo(1)
            } finally {
                workers.shutdownNow()
            }
        }

        private fun count() =
            jdbc.queryForObject("SELECT count(*) FROM firebase_user WHERE firebase_uid = ?", Long::class.java, uid)
    }
