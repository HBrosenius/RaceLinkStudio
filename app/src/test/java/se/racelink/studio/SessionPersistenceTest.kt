package se.racelink.studio

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the storage model used by [TrainingStore]: each session is written as its own payload
 * (no nested archive, no inline audit) while audit entries are kept separately, as the append-only
 * journal does. Reconstructing from those parts must reproduce the original tree exactly.
 */
class SessionPersistenceTest {
    private fun roundTrip(root: Training): Training {
        val sessions = listOf(root) + root.archivedSessions
        val payloads = sessions.associate { it.sessionId to TrainingJson.sessionPayload(it).toString() }
        val journal = sessions.associate { it.sessionId to it.audit }
        fun build(id: String) = TrainingJson.decode(JSONObject(payloads.getValue(id))).copy(audit = journal.getValue(id))
        return build(root.sessionId).copy(archivedSessions = root.archivedSessions.map { build(it.sessionId) })
    }

    @Test fun perSessionRowsReconstructCurrentAndArchivedSessionsWithJournalAudit() {
        val root = demoTraining().correct("seed-0", 2, false, setOf(1), "Anteckning").startNewSession()
        assertTrue(root.archivedSessions.isNotEmpty())
        assertEquals(root, roundTrip(root))
    }

    @Test fun storedSessionPayloadOmitsNestedArchiveAndInlineAudit() {
        val root = demoTraining().correct("seed-0", 2, false, setOf(1), "Anteckning").startNewSession()
        assertTrue(root.archivedSessions.single().audit.isNotEmpty())
        val payload = TrainingJson.sessionPayload(root)
        assertEquals(0, payload.getJSONArray("archivedSessions").length())
        assertEquals(0, payload.getJSONArray("audit").length())
        assertEquals(root.sessionId, payload.getString("sessionId"))
    }

    @Test fun journalAuditIsPreservedPerSessionSeparatelyFromArchivedSessions() {
        val root = demoTraining().correct("seed-0", 2, false, setOf(1), "Anteckning").startNewSession()
        val rebuilt = roundTrip(root)
        assertEquals(root.archivedSessions.single().audit, rebuilt.archivedSessions.single().audit)
        assertEquals(root.audit, rebuilt.audit)
    }
}
