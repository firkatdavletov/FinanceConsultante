package com.firkatdavletov.financeconsultante.mobileapi

import com.firkatdavletov.financeconsultante.PostgresIntegrationTest
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.util.UUID

@AutoConfigureMockMvc
class LegacyAppAccessTests
    @Autowired
    constructor(
        private val mvc: MockMvc,
    ) : PostgresIntegrationTest() {
        @Test
        fun `legacy endpoint directs every installation to local mode`() {
            listOf(null, UUID.randomUUID().toString(), "invalid").forEach { id ->
                val request = get("/api/v4/app/access")
                if (id != null) request.header("X-Device-Id", id)
                mvc
                    .perform(request)
                    .andExpect(status().isOk)
                    .andExpect(header().string("Cache-Control", "no-store"))
                    .andExpect(jsonPath("$.mode").value("OFFLINE_FREE"))
            }
        }
    }
