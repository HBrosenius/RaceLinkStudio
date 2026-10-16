package se.racelink.studio

import org.junit.Assert.*
import org.junit.Test
import org.json.JSONObject

class RaceTest {
    private fun race(format: RaceFormat = RaceFormat.TOTAL): Training {
        val s = demoTraining().startNewSession("Klubbtävling")
        return s.configureRace(RaceConfig(format, listOf("U12", "Öppen"), mapOf(1 to "U12", 2 to "U12", 3 to "Öppen", 4 to "Öppen")))
    }
    private fun Training.finish(id: String, athlete: Int, ms: Long?, round: Int): Training = selectRaceRound(round).ingest(
        TimingObservation(id, athletes.single { it.id == athlete }.bib, nextTimingSequence(), ms, ms != null))

    @Test fun simulationFillsTwoSlotsPerSkierWithoutManualRoundSwitch() {
        var s = race()
        for (id in listOf(1, 2, 3, 4)) {
            s = s.selectNextAthlete(id).simulateNext(SimulatorAdapter())
            assertNotNull(s.raceRun(id, 1))
            assertNull(s.raceRun(id, 2))
            s = s.simulateNext(SimulatorAdapter())
            assertNotNull(s.raceRun(id, 1))
            assertNotNull(s.raceRun(id, 2))
            assertNull(s.nextSimulatedRaceRound(id))
            assertTrue(runCatching { s.simulateNext(SimulatorAdapter()) }.isFailure)
        }
        assertEquals(8, s.race!!.roundByAttempt.size)
        assertTrue(s.raceStandings().all { it.place != null })
    }

    @Test fun simulationHonorsEmptyActiveRoundAndDoesNotBypassUnresolvedExtra() {
        val s = race().selectNextAthlete(1).selectRaceRound(2).simulateNext(SimulatorAdapter(), true)
        assertFalse(s.raceRun(1, 2)!!.complete)
        val next = s.simulateNext(SimulatorAdapter())
        assertNotNull(next.raceRun(1, 1))
        assertNull(next.raceStandings().first { it.athlete.id == 1 }.place)
        val extra = race().finish("a1", 1, 30000, 1).finish("extra", 1, 30100, 1).selectNextAthlete(1)
        assertNull(extra.nextSimulatedRaceRound(1))
        assertTrue(runCatching { extra.simulateNext(SimulatorAdapter()) }.isFailure)
    }

