package com.firkatdavletov.financeconsultante.auth.application

import java.util.UUID

interface FirebaseAccountAccess {
    fun active(uid: String)

    fun budget(
        uid: String,
        budgetId: UUID,
    )
}

fun interface FirebaseAccountDeleter {
    fun delete(uid: String)
}

class ProfileDeletionFailure(
    val code: String,
    val status: Int,
) : RuntimeException(code)
