package com.firkatdavletov.financeconsultante.access.application

import com.firkatdavletov.financeconsultante.access.domain.FamilyInvitation
import com.firkatdavletov.financeconsultante.access.domain.FamilyMember
import com.firkatdavletov.financeconsultante.access.domain.FamilyOverview
import com.firkatdavletov.financeconsultante.access.domain.MemberRole
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

@Suppress("TooManyFunctions") // Family membership transitions share one transactional persistence boundary.
interface FamilyBudgetRepository {
    fun activeMember(telegramUserId: Long): FamilyMember?

    fun activeMember(
        telegramUserId: Long,
        memberId: UUID,
    ): FamilyMember?

    fun pendingInvitation(telegramUserId: Long): FamilyInvitation?

    fun members(householdId: UUID): List<FamilyMember>

    fun pendingInvitations(householdId: UUID): List<FamilyInvitation>

    fun invitationBySource(
        householdId: UUID,
        source: String,
    ): FamilyInvitation?

    fun createInvitation(
        invitation: FamilyInvitation,
        invitedBy: UUID,
        source: String,
    ): Boolean

    fun acceptInvitation(
        invitation: FamilyInvitation,
        member: FamilyMember,
        acceptedAt: Instant,
    ): Boolean

    fun revokeInvitation(
        householdId: UUID,
        telegramUserId: Long,
        revokedAt: Instant,
    ): Boolean

    fun revokeMember(
        householdId: UUID,
        telegramUserId: Long,
        revokedAt: Instant,
    ): FamilyMember?

    fun setReportsEnabled(
        householdId: UUID,
        telegramUserId: Long,
        enabled: Boolean,
    ): FamilyMember?

    fun stopReports(memberId: UUID)
}

interface FamilyBudgetAccess {
    fun canReceive(telegramUserId: Long): Boolean

    fun canProcess(
        telegramUserId: Long,
        memberId: UUID?,
    ): Boolean

    fun canDeliver(
        telegramUserId: Long,
        memberId: UUID,
        report: Boolean,
    ): Boolean

    fun activeMember(
        telegramUserId: Long,
        memberId: UUID? = null,
    ): FamilyMember?

    fun pendingInvitation(telegramUserId: Long): FamilyInvitation?
}

sealed interface FamilyChange<out T> {
    data class Applied<T>(
        val value: T,
        val changed: Boolean = true,
    ) : FamilyChange<T>

    data class Rejected(
        val message: String,
    ) : FamilyChange<Nothing>
}

