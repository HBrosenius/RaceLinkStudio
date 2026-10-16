package se.racelink.server

import se.racelink.publish.PublishAthlete
import se.racelink.publish.PublishEnvelope
import se.racelink.publish.CoachProjection
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.nio.file.Path
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Base64
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** How a skier is identified on their public page. `BIB` hides the name entirely. */
enum class NamePolicy { FIRST, BIB }

/**
 * The password the coach sets on the tablet for the session's link index, kept only as a salted
 * SHA-256 so the server never stores the password itself. A per-session salt means two sessions that
 * happen to share a password do not share a digest.
 */
internal class IndexPassword private constructor(private val salt: ByteArray, private val hash: ByteArray) {
    fun matches(candidate: String): Boolean = MessageDigest.isEqual(hash, hashOf(candidate, salt))
    fun encoded(): List<String> = listOf(salt, hash).map { Base64.getEncoder().encodeToString(it) }

    companion object {
        private const val SALT_BYTES = 16
        fun restore(parts: List<String>): IndexPassword {
            require(parts.size == 2)
            val bytes = parts.map { Base64.getDecoder().decode(it) }
            require(bytes[0].size == SALT_BYTES && bytes[1].size == 32)
            return IndexPassword(bytes[0], bytes[1])
        }

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
 * after a flaky link can never move the public page backwards. An optional atomic file snapshot
 * keeps projections, receipt times and password hashes across restarts on a single server.
 *
 * Retention is measured from the last *accepted* publish, which for a closed session is
 * effectively its close time. Purges drop the session and its token index together, so an expired
 * link 404s instead of leaking a stale page.
 */
class ProjectionStore(private val dataFile: Path? = null, private val now: () -> Instant = Instant::now) {
    @Serializable
    private data class SavedSession(val envelope: PublishEnvelope, val receivedAt: String, val password: List<String>? = null,
        val coaching: CoachProjection? = null)
    @Serializable
    private data class Snapshot(val version: Int = 1, val sessions: List<SavedSession>)
    private data class Stored(
        val revision: Long,
        val envelope: PublishEnvelope,
        val receivedAt: Instant,
        /** The coach-set index password for this session's publish, or null when never set. */
        val indexPassword: IndexPassword? = null,
        val coaching: CoachProjection? = null,
    )

    private val bySession = ConcurrentHashMap<String, Stored>()
    private val tokenToSession = ConcurrentHashMap<String, String>()
    private var durableState: Map<String, Stored> = emptyMap()

    init {
        if (dataFile != null && Files.exists(dataFile)) {
            val snapshot = Json.decodeFromString<Snapshot>(Files.readString(dataFile))
            require(snapshot.version == 1) { "Unsupported projection store version" }
            snapshot.sessions.forEach {
                bySession[it.envelope.session.id] = Stored(it.envelope.revision, it.envelope,
                    Instant.parse(it.receivedAt), it.password?.let(IndexPassword::restore), it.coaching)
            }
            rebuildTokens()
        }
        durableState = bySession.toMap()
    }

    private fun rebuildTokens() {
        tokenToSession.clear()
        bySession.values.forEach { stored ->
            stored.envelope.athletes.forEach { tokenToSession[it.token] = stored.envelope.session.id }
        }
    }

    /** Atomic replacement; an unreadable/corrupt snapshot fails startup rather than losing data. */
    private fun persist() {
        val target = dataFile?.toAbsolutePath() ?: return
        var temporary: Path? = null
        try {
            Files.createDirectories(target.parent)
            temporary = Files.createTempFile(target.parent, ".racelink-", ".tmp")
            val snapshot = Snapshot(sessions = bySession.values.map {
                SavedSession(it.envelope, it.receivedAt.toString(), it.indexPassword?.encoded(), it.coaching)
            })
            java.nio.channels.FileChannel.open(temporary, java.nio.file.StandardOpenOption.WRITE).use {
                val bytes = java.nio.ByteBuffer.wrap(Json.encodeToString(snapshot).toByteArray(Charsets.UTF_8))
                while (bytes.hasRemaining()) it.write(bytes)
                it.force(true)
            }
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            durableState = bySession.toMap()
        } catch (failure: Exception) {
            bySession.clear()
            bySession.putAll(durableState)
            rebuildTokens()
            throw failure
        } finally {
            temporary?.let { Files.deleteIfExists(it) }
        }
    }

    /** @return the revision now stored (the incoming one when accepted, otherwise the newer existing). */
    @Synchronized fun put(envelope: PublishEnvelope, coaching: CoachProjection? = null): Long {
        var storedRevision = envelope.revision
        bySession.compute(envelope.session.id) { _, existing ->
            if (existing == null || envelope.revision > existing.revision) {
                Stored(envelope.revision, envelope, now(), existing?.indexPassword, coaching)
            } else {
                storedRevision = existing.revision
                existing
            }
        }
        // A correction can add or rename athletes, so rebuild this session's token index.
        rebuildTokens()
        persist()
        return storedRevision
    }

    /**
     * Records the password the coach just published for [sessionId]; a blank value clears it, so the
     * coach can turn the protection off again from the tablet. Trimmed, because a trailing space is
     * invisible in the tablet's field but would have to be typed into the browser's prompt.
     */
    @Synchronized fun setIndexPassword(sessionId: String, password: String) {
        bySession.computeIfPresent(sessionId) { _, existing ->
            existing.copy(indexPassword = password.trim().takeIf { it.isNotEmpty() }?.let(IndexPassword::of))
        }
        persist()
    }

    /** @return true when [candidate] opens the index for at least one live session. */
    fun acceptsIndexPassword(candidate: String): Boolean =
        bySession.values.any { it.indexPassword?.matches(candidate) == true }

    /** @return true when any live session protects the index with its own password. */
    fun hasIndexPassword(): Boolean = bySession.values.any { it.indexPassword != null }

    fun session(id: String): PublishEnvelope? = bySession[id]?.envelope
    fun coaching(id: String): CoachProjection? = bySession[id]?.coaching

    fun athlete(token: String): Pair<PublishEnvelope, PublishAthlete>? {
        val sessionId = tokenToSession[token] ?: return null
        val envelope = bySession[sessionId]?.envelope ?: return null
        val athlete = envelope.athletes.firstOrNull { it.token == token } ?: return null
        return envelope to athlete
    }

    fun sessions(): List<PublishEnvelope> = bySession.values.map { it.envelope }

    /** Hard-deletes one session and every token that pointed at it. @return true when it existed. */
    @Synchronized fun delete(sessionId: String): Boolean {
        val existed = bySession.remove(sessionId) != null
        if (existed) tokenToSession.entries.removeIf { it.value == sessionId }
        if (existed) persist()
        return existed
    }

    /** Drops sessions unseen for [retentionHours]. A non-positive window disables expiry. @return count purged. */
    @Synchronized fun purge(retentionHours: Int): Int {
        if (retentionHours <= 0) return 0
        val cutoff = now().minus(Duration.ofHours(retentionHours.toLong()))
        val expired = mutableListOf<String>()
        bySession.entries.removeIf { (id, stored) ->
            val stale = stored.receivedAt.isBefore(cutoff)
            if (stale) expired += id
            stale
        }
        expired.forEach { id -> tokenToSession.entries.removeIf { it.value == id } }
        if (expired.isNotEmpty()) persist()
        return expired.size
    }
}
