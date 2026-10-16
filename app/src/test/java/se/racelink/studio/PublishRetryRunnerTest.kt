package se.racelink.studio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PublishRetryRunnerTest {
    private class FakeSettings(var current: PublishConfig) : PublishSettings {
        override fun read() = current
        override fun write(config: PublishConfig): Boolean { current = config; return true }
        override fun indexPassword(sessionId: String) = ""
        override fun writeIndexPassword(sessionId: String, password: String) = true
    }

    private class FakeJournal : PublishJournal {
        private val revisions = mutableMapOf<String, Long>()
        private val pending = mutableSetOf<String>()
        override fun nextRevision(sessionId: String) = (revisions[sessionId] ?: 0L) + 1
        override fun recordDelivered(sessionId: String, revision: Long, at: String) { revisions[sessionId] = revision; pending -= sessionId }
        override fun markPending(sessionId: String) { pending += sessionId }
        override fun isPending(sessionId: String) = sessionId in pending
        override fun lastDeliveredAt(sessionId: String): String? = null
    }

    private class FakeTransport(var outcome: PublishOutcome) : PublishTransport {
        var calls = 0
        override fun publish(endpoint: String, token: String, body: String): PublishOutcome { calls++; return outcome }
    }

    private fun config(enabled: Boolean = true, url: String = "https://live.example.se", token: String = "tk") =
        PublishConfig(url, token, enabled)

    private fun runner(settings: FakeSettings, journal: FakeJournal, transport: FakeTransport, state: Training? = null) =
        PublishRetryRunner(Publisher(settings, journal, transport, now = { "t" }), settings, journal) { state }

    @Test
    fun skipsWhenPublishingIsOffOrUnconfigured() {
        val off = FakeTransport(PublishOutcome.Delivered(1, true))
        assertFalse(runner(FakeSettings(config(enabled = false)), FakeJournal(), off, demoTraining()).run())
        assertEquals(0, off.calls)

        val blank = FakeTransport(PublishOutcome.Delivered(1, true))
        assertFalse(runner(FakeSettings(PublishConfig("", "", enabled = true)), FakeJournal(), blank, demoTraining()).run())
        assertEquals(0, blank.calls)
    }

    @Test
    fun skipsWhenNothingIsPendingOrThereIsNoSession() {
        val transport = FakeTransport(PublishOutcome.Delivered(1, true))
        assertFalse(runner(FakeSettings(config()), FakeJournal(), transport, demoTraining()).run())
        assertFalse(runner(FakeSettings(config()), FakeJournal(), transport, state = null).run())
        assertEquals(0, transport.calls)
    }

    @Test
    fun deliversAPendingPublishAndClearsTheMarker() {
        val state = demoTraining()
        val journal = FakeJournal().apply { markPending(state.sessionId) }
        val transport = FakeTransport(PublishOutcome.Delivered(1, true))

        assertFalse("a delivered publish needs no retry", runner(FakeSettings(config()), journal, transport, state).run())
        assertEquals(1, transport.calls)
        assertFalse(journal.isPending(state.sessionId))
    }

    @Test
    fun stillFailingKeepsTheMarkerAndAsksForRetry() {
        val state = demoTraining()
        val journal = FakeJournal().apply { markPending(state.sessionId) }
        val transport = FakeTransport(PublishOutcome.Failed("timeout"))

        assertTrue(runner(FakeSettings(config()), journal, transport, state).run())
        assertEquals(1, transport.calls)
        assertTrue(journal.isPending(state.sessionId))
    }
}
