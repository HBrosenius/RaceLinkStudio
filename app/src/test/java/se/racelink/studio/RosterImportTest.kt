package se.racelink.studio

import org.junit.Assert.*
import org.junit.Test

class RosterImportTest {
    @Test fun quotedBomAndReorderedColumnsAreSupported() {
        val preview = previewRosterImport("\uFEFFNamn;Nummer\r\n\"Åsa; \"\"Snabb\"\"\";101\r\n", emptyList())
        assertTrue(preview.canApply)
        assertEquals("Åsa; \"Snabb\"", preview.additions.single().name)
        assertEquals(101, preview.additions.single().bib)
    }
    @Test fun ambiguousAndMalformedRowsBlockTheWholeImport() {
        val s = demoTraining()
        for (text in listOf("Nummer;Namn\n12;Annan", "Nummer;Namn\n99;Alma", "Nummer;Namn\n100;Ny\n100;Annan", "Nummer;Namn\n100;Ny\n101;Ny", "Nummer;Namn\n0;Ny", "Nummer;Namn\n9999999999999;Ny", "Nummer;Namn\n100;\"Ny", "Nummer;Namn\n100;\"Ny\"fel")) {
            assertFalse(text, previewRosterImport(text, s.roster).canApply)
            assertTrue(runCatching { s.importRosterCsv(text) }.isFailure)
        }
    }
    @Test fun existingEntriesAreSkippedAndHistoricalDataIsPreserved() {
        val s = demoTraining().closeSession()
        val text = "Nummer,Namn\n12,Alma\n101,Ny åkare"
        assertTrue(previewRosterImport(text, s.roster).rows.first().existing)
        val updated = s.importRosterCsv(text)
        assertEquals(s.roster.size + 1, updated.roster.size)
        assertEquals(s.athletes, updated.athletes)
        assertEquals(s.participantIds, updated.participantIds)
        assertEquals(s.attempts, updated.attempts)
        assertEquals(s.archivedSessions, updated.archivedSessions)
        assertFalse(previewRosterImport(text, updated.roster).canApply)
    }
    @Test fun invalidHeadersAndEmptyFilesAreRejected() {
        listOf("", "Bib;Name\n1;Test", "Nummer;Namn", "Nummer;Namn\n1;\"Rad\nBrytning\"").forEach {
            assertFalse(previewRosterImport(it, emptyList()).canApply)
        }
    }
    @Test fun allocatedIdsCannotCollideWithHistoricalIdentities() {
        val s = demoTraining()
        val historical = Athlete(999, 77, "Historisk åkare")
        val archived = s.copy(sessionId = "archived", roster = listOf(historical), athletes = listOf(historical))
        val updated = s.copy(archivedSessions = listOf(archived)).importRosterCsv("Nummer;Namn\n101;Ny person")
        assertEquals(1000, updated.roster.last().id)
        assertEquals(listOf(archived), updated.archivedSessions)
    }
}
