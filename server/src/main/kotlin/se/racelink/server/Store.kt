package se.racelink.server

import se.racelink.publish.PublishAthlete
import se.racelink.publish.PublishEnvelope
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/** How a skier is identified on their public page. `BIB` hides the name entirely. */
enum class NamePolicy { FIRST, BIB }

/**
 * The password the coach sets on the tablet for the session's link index, kept only as a salted
 * SHA-256 so the server never stores the password itself. A per-session salt means two sessions that
 * happen to share a password do not share a digest.
 */
internal class IndexPassword private constructor(private val salt: ByteArray, private val hash: ByteArray) {
    fun matches(candidate: String): Boolean = MessageDigest.isEqual(hash, hashOf(candidate, salt))

    companion object {
        private const val SALT_BYTES = 16

        fun of(password: String): IndexPassword {
            val salt = ByteArray(SALT_BYTES).also { SecureRandom().nextBytes(it) }
            return IndexPassword(salt, hashOf(password, salt))
        }

        private fun hashOf(password: String, salt: ByteArray): ByteArray =
            MessageDigest.getInstance("SHA-256")
                .apply {
                    update(salt)
                    update(password.toByteArray(Charsets.UTF_8))
                }
                .digest()
    }
}

data class ServerConfig(
    val publishToken: String,
    /** Hours after the last accepted publish before a session is purged; 0 disables expiry. */
    val retentionHours: Int = 24,
    val namePolicy: NamePolicy = NamePolicy.FIRST,
    /**
     * Optional operator override for the index password. The normal path is the password the coach
     * sets on the tablet for a session, which travels with that session's publish; this one is for a
     * server run by someone who is not also holding the tablet. Only the password is checked, never a
     * username, because it is a single shared secret.
     */
    val adminPassword: String? = null,
) {
    companion object {
        const val DEV_TOKEN = "dev-token"
        fun fromEnv(env: Map<String, String> = System.getenv()) = ServerConfig(
            publishToken = env["RACELINK_PUBLISH_TOKEN"]?.takeIf { it.isNotBlank() } ?: DEV_TOKEN,
            retentionHours = env["RACELINK_RETENTION_HOURS"]?.toIntOrNull() ?: 24,
            namePolicy = when (env["RACELINK_NAME_POLICY"]?.trim()?.lowercase()) {
                "bib" -> NamePolicy.BIB
                else -> NamePolicy.FIRST
            },
            adminPassword = env["RACELINK_ADMIN_PASSWORD"]?.takeIf { it.isNotBlank() },
        )
    }
}

/**
 * Holds the latest accepted projection per session and rejects stale revisions, so a retried publish
 * after a flaky link can never move the public page backwards. In-memory for the spike; the surface
 * is deliberately small so a durable (Postgres) store can replace it without touching the routes.
 *
 * Retention is measured from the last *accepted* publish, which for a closed session is
 * effectively its close time. Purges drop the session and its token index together, so an expired
 * link 404s instead of leaking a stale page.
 */
class ProjectionStore(private val now: () -> Instant = Instant::now) {
    private data class Stored(
        val revision: Long,
        val envelope: PublishEnvelope,
        val receivedAt: Instant,
        /** The coach-set index password for this session's publish, or null when never set. */
        val indexPassword: IndexPassword? = null,
    )

    private val bySession = ConcurrentHashMap<String, Stored>()
    private val tokenToSession = ConcurrentHashMap<String, String>()

    /** @return the revision now stored (the incoming one when accepted, otherwise the newer existing). */
    fun put(envelope: PublishEnvelope): Long {
        var storedRevision = envelope.revision
        bySession.compute(envelope.session.id) { _, existing ->
            if (existing == null || envelope.revision > existing.revision) {
                Stored(envelope.revision, envelope, now(), existing?.indexPassword)
            } else {
                storedRevision = existing.revision
                existing
            }
        }
        // A correction can add or rename athletes, so rebuild this session's token index.
        tokenToSession.entries.removeIf { it.value == envelope.session.id }
        envelope.athletes.forEach { tokenToSession[it.token] = envelope.session.id }
        return storedRevision
    }

    /**
     * Records the password the coach just published for [sessionId]; a blank value clears it, so the
     * coach can turn the protection off again from the tablet. Trimmed, because a trailing space is
     * invisible in the tablet's field but would have to be typed into the browser's prompt.
     */
    fun setIndexPassword(sessionId: String, password: String) {
        bySession.computeIfPresent(sessionId) { _, existing ->
            existing.copy(indexPassword = password.trim().takeIf { it.isNotEmpty() }?.let(IndexPassword::of))
        }
    }

    /** @return true when [candidate] opens the index for at least one live session. */
    fun acceptsIndexPassword(candidate: String): Boolean =
        bySession.values.any { it.indexPassword?.matches(candidate) == true }

    /** @return true when any live session protects the index with its own password. */
    fun hasIndexPassword(): Boolean = bySession.values.any { it.indexPassword != null }

    fun session(id: String): PublishEnvelope? = bySession[id]?.envelope

    fun athlete(token: String): Pair<PublishEnvelope, PublishAthlete>? {
        val sessionId = tokenToSession[token] ?: return null
        val envelope = bySession[sessionId]?.envelope ?: return null
        val athlete = envelope.athletes.firstOrNull { it.token == token } ?: return null
        return envelope to athlete
    }

    fun sessions(): List<PublishEnvelope> = bySession.values.map { it.envelope }

    /** Hard-deletes one session and every token that pointed at it. @return true when it existed. */
    fun delete(sessionId: String): Boolean {
        val existed = bySession.remove(sessionId) != null
        if (existed) tokenToSession.entries.removeIf { it.value == sessionId }
        return existed
    }

    /** Drops sessions unseen for [retentionHours]. A non-positive window disables expiry. @return count purged. */
    fun purge(retentionHours: Int): Int {
        if (retentionHours <= 0) return 0
        val cutoff = now().minus(Duration.ofHours(retentionHours.toLong()))
        val expired = mutableListOf<String>()
        bySession.entries.removeIf { (id, stored) ->
            val stale = stored.receivedAt.isBefore(cutoff)
            if (stale) expired += id
            stale
        }
        expired.forEach { id -> tokenToSession.entries.removeIf { it.value == id } }
        return expired.size
    }
}
