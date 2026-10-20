package com.firkatdavletov.financeconsultante.auth.infrastructure

import com.firkatdavletov.financeconsultante.auth.application.AuthenticatedUser
import com.firkatdavletov.financeconsultante.auth.application.FirebaseUserStore
import com.firkatdavletov.financeconsultante.auth.application.VerifiedFirebaseIdentity
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
class JdbcFirebaseUserStore(
    private val jdbc: JdbcClient,
) : FirebaseUserStore {
    override fun upsert(identity: VerifiedFirebaseIdentity): AuthenticatedUser =
        jdbc
            .sql(
                """
                INSERT INTO firebase_user(id, firebase_uid, display_name, email)
                VALUES (:id, :uid, :name, :email)
                ON CONFLICT (firebase_uid) DO UPDATE SET display_name = EXCLUDED.display_name, email = EXCLUDED.email
                RETURNING id, firebase_uid, display_name, email
                """.trimIndent(),
            ).param("id", UUID.randomUUID())
            .param("uid", identity.uid)
            .param("name", identity.displayName)
            .param("email", identity.email)
            .query { rs, _ ->
                AuthenticatedUser(
                    rs.getObject("id", UUID::class.java),
                    rs.getString("firebase_uid"),
                    rs.getString("display_name"),
                    rs.getString("email"),
                )
            }.single()
}
