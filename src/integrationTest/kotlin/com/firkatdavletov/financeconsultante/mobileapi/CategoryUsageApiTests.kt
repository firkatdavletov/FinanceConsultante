package com.firkatdavletov.financeconsultante.mobileapi

import com.firkatdavletov.financeconsultante.auth.application.VerifiedFirebaseIdentity
import com.firkatdavletov.financeconsultante.mobileapi.application.CategoryUsage
import com.firkatdavletov.financeconsultante.mobileapi.application.CategoryUsageEvent
import com.firkatdavletov.financeconsultante.mobileapi.application.CloudBudgetService
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActions
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import tools.jackson.databind.JsonNode
import java.util.Base64
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

@Suppress("MagicNumber", "LongMethod", "TooManyFunctions")
class CategoryUsageApiTests
    @Autowired
    constructor(
        cloud: CloudBudgetService,
        jdbc: JdbcTemplate,
        mvc: MockMvc,
        private val usage: CategoryUsage,
        private val transactions: PlatformTransactionManager,
    ) : RuleEditingApiTestSupport(cloud, jdbc, mvc) {
        @Test fun `retries are idempotent and conflicts on either key roll back the entire batch`() {
            val saved = event(Long.MAX_VALUE)
            val pending = event(0)
            repeat(2) {
                push(listOf(saved, saved))
                    .andExpect(status().isOk)
                    .andExpect(jsonPath("$.scopeKey").value("${request.householdId}:$member"))
                    .andExpect(jsonPath("$.acceptedIds.length()").value(1))
                    .andExpect(jsonPath("$.acceptedIds[0]").value(saved.id.toString()))
            }
            val collisions = listOf(saved.copy(usedAtMillis = 1), saved.copy(id = UUID.randomUUID()))
            collisions.forEach { conflict ->
                push(listOf(pending, conflict))
                    .andExpect(status().isConflict)
                    .andExpect(jsonPath("$.code").value("event_conflict"))
            }
            push(listOf(pending, pending.copy(operationId = UUID.randomUUID())))
                .andExpect(status().isConflict)
            val events = page()["events"]
            assertThat(events.size()).isEqualTo(1)
            assertThat(events[0]).isEqualTo(mapper.readTree(mapper.writeValueAsString(saved)))
        }

        @Test fun `empty and paginated cursors deliver later arrivals by server position`() {
            val empty = page()
            val initialCursor = empty["nextCursor"].asString()
            assertThat(initialCursor).isNotBlank()
            assertThat(empty["events"].size()).isZero()
            assertThat(empty["hasMore"].asBoolean()).isFalse()

            val events = List(101) { event(100L - it) }
            events.chunked(100).forEach { push(it).andExpect(status().isOk) }
            val first = page(initialCursor)
            assertThat(first["hasMore"].asBoolean()).isTrue()
            assertThat(first["nextCursor"].asString()).isNotEqualTo(initialCursor)
            assertThat(eventIds(first)).containsExactlyElementsOf(events.take(100).map { it.id.toString() })
            val last = page(first["nextCursor"].asString())
            assertThat(last["hasMore"].asBoolean()).isFalse()
            assertThat(eventIds(last)).containsExactly(events.last().id.toString())

            val tail = page(last["nextCursor"].asString())
            assertThat(tail["nextCursor"].asString()).isNotBlank()
            assertThat(eventIds(tail)).isEmpty()
            val later = event(0)
            push(listOf(later)).andExpect(status().isOk)
            assertThat(eventIds(page(tail["nextCursor"].asString()))).containsExactly(later.id.toString())
        }

        @Test fun `valid Firebase membership is required and even an owner cannot use a member cursor`() {
            val otherUid = "synthetic-usage-member"
            val token = "usage-member"
            Mockito.`when`(verifier.verify(token)).thenReturn(VerifiedFirebaseIdentity(otherUid, null, null))
            pull(token = null).andExpect(status().isUnauthorized).andExpect(jsonPath("$.code").value("unauthorized"))
            pull(token = token).andExpect(status().isForbidden).andExpect(jsonPath("$.code").value("access_denied"))
            val otherMember = UUID.randomUUID()
            jdbc.update(
                "INSERT INTO member(id, household_id, role) VALUES (?, ?, 'MEMBER')",
                otherMember,
                request.householdId,
            )
            jdbc.update(
                "INSERT INTO firebase_budget_access VALUES (?, ?, ?)",
                otherUid,
                request.householdId,
                otherMember,
            )
            try {
                val saved = event(42)
                push(listOf(saved), token)
                    .andExpect(status().isOk)
                    .andExpect(jsonPath("$.scopeKey").value("${request.householdId}:$otherMember"))
                val memberPage = body(pull(token = token))
                assertThat(eventIds(memberPage)).containsExactly(saved.id.toString())
                assertThat(eventIds(page())).isEmpty()
                pull(memberPage["nextCursor"].asString())
                    .andExpect(status().isForbidden)
                    .andExpect(jsonPath("$.code").value("access_denied"))
                jdbc.update("UPDATE member SET revoked_at=now() WHERE id=?", otherMember)
                pull(token = token).andExpect(status().isForbidden)
                push(listOf(event(43)), token).andExpect(status().isForbidden)
            } finally {
                jdbc.update("DELETE FROM firebase_budget_access WHERE firebase_uid=?", otherUid)
                jdbc.update("DELETE FROM firebase_user WHERE firebase_uid=?", otherUid)
            }
        }

        @Test fun `invalid inputs use the API error format and an expired cursor can be reset`() {
            val saved = event(0)
            val encoded = mapper.writeValueAsString(mapOf("events" to listOf(saved)))
            val invalidBodies =
                listOf(
                    """{"events":[]}""",
                    """{"events":[""",
                    mapper.writeValueAsString(mapOf("events" to List(101) { saved })),
                    encoded.replace(saved.categoryId.toString(), "AAAAAAAA-AAAA-4AAA-8AAA-AAAAAAAAAAAA"),
                ) +
                    listOf("-1", "0.0", "\"0\"", "9223372036854775808").map {
                        encoded.replace("\"usedAtMillis\":0", "\"usedAtMillis\":$it")
                    }
            invalidBodies.forEach {
                pushJson(it).andExpect(status().isBadRequest).andExpect(jsonPath("$.code").value("invalid_request"))
            }
            mvc
                .perform(get(PATH).header("Authorization", "Bearer synthetic-owner"))
                .andExpect(status().isBadRequest)
                .andExpect(jsonPath("$.code").value("invalid_request"))
            pull("???").andExpect(status().isBadRequest).andExpect(jsonPath("$.code").value("invalid_request"))
            assertThat(eventIds(page())).isEmpty()

            push(listOf(saved)).andExpect(status().isOk)
            val cursor = page()["nextCursor"].asString()
            val decoded = String(Base64.getUrlDecoder().decode(cursor), Charsets.UTF_8)
            val expired = Base64.getUrlEncoder().encodeToString((decoded.substringBeforeLast('|') + "|0").toByteArray())
            pull(expired).andExpect(status().isGone).andExpect(jsonPath("$.code").value("cursor_expired"))
            assertThat(eventIds(page())).containsExactly(saved.id.toString())
        }

        @Test fun `an uncommitted upload cannot leave a gap behind a cursor`() {
            val initialCursor = page()["nextCursor"].asString()
            val first = event(200)
            val second = event(100)
            val inserted = CountDownLatch(1)
            val commit = CountDownLatch(1)
            val secondStarted = CountDownLatch(1)
            val executor = Executors.newFixedThreadPool(2)
            try {
                val firstWriter =
                    executor.submit {
                        TransactionTemplate(transactions).executeWithoutResult {
                            usage.push(uid, request.householdId, listOf(first))
                            inserted.countDown()
                            check(commit.await(10, TimeUnit.SECONDS))
                        }
                    }
                check(inserted.await(10, TimeUnit.SECONDS))
                val secondWriter =
                    executor.submit {
                        secondStarted.countDown()
                        usage.push(uid, request.householdId, listOf(second))
                    }
                check(secondStarted.await(10, TimeUnit.SECONDS))
                assertThatThrownBy {
                    secondWriter.get(
                        300,
                        TimeUnit.MILLISECONDS,
                    )
                }.isInstanceOf(TimeoutException::class.java)
                val concurrentPage = page(initialCursor)
                assertThat(eventIds(concurrentPage)).isEmpty()
                commit.countDown()
                firstWriter.get(10, TimeUnit.SECONDS)
                secondWriter.get(10, TimeUnit.SECONDS)
                assertThat(eventIds(page(concurrentPage["nextCursor"].asString())))
                    .containsExactly(first.id.toString(), second.id.toString())
            } finally {
                commit.countDown()
                executor.shutdownNow()
            }
        }

        private fun event(millis: Long) =
            CategoryUsageEvent(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), millis)

        private fun push(
            events: List<CategoryUsageEvent>,
            token: String = "synthetic-owner",
        ) = pushJson(mapper.writeValueAsString(mapOf("events" to events)), token)

        private fun pushJson(
            json: String,
            token: String = "synthetic-owner",
        ) = mvc.perform(
            post(PATH)
                .queryParam("budgetId", request.householdId.toString())
                .header("Authorization", "Bearer $token")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json),
        )

        private fun pull(
            cursor: String? = null,
            token: String? = "synthetic-owner",
        ) = mvc.perform(
            get(PATH).queryParam("budgetId", request.householdId.toString()).apply {
                token?.let { header("Authorization", "Bearer $it") }
                cursor?.let { queryParam("cursor", it) }
            },
        )

        private fun page(cursor: String? = null) = body(pull(cursor))

        private fun body(result: ResultActions) =
            mapper.readTree(
                result
                    .andExpect(status().isOk)
                    .andReturn()
                    .response.contentAsString,
            )

        private fun eventIds(page: JsonNode) = page["events"].values().map { it["id"].asString() }

        companion object {
            private const val PATH = "/api/v4/category-usage/events"
        }
    }
