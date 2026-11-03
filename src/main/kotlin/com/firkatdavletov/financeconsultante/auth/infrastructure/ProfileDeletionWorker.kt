package com.firkatdavletov.financeconsultante.auth.infrastructure

import com.firkatdavletov.financeconsultante.auth.application.FirebaseAccountDeleter
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.util.UUID

@Component
@ConditionalOnProperty(prefix = "app.firebase-auth", name = ["enabled"], havingValue = "true")
class ProfileDeletionWorker(
    private val jdbc: JdbcClient,
    private val deleter: FirebaseAccountDeleter,
    registry: MeterRegistry,
) {
    private val errors = registry.counter("profile.deletion.errors")

    init {
        registry.gauge("profile.deletion.pending", this) { it.pending().toDouble() }
        registry.gauge("profile.deletion.oldest.seconds", this) { it.oldest() }
    }

    @Scheduled(fixedDelayString = "\${app.firebase-auth.deletion-poll-delay:PT10S}")
    fun tick() {
        try {
            runOnce()
        } catch (_: RuntimeException) {
            // Scheduler must not log SQL exceptions containing personal row values.
            errors.increment()
        }
    }

    @Suppress("ReturnCount") // Idle, retry scheduled, or completed are distinct worker outcomes.
    fun runOnce(): Boolean {
        val lease = UUID.randomUUID()
        val job =
            jdbc
                .sql(
                    """
                    UPDATE firebase_profile_deletion SET lease_token=:lease, lease_until=now()+interval '1 minute', attempts=attempts+1
                    WHERE uid_hash=(SELECT uid_hash FROM firebase_profile_deletion
                        WHERE status='PENDING' AND next_attempt_at<=now() AND (lease_until IS NULL OR lease_until<=now())
                        ORDER BY next_attempt_at FOR UPDATE SKIP LOCKED LIMIT 1)
                    RETURNING uid_hash, firebase_uid
                    """.trimIndent(),
                ).param("lease", lease)
                .query { rs, _ ->
                    rs.getString("uid_hash") to
                        rs.getString("firebase_uid")
                }.optional()
                .orElse(null) ?: return false
        try {
            deleter.delete(job.second)
        } catch (_: RuntimeException) {
            errors.increment()
            jdbc
                .sql(
                    """
                    UPDATE firebase_profile_deletion SET lease_token=NULL, lease_until=NULL,
                    next_attempt_at=now()+least(3600, 10*power(2, least(attempts-1, 9))) * interval '1 second'
                    WHERE uid_hash=? AND lease_token=?
                    """.trimIndent(),
                ).params(job.first, lease)
                .update()
            return true
        }
        // A DB failure here leaves the lease recoverable; repeating delete is safe.
        jdbc
            .sql(
                """
                UPDATE firebase_profile_deletion SET status='COMPLETED', completed_at=now(), firebase_uid=NULL,
                    lease_token=NULL, lease_until=NULL WHERE uid_hash=? AND lease_token=?
                """.trimIndent(),
            ).params(job.first, lease)
            .update()
        return true
    }

    private fun pending(): Long =
        jdbc
            .sql("SELECT count(*) FROM firebase_profile_deletion WHERE status='PENDING'")
            .query(Long::class.java)
            .single()

    private fun oldest(): Double =
        jdbc
            .sql(
                """
                SELECT COALESCE(extract(epoch FROM now()-min(created_at)), 0)::float8
                FROM firebase_profile_deletion WHERE status='PENDING'
                """.trimIndent(),
            ).query(Double::class.java)
            .single()
}
