package se.racelink.studio

import org.junit.Assert.*
import org.junit.Test

class ReportFilterTest {
    private val sessions = (1..3).map { demoTraining().copy(sessionId = "session-$it", sessionDate = "2026-10-${10 + it}") }
    @Test fun selectedSessionsAndMultipleSkiersCombineWithoutIncludingOthers() {
        val rows = filterReport(reportRuns(sessions), setOf("session-1", "session-3"), athleteIds = setOf(1, 3))
        assertEquals(setOf("session-1", "session-3"), rows.map { it.session.sessionId }.toSet())
        assertEquals(setOf(1, 3), rows.map { it.athlete.id }.toSet())
        assertEquals(2 * (sessions.first().runs(1).size + sessions.first().runs(3).size), rows.size)
        assertFalse(reportCsv(rows).contains("Elias"))
        assertFalse(reportParagraphs(rows).joinToString("\n").contains("Elias"))
    }
    @Test fun dateIntervalsIncludeBothEndpointsAndRespectSkierSelection() {
        val rows = filterReport(reportRuns(sessions), from = "2026-10-11", through = "2026-10-12", athleteIds = setOf(2, 4))
        assertEquals(setOf("session-1", "session-2"), rows.map { it.session.sessionId }.toSet())
        assertEquals(setOf(2, 4), rows.map { it.athlete.id }.toSet())
        assertEquals(sessions[1].attempts.size, filterReport(reportRuns(sessions), from = "2026-10-12", through = "2026-10-12").size)
    }
    @Test fun allScopeEmptySelectionsAndInvalidRangesAreExplicit() {
        val rows = reportRuns(sessions)
        assertEquals(rows, filterReport(rows))
        assertTrue(filterReport(rows, sessionIds = emptySet()).isEmpty())
        assertTrue(filterReport(rows, athleteIds = emptySet()).isEmpty())
        assertTrue(filterReport(rows, from = "2027-01-01", through = "2027-01-02").isEmpty())
        assertTrue(runCatching { filterReport(rows, from = "2026-10-12", through = "2026-10-11") }.isFailure)
        assertTrue(runCatching { filterReport(rows, from = "2026-02-30") }.isFailure)
    }
}
