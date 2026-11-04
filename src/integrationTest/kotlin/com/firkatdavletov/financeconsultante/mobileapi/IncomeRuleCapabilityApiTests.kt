package com.firkatdavletov.financeconsultante.mobileapi

import com.firkatdavletov.financeconsultante.mobileapi.application.CloudBudgetService
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.util.UUID

@Suppress("LongMethod")
class IncomeRuleCapabilityApiTests
    @Autowired
    constructor(
        cloud: CloudBudgetService,
        jdbc: JdbcTemplate,
        mvc: MockMvc,
    ) : RuleEditingApiTestSupport(cloud, jdbc, mvc) {
        @Test fun `budget advertises income editor independently of client capabilities without remainder support`() {
            for (capabilities in listOf(null, "confirmedPlanLinkingV1", ANDROID_CAPABILITIES)) {
                mvc
                    .perform(
                        get("/api/v4/budgets/${request.householdId}")
                            .header("Authorization", "Bearer synthetic-owner")
                            .apply { if (capabilities != null) header("X-Fillin-Capabilities", capabilities) },
                    ).andExpect(status().isOk)
                    .andExpect(header().string("Cache-Control", "no-store"))
                    .andExpect(jsonPath("$.scope.householdId").value(request.householdId.toString()))
                    .andExpect(jsonPath("$.scope.timeZone").value("Asia/Almaty"))
                    .andExpect(jsonPath("$.scope.currency").value("KZT"))
                    .andExpect(jsonPath("$.capabilities[0]").value("confirmedPlanLinkingV1"))
                    .andExpect(jsonPath("$.capabilities[1]").value("incomeRuleEditingV1"))
                    .andExpect(jsonPath("$.capabilities.length()").value(2))
            }
        }

        @Test fun `income rule commands round trip with editor capability and full Android header`() {
            for (capabilities in listOf("confirmedPlanLinkingV1,incomeRuleEditingV1", ANDROID_CAPABILITIES)) {
                val id = UUID.randomUUID()
                val fields =
                    mapOf(
                        "categoryId" to request.categories[2].id,
                        "amount" to "100.00",
                        "dueOn" to "2026-09-30",
                        "incomeName" to "  Synthetic income  ",
                        "incomeFrequency" to "MONTHLY",
                        "incomeActive" to true,
                    )
                send(command("CREATE_INCOME_RULE", id, fields = fields), capabilities).andExpect(status().isOk)
                val created = record("INCOME_RULE", id, capabilities)
                assertThat(created["incomeName"].asString()).isEqualTo("Synthetic income")
                assertThat(created["incomeFrequency"].asString()).isEqualTo("MONTHLY")
                assertThat(created["incomeActive"].asBoolean()).isTrue()
                val occurrence = incomeOccurrence(id, "2026-09-30")
                val payment = record("EXPECTED_INCOME", occurrence, capabilities)
                assertThat(payment["ruleId"].asString()).isEqualTo(id.toString())
                assertThat(payment["scheduledOn"].asString()).isEqualTo("2026-09-30")

                val update =
                    command(
                        "UPDATE_INCOME_RULE",
                        id,
                        fields = fields + mapOf("incomeName" to "Edited income", "incomeFrequency" to "ONCE"),
                    )
                val receipt =
                    send(update, capabilities)
                        .andExpect(status().isOk)
                        .andReturn()
                        .response.contentAsString
                assertThat(
                    send(update, capabilities)
                        .andExpect(status().isOk)
                        .andReturn()
                        .response.contentAsString,
                ).isEqualTo(receipt)
                val updated = record("INCOME_RULE", id, capabilities)
                assertThat(updated["incomeName"].asString()).isEqualTo("Edited income")
                assertThat(updated["incomeFrequency"].asString()).isEqualTo("ONCE")
                assertThat(incomeOccurrence(id, "2026-09-30")).isEqualTo(occurrence)

                val delete = command("DELETE_INCOME_RULE", id)
                send(delete, capabilities).andExpect(status().isOk)
                val deleted = record("INCOME_RULE", id, capabilities)
                assertThat(deleted["incomeActive"].asBoolean()).isFalse()
                assertThat(deleted["status"].asString()).isEqualTo("CANCELLED")
                assertThat(record("EXPECTED_INCOME", occurrence, capabilities)["status"].asString())
                    .isEqualTo("CANCELLED")
                val before = snapshot("income_rule", id)
                send(delete, capabilities).andExpect(status().isOk)
                assertThat(snapshot("income_rule", id)).isEqualTo(before)
            }
        }

        @Test fun `legacy income commands remain accepted without editor capability and metadata round trips`() {
            val id = UUID.randomUUID()
            val fields =
                mapOf(
                    "categoryId" to request.categories[2].id,
                    "amount" to "100.00",
                    "dueOn" to "2026-09-30",
                )
            send(command("CREATE_INCOME", id, fields = fields), "").andExpect(status().isOk)
            send(command("UPDATE_INCOME", id, fields = fields + ("amount" to "200.00")), "")
                .andExpect(status().isOk)
            send(
                command(
                    "UPDATE_INCOME",
                    id,
                    fields =
                        fields +
                            mapOf(
                                "incomeName" to "  Edited standalone  ",
                                "incomeFrequency" to "ONCE",
                                "incomeActive" to false,
                            ),
                ),
                "confirmedPlanLinkingV1,incomeRuleEditingV1",
            ).andExpect(status().isOk)
            val updated = record("EXPECTED_INCOME", id)
            assertThat(updated["incomeName"].asString()).isEqualTo("Edited standalone")
            assertThat(updated["incomeFrequency"].asString()).isEqualTo("ONCE")
            assertThat(updated["incomeActive"].asBoolean()).isFalse()
        }

        companion object {
            private const val ANDROID_CAPABILITIES =
                "confirmedPlanLinkingV1,incomeRuleEditingV1,dailyLimitPlanningV1"
        }
    }
