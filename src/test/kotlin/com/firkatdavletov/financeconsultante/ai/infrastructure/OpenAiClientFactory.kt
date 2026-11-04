package com.firkatdavletov.financeconsultante.ai.infrastructure

import com.openai.client.OpenAIClient
import com.openai.client.okhttp.OpenAIOkHttpClient

/** Explicit test/evaluation construction; it never registers a runtime client or worker. */
class OpenAiClientFactory {
    fun openAiClient(properties: OpenAiProperties): OpenAIClient {
        properties.validateEnabled()
        return OpenAIOkHttpClient
            .builder()
            .apiKey(properties.apiKey)
            .baseUrl(properties.baseUrl)
            .timeout(properties.requestTimeout)
            .maxRetries(properties.maxRetries)
            .responseValidation(true)
            .build()
    }
}
