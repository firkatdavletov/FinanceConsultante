package com.firkatdavletov.financeconsultante.auth.application

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.util.UUID

class FirebaseAuthenticationServiceTests {
    @Test fun `missing malformed and oversized credentials are rejected before verification and storage`() {
        val service =
            FirebaseAuthenticationService(
                { error("Must not verify malformed Authorization") },
                { error("Must not store an unverified identity") },
            )
        listOf(null, "", "Basic synthetic", "Bearer ", "Bearer token extra", "Bearer " + "a".repeat(16385))
            .forEach { value ->
                assertThatThrownBy { service.authenticate(value) }.isInstanceOf(InvalidFirebaseToken::class.java)
            }
    }

    @Test fun `unverified email cannot create a user`() {
        val service =
            FirebaseAuthenticationService(
                { throw EmailNotVerified() },
                { error("Unverified tokens must not create users") },
            )
        assertThatThrownBy { service.authenticate("Bearer synthetic-token") }
            .isInstanceOf(EmailNotVerified::class.java)
    }

    @Test fun `only verified claims reach the store`() {
        val verified = VerifiedFirebaseIdentity("synthetic-uid", "Synthetic User", "user@example.test")
        val expected = AuthenticatedUser(UUID.randomUUID(), verified.uid, verified.displayName, verified.email)
        val service =
            FirebaseAuthenticationService(
                { token ->
                    assertThat(token).isEqualTo("synthetic-token")
                    verified
                },
                { identity ->
                    assertThat(identity).isEqualTo(verified)
                    expected
                },
            )
        assertThat(service.authenticate("bEaReR synthetic-token")).isEqualTo(expected)
    }
}
