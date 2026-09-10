package com.firkatdavletov.financeconsultante.ai

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class RecognitionCorpusTests {
    @Test
    fun `fixed corpus has unique cases and all safety boundaries`() {
        val corpus =
            checkNotNull(javaClass.getResourceAsStream("/openai/recognition-corpus-v1.json")).use {
                ObjectMapper().readTree(it)
            }
        val cases = corpus.required("cases").toList()

        assertThat(corpus.required("version").textValue()).isEqualTo("recognition-corpus-v1")
        assertThat(cases).hasSize(12)
        assertThat(cases.map { it.required("id").textValue() }).doesNotHaveDuplicates()
        assertThat(cases.map { it.required("needsClarification").booleanValue() })
            .contains(true, false)
        assertThat(cases.map(JsonNode::toString).joinToString())
            .contains("RECORD_TRANSACTION", "PURCHASE_QUESTION", "UNKNOWN", "prompt-injection")
            .doesNotContain("telegramUserId", "householdId", "apiKey")
    }
}
