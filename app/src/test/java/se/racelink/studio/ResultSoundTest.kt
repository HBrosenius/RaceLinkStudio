package se.racelink.studio

import org.junit.Assert.*
import org.junit.Test

class ResultSoundTest {
    @Test fun onlyNewCompletedRunsInTheSameSessionTriggerSound() {
        val s = demoTraining()
        val run = Attempt("new-result", s.athletes.first().id, 999, 33000)
        val after = s.copy(attempts = s.attempts + run)
        assertTrue(hasNewCompletedResult(s, after))
        assertFalse(hasNewCompletedResult(after, after))
        assertFalse(hasNewCompletedResult(s, s.toggleTag(s.attempts.first().id, 1)))
        assertFalse(hasNewCompletedResult(s, s.copy(attempts = s.attempts + run.copy(complete = false))))
        assertFalse(hasNewCompletedResult(s, after.copy(sessionId = "different-session")))
    }
}