@Service
@Transactional
@Suppress("TooManyFunctions") // Commands and access checks intentionally share the same membership rules.
class FamilyBudgetService(
    private val repository: FamilyBudgetRepository,
    private val clock: Clock,
) : FamilyBudgetAccess {
    override fun canReceive(telegramUserId: Long): Boolean =
        repository.activeMember(telegramUserId) != null || repository.pendingInvitation(telegramUserId) != null

    override fun canProcess(
        telegramUserId: Long,
        memberId: UUID?,
    ): Boolean =
        if (memberId == null) {
            repository.pendingInvitation(telegramUserId) != null
        } else {
            repository.activeMember(telegramUserId, memberId) != null
        }

    override fun canDeliver(
        telegramUserId: Long,
        memberId: UUID,
        report: Boolean,
    ): Boolean = repository.activeMember(telegramUserId, memberId)?.let { !report || it.reportsEnabled } == true

    override fun activeMember(
        telegramUserId: Long,
        memberId: UUID?,
    ): FamilyMember? =
        when (memberId) {
            null -> repository.activeMember(telegramUserId)
            else -> repository.activeMember(telegramUserId, memberId)
        }

    override fun pendingInvitation(telegramUserId: Long): FamilyInvitation? =
        repository.pendingInvitation(telegramUserId)

    fun overview(telegramUserId: Long): FamilyOverview? {
        val requester = repository.activeMember(telegramUserId) ?: return null
        val pendingInvitations =
            if (requester.role == MemberRole.OWNER) {
                repository.pendingInvitations(requester.householdId)
            } else {
                emptyList()
            }
        return FamilyOverview(
            requester,
            repository.members(requester.householdId),
            pendingInvitations,
        )
    }

    @Suppress("ReturnCount")
    fun invite(
        ownerTelegramUserId: Long,
        invitedTelegramUserId: Long,
        source: String,
    ): FamilyChange<FamilyInvitation> {
        val owner = owner(ownerTelegramUserId) ?: return denied()
        if (invitedTelegramUserId <= 0) return FamilyChange.Rejected("Нужен положительный числовой Telegram ID.")
        if (invitedTelegramUserId == ownerTelegramUserId) {
            return FamilyChange.Rejected("Владелец уже состоит в семейном бюджете.")
        }
        repository.invitationBySource(owner.householdId, source)?.let {
            return FamilyChange.Applied(it, changed = false)
        }
        repository.activeMember(invitedTelegramUserId)?.let {
            return FamilyChange.Rejected("Этот Telegram ID уже состоит в активном бюджете.")
        }
        repository.pendingInvitation(invitedTelegramUserId)?.let {
            return if (it.householdId == owner.householdId) {
                FamilyChange.Applied(it, changed = false)
            } else {
                FamilyChange.Rejected("Для этого Telegram ID уже есть приглашение в другой бюджет.")
            }
        }
        val invitation = FamilyInvitation(UUID.randomUUID(), owner.householdId, invitedTelegramUserId, now())
        return if (repository.createInvitation(invitation, owner.id, source)) {
            FamilyChange.Applied(invitation)
        } else {
            FamilyChange.Rejected("Приглашение не создано: состояние участников изменилось.")
        }
    }

    @Suppress("ReturnCount") // Guard each invitation state before creating a membership.
    fun accept(invitedTelegramUserId: Long): FamilyChange<FamilyMember> {
        repository.activeMember(invitedTelegramUserId)?.let { return FamilyChange.Applied(it, changed = false) }
        val invitation =
            repository.pendingInvitation(invitedTelegramUserId)
                ?: return FamilyChange.Rejected("Активного приглашения нет.")
        val member =
            FamilyMember(
                UUID.randomUUID(),
                invitation.householdId,
                invitedTelegramUserId,
                MemberRole.MEMBER,
                reportsEnabled = false,
                joinedAt = now(),
            )
        return if (repository.acceptInvitation(invitation, member, now())) {
            FamilyChange.Applied(member)
        } else {
            FamilyChange.Rejected("Приглашение уже использовано или отозвано.")
        }
    }

    @Suppress("ReturnCount") // Owner, active member, and pending invitation are distinct outcomes.
    fun revoke(
        ownerTelegramUserId: Long,
        targetTelegramUserId: Long,
    ): FamilyChange<Long> {
        val owner = owner(ownerTelegramUserId) ?: return denied()
        if (targetTelegramUserId == ownerTelegramUserId) return FamilyChange.Rejected("Нельзя отозвать доступ OWNER.")
        val now = now()
        val revoked = repository.revokeMember(owner.householdId, targetTelegramUserId, now)
        if (revoked != null) {
            repository.stopReports(revoked.id)
            return FamilyChange.Applied(targetTelegramUserId)
        }
        return if (repository.revokeInvitation(owner.householdId, targetTelegramUserId, now)) {
            FamilyChange.Applied(targetTelegramUserId)
        } else {
            FamilyChange.Rejected("Участник или приглашение недоступны.")
        }
    }

    @Suppress("ReturnCount") // Reject non-owners and missing active members before changing jobs.
    fun configureReports(
        ownerTelegramUserId: Long,
        targetTelegramUserId: Long,
        enabled: Boolean,
    ): FamilyChange<FamilyMember> {
        val owner = owner(ownerTelegramUserId) ?: return denied()
        val member =
            repository.setReportsEnabled(owner.householdId, targetTelegramUserId, enabled)
                ?: return FamilyChange.Rejected("Активный участник недоступен.")
        if (!enabled) repository.stopReports(member.id)
        return FamilyChange.Applied(member)
    }

    private fun owner(telegramUserId: Long): FamilyMember? =
        repository.activeMember(telegramUserId)?.takeIf { it.role == MemberRole.OWNER }

    private fun denied(): FamilyChange.Rejected = FamilyChange.Rejected("Команда доступна только OWNER.")

    private fun now(): Instant = clock.instant().truncatedTo(ChronoUnit.MICROS)
}