    @Test fun totalUsesBothRunsAndRanksOnlyWithinClass() {
        var s = race().finish("a1", 1, 30000, 1).finish("b1", 2, 29000, 1).finish("c1", 3, 20000, 1)
        assertTrue(s.raceStandings().all { it.place == null })
        s = s.finish("a2", 1, 31000, 2).finish("b2", 2, 33000, 2).finish("c2", 3, 20000, 2)
        val standings = s.raceStandings().associateBy { it.athlete.id }
        assertEquals(61000L, standings.getValue(1).scoreMs)
        assertEquals(1, standings.getValue(1).place)
        assertEquals(2, standings.getValue(2).place)
        assertEquals(1, standings.getValue(3).place)
        assertNull(standings.getValue(4).place)
    }
    @Test fun differenceUsesAbsoluteDifferenceNotTheFastestTotal() {
        val s = race(RaceFormat.DIFFERENCE).finish("a1", 1, 30000, 1).finish("a2", 1, 29900, 2)
            .finish("b1", 2, 45000, 1).finish("b2", 2, 45050, 2)
        assertEquals(listOf(2,1), s.raceStandings().filter { it.className == "U12" }.map { it.athlete.id })
        assertEquals(50L, s.raceStandings().first().scoreMs)
        assertEquals(100L, s.raceStandings().first { it.athlete.id == 1 }.scoreMs)
    }
    @Test fun tiesSharePlacesAndDnfKeepsOriginalTime() {
        var s = race().finish("a1", 1, 30000, 1).finish("a2", 1, 31000, 2).finish("b1", 2, 31000, 1).finish("b2", 2, 30000, 2)
        assertEquals(listOf(1,1), s.raceStandings().filter { it.className == "U12" }.map { it.place })
        s = s.setRunDnf("a2", true)
        assertNull(s.raceStandings().first { it.athlete.id == 1 }.place)
        assertEquals(31000L, s.attempts.single { it.id == "a2" }.durationMs)
        assertEquals(1, s.raceStandings().first { it.athlete.id == 2 }.place)
        assertEquals(1, s.setRunDnf("a2", false).raceStandings().first { it.athlete.id == 1 }.place)
    }
    @Test fun replayAndExtraResultNeverOverwriteAnOccupiedSlot() {
        val first = race().finish("a1", 1, 30000, 1)
        val event = first.timingEvents.last().observation
        assertEquals(first, first.ingest(event))
        val extra = first.finish("extra", 1, 20000, 1)
        assertEquals("a1", extra.raceRun(1,1)?.id)
        assertFalse("extra" in extra.race!!.roundByAttempt)
        assertTrue(runCatching { extra.closeSession() }.isFailure)
        assertTrue(runCatching { extra.assignRaceRun("extra", 1) }.isFailure)
        val replaced = extra.assignRaceRun("a1", null).assignRaceRun("extra", 1).excludeRaceRun("a1")
        assertEquals("extra", replaced.raceRun(1,1)?.id)
        assertEquals(2, replaced.attempts.size)
        assertNotNull(replaced.closeSession().closedAt)
    }
    @Test fun invalidClassOrConflictingCorrectionIsRejected() {
        val s = race().finish("a1", 1, 30000, 1).finish("b1", 2, 31000, 1)
        assertTrue(runCatching { s.correct("a1", 2, true, emptySet(), "") }.isFailure)
        assertTrue(runCatching { s.configureRace(s.race!!.copy(classes = listOf("U12", "u12"))) }.isFailure)
        assertTrue(runCatching { s.configureRace(s.race!!.copy(athleteClasses = emptyMap())) }.isFailure)
        assertTrue(runCatching { s.configureRace(s.race!!.copy(format = RaceFormat.DIFFERENCE)) }.isFailure)
        assertTrue(runCatching { s.closeSession().selectRaceRound(2) }.isFailure)
    }
    @Test fun backupAndArchivePreserveRulesClassesAndRoundAssignments() {
        val s = race(RaceFormat.DIFFERENCE).finish("a1", 1, 30000, 1).finish("a2", 1, 30000, 2)
        val restored = decodeBackup(encodeBackup(s))
        assertEquals(s, restored)
        assertEquals(0L, restored.raceStandings().first().scoreMs)
        val newTraining = s.startNewSession("Träning igen")
        assertNull(newTraining.race)
        assertEquals(s.race, newTraining.archivedSessions.first().race)
        assertTrue(newTraining.attempts.isEmpty())
        val legacy = JSONObject(encodeBackup(demoTraining())).put("version", 6)
        legacy.getJSONObject("state").remove("race")
        assertNull(decodeBackup(legacy.toString()).race)
    }
    @Test fun raceExportsShowScoresAndKeepClassPlacementWhenFiltered() {
        val s = race(RaceFormat.DIFFERENCE).finish("a1", 1, 30000, 1).finish("a2", 1, 30050, 2)
            .finish("b1", 2, 30000, 1).finish("b2", 2, 30100, 2)
        val paragraphs = raceReportParagraphs(s, setOf(2))
        assertTrue(paragraphs.any { it.startsWith("2. 24") })
        assertTrue(paragraphs.any { it.contains("0,100 s") })
        assertFalse(paragraphs.any { it.contains("12 · Alma") })
        assertTrue(raceResultsCsv(s).contains("Differensslalom"))
        val rows = reportRuns(listOf(s))
        assertTrue(reportCsv(rows).contains("Tävlingsresultat (s)"))
        assertTrue(reportParagraphs(rows).any { it.contains("Tidsskillnad:") })
        assertFalse(reportParagraphs(rows, includeGraphs = true).any { it.startsWith(PDF_GRAPH_PREFIX) })
    }
    @Test fun overflowingTotalDoesNotCrashOrRank() {
        val s = race().finish("a1", 1, Long.MAX_VALUE, 1).finish("a2", 1, 1, 2)
        assertNull(s.raceStandings().first { it.athlete.id == 1 }.scoreMs)
    }
    @Test fun reviewedResultRequiresExplicitRaceRoundEvenIfActiveRoundChanges() {
        val s = race().ingest(TimingObservation("unknown", -1, 1, 30000, true)).selectRaceRound(2)
        val reviewed = s.resolveTiming(s.reviewQueue().single().id, TimingDecision.ASSIGNED, athleteId = 1)
        assertFalse("unknown" in reviewed.race!!.roundByAttempt)
        assertEquals("unknown", reviewed.assignRaceRun("unknown", 1).raceRun(1,1)?.id)
    }
    @Test fun malformedRaceBackupIsNotSilentlyLoadedAsTraining() {
        val root = JSONObject(encodeBackup(race())).also { it.getJSONObject("state").put("race", "broken") }
        assertTrue(runCatching { decodeBackup(root.toString()) }.isFailure)
    }
    @Test fun equalScoresSharePlaceWithoutAnUnrequestedTieBreaker() {
        val base = race(RaceFormat.DIFFERENCE)
        val s = base.configureRace(base.race!!.copy(athleteClasses = mapOf(1 to "U12", 2 to "U12", 3 to "U12", 4 to "Öppen")))
            .finish("a1", 1, 30000, 1).finish("a2", 1, 30001, 2)
            .finish("b1", 2, 40000, 1).finish("b2", 2, 39999, 2)
            .finish("c1", 3, 20000, 1).finish("c2", 3, 20002, 2)
        assertEquals(listOf(1,1,3), s.raceStandings().filter { it.className == "U12" }.map { it.place })
        assertEquals("0,001", raceSeconds(1))
        assertEquals("0,002", raceSeconds(2))
    }
}
