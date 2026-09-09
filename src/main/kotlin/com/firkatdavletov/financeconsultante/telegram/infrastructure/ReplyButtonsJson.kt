package com.firkatdavletov.financeconsultante.telegram.infrastructure

import com.firkatdavletov.financeconsultante.telegram.application.ReplyButton
import tools.jackson.databind.json.JsonMapper

internal object ReplyButtonsJson {
    private val mapper = JsonMapper.builder().build()

    fun encode(buttons: List<ReplyButton>): String =
        mapper.writeValueAsString(
            buttons.map {
                mapOf("text" to it.text, "data" to it.data)
            },
        )

    fun decode(json: String): List<ReplyButton> =
        mapper
            .readTree(json)
            .iterator()
            .asSequence()
            .map {
                ReplyButton(it.get("text").asString(), it.get("data").asString())
            }.toList()
}
