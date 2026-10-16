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
    private val logo: () -> String? = { null },
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
                PublishProjection.of(state, revision, at, shownName).copy(logoPngBase64 = logo()),
                // Sent with every publish, empty included: that is how the coach takes the password
                // off the index again from the tablet.
                indexPassword = settings.indexPassword(state.sessionId),
                coaching = PublishProjection.coaching(state),
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

    /**
     * Takes a session off the web: the server drops it and every skier link in it. The caller must also
     * stop publishing afterwards — the app republishes on each committed edit — which is why the
     * settings screen switches publishing off when this succeeds.
     */
    fun withdraw(state: Training): WithdrawResult {
        val config = settings.read()
        if (!config.configured) return WithdrawResult.Failed("Ange serveradress och publiceringstoken först.")
        return when (val outcome = transport.withdraw(config.sessionEndpoint(state.sessionId), config.token)) {
            is PublishOutcome.Delivered -> WithdrawResult.Removed("Borttaget från webben. Publiceringen är avstängd.")
            is PublishOutcome.Failed -> WithdrawResult.Failed("Kunde inte ta bort från webben: ${outcome.message}")
        }
    }
}

/** What a withdrawal attempt did, so the screen can react without reading its own Swedish. */
internal sealed interface WithdrawResult {
    /** The session and its links are gone; the caller should stop publishing [message] for the screen. */
    data class Removed(val message: String) : WithdrawResult
    data class Failed(val message: String) : WithdrawResult
}
