package com.firkatdavletov.financeconsultante.mobileapi.application

import com.firkatdavletov.financeconsultante.access.application.FamilyBudgetRepository
import com.firkatdavletov.financeconsultante.auth.application.FirebaseAccountAccess
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Instant
import java.util.Locale
import java.util.UUID

data class AcceptFirebaseInvitation(
    val code: String,
)

data class FirebaseBudgetMember(
    val id: UUID,
    val name: String,
    val role: String,
    val joinedAt: Instant,
    val currentUser: Boolean,
)

data class FirebaseBudgetMembers(
    val budgetId: UUID,
    val members: List<FirebaseBudgetMember>,
)

data class FirebaseInvitationAcceptance(
    val id: UUID,
    val budgetId: UUID,
    val expiresAt: Instant,
    val revoked: Boolean,
    val accepted: Boolean,
    val acceptedUid: String?,
    val acceptedMemberId: UUID?,
)

data class FirebaseMembership(
    val memberId: UUID,
    val role: String,
    val active: Boolean,
)

interface FirebaseFamilyBudgetStore {
    fun invitation(
        hash: String,
        lock: Boolean,
    ): FirebaseInvitationAcceptance?

    fun lockActiveBudget(budgetId: UUID): Boolean

    fun membership(
        uid: String,
        budgetId: UUID,
    ): FirebaseMembership?

    fun addMember(
        uid: String,
        budgetId: UUID,
        now: Instant,
    ): UUID

    fun accept(
        invitationId: UUID,
        uid: String,
        memberId: UUID,
        now: Instant,
    )

    fun summary(
        budgetId: UUID,
        memberId: UUID,
    ): FirebaseBudgetSummary

    fun members(
        uid: String,
        budgetId: UUID,
    ): List<FirebaseBudgetMember>

    fun memberRole(
        budgetId: UUID,
        memberId: UUID,
    ): String?

    fun revoke(
        budgetId: UUID,
        memberId: UUID,
        now: Instant,
    ): Boolean
}

@Service
class FirebaseFamilyBudgets(
    private val budgets: FirebaseBudgets,
    private val store: FirebaseFamilyBudgetStore,
    private val codes: FirebaseInvitationCodes,
    private val family: FamilyBudgetRepository,
    private val clock: Clock,
    private val accountAccess: FirebaseAccountAccess,
) {
    @Transactional
    fun join(
        uid: String,
        request: AcceptFirebaseInvitation,
    ): FirebaseBudgetSummary {
        accountAccess.active(uid)
        val hash = codes.hash(normalize(request.code))
        val preview = store.invitation(hash, false) ?: invalid()
        accountAccess.budget(uid, preview.budgetId)
        if (!store.lockActiveBudget(preview.budgetId)) invalid()
        val invitation = store.invitation(hash, true) ?: invalid()
        val membership = store.membership(uid, invitation.budgetId)
        val memberId =
            if (invitation.accepted) {
                replay(uid, invitation, membership)
            } else {
                if (invitation.revoked || !invitation.expiresAt.isAfter(clock.instant())) invalid()
                if (membership?.active == true && membership.role == "OWNER") throw CloudBudgetFailure("already_member")
                val member =
                    membership?.takeIf { it.active }?.memberId
                        ?: store.addMember(uid, invitation.budgetId, clock.instant())
                store.accept(invitation.id, uid, member, clock.instant())
                member
            }
        return store.summary(invitation.budgetId, memberId)
    }

    @Transactional
    fun members(
        uid: String,
        budgetId: UUID,
    ): FirebaseBudgetMembers {
        budgets.budget(uid, budgetId)
        return FirebaseBudgetMembers(budgetId, store.members(uid, budgetId))
    }

    @Transactional
    fun revoke(
        uid: String,
        budgetId: UUID,
        memberId: UUID,
    ) {
        if (budgets.budget(uid, budgetId).scope.role != "OWNER") throw MobileApiAccessDenied()
        // UPDATE of the target member waits for in-flight financial transactions' shared locks.
        if (store.memberRole(budgetId, memberId) == "OWNER") throw MobileApiAccessDenied()
        if (store.revoke(budgetId, memberId, clock.instant())) family.stopReports(memberId)
    }

    private fun replay(
        uid: String,
        invitation: FirebaseInvitationAcceptance,
        membership: FirebaseMembership?,
    ): UUID {
        if (invitation.acceptedUid != uid || membership?.active != true ||
            membership.memberId != invitation.acceptedMemberId
        ) {
            invalid()
        }
        return membership.memberId
    }

    private fun normalize(value: String): String {
        if (value.length > MAX_INPUT_LENGTH) invalid()
        val compact = value.uppercase(Locale.ROOT).filterNot { it == '-' || it.isWhitespace() }
        if (!Regex("[0-9A-HJKMNP-TV-Z]{20}").matches(compact)) invalid()
        return compact.chunked(GROUP_LENGTH).joinToString("-")
    }

    private fun invalid(): Nothing = throw CloudBudgetFailure("invalid_invitation")

    companion object {
        private const val MAX_INPUT_LENGTH = 80
        private const val GROUP_LENGTH = 5
    }
}
