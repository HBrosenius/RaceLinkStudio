package se.racelink.studio

import org.junit.Assert.*
import org.junit.Test

class ReportImageSelectionTest {
    @Test fun individualImageRequiresOneSkierAndOneSession() {
        val s = demoTraining()
        val rows = reportRuns(listOf(s))
        val individual = rows.filter { it.athlete.id == 1 }
        assertTrue(individualImageAllowed(individual))
        assertFalse(individualImageAllowed(emptyList()))
        assertFalse(individualImageAllowed(rows))
        assertFalse(individualImageAllowed(individual + individual.first().copy(session = s.copy(sessionId = "other"))))
    }
}
