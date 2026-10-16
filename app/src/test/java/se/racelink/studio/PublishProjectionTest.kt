package se.racelink.studio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import se.racelink.publish.PublishFormat
import se.racelink.publish.PublishJson
import se.racelink.publish.PublishMode
import se.racelink.publish.RunStatus

class PublishProjectionTest {
    @Test
    fun trainingProjectionExposesPersonalRunsOnly() {
        val state = demoTraining()
        val envelope = PublishProjection.of(state, revision = 1, publishedAt = "2026-10-16T09:00:00Z")

        assertEquals(PublishMode.TRAINING, envelope.session.mode)
        assertNull(envelope.race)
        assertEquals(4, envelope.athletes.size)
        assertEquals(17, envelope.runs.size)
        assertTrue(envelope.standings.isEmpty())
        assertEquals(listOf("Portarna flyttade"), envelope.markers.map { it.label })
        assertEquals(RunStatus.INCOMPLETE, envelope.runs.single { it.id == "seed-8" }.status)
        assertEquals(RunStatus.COMPLETED, envelope.runs.first().status)

        // The projection must survive the wire contract unchanged.
        assertEquals(envelope, PublishJson.decode(PublishJson.encode(envelope)))

        // Tokens are stable per athlete and differ between athletes.
        assertEquals(PublishProjection.tokenFor(state.sessionId, 1), PublishProjection.tokenFor(state.sessionId, 1))
        assertNotEquals(PublishProjection.tokenFor(state.sessionId, 1), PublishProjection.tokenFor(state.sessionId, 2))
    }

    @Test
    fun raceProjectionCarriesStandingsAndRounds() {
        val raced = demoTraining().startNewSession("Klubbcupen")
            .configureRace(RaceConfig(RaceFormat.TOTAL, listOf("Öppen"), (1..4).associateWith { "Öppen" }))
            .selectNextAthlete(1).simulateNext(SimulatorAdapter()).simulateNext(SimulatorAdapter())
            .selectNextAthlete(2).simulateNext(SimulatorAdapter()).simulateNext(SimulatorAdapter())

        val envelope = PublishProjection.of(raced, revision = 3, publishedAt = "2026-10-16T09:00:00Z")

        assertEquals(PublishMode.RACE, envelope.session.mode)
        assertEquals(PublishFormat.TOTAL, envelope.race?.format)
        assertEquals(listOf("Öppen"), envelope.race?.classes)
        assertEquals(4, envelope.standings.size)
        assertEquals(setOf(1, 2), envelope.standings.mapNotNull { it.place }.toSet())
        assertTrue(envelope.runs.all { it.round in listOf(1, 2) })
    }

    @Test
    fun displayNameDefaultsToFirstName() {
        val base = demoTraining()
        val renamed = base.copy(athletes = base.athletes.map { if (it.id == 1) it.copy(name = "Alma Andersson") else it })
        val envelope = PublishProjection.of(renamed, revision = 1, publishedAt = "2026-10-16T09:00:00Z")
        assertEquals("Alma", envelope.athletes.single { it.id == 1 }.displayName)
    }
}
