package se.racelink.studio

import org.junit.Assert.*
import org.junit.Test

class TimingReviewTest {
    @Test fun unresolvedEventsStayOutOfResultsAndAssignmentPreservesOriginal() {
        val old = demoTraining()
        val raw = TimingObservation("unknown", 999, 18, 32000, true)
        val queued = old.ingest(raw)
        assertEquals(old.attempts, queued.attempts)
        assertEquals(queued, queued.ingest(raw))
        assertTrue(runCatching { queued.startNewSession() }.isFailure)
        val item = queued.reviewQueue().single()
        val resolved = queued.resolveTiming(item.id, TimingDecision.ASSIGNED, 2)
        assertEquals(2, resolved.attempts.last().athleteId)
        assertEquals(32000L, resolved.attempts.last().durationMs)
        assertEquals(raw, resolved.timingEvents.single().observation)
        assertEquals(TimingDecision.ASSIGNED, resolved.timingEvents.single().decision)
        assertTrue(resolved.reviewQueue().isEmpty())
        assertEquals(1, resolved.audit.size)
        assertEquals(resolved, resolved.ingest(raw))
        assertTrue(runCatching { resolved.resolveTiming(item.id, TimingDecision.REJECTED) }.isFailure)
        assertEquals(resolved.timingEvents, resolved.startNewSession().archivedSessions.first().timingEvents)
        assertEquals(resolved, decodeBackup(encodeBackup(resolved)))
    }
    @Test fun duplicatesAndRejectionsDoNotCreateOrDeleteRuns() {
        val old = demoTraining()
        val queued = old.simulateReview(true).simulateReview(false)
        val first = queued.reviewQueue().first()
        val duplicate = queued.resolveTiming(first.id, TimingDecision.DUPLICATE, duplicateId = old.attempts.first().id)
        val rejected = duplicate.resolveTiming(duplicate.reviewQueue().single().id, TimingDecision.REJECTED)
        assertEquals(old.attempts, rejected.attempts)
        assertEquals(2, rejected.audit.size)
        assertEquals(listOf(TimingDecision.DUPLICATE, TimingDecision.REJECTED), rejected.timingEvents.map { it.decision })
        assertTrue(runCatching { queued.resolveTiming(first.id, TimingDecision.DUPLICATE, duplicateId = "missing") }.isFailure)
        assertEquals(rejected, decodeBackup(encodeBackup(rejected)))
    }
    @Test fun conflictingReplayRequiresReviewButEqualLegitimateTimesDoNot() {
        val raw = TimingObservation("one", 12, 18, 33000, true)
        val accepted = demoTraining().ingest(raw)
        val conflict = accepted.ingest(raw.copy(durationMs = 34000))
        assertEquals(accepted.attempts, conflict.attempts)
        val resolved = conflict.resolveTiming(conflict.reviewQueue().single().id, TimingDecision.ASSIGNED, 1)
        assertEquals(2, resolved.attempts.takeLast(2).size)
        assertEquals(resolved.attempts.size, resolved.attempts.map { it.id }.distinct().size)
        assertEquals(resolved.attempts.size, resolved.attempts.map { it.sequence }.distinct().size)
        val equal = accepted.ingest(raw.copy(id = "two", sequence = 19))
        assertTrue(equal.reviewQueue().isEmpty())
        assertEquals(accepted.attempts.size + 1, equal.attempts.size)
    }
    @Test fun pendingReviewSurvivesEditingBackupAndNewTimingUsesUnusedSequence() {
        val queued = demoTraining().selectNextAthlete(1).simulateReview(true)
        val edited = queued.editSession("Nytt namn", queued.sessionDate, "", "", "", queued.roster, queued.participantIds)
        assertEquals(queued.timingEvents, edited.timingEvents)
        assertEquals(queued, decodeBackup(encodeBackup(queued)))
        val finished = queued.simulateNext(SimulatorAdapter())
        assertTrue(finished.attempts.last().sequence > queued.reviewQueue().single().observation.sequence)
    }
}
