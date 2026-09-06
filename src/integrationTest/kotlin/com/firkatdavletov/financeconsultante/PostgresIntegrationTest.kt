package com.firkatdavletov.financeconsultante

import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.postgresql.PostgreSQLContainer

@SpringBootTest
@ActiveProfiles("test")
@Import(AccountingTestClock::class)
abstract class PostgresIntegrationTest protected constructor() {
    companion object {
        private val postgres: PostgreSQLContainer by lazy {
            PostgreSQLContainer("postgres:17.11-alpine")
                .withDatabaseName("financeconsultante_test")
                .withUsername("test_owner")
                .withPassword("synthetic-test-password")
                .withCommand("postgres", "-c", "log_min_error_statement=panic", "-c", "log_error_verbosity=terse")
                .apply { start() }
        }

        @JvmStatic
        @DynamicPropertySource
        fun databaseProperties(registry: DynamicPropertyRegistry) {
            val localTestUrl = System.getProperty("test.postgres.url")
            if (localTestUrl == null) {
                registry.add("spring.datasource.url") { postgres.jdbcUrl }
                registry.add("spring.datasource.username") { postgres.username }
                registry.add("spring.datasource.password") { postgres.password }
            } else {
                require(
                    Regex("jdbc:postgresql://127\\.0\\.0\\.1:[0-9]+/financeconsultante_test").matches(localTestUrl),
                ) {
                    "An explicitly selected local test database is required"
                }
                registry.add("spring.datasource.url") { localTestUrl }
                registry.add("spring.datasource.username") { "test_owner" }
                registry.add("spring.datasource.password") { "synthetic-test-password" }
            }
        }
    }
}
