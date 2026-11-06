package com.firkatdavletov.financeconsultante.mobileapi

import com.firkatdavletov.financeconsultante.PostgresIntegrationTest
import com.firkatdavletov.financeconsultante.auth.application.VerifiedFirebaseIdentity
import com.firkatdavletov.financeconsultante.auth.infrastructure.FirebaseAdminTokenVerifier
import com.firkatdavletov.financeconsultante.mobileapi.infrastructure.InternalDeviceAccess
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
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.util.UUID

@AutoConfigureMockMvc
@TestPropertySource(
    properties = [
        "app.internal-device-gate.enabled=true",
        "app.firebase-auth.enabled=true",
        "app.mobile-api.enabled=true",
    ],
)
class InternalDeviceAccessTests
    @Autowired
    constructor(
        private val jdbc: JdbcTemplate,
        private val mvc: MockMvc,
        private val access: InternalDeviceAccess,
    ) : PostgresIntegrationTest() {
        @MockitoBean private lateinit var verifier: FirebaseAdminTokenVerifier
        private val id = UUID.randomUUID()

        @AfterEach
        fun cleanup() {
            jdbc
                .queryForList(
                    "SELECT household_id FROM firebase_budget_access WHERE firebase_uid = ?",
                    UUID::class.java,
                    uid,
                ).forEach { budget ->
                    jdbc.update("DELETE FROM firebase_budget_creation WHERE household_id = ?", budget)
                    jdbc.update("DELETE FROM firebase_budget_access WHERE household_id = ?", budget)
                    listOf("entry_revision", "ledger_entry", "category", "account", "member").forEach {
                        jdbc.update("DELETE FROM $it WHERE household_id = ?", budget)
                    }
                    jdbc.update("DELETE FROM household WHERE id = ?", budget)
                }
            jdbc.update("DELETE FROM firebase_user WHERE firebase_uid = ?", uid)
            jdbc.update("DELETE FROM internal_app_installation WHERE device_id = ?", id)
        }

        private val uid = "synthetic-device-${UUID.randomUUID()}"

        @Test
        fun `access endpoint returns current state without Firebase or version`() {
            mvc
                .perform(get("/api/v4/app/access").header("X-Device-Id", id))
                .andExpect(status().isOk)
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.mode").value("OFFLINE_FREE"))
            allow()
            mvc
                .perform(get("/api/v4/app/access").header("X-Device-Id", id).header("X-App-Version", "0.1"))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.mode").value("INTERNAL_FULL"))
            assertThat(access.isActive(id)).isTrue()
            revoke()
            mvc
                .perform(get("/api/v4/app/access").header("X-Device-Id", id))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.mode").value("OFFLINE_FREE"))
        }

        @Test
        fun `access endpoint rejects missing repeated and noncanonical ids`() {
            mvc
                .perform(get("/api/v4/app/access"))
                .andExpect(status().isBadRequest)
                .andExpect(jsonPath("$.code").value("invalid_device_id"))
            for (bad in listOf(id.toString().uppercase(), id.toString().replace("-", ""), " $id", "$id,$id")) {
                mvc
                    .perform(get("/api/v4/app/access").header("X-Device-Id", bad))
                    .andExpect(status().isBadRequest)
                    .andExpect(jsonPath("$.code").value("invalid_device_id"))
            }
            mvc
                .perform(get("/api/v4/app/access").header("X-Device-Id", id, id))
                .andExpect(status().isBadRequest)
        }

        @Test
        fun `gate rejects before authentication and follows revoke and restore`() {
            mvc
                .perform(post("/api/v4/auth/firebase"))
                .andExpect(status().isForbidden)
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.code").value("device_access_denied"))
            allow()
            mvc
                .perform(post("/api/v4/auth/firebase").header("X-Device-Id", id))
                .andExpect(status().isUnauthorized)
            revoke()
            mvc
                .perform(post("/api/v4/auth/firebase").header("X-Device-Id", id))
                .andExpect(status().isForbidden)
            allow()
            mvc
                .perform(post("/api/v4/auth/firebase").header("X-Device-Id", id))
                .andExpect(status().isUnauthorized)
            Mockito.verifyNoInteractions(verifier)
        }

        @Test
        fun `gate covers budget routes and preserves deletion exceptions`() {
            for (path in listOf(
                "/api/v4/budgets",
                "/api/v4/budgets/import",
                "/api/v4/budgets/$id/sync/changes",
                "/api/v4/budgets/$id/invitations/current",
                "/api/v4/budgets/$id/members",
            )) {
                mvc
                    .perform(get(path))
                    .andExpect(status().isForbidden)
                    .andExpect(jsonPath("$.code").value("device_access_denied"))
            }
            mvc.perform(delete("/api/v4/profile")).andExpect(status().isBadRequest)
            mvc.perform(get("/api/v4/profile/deletion")).andExpect(status().isBadRequest)
        }

        @Test
        fun `revoke blocks creation replay before receipt and restoration returns same budget`() {
            Mockito.`when`(verifier.verify("device-owner")).thenReturn(VerifiedFirebaseIdentity(uid, "Owner", null))
            allow()
            val requestId = UUID.randomUUID()
            val payload =
                """{"requestId":"$requestId","name":"Synthetic budget","openingBalance":"100",
                    "accountingStartedOn":"2026-09-05","currency":"KZT"}"""
            val first =
                mvc
                    .perform(createBudget(payload))
                    .andExpect(status().isOk)
                    .andReturn()
                    .response.contentAsString
            val budgetCount =
                jdbc.queryForObject(
                    "SELECT count(*) FROM firebase_budget_creation WHERE request_id = ?",
                    Int::class.java,
                    requestId,
                )
            revoke()
            mvc
                .perform(createBudget(payload))
                .andExpect(status().isForbidden)
                .andExpect(jsonPath("$.code").value("device_access_denied"))
            assertThat(
                jdbc.queryForObject(
                    "SELECT count(*) FROM firebase_budget_creation WHERE request_id = ?",
                    Int::class.java,
                    requestId,
                ),
            ).isEqualTo(budgetCount)
            allow()
            val replay =
                mvc
                    .perform(
                        createBudget(payload),
                    ).andExpect(status().isOk)
                    .andReturn()
                    .response.contentAsString
            assertThat(replay).isEqualTo(first)
        }

        private fun createBudget(payload: String) =
            post("/api/v4/budgets")
                .header("X-Device-Id", id)
                .header("Authorization", "Bearer device-owner")
                .contentType("application/json")
                .content(payload)

        private fun allow() {
            jdbc.update(
                "INSERT INTO internal_app_installation (device_id, active, label, updated_at) " +
                    "VALUES (?, TRUE, ?, CURRENT_TIMESTAMP) " +
                    "ON CONFLICT (device_id) DO UPDATE SET active = TRUE, updated_at = CURRENT_TIMESTAMP",
                id,
                "Synthetic device",
            )
        }

        private fun revoke() {
            jdbc.update(
                "UPDATE internal_app_installation SET active = FALSE, " +
                    "updated_at = CURRENT_TIMESTAMP WHERE device_id = ?",
                id,
            )
        }
    }
