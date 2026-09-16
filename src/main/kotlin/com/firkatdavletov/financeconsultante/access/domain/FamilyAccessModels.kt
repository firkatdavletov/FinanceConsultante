package com.firkatdavletov.financeconsultante.access.domain

import java.time.Instant
import java.util.UUID

enum class MemberRole {
    OWNER,
    MEMBER,
}

data class FamilyMember(
    val id: UUID,
    val householdId: UUID,
    val telegramUserId: Long,
    val role: MemberRole,
    val reportsEnabled: Boolean,
    val joinedAt: Instant,
)

data class FamilyInvitation(
    val id: UUID,
    val householdId: UUID,
    val invitedTelegramUserId: Long,
    val createdAt: Instant,
)

data class FamilyOverview(
    val requester: FamilyMember,
    val members: List<FamilyMember>,
    val pendingInvitations: List<FamilyInvitation>,
)
