package com.firkatdavletov.financeconsultante.auth.infrastructure

import com.firkatdavletov.financeconsultante.auth.application.FirebaseAccountAccess
import com.firkatdavletov.financeconsultante.auth.application.InvalidFirebaseToken
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Component
import java.security.MessageDigest
import java.util.UUID

fun deletionHash(value: String): String =
    MessageDigest
        .getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

@Component
class JdbcFirebaseAccountAccess(
    private val jdbc: JdbcClient,
) : FirebaseAccountAccess {
    override fun active(uid: String) {
        lock("profile:$uid", true)
        if (jdbc
                .sql("SELECT EXISTS(SELECT 1 FROM firebase_profile_deletion WHERE uid_hash = ?)")
                .param(deletionHash(uid))
                .query(Boolean::class.java)
                .single()
        ) {
            throw InvalidFirebaseToken()
        }
    }

    override fun budget(
        uid: String,
        budgetId: UUID,
    ) {
        active(uid)
        lock("profile-budget:$budgetId", true)
    }

    fun lock(
        key: String,
        shared: Boolean,
    ) {
        val function = if (shared) "pg_advisory_xact_lock_shared" else "pg_advisory_xact_lock"
        jdbc
            .sql("SELECT $function(hashtextextended(?, 0))")
            .param(key)
            .query { _, _ -> Unit }
            .single()
    }
}
