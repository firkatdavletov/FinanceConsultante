package com.firkatdavletov.financeconsultante.telegram.application

import com.firkatdavletov.financeconsultante.telegram.domain.BudgetSettings
import com.firkatdavletov.financeconsultante.telegram.domain.SetupDialog
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Clock

class OwnerAccessTests {
    @Test
    fun `only the configured human owner in their private chat is authorized`() {
        val access = OwnerAccess(555)
        assertThat(access.allows(555, 555, "private", false)).isTrue()
        assertThat(access.allows(999, 555, "private", false)).isFalse()
        assertThat(access.allows(555, 555, "group", false)).isFalse()
        assertThat(access.allows(555, -555, "private", false)).isFalse()
        assertThat(access.allows(555, 555, "private", true)).isFalse()
        assertThat(access.allows(null, null, null, false)).isFalse()
    }

    @Test
    fun `previous owner inbox is rejected before any financial access`() {
        var ignored = false
        val inbox =
            object : InboxStore {
                override fun next(botId: Long): InboxMessage = InboxMessage(1, 999, "/settings")

                override fun complete(
                    message: InboxMessage,
                    botId: Long,
                    reply: String?,
                ) {
                    assertThat(reply).isNull()
                    ignored = true
                }

                override fun failedAttempt(
                    botId: Long,
                    messageId: Long,
                ) = error("Unexpected retry")
            }
        val forbidden =
            object : SetupStore {
                override fun dialog(ownerId: Long): SetupDialog = error("Accessed private dialog")

                override fun settings(ownerId: Long): OwnedSettings? = error("Accessed private settings")

                override fun saveDialog(
                    ownerId: Long,
                    dialog: SetupDialog,
                ) = error("Changed private dialog")

                override fun saveSettings(
                    ownerId: Long,
                    settings: BudgetSettings,
                ) = error("Changed private settings")
            }
        TelegramInboxProcessor(
            inbox,
            forbidden,
            OwnerAccess(555),
            Clock.systemUTC(),
            object : LedgerHandler {
                override fun handle(
                    message: InboxMessage,
                    botId: Long,
                ): TelegramReply = error("Unauthorized ledger")

                override fun recognized(
                    message: InboxMessage,
                    botId: Long,
                    proposal: com.firkatdavletov.financeconsultante.ledger.domain.MessageParseResult.RecordTransaction,
                ): TelegramReply = error("Unauthorized ledger")
            },
        ).processOne(1)
        assertThat(ignored).isTrue()
    }
}
