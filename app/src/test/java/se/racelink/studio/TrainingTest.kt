package se.racelink.studio

import org.junit.Assert.*
import org.junit.Test

class TrainingTest {
    @Test fun sessionSetupPreservesRosterAndHistoricalNames() {
        val old = demoTraining()
        val roster = old.roster.map { if (it.id == 1) it.copy(name = "Alma Ny", bib = 99) else it } + Athlete(5, 50, "Sara")
        val next = old.startNewSession(" Kvällsslalom ", "2026-10-15", " Åre ", "Bana 2", "Slalom", roster, setOf(2, 5))
        assertEquals("Kvällsslalom", next.sessionName)
        assertEquals("Åre", next.location)
        assertEquals("Bana 2", next.course)
        assertEquals("Slalom", next.trainingType)
        assertEquals("2026-10-15", next.sessionDate)
        assertEquals(listOf(2, 5), next.athletes.map { it.id })
        assertEquals(5, next.roster.size)
        assertEquals("Alma", next.archivedSessions.single().athletes.first().name)
        assertEquals(12, next.archivedSessions.single().athletes.first().bib)
        val later = next.startNewSession(participants = setOf(1))
        assertEquals("Alma Ny", later.athletes.single().name)
        assertEquals(99, later.athletes.single().bib)
        assertEquals(5, later.roster.size)
    }
    @Test fun sessionSetupRejectsInvalidAttendanceAndDuplicateBibs() {
        val s = demoTraining()
        assertTrue(runCatching { s.startNewSession(participants = emptySet()) }.isFailure)
        assertTrue(runCatching { s.startNewSession(participants = setOf(999)) }.isFailure)
        assertTrue(runCatching { s.startNewSession(name = " ") }.isFailure)
        assertTrue(runCatching { s.startNewSession(date = "2026-02-30") }.isFailure)
        assertTrue(runCatching { s.startNewSession(roster = s.roster + Athlete(5, 12, "Sara")) }.isFailure)
    }
    @Test fun reportFiltersKeepAllRunsAndSessionIdentity() {
        val first = demoTraining().copy(sessionId = "first", sessionDate = "2026-10-13")
        // Simulator IDs and sequence numbers repeat between sessions, and must remain separate.
        val second = first.copy(sessionId = "second", sessionDate = "2026-10-14",
            attempts = first.attempts.take(2), archivedSessions = emptyList())
        val sessions = listOf(first, second)
        assertEquals(first.attempts.size + second.attempts.size, reportRuns(sessions).size)
        val skier = reportRuns(sessions, athleteId = 1)
        assertEquals(first.runs(1).size + second.runs(1).size, skier.size)
        assertEquals(listOf("first", "second"), skier.map { it.session.sessionId }.distinct())
        val oneSession = reportRuns(sessions, "second", 1)
        assertEquals(1, oneSession.size)
        assertEquals("seed-0", oneSession.single().attempt.id)
        assertEquals(1, oneSession.single().number)
        assertEquals(1, skier.count { !it.attempt.complete })
        assertTrue(reportRuns(sessions, "missing", 1).isEmpty())
    }
    @Test fun reportCsvIncludesSessionContextAndOnlySelectedSkierWithoutPrivateNotes() {
        val session = demoTraining().copy(sessionId = "report-session", sessionDate = "2026-10-13")
        val edited = session.correct("seed-0", 1, true, setOf(1), "Private coaching note")
        val output = reportCsv(reportRuns(listOf(edited), athleteId = 1))
        assertEquals(edited.runs(1).size + 1, output.split("\r\n").size)
        assertTrue(output.contains("\"report-session\";\"2026-10-13\""))
        assertTrue(output.contains("\"Alma\""))
        assertTrue(output.contains("\"Avbrutet\""))
        assertFalse(output.contains("Elias"))
        assertFalse(output.contains("Private coaching note"))
        assertFalse(output.contains("Teknikfokus"))
    }
    @Test fun newSessionsPreserveArchivedEvidenceAndStartEmpty() {
        val original = demoTraining().copy(audit = listOf("Rättelse"), pending = listOf("seed-0"))
        val next = original.startNewSession()
        assertNotEquals(original.sessionId, next.sessionId)
        assertTrue(next.attempts.isEmpty())
        assertTrue(next.pending.isEmpty())
        assertTrue(next.markers.isEmpty())
        assertTrue(next.audit.isEmpty())
        assertEquals(original.athletes, next.athletes)
        assertEquals(original.tags, next.tags)
        val archived = next.archivedSessions.single()
        assertEquals(original.attempts, archived.attempts)
        assertEquals(original.audit, archived.audit)
        assertEquals(original.markers, archived.markers)
        val later = next.ingest(SimulatorAdapter().next(1, 12)).startNewSession()
        assertEquals(listOf(next.sessionId, original.sessionId), later.archivedSessions.map { it.sessionId })
        assertTrue(later.archivedSessions.all { it.archivedSessions.isEmpty() })
    }
    @Test fun incompleteAttemptDoesNotBecomeComparisonReference() {
        val s = demoTraining()
        val runs = s.runs(1)
        val afterIncomplete = runs.first { it.sequence == 13 }
        assertEquals(5, runs.previous(afterIncomplete)!!.sequence)
        assertEquals(4, runs.eligible().size)
    }
    @Test fun retriesAreIdempotentButEqualTimesRemainSeparate() {
        val s = demoTraining()
        val e = TimingObservation("new-1", 12, 18, 33000, true)
        val once = s.ingest(e)
        assertEquals(once, once.ingest(e))
        assertEquals(s.attempts.size + 2, once.ingest(e.copy(id = "new-2", sequence = 19)).attempts.size)
    }
    @Test fun reassignmentPreservesEvidenceAndChangesBothHistories() {
        val s = demoTraining()
        val before = s.attempts.first()
        val changed = s.correct(before.id, 2, true, setOf(5), "Fel nummer")
        assertEquals(before.durationMs, changed.attempts.first().durationMs)
        assertEquals(s.runs(1).size - 1, changed.runs(1).size)
        assertEquals(s.runs(2).size + 1, changed.runs(2).size)
        assertEquals(1, changed.audit.size)
    }
    @Test(expected = IllegalArgumentException::class) fun unknownBibRequiresReviewInsteadOfGuessing() {
        demoTraining().ingest(TimingObservation("unknown", 999, 18, 33000, true))
    }
    @Test fun seededSimulatorIsDeterministic() {
        assertEquals(SimulatorAdapter().next(20, 12), SimulatorAdapter().next(20, 12))
    }
    @Test(expected = IllegalArgumentException::class) fun cannotInventFinishForIncompleteAttempt() {
        val s = demoTraining(); val run = s.attempts.first { it.durationMs == null }
        s.correct(run.id, run.athleteId, true, emptySet(), "")
    }
}
