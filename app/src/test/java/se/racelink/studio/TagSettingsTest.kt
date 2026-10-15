package se.racelink.studio

import org.junit.Assert.*
import org.junit.Test

class TagSettingsTest {
    @Test fun namesAreTrimmedAndUniqueWithinTheirCategory() {
        val tag = CoachingTag(1, "Teknik", TagCategory.RUN)
        assertNotNull(tagNameError(" teknik ", TagCategory.RUN, listOf(tag), 2))
        assertNull(tagNameError("Teknik", TagCategory.NEXT_FOCUS, listOf(tag), 2))
        assertNull(tagNameError("Teknik", TagCategory.RUN, listOf(tag), 1))
        assertNotNull(tagNameError("  ", TagCategory.RUN, emptyList(), 2))
    }

    @Test fun renamingAndMovingPreserveRunSelectionsAndArchivedSessions() {
        val old = demoTraining()
        val archived = old.copy(sessionId = "old", closedAt = "2026-10-15T10:00:00Z")
        val current = old.copy(archivedSessions = listOf(archived))
        val tag = current.tags.first { it.category == TagCategory.RUN }
        val updated = current.tags.map { if (it.id == tag.id) it.copy(name = "  Nytt namn  ", category = TagCategory.NEXT_FOCUS) else it }
        val result = current.updateTagSettings(" Lag ", updated)
        assertEquals(current.attempts, result.attempts)
        assertEquals(current.markers, result.markers)
        assertEquals(listOf(archived), result.archivedSessions)
        assertEquals("Nytt namn", result.tags.first { it.id == tag.id }.name)
        assertEquals(current.audit.size + 1, result.audit.size)
        assertEquals("Lag", result.team)
    }

    @Test fun closedSessionsAndChangeCategoryConversionAreRejected() {
        val current = demoTraining()
        assertTrue(runCatching { current.copy(closedAt = "closed").updateTagSettings(current.team, current.tags) }.isFailure)
        val change = current.tags.first { it.category == TagCategory.CHANGE }
        assertTrue(runCatching { current.updateTagSettings(current.team, current.tags.map { if (it.id == change.id) it.copy(category = TagCategory.RUN) else it }) }.isFailure)
        assertTrue(runCatching { current.updateTagSettings(current.team, current.tags.drop(1)) }.isFailure)
    }
}
