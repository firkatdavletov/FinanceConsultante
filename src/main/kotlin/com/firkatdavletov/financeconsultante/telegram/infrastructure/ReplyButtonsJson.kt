package com.firkatdavletov.financeconsultante.telegram.infrastructure

import com.firkatdavletov.financeconsultante.telegram.application.ReplyButton
import tools.jackson.databind.json.JsonMapper

internal object ReplyButtonsJson {
    private val mapper = JsonMapper.builder().build()

    fun encode(buttons: List<ReplyButton>): String =
        mapper.writeValueAsString(
            buttons.map {
                buildMap<String, Any> {
                    put("text", it.text)
                    put("data", it.data)
                    it.row?.let { row -> put("row", row) }
                }
            },
        )

    fun decode(json: String): List<ReplyButton> =
        mapper
            .readTree(json)
            .iterator()
            .asSequence()
            .map {
                ReplyButton(
                    it.get("text").asString(),
                    it.get("data").asString(),
                    it.get("row")?.asInt(),
                )
            }.toList()
}
