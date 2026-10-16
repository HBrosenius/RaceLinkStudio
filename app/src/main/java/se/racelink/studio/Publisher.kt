package se.racelink.studio

import se.racelink.publish.PublishJson
import se.racelink.publish.PublishRequest

/**
 * Best-effort publishing of committed sessions. It is called off the main thread and never throws
 * to the caller: a failure is recorded as pending and retried on the next commit or app start, so
 * losing the server or its link can never affect capture, edits or export.
 */
internal class Publisher(
    private val settings: PublishSettings,
    private val journal: PublishJournal,
    private val transport: PublishTransport,
    private val now: () -> String = { java.time.Instant.now().toString() },
) {
    fun config(): PublishConfig = settings.read()

    /** @return a Swedish status message for the settings screen, or null for a silent no-op. */
    fun publish(state: Training, force: Boolean = false): String? {
        val config = settings.read()
        if (!force && !config.enabled) return null
        if (!config.configured) return if (force) "Ange serveradress och publiceringstoken först." else null
        val at = now()
        val revision = journal.nextRevision(state.sessionId)
        val shownName: (Athlete) -> String =
            if (config.bibOnly) ({ _: Athlete -> "" }) else ({ athlete: Athlete -> athlete.name.substringBefore(' ') })
        val body = PublishJson.encode(
            PublishRequest(
                PublishProjection.of(state, revision, at, shownName),
                // Sent with every publish, empty included: that is how the coach takes the password
                // off the index again from the tablet.
                indexPassword = settings.indexPassword(state.sessionId),
            ),
        )
        return when (val outcome = transport.publish(config.publishEndpoint, config.token, body)) {
            is PublishOutcome.Delivered -> {
                val stored = maxOf(revision, outcome.revision)
                journal.recordDelivered(state.sessionId, stored, at)
                "Publicerat (rev $stored)."
            }
            is PublishOutcome.Failed -> {
                journal.markPending(state.sessionId)
                "Publiceringen misslyckades: ${outcome.message}"
            }
        }
    }
}
