package se.racelink.server

import java.nio.file.Files
import java.time.Instant
import kotlin.test.*
import se.racelink.publish.*

class PersistenceTest {
    private fun envelope(revision: Long = 1, token: String = "current-token") = PublishEnvelope(
        revision, "2026-10-16T12:00:00Z",
        PublishSession("persist-test", "Test", "2026-10-16", PublishMode.TRAINING, false),
        athletes = listOf(PublishAthlete(1, 1, "Test", token)),
        runs = listOf(PublishRun("run", 1, 1, RunStatus.COMPLETED, 35000)),
    )

    @Test fun restartKeepsResultsRevisionAndPasswordWithoutPlaintext() {
        val file = Files.createTempDirectory("racelink-test").resolve("sessions.json")
        val store = ProjectionStore(dataFile = file)
        store.put(envelope(2))
        store.setIndexPassword("persist-test", "private-coach-password")
        val restarted = ProjectionStore(dataFile = file)
        assertEquals(envelope(2), restarted.session("persist-test"))
        assertTrue(restarted.acceptsIndexPassword("private-coach-password"))
        assertFalse(Files.readString(file).contains("private-coach-password"))
        assertEquals(2, restarted.put(envelope(1, "stale-token")))
        assertNotNull(restarted.athlete("current-token"))
        assertNull(restarted.athlete("stale-token"))
        restarted.delete("persist-test")
        assertTrue(ProjectionStore(dataFile = file).sessions().isEmpty())
    }

    @Test fun privateFeedbackPersistsAndStalePublishesCannotReplaceIt() {
        val file = Files.createTempDirectory("racelink-coaching").resolve("sessions.json")
        val feedback = CoachProjection(runs = listOf(CoachRun("run", comment = "Private feedback")))
        val store = ProjectionStore(dataFile = file)
        store.put(envelope(2), feedback)
        store.put(envelope(1), CoachProjection())
        assertEquals(feedback, ProjectionStore(dataFile = file).coaching("persist-test"))
        store.delete("persist-test")
        assertNull(ProjectionStore(dataFile = file).coaching("persist-test"))
    }

    @Test fun retentionUsesOriginalReceivedTimeAfterRestart() {
        val file = Files.createTempDirectory("racelink-expiry").resolve("sessions.json")
        val time = Instant.parse("2026-10-16T12:00:00Z")
        ProjectionStore(now = { time }, dataFile = file).put(envelope())
        val restarted = ProjectionStore(now = { time.plusSeconds(25 * 3600) }, dataFile = file)
        assertEquals(1, restarted.purge(24))
        assertTrue(ProjectionStore(dataFile = file).sessions().isEmpty())
    }

    @Test fun corruptFileFailsInsteadOfStartingEmpty() {
        val file = Files.createTempFile("racelink-corrupt", ".json")
        Files.writeString(file, "broken")
        assertFails { ProjectionStore(dataFile = file) }
    }

    @Test fun failedDiskWriteDoesNotAcceptANewerRevisionInMemory() {
        val file = Files.createTempDirectory("racelink-failure").resolve("sessions.json")
        val store = ProjectionStore(dataFile = file)
        store.put(envelope())
        Files.delete(file)
        Files.createDirectory(file)
        assertFails { store.put(envelope(2, "new-token")) }
        assertEquals(1, store.session("persist-test")?.revision)
        assertNotNull(store.athlete("current-token"))
        assertNull(store.athlete("new-token"))
    }
}
