package se.racelink.studio

import org.junit.Assert.*
import org.junit.Test

class RunCardSelectionTest {
    @Test fun browsingIncludesDnfAndStopsAtTheEdgesOfLongSessions() {
        val runs = (1..100).map { Attempt("run-$it", 1, it, if (it == 50) null else 33000L, complete = it != 50) }
        assertEquals("run-50", adjacentCardRun(runs, "run-49", 1)?.id)
        assertEquals("run-49", adjacentCardRun(runs, "run-50", -1)?.id)
        assertEquals("run-99", adjacentCardRun(runs, null, -1)?.id)
        assertNull(adjacentCardRun(runs, "run-1", -1))
        assertNull(adjacentCardRun(runs, "run-100", 1))
        assertNull(adjacentCardRun(emptyList(), null, 1))
    }
    @Test fun noteSaveMergesWithCurrentFieldsAndRemainsAudited() {
        val original = demoTraining()
        val id = original.runs(1).first().id
        val revised = original.toggleTag(id, 1).setRunDnf(id, true)
        val saved = revised.setRunNote(id, "  Inline note  ")
        assertEquals(revised.attempts.first { it.id == id }.copy(note = "Inline note"), saved.attempts.first { it.id == id })
        assertEquals(revised.attempts.filter { it.id != id }, saved.attempts.filter { it.id != id })
        assertTrue(saved.runHistory(id).last().after!!.contains("Inline note"))
        assertTrue(runCatching { saved.closeSession().setRunNote(id, "closed") }.isFailure)
    }
    @Test fun selectionStaysOnAnOlderRunWhenNewResultsArrive() {
        val s = demoTraining()
        val runs = s.runs(1)
        val first = runs.first()
        val newer = Attempt("new", 1, 100, 31000)
        assertEquals(first, selectedCardRun(runs + newer, first.id))
        assertEquals(newer, selectedCardRun(runs + newer, null))
        assertEquals(runs.last(), selectedCardRun(runs, "missing"))
        assertEquals(s.runs(2).last(), selectedCardRun(s.runs(2), first.id))
        assertNull(selectedCardRun(emptyList(), first.id))
    }
    @Test fun taggingAndDnfAffectOnlyTheSelectedRunAndKeepItsComparisonContext() {
        val original = demoTraining()
        val runs = original.runs(1)
        val selected = runs[1]
        val changed = original.toggleTag(selected.id, 1).setRunDnf(selected.id, true)
        val displayed = selectedCardRun(changed.runs(1), selected.id)!!
        assertFalse(displayed.complete)
        assertEquals(if (1 in selected.tags) selected.tags - 1 else selected.tags + 1, displayed.tags)
        assertEquals(selected.durationMs, displayed.durationMs)
        assertEquals(runs.first(), changed.runs(1).previous(displayed))
        assertEquals(original.attempts.filter { it.id != selected.id }, changed.attempts.filter { it.id != selected.id })
    }
}
