package se.racelink.studio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import se.racelink.publish.PublishJson

class PublisherTest {
    private class FakeSettings(var current: PublishConfig, private val passwords: MutableMap<String, String> = mutableMapOf()) : PublishSettings {
        override fun read() = current
        override fun write(config: PublishConfig): Boolean { current = config; return true }
        override fun indexPassword(sessionId: String) = passwords[sessionId].orEmpty()
        override fun writeIndexPassword(sessionId: String, password: String): Boolean {
            passwords[sessionId] = password.trim(); return true
        }
        fun setPassword(sessionId: String, password: String) { passwords[sessionId] = password }
    }

    private class FakeJournal : PublishJournal {
        val revisions = mutableMapOf<String, Long>()
        val delivered = mutableMapOf<String, String>()
        val pending = mutableSetOf<String>()
        override fun nextRevision(sessionId: String) = (revisions[sessionId] ?: 0L) + 1
        override fun recordDelivered(sessionId: String, revision: Long, at: String) {
            revisions[sessionId] = revision; delivered[sessionId] = at; pending -= sessionId
        }
        override fun markPending(sessionId: String) { pending += sessionId }
        override fun isPending(sessionId: String) = sessionId in pending
        override fun lastDeliveredAt(sessionId: String) = delivered[sessionId]
    }

    private class FakeTransport(var outcome: PublishOutcome) : PublishTransport {
        var calls = 0
        var lastEndpoint: String? = null
        var lastToken: String? = null
        var lastBody: String? = null
        override fun publish(endpoint: String, token: String, body: String): PublishOutcome {
            calls++; lastEndpoint = endpoint; lastToken = token; lastBody = body
            return outcome
        }
    }

    private fun config(enabled: Boolean = true, url: String = "https://live.example.se/", token: String = "tk") =
        PublishConfig(url, token, enabled)

    @Test
    fun disabledIsASilentNoOp() {
        val transport = FakeTransport(PublishOutcome.Delivered(1, true))
        val publisher = Publisher(FakeSettings(config(enabled = false)), FakeJournal(), transport, now = { "t" })
        assertNull(publisher.publish(demoTraining()))
        assertEquals(0, transport.calls)
    }

    @Test
    fun deliversThenIncrementsRevisionAndTrimsEndpoint() {
        val state = demoTraining()
        val journal = FakeJournal()
        val transport = FakeTransport(PublishOutcome.Delivered(1, true))
        val publisher = Publisher(FakeSettings(config()), journal, transport, now = { "2026-10-16T09:00:00Z" })

        assertEquals("Publicerat (rev 1).", publisher.publish(state))
        assertEquals("https://live.example.se/api/v1/publish", transport.lastEndpoint)
        assertEquals("tk", transport.lastToken)
        assertEquals("2026-10-16T09:00:00Z", journal.lastDeliveredAt(state.sessionId))
        assertFalse(journal.isPending(state.sessionId))

        // The body is exactly the shared contract and carries runs but no coaching data.
        val envelope = PublishJson.decodeRequest(transport.lastBody!!).envelope
        assertEquals(state.sessionId, envelope.session.id)
        assertTrue(envelope.runs.isNotEmpty())

        assertEquals(2L, journal.nextRevision(state.sessionId))
    }

    @Test
    fun failureStaysPendingAndRetries() {
        val state = demoTraining()
        val journal = FakeJournal()
        val transport = FakeTransport(PublishOutcome.Failed("timeout"))
        val publisher = Publisher(FakeSettings(config()), journal, transport, now = { "t" })

        assertTrue(publisher.publish(state)!!.startsWith("Publiceringen misslyckades"))
        assertTrue(journal.isPending(state.sessionId))
        assertNull(journal.lastDeliveredAt(state.sessionId))

        transport.outcome = PublishOutcome.Delivered(2, true)
        assertEquals("Publicerat (rev 2).", publisher.publish(state))
        assertFalse(journal.isPending(state.sessionId))
    }

