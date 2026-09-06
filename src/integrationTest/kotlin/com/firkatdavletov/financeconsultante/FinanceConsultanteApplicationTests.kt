package com.firkatdavletov.financeconsultante

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.core.env.Environment
import org.springframework.test.context.ActiveProfiles

@SpringBootTest
@ActiveProfiles("test")
class FinanceConsultanteApplicationTests
    @Autowired
    constructor(
        private val environment: Environment,
    ) {
        @Test
        fun `context starts with external integrations disabled`() {
            assertThat(environment.activeProfiles).containsExactly("test")
            assertThat(environment.getProperty("app.telegram.enabled", Boolean::class.java)).isFalse()
            assertThat(environment.getProperty("app.openai.enabled", Boolean::class.java)).isFalse()
            assertThat(environment.getProperty("app.notifications.enabled", Boolean::class.java)).isFalse()
        }
    }
