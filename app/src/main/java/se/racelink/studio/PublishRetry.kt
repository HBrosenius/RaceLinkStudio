package se.racelink.studio

/**
 * One background attempt to flush an outstanding publish (e.g. the link was down during a run).
 * Kept free of WorkManager and Android so the decision "publish now, or ask to be retried later"
 * can be tested off-device. It never changes local state beyond the publish journal.
 */
internal class PublishRetryRunner(
    private val publisher: Publisher,
    private val settings: PublishSettings,
    private val journal: PublishJournal,
    private val readState: () -> Training?,
) {
    /** @return true when a pending publish still failed and WorkManager should retry later. */
    fun run(): Boolean {
        val config = settings.read()
        if (!config.enabled || !config.configured) return false
        val state = readState() ?: return false
        // Only flush an outstanding publish; a session that is already up to date needs no traffic.
        if (!journal.isPending(state.sessionId)) return false
        publisher.publish(state)
        return journal.isPending(state.sessionId)
    }
}
