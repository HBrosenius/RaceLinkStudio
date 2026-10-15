package se.racelink.studio

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class SaveOperationsTest {
    @Test fun queuedPreviewAndAcknowledgementHaveTheSameIdentityEvenWithNewerRuns() {
        val s = demoTraining().copy(pending = emptyList()).selectNextAthlete(1).simulateNext(SimulatorAdapter())
            .selectNextAthlete(2).simulateNext(SimulatorAdapter()).selectNextAthlete(1).simulateNext(SimulatorAdapter())
        var remaining = s
        val expected = s.pending.toList()
        expected.forEachIndexed { index, id ->
            assertEquals(id, remaining.nextPendingRun()!!.id)
            assertEquals(expected.size - index, remaining.pending.size)
            remaining = remaining.acknowledgePending(id)
        }
        assertNull(remaining.nextPendingRun())
        assertTrue(runCatching { s.acknowledgePending(expected.last()) }.isFailure)
    }
    @Test fun failedCommitDoesNotPublishOrInvokeSuccessAndCanRetryTheSameDraft() = runBlocking {
        val before = demoTraining()
        val run = before.attempts.first()
        val draft = before.correct(run.id, run.athleteId, run.complete, run.tags, "Draft retained")
        var published = before
        var closes = 0
        val failed = commitTraining(before, { draft }) { throw java.io.IOException("Disk full") }
        failed.onSuccess { published = it; closes++ }
        assertTrue(failed.isFailure)
        assertEquals(before, published)
        assertEquals(0, closes)
        var stored: Training? = null
        commitTraining(before, { draft }) { stored = it }.onSuccess { published = it; closes++ }
        assertEquals(draft, stored)
        assertEquals(draft, published)
        assertEquals(1, closes)
    }
    @Test fun tagUndoRetainsLaterFinishesNotesAndOtherTags() {
        val before = demoTraining()
        val run = before.attempts.first()
        val tag = before.tags.first { it.category == TagCategory.RUN }.id
        val other = before.tags.last { it.category == TagCategory.RUN }.id
        val undo = quickTagUndo(before, run.id, tag)
        val later = before.toggleTag(run.id, tag).toggleTag(run.id, other).selectNextAthlete(2).simulateNext(SimulatorAdapter())
        val changed = later.correct(run.id, run.athleteId, run.complete, later.attempts.first().tags, "Later note")
        val result = undo.apply(changed)
        assertEquals(changed.attempts.size, result.attempts.size)
        assertEquals(changed.attempts.last(), result.attempts.last())
        assertEquals("Later note", result.attempts.first().note)
        assertEquals(tag in run.tags, tag in result.attempts.first().tags)
        assertEquals(other in changed.attempts.first().tags, other in result.attempts.first().tags)
        assertTrue(runCatching { undo.apply(result) }.isFailure)
    }
    @Test fun dnfUndoRetainsCoachingEditsAndOriginalEvidence() {
        val before = demoTraining()
        val run = before.attempts.first { it.complete }
        val undo = dnfUndo(before, run.id)
        val flagged = before.setRunDnf(run.id, true).toggleTag(run.id, before.tags.first().id)
        val result = undo.apply(flagged)
        assertTrue(result.attempts.first { it.id == run.id }.complete)
        assertEquals(flagged.attempts.first { it.id == run.id }.tags, result.attempts.first { it.id == run.id }.tags)
        assertEquals(before.timingEvents, result.timingEvents)
    }
    @Test fun deletedChangeUndoPreservesNewerChangesAndFinishesAndChecksSession() {
        val before = demoTraining().recordChange(emptySet(), "Original")
        val index = before.markers.lastIndex
        val undo = removedChangeUndo(before, index)
        val later = before.removeChange(index).recordChange(emptySet(), "Later").selectNextAthlete(1).simulateNext(SimulatorAdapter())
        val result = undo.apply(later)
        assertEquals(later.attempts, result.attempts)
        assertTrue(result.markers.contains(before.markers[index]))
        assertTrue(result.markers.any { it.label == "Later" })
        assertTrue(runCatching { undo.apply(result) }.isFailure)
        assertTrue(runCatching { undo.apply(later.copy(sessionId = "another")) }.isFailure)
    }
}
