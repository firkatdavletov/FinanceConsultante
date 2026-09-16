package com.firkatdavletov.financeconsultante.telegram.application

import com.firkatdavletov.financeconsultante.access.application.FamilyBudgetService
import com.firkatdavletov.financeconsultante.access.application.FamilyChange
import com.firkatdavletov.financeconsultante.access.domain.FamilyInvitation
import com.firkatdavletov.financeconsultante.access.domain.FamilyMember
import com.firkatdavletov.financeconsultante.access.domain.FamilyOverview
import com.firkatdavletov.financeconsultante.access.domain.MemberRole
import com.firkatdavletov.financeconsultante.ledger.domain.MessageParseResult.Command

class TelegramFamilyHandler(
    private val families: FamilyBudgetService,
) {
    @Suppress("CyclomaticComplexMethod", "MagicNumber", "ReturnCount") // Explicit command arities reject invalid input.
    fun command(
        message: InboxMessage,
        command: Command,
        botId: Long,
    ): TelegramReply? {
        if (command.name != COMMAND) return null
        val parts = command.arguments.split(' ').filter(String::isNotEmpty)
        return when {
            parts.isEmpty() -> {
                overview(message.ownerId)
            }

            parts.size == 1 && parts[0] == "accept" -> {
                accepted(families.accept(message.ownerId))
            }

            parts.size == 2 && parts[0] == "invite" -> {
                val target = telegramId(parts[1]) ?: return ID_HELP.reply()
                invited(families.invite(message.ownerId, target, source(message, botId)))
            }

            parts.size == 2 && parts[0] == "revoke" -> {
                val target = telegramId(parts[1]) ?: return ID_HELP.reply()
                changed(
                    families.revoke(message.ownerId, target),
                    "Доступ Telegram ID $target отозван. История сохранена.",
                )
            }

            parts.size == 3 && parts[0] == "reports" && parts[2] in setOf("on", "off") -> {
                val target = telegramId(parts[1]) ?: return ID_HELP.reply()
                val enabled = parts[2] == "on"
                reports(families.configureReports(message.ownerId, target, enabled))
            }

            else -> {
                HELP.reply()
            }
        }
    }

    fun pending(telegramUserId: Long): TelegramReply? =
        families.pendingInvitation(telegramUserId)?.let {
            "У вас есть приглашение в семейный бюджет. Принять: /family accept. $RIGHTS".reply()
        }

    private fun overview(telegramUserId: Long): TelegramReply =
        families.overview(telegramUserId)?.let(::renderOverview)
            ?: pending(telegramUserId)
            ?: "Семейный бюджет недоступен. OWNER сначала настраивает бюджет через /start.".reply()

    private fun renderOverview(overview: FamilyOverview): TelegramReply =
        buildString {
            appendLine("Семейный бюджет. Ваша роль: ${overview.requester.role}.")
            appendLine(RIGHTS)
            appendLine("Участники:")
            overview.members.forEach {
                appendLine(
                    "${it.telegramUserId}: ${it.role}, отчёты ${if (it.reportsEnabled) "включены" else "выключены"}.",
                )
            }
            if (overview.requester.role == MemberRole.OWNER) {
                if (overview.pendingInvitations.isNotEmpty()) {
                    val pending = overview.pendingInvitations.joinToString { it.invitedTelegramUserId.toString() }
                    appendLine("Ожидают принятия: $pending.")
                }
                append(HELP)
            }
        }.trim().reply()

    private fun invited(result: FamilyChange<FamilyInvitation>): TelegramReply =
        when (result) {
            is FamilyChange.Rejected -> {
                result.message.reply()
            }

            is FamilyChange.Applied -> {
                (
                    "Приглашение для Telegram ID ${result.value.invitedTelegramUserId} создано. " +
                        "Участник принимает его в личном чате командой /family accept. $RIGHTS " +
                        "Отчёты для него выключены до команды /family reports ${result.value.invitedTelegramUserId} on."
                ).reply()
            }
        }

    private fun accepted(result: FamilyChange<FamilyMember>): TelegramReply =
        when (result) {
            is FamilyChange.Rejected -> {
                result.message.reply()
            }

            is FamilyChange.Applied -> {
                (
                    "Приглашение принято. Вы вошли в общий семейный бюджет как MEMBER. $RIGHTS " +
                        "Автоматические отчёты пока выключены; их включает OWNER."
                ).reply()
            }
        }

    private fun changed(
        result: FamilyChange<Long>,
        success: String,
    ): TelegramReply =
        when (result) {
            is FamilyChange.Rejected -> result.message.reply()
            is FamilyChange.Applied -> success.reply()
        }

    private fun reports(result: FamilyChange<FamilyMember>): TelegramReply =
        when (result) {
            is FamilyChange.Rejected -> {
                result.message.reply()
            }

            is FamilyChange.Applied -> {
                val state = if (result.value.reportsEnabled) "включены" else "выключены"
                "Периодические отчёты для Telegram ID ${result.value.telegramUserId} $state.".reply()
            }
        }

    private fun telegramId(value: String): Long? = value.toLongOrNull()?.takeIf { it > 0 }

    private fun source(
        message: InboxMessage,
        botId: Long,
    ): String = "telegram:$botId:inbox:${message.id}"

    companion object {
        const val COMMAND = "/family"
        const val RIGHTS =
            "OWNER управляет настройками, участниками, категориями, планами, общими целями и любыми " +
                "операциями. MEMBER видит общий бюджет, добавляет операции и меняет только свои; групповые чаты отключены."
        private const val ID_HELP = "Нужен положительный числовой Telegram ID."
        private const val HELP =
            "OWNER: /family invite <Telegram ID>; /family revoke <Telegram ID>; " +
                "/family reports <Telegram ID> on|off. Приглашённый: /family accept."
    }
}