    @Test
    fun forcedPublishRequiresConfigurationButIgnoresTheEnabledFlag() {
        val unconfigured = FakeTransport(PublishOutcome.Delivered(1, true))
        val blank = Publisher(FakeSettings(PublishConfig("", "", enabled = false)), FakeJournal(), unconfigured, now = { "t" })
        assertEquals("Ange serveradress och publiceringstoken först.", blank.publish(demoTraining(), force = true))
        assertEquals(0, unconfigured.calls)

        val transport = FakeTransport(PublishOutcome.Delivered(1, true))
        val disabled = Publisher(FakeSettings(config(enabled = false)), FakeJournal(), transport, now = { "t" })
        assertEquals("Publicerat (rev 1).", disabled.publish(demoTraining(), force = true))
        assertEquals(1, transport.calls)
    }

    @Test
    fun staleAckKeepsRevisionMonotonic() {
        val state = demoTraining()
        val journal = FakeJournal()
        val publisher = Publisher(FakeSettings(config()), journal, FakeTransport(PublishOutcome.Delivered(9, accepted = false)), now = { "t" })
        assertEquals("Publicerat (rev 9).", publisher.publish(state))
        assertEquals(10L, journal.nextRevision(state.sessionId))
    }

    @Test
    fun namePolicyDefaultsToFirstNameAndCanBeBibOnly() {
        val state = demoTraining()
        val firstTransport = FakeTransport(PublishOutcome.Delivered(1, true))
        Publisher(FakeSettings(config()), FakeJournal(), firstTransport, now = { "t" }).publish(state)
        assertEquals("Alma", PublishJson.decodeRequest(firstTransport.lastBody!!).envelope.athletes.first().displayName)

        val bibTransport = FakeTransport(PublishOutcome.Delivered(1, true))
        Publisher(FakeSettings(config().copy(bibOnly = true)), FakeJournal(), bibTransport, now = { "t" }).publish(state)
        assertTrue(PublishJson.decodeRequest(bibTransport.lastBody!!).envelope.athletes.all { it.displayName.isEmpty() })
    }

    @Test
    fun theSessionPasswordTravelsOutsideTheProjectionAndOnlyForItsOwnSession() {
        val state = demoTraining()
        val settings = FakeSettings(config())
        settings.setPassword(state.sessionId, "hemligt")
        settings.setPassword("annat-pass", "annat")
        val transport = FakeTransport(PublishOutcome.Delivered(1, true))
        Publisher(settings, FakeJournal(), transport, now = { "t" }).publish(state)

        val request = PublishJson.decodeRequest(transport.lastBody!!)
        // Only this session's password is sent, and the projection itself stays credential-free because
        // the projection is what a public read of the session returns.
        assertEquals("hemligt", request.indexPassword)
        assertFalse(transport.lastBody!!.contains("annat-pass"))
        assertFalse(PublishJson.encode(request.envelope).contains("hemligt"))
    }

    @Test
    fun anUnsetPasswordIsSentEmptySoTheCoachCanTurnTheProtectionOffFromTheTablet() {
        val transport = FakeTransport(PublishOutcome.Delivered(1, true))
        Publisher(FakeSettings(config()), FakeJournal(), transport, now = { "t" }).publish(demoTraining())
        assertEquals("", PublishJson.decodeRequest(transport.lastBody!!).indexPassword)
    }

    @Test
    fun parseAckHandlesServerReplies() {
        assertEquals(PublishOutcome.Delivered(4, true), parseAck(202, """{"sessionId":"s","revision":4,"accepted":true}"""))
        assertTrue(parseAck(401, "") is PublishOutcome.Failed)
        assertTrue(parseAck(500, "boom") is PublishOutcome.Failed)
        assertEquals(PublishOutcome.Delivered(0, true), parseAck(200, "not-json"))
    }
}
