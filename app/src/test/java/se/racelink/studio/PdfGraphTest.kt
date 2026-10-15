package se.racelink.studio

import org.junit.Assert.*
import org.junit.Test

class PdfGraphTest {
    @Test fun graphsAreOptionalAndUseOnlySelectedPeopleAndSessions() {
        val sessions = listOf(demoTraining().copy(sessionId = "one"), demoTraining().copy(sessionId = "two"))
        val rows = filterReport(reportRuns(sessions), setOf("two"), athleteIds = setOf(1, 3))
        assertFalse(reportParagraphs(rows).any { it.startsWith(PDF_GRAPH_PREFIX) })
        val graphs = reportParagraphs(rows, includeGraphs = true).filter { it.startsWith(PDF_GRAPH_PREFIX) }
        assertEquals(2, graphs.size)
        assertEquals(rows.filter { it.athlete.id == 1 }.map { it.number }, pdfGraphPoints(graphs.first()).map { it.number })
        assertNull(pdfGraphPoints(graphs.first())[2].durationMs)
    }
    @Test fun longGraphsSplitWithoutDroppingRunsOrExposingPrivateText() {
        val s = demoTraining().copy(attempts = (1..35).map { Attempt("id-$it", 1, it, 33000, note = "Private note") })
        val paragraphs = reportParagraphs(reportRuns(listOf(s)), includeGraphs = true)
        val graphs = paragraphs.filter { it.startsWith(PDF_GRAPH_PREFIX) }.map(::pdfGraphPoints)
        assertEquals(listOf(16, 16, 3), graphs.map { it.size })
        assertEquals((1..35).toList(), graphs.flatten().map { it.number })
        assertFalse(paragraphs.joinToString("\n").contains("Private note"))
    }
}
