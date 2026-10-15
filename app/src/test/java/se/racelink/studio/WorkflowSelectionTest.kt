package se.racelink.studio

import androidx.compose.runtime.saveable.SaverScope
import org.junit.Assert.*
import org.junit.Test

class WorkflowSelectionTest {
    private val sessions = (1..3).map { i -> demoTraining().copy(sessionId = "pass-$i", sessionDate = "2026-10-${10+i}") }
    @Test fun displayedSelectionIsTheExactCsvAndPdfScope() {
        val selection = ReportSelection("sessions", setOf("pass-1", "pass-3"), setOf(1, 3))
        val rows = selection.rows(sessions)
        assertEquals(setOf("pass-1", "pass-3"), rows.map { it.session.sessionId }.toSet())
        assertEquals(setOf(1, 3), rows.map { it.athlete.id }.toSet())
        assertTrue(reportScope(rows).startsWith("2 pass · 2 åkare"))
        assertFalse(reportCsv(rows).contains("Elias"))
        assertFalse(reportParagraphs(rows).joinToString("\n").contains("Elias"))
        assertTrue(reportParagraphs(rows).joinToString("\n").contains("2026-10-13"))
    }
    @Test fun datesAreInclusiveAndInvalidDraftsCannotProduceRows() {
        val selected = ReportSelection("dates", athletes = setOf(2), from = "2026-10-11", through = "2026-10-12").rows(sessions)
        assertEquals(setOf("pass-1", "pass-2"), selected.map { it.session.sessionId }.toSet())
        assertTrue(runCatching { ReportSelection("dates").rows(sessions) }.isFailure)
        assertTrue(runCatching { ReportSelection("dates", from = "2026-10-13", through = "2026-10-11").rows(sessions) }.isFailure)
    }
    @Test fun filterRestorationPreservesAllVersusNoSkierSelectionsAndEmptySessions() {
        val scope = SaverScope { true }
        listOf(ReportSelection(), ReportSelection("sessions", emptySet(), emptySet()),
            ReportSelection("dates", athletes = setOf(1, 4), from = "2026-10-11", through = "2026-10-13")).forEach { selection ->
            val saved = with(ReportSelectionSaver) { scope.save(selection) }!!
            assertEquals(selection, ReportSelectionSaver.restore(saved))
        }
        assertTrue(ReportSelection("sessions").rows(sessions).isEmpty())
        assertTrue(ReportSelection(athletes = emptySet()).rows(sessions).isEmpty())
        assertEquals(reportRuns(sessions), ReportSelection().rows(sessions))
    }
    @Test fun rosterValidationIdentifiesPositiveNumberDuplicatesWithoutRejectingAnUnchangedRow() {
        val roster = listOf(SkierDraft(1, "Alma", "12"), SkierDraft(2, "Elias", "24"))
        assertTrue(skierErrors(roster.first(), roster).valid)
        assertNotNull(skierErrors(roster[1].copy(bib = "012"), roster).bib)
        assertNotNull(skierErrors(roster[1].copy(name = " "), roster).name)
        listOf("0", "-1", "", "bib", "2147483648").forEach { assertNotNull(skierErrors(roster[1].copy(bib = it), roster).bib) }
        assertTrue(skierErrors(SkierDraft(3, "Maja", "31"), roster).valid)
    }
    @Test fun calendarDateRoundTripUsesUtcEvenAtDaylightSavingBoundaries() {
        listOf("2026-03-29", "2026-10-25", "2024-02-29").forEach { assertEquals(it, dateFromMillis(dateMillis(it)!!)) }
        assertNull(dateMillis("2026-02-29"))
        assertEquals(86400000L, dateMillis("2026-03-30")!! - dateMillis("2026-03-29")!!)
    }
}
