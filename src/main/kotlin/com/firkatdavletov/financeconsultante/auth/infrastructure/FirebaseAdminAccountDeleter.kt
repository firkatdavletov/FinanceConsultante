package com.firkatdavletov.financeconsultante.auth.infrastructure

import com.firkatdavletov.financeconsultante.auth.application.FirebaseAccountDeleter
import com.firkatdavletov.financeconsultante.auth.application.FirebaseAuthenticationUnavailable
import com.google.firebase.auth.AuthErrorCode
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthException

class FirebaseAdminAccountDeleter(
    private val auth: () -> FirebaseAuth,
) : FirebaseAccountDeleter {
    override fun delete(uid: String) {
        try {
            auth().deleteUser(uid)
        } catch (error: FirebaseAuthException) {
            if (error.authErrorCode != AuthErrorCode.USER_NOT_FOUND) throw FirebaseAuthenticationUnavailable()
        }
    }
}
