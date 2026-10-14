package se.racelink.studio

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class BackupTest {
    @Test fun legacyTagsKeepTheirAssignmentsAndGainSeparateFocusChoices() {
        val s = demoTraining().copy(tags = demoTraining().tags.filter { it.category == TagCategory.RUN })
        val json = TrainingJson.encode(s)
        val tags = json.getJSONArray("tags")
        (0 until tags.length()).forEach { tags.getJSONObject(it).remove("category") }
        val loaded = TrainingJson.decode(json)
        assertEquals(s.attempts, loaded.attempts)
        assertEquals(s.tags, loaded.tags.filter { it.category == TagCategory.RUN })
        assertEquals(3, loaded.tags.count { it.category == TagCategory.NEXT_FOCUS })
        assertEquals(loaded, TrainingJson.decode(TrainingJson.encode(loaded)))
    }
    @Test fun fullBackupRoundTripPreservesEveryFieldAndArchivedEvidence() {
        val old = demoTraining().correct("seed-0", 1, true, setOf(1, 3), "Privat: åäö och \"citat\"")
        val active = old.startNewSession("Slalom", "2026-10-15", "Åre", "Bana 2", "Teknik", participants = setOf(1, 2))
            .ingest(SimulatorAdapter().next(1, 12)).toggleTag("demo-1", 2).selectNextAthlete(2)
        val restored = readBackup(encodeBackup(active).byteInputStream())
        assertEquals(active, restored)
        assertEquals(old.attempts, restored.archivedSessions.single().attempts)
        assertEquals(old.audit, restored.archivedSessions.single().audit)
    }
    @Test fun invalidBackupsAreRejectedWithoutFallingBackToSeedData() {
        val valid = encodeBackup(demoTraining())
        assertTrue(runCatching { decodeBackup(valid.take(valid.length / 2)) }.isFailure)
        assertTrue(runCatching { decodeBackup(JSONObject(valid).put("version", 99).toString()) }.isFailure)
        assertTrue(runCatching { decodeBackup(JSONObject(valid).put("format", "other-app").toString()) }.isFailure)
        val missing = JSONObject(valid)
        missing.getJSONObject("state").remove("roster")
        assertTrue(runCatching { decodeBackup(missing.toString()) }.isFailure)
        assertTrue(runCatching { decodeBackup("[".repeat(33) + "]".repeat(33)) }.isFailure)
    }
    @Test fun validationRejectsBrokenLinksDuplicateSessionsAndInvalidTimes() {
        val s = demoTraining()
        assertTrue(runCatching { encodeBackup(s.copy(participantIds = setOf(999))) }.isFailure)
        assertTrue(runCatching { encodeBackup(s.copy(attempts = s.attempts + s.attempts.first())) }.isFailure)
        assertTrue(runCatching { encodeBackup(s.copy(attempts = listOf(s.attempts.first().copy(athleteId = 999)))) }.isFailure)
        assertTrue(runCatching { encodeBackup(s.copy(attempts = listOf(s.attempts.first().copy(durationMs = null)))) }.isFailure)
        assertTrue(runCatching { encodeBackup(s.copy(archivedSessions = listOf(s))) }.isFailure)
        assertTrue(runCatching { encodeBackup(s.copy(pending = listOf("missing"))) }.isFailure)
        assertTrue(runCatching { encodeBackup(s.copy(attempts = listOf(s.attempts.first().copy(tags = setOf(999))))) }.isFailure)
    }
    @Test fun previousLocalSnapshotsStillDecodeWithRosterAndAttendanceDefaults() {
        val s = demoTraining()
        val json = TrainingJson.encode(s)
        listOf("roster", "participantIds", "sessionName", "location", "course", "trainingType", "nextAthleteId").forEach { json.remove(it) }
        val migrated = TrainingJson.decode(json)
        assertEquals(s.athletes, migrated.roster)
        assertEquals(s.athletes.map { it.id }.toSet(), migrated.participantIds)
        assertEquals(s.attempts, migrated.attempts)
        assertNull(migrated.nextAthleteId)
    }
    @Test fun versionOneBackupLoadsButVersionTwoRequiresOriginalEventCollection() {
        val root = JSONObject(encodeBackup(demoTraining()))
        root.getJSONObject("state").remove("timingEvents")
        assertTrue(runCatching { decodeBackup(root.toString()) }.isFailure)
        root.put("version", 1)
        assertTrue(decodeBackup(root.toString()).timingEvents.isEmpty())
    }
}
