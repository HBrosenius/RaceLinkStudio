package se.racelink.studio

import android.content.Context

internal data class PublishConfig(
    val serverUrl: String = "",
    val token: String = "",
    val enabled: Boolean = false,
    /** When true, the published page shows only the bib, never the skier's name. */
    val bibOnly: Boolean = false,
) {
    private val base get() = serverUrl.trim().trimEnd('/')
    val publishEndpoint: String get() = "$base/api/v1/publish"
    val configured: Boolean get() = serverUrl.isNotBlank() && token.isNotBlank()
}

internal interface PublishSettings {
    fun read(): PublishConfig
    fun write(config: PublishConfig): Boolean

    /**
     * The password the coach set for [sessionId]'s link index on the server. Empty means no opinion, and
     * the index falls back to the server's own rule. It is kept per session so a new event starts clean
     * and a shared password is not carried from one group of children to the next.
     */
    fun indexPassword(sessionId: String): String
    fun writeIndexPassword(sessionId: String, password: String): Boolean
}

internal class PublishConfigStore(context: Context) : PublishSettings {
    private val preferences = context.getSharedPreferences("publish", Context.MODE_PRIVATE)

    override fun read() = PublishConfig(
        serverUrl = preferences.getString("server-url", "").orEmpty(),
        token = preferences.getString("token", "").orEmpty(),
        enabled = preferences.getBoolean("enabled", false),
        bibOnly = preferences.getBoolean("bib-only", false),
    )

    override fun write(config: PublishConfig): Boolean = preferences.edit()
        .putString("server-url", config.serverUrl.trim())
        .putString("token", config.token.trim())
        .putBoolean("enabled", config.enabled)
        .putBoolean("bib-only", config.bibOnly)
        .commit()

    // Trimmed on the way in: a trailing space is invisible in the field, but it would also have to be
    // typed into the browser's prompt and the password would appear not to work.
    override fun indexPassword(sessionId: String): String =
        preferences.getString("index-password:$sessionId", "").orEmpty()

    override fun writeIndexPassword(sessionId: String, password: String): Boolean =
        preferences.edit().putString("index-password:$sessionId", password.trim()).commit()
}

/** Monotonic per-session revision plus a pending marker, persisted so a retry survives restart. */
internal interface PublishJournal {
    fun nextRevision(sessionId: String): Long
    fun recordDelivered(sessionId: String, revision: Long, at: String)
    fun markPending(sessionId: String)
    fun isPending(sessionId: String): Boolean
    fun lastDeliveredAt(sessionId: String): String?
}

internal class PublishStateStore(context: Context) : PublishJournal {
    private val preferences = context.getSharedPreferences("publish-state", Context.MODE_PRIVATE)

    override fun nextRevision(sessionId: String): Long = preferences.getLong("revision:$sessionId", 0L) + 1

    override fun recordDelivered(sessionId: String, revision: Long, at: String) {
        preferences.edit()
            .putLong("revision:$sessionId", revision)
            .putString("delivered:$sessionId", at)
            .remove("pending:$sessionId")
            .apply()
    }

    override fun markPending(sessionId: String) { preferences.edit().putBoolean("pending:$sessionId", true).apply() }
    override fun isPending(sessionId: String): Boolean = preferences.getBoolean("pending:$sessionId", false)
    override fun lastDeliveredAt(sessionId: String): String? = preferences.getString("delivered:$sessionId", null)
}
