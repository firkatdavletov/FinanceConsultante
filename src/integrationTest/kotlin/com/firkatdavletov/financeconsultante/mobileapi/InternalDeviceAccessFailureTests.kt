package com.firkatdavletov.financeconsultante.mobileapi

import com.firkatdavletov.financeconsultante.PostgresIntegrationTest
import com.firkatdavletov.financeconsultante.mobileapi.infrastructure.InternalDeviceAccess
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.dao.DataAccessResourceFailureException
import org.springframework.test.context.TestPropertySource
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.util.UUID

@AutoConfigureMockMvc
@TestPropertySource(properties = ["app.internal-device-gate.enabled=true"])
class InternalDeviceAccessFailureTests
    @Autowired
    constructor(
        private val mvc: MockMvc,
    ) : PostgresIntegrationTest() {
        @MockitoBean private lateinit var access: InternalDeviceAccess

        @Test
        fun `database failure returns 503 on endpoint and gated route`() {
            val id = UUID.randomUUID()
            Mockito.`when`(access.isActive(id)).thenThrow(DataAccessResourceFailureException("synthetic outage"))
            mvc
                .perform(get("/api/v4/app/access").header("X-Device-Id", id))
                .andExpect(status().isServiceUnavailable)
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.code").value("device_access_unavailable"))
            mvc
                .perform(get("/api/v4/budgets").header("X-Device-Id", id))
                .andExpect(status().isServiceUnavailable)
                .andExpect(jsonPath("$.code").value("device_access_unavailable"))
        }
    }
