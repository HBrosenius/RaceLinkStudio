package se.racelink.server

import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import se.racelink.publish.PublishAthlete
import se.racelink.publish.PublishEnvelope
import se.racelink.publish.PublishMode
import se.racelink.publish.PublishRun
import se.racelink.publish.PublishSession
import se.racelink.publish.RunStatus

class ProjectionStoreTest {
    private val start = Instant.parse("2026-10-16T09:00:00Z")

    private fun envelope(revision: Long, sessionId: String) = PublishEnvelope(
        revision = revision,
        publishedAt = "2026-10-16T09:00:00Z",
        session = PublishSession(id = sessionId, name = "Pass", date = "2026-10-16", mode = PublishMode.TRAINING, closed = false),
        athletes = listOf(PublishAthlete(id = 1, bib = 12, displayName = "Alma", token = "tok-$sessionId")),
        runs = listOf(PublishRun(id = "r$revision", athleteId = 1, sequence = 1, status = RunStatus.COMPLETED, durationMs = 35000)),
    )

    @Test
    fun purgeKeepsFreshSessionsAndDropsStaleOnesWithTheirTokenIndex() {
        var clock = start
        val store = ProjectionStore { clock }
        store.put(envelope(1, "fresh"))
        clock = clock.plus(Duration.ofHours(10))
        store.put(envelope(1, "stale"))
        clock = clock.plus(Duration.ofHours(20)) // fresh is now 30h old, stale 20h

        assertEquals(1, store.purge(24))
        assertNull(store.session("fresh"))
        assertNull(store.athlete("tok-fresh"), "the expired token is gone from the index")
        assertNotNull(store.session("stale"))
        assertNotNull(store.athlete("tok-stale"))
    }

    @Test
    fun retentionCanBeDisabled() {
        var clock = start
        val store = ProjectionStore { clock }
        store.put(envelope(1, "s-1"))
        clock = clock.plus(Duration.ofHours(500))

        assertEquals(0, store.purge(0))
        assertNotNull(store.session("s-1"))
    }

    @Test
    fun deleteRemovesTheSessionAndItsTokenIndex() {
        val store = ProjectionStore()
        store.put(envelope(1, "s-1"))

        assertTrue(store.delete("s-1"))
        assertNull(store.session("s-1"))
        assertNull(store.athlete("tok-s-1"))
        assertFalse(store.delete("s-1"))
    }

    @Test
    fun aStaleRevisionDoesNotExtendRetention() {
        var clock = start
        val store = ProjectionStore { clock }
        store.put(envelope(2, "s-1"))
        clock = clock.plus(Duration.ofHours(20))
        store.put(envelope(1, "s-1")) // rejected: older revision

        assertEquals(2L, store.session("s-1")?.revision)
        clock = clock.plus(Duration.ofHours(10))
        assertEquals(1, store.purge(24), "retention is measured from the last accepted publish")
    }

    @Test
    fun aSessionKeepsItsIndexPasswordUntilItIsClearedOrDeleted() {
        val store = ProjectionStore()
        store.put(envelope(1, "s-1"))
        store.setIndexPassword("s-1", "hemlig")

        assertTrue(store.hasIndexPassword())
        assertTrue(store.acceptsIndexPassword("hemlig"))
        assertFalse(store.acceptsIndexPassword("fel"))
        assertFalse(ProjectionStore().acceptsIndexPassword("hemlig"), "an empty store opens nothing")

        store.put(envelope(2, "s-1"))
        assertTrue(store.acceptsIndexPassword("hemlig"), "a later publish must not silently drop the protection")

        store.setIndexPassword("s-1", "  ")
        assertFalse(store.hasIndexPassword(), "a blank password clears it")
        assertFalse(store.acceptsIndexPassword("hemlig"))

        store.setIndexPassword("s-1", "igen")
        assertTrue(store.delete("s-1"))
        assertFalse(store.acceptsIndexPassword("igen"), "a deleted session takes its password with it")
        assertFalse(store.hasIndexPassword())
    }

    @Test
    fun setIndexPasswordIgnoresAnUnknownSession() {
        val store = ProjectionStore()
        store.setIndexPassword("nope", "hemlig")
        assertFalse(store.hasIndexPassword(), "only a live session can protect the index")
    }

    @Test
    fun theStoredPasswordIsVerifiedWithoutKeepingIt() {
        val digest = IndexPassword.of("hemlig")

        assertTrue(digest.matches("hemlig"))
        assertFalse(digest.matches("hemliG"))
        assertFalse(digest.matches("hemlig "))
        assertFalse(digest.matches(""))
    }
}
