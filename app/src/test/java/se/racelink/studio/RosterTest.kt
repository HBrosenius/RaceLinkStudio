package se.racelink.studio

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class RosterTest {
    @Test fun archivingPreservesCurrentAttendanceAndHistoricalIdentity() {
        val original = demoTraining()
        val archived = original.copy(sessionId = "old")
        val s = original.copy(archivedSessions = listOf(archived)).archiveRosterMember(1, true)
        assertEquals(original.participantIds, s.participantIds)
        assertEquals(original.attempts, s.attempts)
        assertEquals(original.athletes, s.athletes)
        assertEquals(original.roster, s.roster)
        assertEquals(listOf(archived), s.archivedSessions)
        assertTrue(s.startNewSession().participantIds.none { it == 1 })
        assertEquals(original, s.copy(archivedSessions = emptyList()).archiveRosterMember(1, false))
    }
    @Test fun archiveManagementIsAvailableAfterClosureAndRejectsUnknownIdentity() {
        val s = demoTraining().closeSession()
        assertEquals(s.attempts, s.archiveRosterMember(1, true).attempts)
        assertEquals(s.closedAt, s.archiveRosterMember(1, true).closedAt)
        assertTrue(runCatching { s.archiveRosterMember(999, true) }.isFailure)
    }
    @Test fun templatesUseCurrentRosterAndOmitArchivedOrMissingSkiers() {
        val current = demoTraining().archiveRosterMember(1, true)
        val previous = demoTraining().copy(sessionName = "Teknik", location = "Backen", course = "Bana 2", participantIds = setOf(1, 2, 999))
        val template = current.templateFrom(previous)
        assertEquals(setOf(2), template.participants)
        assertEquals(2, template.omitted)
        val next = current.startNewSession(template.name, "2026-10-16", template.location, template.course, template.type, participants = template.participants)
        assertEquals(current.roster, next.roster)
        assertTrue(next.attempts.isEmpty() && next.markers.isEmpty() && next.pending.isEmpty() && next.timingEvents.isEmpty())
        assertEquals("Bana 2", next.course)
        assertEquals("2026-10-16", next.sessionDate)
    }
    @Test fun archiveStatusSurvivesBackupAndLegacySnapshotsDefaultToActive() {
        val s = demoTraining().archiveRosterMember(1, true)
        assertEquals(s, decodeBackup(encodeBackup(s)))
        val old = JSONObject(encodeBackup(demoTraining())).put("version", 5)
        old.getJSONObject("state").remove("archivedRosterIds")
        assertTrue(decodeBackup(old.toString()).archivedRosterIds.isEmpty())
        val invalid = JSONObject(encodeBackup(s))
        invalid.getJSONObject("state").remove("archivedRosterIds")
        assertTrue(runCatching { decodeBackup(invalid.toString()) }.isFailure)
        assertTrue(runCatching { encodeBackup(s.copy(archivedRosterIds = setOf(999))) }.isFailure)
    }
}
