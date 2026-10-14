package se.racelink.studio

import org.junit.Assert.*
import org.junit.Test

class TrainingTest {
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
