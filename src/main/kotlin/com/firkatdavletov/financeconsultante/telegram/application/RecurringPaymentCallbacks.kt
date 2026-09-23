package com.firkatdavletov.financeconsultante.telegram.application

import java.nio.ByteBuffer
import java.util.Base64
import java.util.UUID

internal data class RecurringPaymentCallback(
    val occurrenceId: UUID,
    val occurrenceVersion: Long,
    val ledgerEntryId: UUID,
)

internal object RecurringPaymentCallbacks {
    const val PREFIX = "rpm"

    private val encoder = Base64.getUrlEncoder().withoutPadding()
    private val decoder = Base64.getUrlDecoder()

    fun data(
        occurrenceId: UUID,
        occurrenceVersion: Long,
        ledgerEntryId: UUID,
    ): String {
        require(occurrenceVersion >= 0)
        val data =
            "$PREFIX:${encode(occurrenceId)}:${occurrenceVersion.toString(VERSION_RADIX)}:${encode(ledgerEntryId)}"
        check(data.toByteArray(Charsets.UTF_8).size <= MAX_CALLBACK_BYTES)
        return data
    }

    fun parse(data: String): RecurringPaymentCallback? {
        val parts = data.split(':')
        val occurrenceId = parts.getOrNull(OCCURRENCE_INDEX)?.let(::decode)
        val version =
            parts
                .getOrNull(VERSION_INDEX)
                ?.toLongOrNull(VERSION_RADIX)
                ?.takeIf { it >= 0 }
        val entryId = parts.getOrNull(ENTRY_INDEX)?.let(::decode)
        if (parts.size != PARTS || parts[PREFIX_INDEX] != PREFIX) return null
        return if (occurrenceId == null || version == null || entryId == null) {
            null
        } else {
            RecurringPaymentCallback(occurrenceId, version, entryId)
        }
    }

    private fun encode(id: UUID): String =
        encoder.encodeToString(
            ByteBuffer
                .allocate(UUID_BYTES)
                .putLong(id.mostSignificantBits)
                .putLong(id.leastSignificantBits)
                .array(),
        )

    @Suppress("SwallowedException")
    private fun decode(value: String): UUID? =
        try {
            val bytes = decoder.decode(value)
            if (bytes.size != UUID_BYTES) return null
            val buffer = ByteBuffer.wrap(bytes)
            UUID(buffer.long, buffer.long)
        } catch (_: IllegalArgumentException) {
            null
        }

    private const val PARTS = 4
    private const val PREFIX_INDEX = 0
    private const val OCCURRENCE_INDEX = 1
    private const val VERSION_INDEX = 2
    private const val ENTRY_INDEX = 3
    private const val VERSION_RADIX = 36
    private const val UUID_BYTES = 16
    private const val MAX_CALLBACK_BYTES = 64
}
