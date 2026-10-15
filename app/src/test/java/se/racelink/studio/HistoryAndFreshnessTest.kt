package se.racelink.studio

import org.junit.Assert.*
import org.junit.Test

class HistoryAndFreshnessTest {
    @Test fun historyPreservesNamesAndPrivateTextAtEditTimeThroughBackup() {
        val original = demoTraining()
        val revised = original.correct("seed-0", 2, false, setOf(1), "Citat \" -> | \"\nNy rad")
        val entry = decodeBackup(encodeBackup(revised)).runHistory("seed-0").single()
        assertTrue(entry.before!!.contains("Alma"))
        assertTrue(entry.after!!.contains("Elias"))
        assertTrue(entry.after.contains("DNF"))
        assertTrue(entry.after.contains("Citat \" -> | \"\nNy rad"))
        assertEquals(emptyList<RunHistoryEntry>(), revised.runHistory("seed-1"))
        assertEquals(entry, revised.copy(athletes = revised.athletes.map { it.copy(name = "Renamed") }).runHistory("seed-0").single())
    }
    @Test fun legacyHistoryMatchesExactIdentityAndDoesNotInventDetails() {
        val s = demoTraining().copy(audit = listOf("2026-10-15 | seed-0 | stored before -> after", "2026-10-15 | seed-01 | unrelated", "unknown legacy text"))
        val entry = s.runHistory("seed-0").single()
        assertNull(entry.before)
        assertEquals("stored before -> after", entry.legacy)
    }
    @Test fun fingerprintSurvivesPersistenceAndDetectsDataAndArchiveChanges() {
        val s = demoTraining()
        assertEquals(s.backupFingerprint(), decodeBackup(encodeBackup(s)).backupFingerprint())
        assertNotEquals(s.backupFingerprint(), s.toggleTag("seed-0", 1).backupFingerprint())
        assertNotEquals(s.backupFingerprint(), s.copy(archivedSessions = listOf(s.copy(sessionId = "old"))).backupFingerprint())
    }
    @Test fun receiptDescribesTheExportedSnapshotRatherThanLaterChanges() {
        val original = demoTraining()
        val receipt = BackupReceipt("2026-10-15T10:00:00Z", original.backupFingerprint())
        assertTrue(backupFreshness(null, original.backupFingerprint()).startsWith("Ingen extern"))
        assertTrue(backupFreshness(receipt, original.backupFingerprint()).contains("Alla aktuella"))
        assertTrue(backupFreshness(receipt, original.toggleTag("seed-0", 1).backupFingerprint()).contains("har ändrats"))
        assertTrue(backupFreshness(receipt, null).contains("kan inte kontrolleras"))
    }
}
