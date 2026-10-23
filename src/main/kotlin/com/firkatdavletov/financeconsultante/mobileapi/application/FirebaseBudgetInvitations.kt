package com.firkatdavletov.financeconsultante.mobileapi.application

import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID

data class FirebaseInvitationInfo(
    val id: UUID,
    val budgetId: UUID,
    val expiresAt: Instant,
)

data class FirebaseInvitationStatus(
    val budgetId: UUID,
    val current: FirebaseInvitationInfo?,
)

data class IssuedFirebaseInvitation(
    val id: UUID,
    val budgetId: UUID,
    val expiresAt: Instant,
    val code: String,
)

interface FirebaseBudgetInvitationStore {
    fun lock(budgetId: UUID)

    fun current(
        budgetId: UUID,
        now: Instant,
    ): FirebaseInvitationInfo?

    fun replace(
        invitation: FirebaseInvitationInfo,
        memberId: UUID,
        codeHash: String,
        now: Instant,
    )

    fun revoke(
        budgetId: UUID,
        invitationId: UUID,
        now: Instant,
    )
}

@Component
class FirebaseInvitationCodes {
    private val random = SecureRandom()

    fun generate(): String =
        (1..CODE_LENGTH)
            .map { ALPHABET[random.nextInt(ALPHABET.length)] }
            .joinToString("")
            .chunked(GROUP_LENGTH)
            .joinToString("-")

    fun hash(code: String): String =
        MessageDigest
            .getInstance("SHA-256")
            .digest(code.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    companion object {
        private const val ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"
        private const val CODE_LENGTH = 20
        private const val GROUP_LENGTH = 5
    }
}

/** Authentication precedes this transaction; membership locks cover every invitation mutation. */
@Service
class FirebaseBudgetInvitations(
    private val budgets: FirebaseBudgets,
    private val store: FirebaseBudgetInvitationStore,
    private val codes: FirebaseInvitationCodes,
    private val clock: Clock,
) {
    @Transactional
    fun current(
        uid: String,
        budgetId: UUID,
    ): FirebaseInvitationStatus {
        owner(uid, budgetId)
        return FirebaseInvitationStatus(budgetId, store.current(budgetId, clock.instant()))
    }

    @Transactional
    fun create(
        uid: String,
        budgetId: UUID,
    ): IssuedFirebaseInvitation {
        val memberId = owner(uid, budgetId)
        store.lock(budgetId)
        val now = clock.instant()
        val invitation = FirebaseInvitationInfo(UUID.randomUUID(), budgetId, now.plus(Duration.ofHours(VALID_HOURS)))
        val code = codes.generate()
        store.replace(invitation, memberId, codes.hash(code), now)
        return IssuedFirebaseInvitation(invitation.id, budgetId, invitation.expiresAt, code)
    }

    @Transactional
    fun revoke(
        uid: String,
        budgetId: UUID,
        invitationId: UUID,
    ) {
        owner(uid, budgetId)
        store.lock(budgetId)
        store.revoke(budgetId, invitationId, clock.instant())
    }

    private fun owner(
        uid: String,
        budgetId: UUID,
    ): UUID {
        val scope = budgets.budget(uid, budgetId).scope
        if (scope.role != "OWNER") throw MobileApiAccessDenied()
        return scope.memberId
    }

    companion object {
        private const val VALID_HOURS = 24L
    }
}
