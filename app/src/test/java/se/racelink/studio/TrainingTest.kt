package se.racelink.studio

import org.junit.Assert.*
import org.junit.Test

class TrainingTest {
    @Test fun removingChangeClearsInheritedContextAndKeepsOtherChangesAndRuns() {
        val original = demoTraining().copy(markers = emptyList()).recordChange(emptySet(), "Remove me")
            .recordChange(emptySet(), "Keep me").selectNextAthlete(1).simulateNext(SimulatorAdapter())
        val removed = original.removeChange(0)
        assertEquals(original.attempts, removed.attempts)
        assertEquals(listOf(original.markers[1]), removed.markers)
        assertEquals("Keep me", removed.tagDescription(removed.attempts.last(), TagCategory.CHANGE))
        assertTrue(removed.audit.last().detail.contains("Remove me"))
        assertEquals(removed, decodeBackup(encodeBackup(removed)))
        assertTrue(runCatching { original.removeChange(9) }.isFailure)
        assertTrue(runCatching { original.copy(closedAt = java.time.Instant.now().toString()).removeChange(0) }.isFailure)
    }

    @Test fun editingChangePreservesBoundaryAndUpdatesAffectedRuns() {
        val initial = demoTraining().copy(markers = emptyList()).recordChange(emptySet(), "Old")
        val later = initial.selectNextAthlete(1).simulateNext(SimulatorAdapter())
        val selected = later.tags.filter { it.category == TagCategory.CHANGE }.take(2).map { it.id }.toSet()
        val edited = later.editChange(0, selected, "Updated")
        assertEquals(later.markers[0].afterSequence, edited.markers[0].afterSequence)
        assertEquals(later.attempts, edited.attempts)
        assertTrue(edited.changesFor(edited.attempts.first()).isEmpty())
        assertTrue(edited.tagDescription(edited.attempts.last(), TagCategory.CHANGE).contains("Updated"))
        assertEquals(selected, edited.markers[0].tags)
        assertEquals(edited, decodeBackup(encodeBackup(edited)))
        assertTrue(runCatching { later.editChange(0, emptySet(), " ") }.isFailure)
        assertTrue(runCatching { later.copy(closedAt = java.time.Instant.now().toString()).editChange(0, selected, "No") }.isFailure)
    }

    @Test fun changeBoundaryUsesOriginalSequenceWhenReviewReassignsRunSequence() {
        val s = demoTraining().copy(markers = emptyList()).recordChange(emptySet(), "Ny bana")
        val run = s.attempts.first().copy(id = "late", sequence = 99)
        val event = RecordedTiming("late-event", TimingObservation("late", 12, 1, run.durationMs, true), "Granskad",
            TimingDecision.ASSIGNED, run.athleteId, run.id, java.time.Instant.now().toString())
        val reviewed = s.copy(attempts = s.attempts + run, timingEvents = s.timingEvents + event)
        assertTrue(reviewed.changesFor(run).isEmpty())
    }
    @Test fun changesApplyOnlyToSubsequentRunsAcrossAllSkiersAndSurviveBackup() {
        val original = demoTraining().copy(markers = emptyList())
        val ids = original.tags.filter { it.category == TagCategory.CHANGE }.take(2).map { it.id }.toSet()
        val changed = original.recordChange(ids, "Ny kombination")
        assertTrue(changed.attempts.all { changed.changesFor(it).isEmpty() })
        val first = changed.selectNextAthlete(1).simulateNext(SimulatorAdapter())
        val second = first.selectNextAthlete(2).simulateNext(SimulatorAdapter())
        listOf(first.attempts.last(), second.attempts.last()).forEach {
            assertTrue(second.tagDescription(it, TagCategory.CHANGE).contains("Ny kombination"))
            assertEquals(ids, second.changesFor(it).single().tags)
        }
        assertEquals(second, decodeBackup(encodeBackup(second)))
        val again = second.recordChange(emptySet(), "Ny snö").simulateNext(SimulatorAdapter())
        assertEquals(2, again.changesFor(again.attempts.last()).size)
        assertTrue(runCatching { original.recordChange(emptySet(), " ") }.isFailure)
        assertTrue(runCatching { original.toggleTag(original.attempts.first().id, ids.first()) }.isFailure)
        assertTrue(reportParagraphs(reportRuns(listOf(second)), includeTags = true).any { it.contains("Ny kombination") })
    }

    @Test fun dnfPreservesRecordedTimeButIsExcludedFromComparisonsSummariesAndGraphs() {
        val s = demoTraining().selectNextAthlete(1).simulateNext(SimulatorAdapter())
        val latest = s.runs(1).last()
        val flagged = s.setRunDnf(latest.id, true)
        assertEquals(latest.durationMs, flagged.runs(1).last().durationMs)
        assertEquals(s.timingEvents, flagged.timingEvents)
        assertFalse(flagged.runs(1).eligible().any { it.id == latest.id })
        val summary = summarizeRuns(reportRuns(listOf(flagged), athleteId = 1))
        assertNull(summary.latestMs)
        assertEquals(s.runs(1).eligible().size - 1, summary.completed)
        val graph = reportParagraphs(reportRuns(listOf(flagged), athleteId = 1), includeGraphs = true).first { it.startsWith(PDF_GRAPH_PREFIX) }
        assertNull(pdfGraphPoints(graph).last().durationMs)
        val next = flagged.simulateNext(SimulatorAdapter()).runs(1)
        assertNotEquals(latest.id, next.previous(next.last())!!.id)
        assertEquals(flagged, decodeBackup(encodeBackup(flagged)))
        assertEquals(latest, flagged.setRunDnf(latest.id, false).runs(1).last())
        val noFinish = s.attempts.first { it.durationMs == null }
        assertTrue(runCatching { s.setRunDnf(noFinish.id, false) }.isFailure)
    }
    @Test fun chartSwipesGoLeftToPreviousAndRightBackWithoutWrapping() {
        val skiers = demoTraining().athletes
        assertEquals(1, adjacentSkier(skiers, 2, -1))
        assertEquals(2, adjacentSkier(skiers, 1, 1))
        assertEquals(1, adjacentSkier(skiers, 1, -1))
        assertEquals(4, adjacentSkier(skiers, 4, 1))
        assertEquals(99, adjacentSkier(skiers, 99, 1))
        assertEquals(2, adjacentSkier(listOf(skiers[1]), 2, -1))
    }
    @Test fun closingSessionPreservesEvidenceWithoutCreatingAnotherSession() {
        val s = demoTraining().selectNextAthlete(2).simulateNext(SimulatorAdapter())
        val closed = s.closeSession()
        assertEquals(s.sessionId, closed.sessionId)
        assertEquals(s.attempts, closed.attempts)
        assertEquals(s.timingEvents, closed.timingEvents)
        assertEquals(s.archivedSessions, closed.archivedSessions)
        assertEquals(s.markers, closed.markers)
        assertNotNull(closed.closedAt)
        assertNull(closed.nextAthleteId)
        assertTrue(closed.pending.isEmpty())
        assertEquals(s.audit.size + 1, closed.audit.size)
        assertEquals(closed, closed.closeSession())
        assertEquals(closed, decodeBackup(encodeBackup(closed)))
        val next = closed.startNewSession()
        assertNull(next.closedAt)
        assertTrue(next.attempts.isEmpty())
        assertEquals(listOf(closed.sessionId), next.archivedSessions.map { it.sessionId })
        assertEquals(closed.closedAt, next.archivedSessions.single().closedAt)
    }
    @Test fun closedSessionRejectsTimingAndEditsWhileUnresolvedQueueBlocksClosing() {
        val s = demoTraining()
        assertTrue(runCatching { s.simulateReview(true).closeSession() }.isFailure)
        val closed = s.closeSession()
        assertTrue(runCatching { closed.selectNextAthlete(1) }.isFailure)
        assertTrue(runCatching { closed.simulateNext(SimulatorAdapter()) }.isFailure)
        assertTrue(runCatching { closed.toggleTag(closed.attempts.first().id, 1) }.isFailure)
        assertTrue(runCatching { closed.ingest(SimulatorAdapter().next(20, 12)) }.isFailure)
        assertTrue(runCatching { closed.editSession("Nytt", closed.sessionDate, "", "", "", closed.roster, closed.participantIds) }.isFailure)
    }
    @Test fun longChartPagesDoNotLoseDuplicateOrRenumberRuns() {
        val runs = (1..30).map { Attempt("run-$it", 1, it, 33000) }
        val items = chartRuns(runs, false)
        assertEquals(listOf(12, 12, 6), (0..2).map { chartPage(items, it).size })
        assertEquals((1..30).toList(), (0..2).flatMap { chartPage(items, it) }.map { it.number })
        assertEquals(chartPage(items, 2), chartPage(items, 99))
        assertEquals(listOf(7, 8, 9, 10, 11, 12), chartPage(items, 1, 6).map { it.number })
        assertTrue(chartPage(emptyList(), 0).isEmpty())
    }
    @Test fun chartFilterKeepsOriginalRunNumbersAndIncompleteTaggedAttempts() {
        val runs = listOf(Attempt("a", 1, 1, 33000), Attempt("b", 1, 2, null, false, setOf(1)),
            Attempt("c", 1, 3, 32000, tags = setOf(2)))
        val filtered = chartRuns(runs, true)
        assertEquals(listOf(2, 3), filtered.map { it.number })
        assertEquals(listOf("b", "c"), filtered.map { it.attempt.id })
        assertEquals(runs, chartRuns(runs, false).map { it.attempt })
        assertTrue(chartRuns(listOf(runs.first()), true).isEmpty())
    }
    @Test fun chartTapsFindTheNearestPointWithinTouchTolerance() {
        val points = listOf(ChartPoint(75f, 100f), ChartPoint(225f, 160f))
        assertEquals(0, chartHit(points, 90f, 105f, 28f))
        assertEquals(1, chartHit(points, 220f, 160f, 28f))
        assertNull(chartHit(points, 150f, 130f, 28f))
        assertNull(chartHit(emptyList(), 0f, 0f, 28f))
    }
    @Test fun multipleTagsInBothCategoriesPersistIndependentlyAndExportWithLabels() {
        val s = demoTraining()
        val runId = s.attempts.first().id
        val actual = s.tags.filter { it.category == TagCategory.RUN }.take(2)
        val focus = s.tags.filter { it.category == TagCategory.NEXT_FOCUS }.take(2)
        val tagged = (actual + focus).fold(s) { state, tag -> state.toggleTag(runId, tag.id) }
        assertEquals((actual + focus).map { it.id }.toSet(), tagged.attempts.first().tags)
        assertEquals(actual.joinToString(" · ") { it.name }, tagged.tagDescription(tagged.attempts.first(), TagCategory.RUN))
        val removed = tagged.toggleTag(runId, actual.first().id)
        assertTrue(removed.attempts.first().tags.containsAll(focus.map { it.id }))
        assertEquals(tagged, decodeBackup(encodeBackup(tagged)))
        val pdf = reportParagraphs(reportRuns(listOf(tagged)), includeTags = true).joinToString("\n")
        assertTrue(pdf.contains("Beskriv åket:"))
        assertTrue(pdf.contains("Fokus nästa åk:"))
        assertFalse(reportParagraphs(reportRuns(listOf(tagged))).joinToString("\n").contains(focus.first().name))
    }
    @Test fun selectedSkierReceivesRepeatedFinishesAndIncompleteAttempts() {
        val s = demoTraining().selectNextAthlete(3)
        val finished = s.simulateNext(SimulatorAdapter()).simulateNext(SimulatorAdapter())
        assertEquals(listOf(3, 3), finished.attempts.takeLast(2).map { it.athleteId })
        assertEquals(3, finished.nextAthleteId)
        val incomplete = finished.simulateNext(SimulatorAdapter(), true)
        assertEquals(3, incomplete.attempts.last().athleteId)
        assertFalse(incomplete.attempts.last().complete)
        assertEquals(2, incomplete.selectNextAthlete(2).simulateNext(SimulatorAdapter()).attempts.last().athleteId)
        assertTrue(runCatching { incomplete.selectNextAthlete(null).simulateNext(SimulatorAdapter()) }.isFailure)
    }
    @Test fun nextSkierMustBeActiveAndClearsOnNewSessionOrAttendanceRemoval() {
        val s = demoTraining().selectNextAthlete(1)
        assertTrue(runCatching { s.selectNextAthlete(999) }.isFailure)
        assertNull(s.startNewSession().nextAthleteId)
        val edited = s.editSession(s.sessionName, s.sessionDate, "", "", "", s.roster, setOf(2))
        assertNull(edited.nextAthleteId)
        assertTrue(runCatching { edited.selectNextAthlete(1) }.isFailure)
        assertEquals(1, s.editSession("Nytt namn", s.sessionDate, "", "", "", s.roster, s.participantIds).nextAthleteId)
    }
    @Test fun quickTagsToggleWithoutChangingRunEvidence() {
        val s = demoTraining()
        val run = s.attempts.first()
        val tagged = s.toggleTag(run.id, 2)
        assertEquals(run.copy(tags = setOf(2)), tagged.attempts.first())
        assertEquals(1, tagged.audit.size)
        val untagged = tagged.toggleTag(run.id, 2)
        assertEquals(run, untagged.attempts.first())
        assertEquals(2, untagged.audit.size)
        assertTrue(runCatching { s.toggleTag(run.id, 999) }.isFailure)
    }
    @Test fun summariesExcludeIncompleteTimesAndNeverCompareDifferentSessions() {
        val s = demoTraining()
        val rows = reportRuns(listOf(s), athleteId = 1)
        val summary = summarizeRuns(rows)
        assertEquals(5, summary.total)
        assertEquals(4, summary.completed)
        assertEquals(33420L, summary.bestMs)
        assertEquals(-2200L, summary.progressMs)
        val endedEarly = summarizeRuns(rows.take(3))
        assertNull(endedEarly.latestMs)
        assertEquals(34980L, endedEarly.bestMs)
        assertNull(summarizeRuns(rows + reportRuns(listOf(s.copy(sessionId = "another")), athleteId = 1)).progressMs)
    }
    @Test fun pdfContentRespectsScopeAndExplicitPrivacyOptions() {
        val s = demoTraining().correct("seed-0", 1, true, setOf(1), "Privat anteckning")
        val rows = reportRuns(listOf(s), athleteId = 1)
        val defaults = reportParagraphs(rows).joinToString("\n")
        assertFalse(defaults.contains("Privat anteckning"))
        assertFalse(defaults.contains("Teknikfokus"))
        assertFalse(defaults.contains("Elias"))
        val included = reportParagraphs(rows, true, true).joinToString("\n")
        assertTrue(included.contains("Privat anteckning"))
        assertTrue(included.contains("Teknikfokus"))
        assertTrue(included.contains(s.sessionLabel()))
        assertTrue(included.contains("DNF"))
    }
    @Test fun editActiveSessionPreservesEvidenceAndArchivesWhileChangingAttendance() {
        val old = demoTraining().copy(archivedSessions = listOf(demoTraining()), pending = listOf("seed-0"))
        val roster = old.roster + Athlete(5, 50, "Sara")
        val edited = old.editSession("Slalom", "2026-10-15", "Åre", "Bana 2", "Teknik", roster, setOf(2, 5))
        assertEquals(old.sessionId, edited.sessionId)
        assertEquals(old.attempts, edited.attempts)
        assertEquals(old.pending, edited.pending)
        assertEquals(old.markers, edited.markers)
        assertEquals(old.archivedSessions, edited.archivedSessions)
        assertEquals(listOf(2, 5), edited.activeAthletes().map { it.id })
        assertEquals(old.runs(1), edited.runs(1))
        assertTrue(edited.athletes.any { it.id == 1 })
        assertEquals("Slalom", edited.sessionName)
        assertEquals(1, edited.audit.size)
        assertEquals(1, edited.ingest(SimulatorAdapter().next(18, 12)).reviewQueue().size)
        assertEquals(5, edited.ingest(SimulatorAdapter().next(18, 50)).attempts.last().athleteId)
        assertEquals(edited, edited.editSession("Slalom", "2026-10-15", "Åre", "Bana 2", "Teknik", roster, setOf(2, 5)))
    }
    @Test fun editActiveSessionRejectsLostIdentitiesAndEmptyAttendance() {
        val s = demoTraining()
        assertTrue(runCatching { s.editSession(s.sessionName, s.sessionDate, "", "", "", s.roster.drop(1), setOf(2)) }.isFailure)
        assertTrue(runCatching { s.editSession(s.sessionName, s.sessionDate, "", "", "", s.roster, emptySet()) }.isFailure)
    }
    @Test fun sessionSetupPreservesRosterAndHistoricalNames() {
        val old = demoTraining()
        val roster = old.roster.map { if (it.id == 1) it.copy(name = "Alma Ny", bib = 99) else it } + Athlete(5, 50, "Sara")
        val next = old.startNewSession(" Kvällsslalom ", "2026-10-15", " Åre ", "Bana 2", "Slalom", roster, setOf(2, 5))
        assertEquals("Kvällsslalom", next.sessionName)
        assertEquals("Åre", next.location)
        assertEquals("Bana 2", next.course)
        assertEquals("Slalom", next.trainingType)
        assertEquals("2026-10-15", next.sessionDate)
        assertEquals(listOf(2, 5), next.athletes.map { it.id })
        assertEquals(5, next.roster.size)
        assertEquals("Alma", next.archivedSessions.single().athletes.first().name)
        assertEquals(12, next.archivedSessions.single().athletes.first().bib)
        val later = next.startNewSession(participants = setOf(1))
        assertEquals("Alma Ny", later.athletes.single().name)
        assertEquals(99, later.athletes.single().bib)
        assertEquals(5, later.roster.size)
    }
    @Test fun sessionSetupRejectsInvalidAttendanceAndDuplicateBibs() {
        val s = demoTraining()
        assertTrue(runCatching { s.startNewSession(participants = emptySet()) }.isFailure)
        assertTrue(runCatching { s.startNewSession(participants = setOf(999)) }.isFailure)
        assertTrue(runCatching { s.startNewSession(name = " ") }.isFailure)
        assertTrue(runCatching { s.startNewSession(date = "2026-02-30") }.isFailure)
        assertTrue(runCatching { s.startNewSession(roster = s.roster + Athlete(5, 12, "Sara")) }.isFailure)
    }
    @Test fun reportFiltersKeepAllRunsAndSessionIdentity() {
        val first = demoTraining().copy(sessionId = "first", sessionDate = "2026-10-13")
        // Simulator IDs and sequence numbers repeat between sessions, and must remain separate.
        val second = first.copy(sessionId = "second", sessionDate = "2026-10-14",
            attempts = first.attempts.take(2), archivedSessions = emptyList())
        val sessions = listOf(first, second)
        assertEquals(first.attempts.size + second.attempts.size, reportRuns(sessions).size)
        val skier = reportRuns(sessions, athleteId = 1)
        assertEquals(first.runs(1).size + second.runs(1).size, skier.size)
        assertEquals(listOf("first", "second"), skier.map { it.session.sessionId }.distinct())
        val oneSession = reportRuns(sessions, "second", 1)
        assertEquals(1, oneSession.size)
        assertEquals("seed-0", oneSession.single().attempt.id)
        assertEquals(1, oneSession.single().number)
        assertEquals(1, skier.count { !it.attempt.complete })
        assertTrue(reportRuns(sessions, "missing", 1).isEmpty())
    }
    @Test fun reportCsvIncludesSessionContextAndOnlySelectedSkierWithoutPrivateNotes() {
        val session = demoTraining().copy(sessionId = "report-session", sessionDate = "2026-10-13")
        val edited = session.correct("seed-0", 1, true, setOf(1), "Private coaching note")
        val output = reportCsv(reportRuns(listOf(edited), athleteId = 1))
        assertEquals(edited.runs(1).size + 1, output.split("\r\n").size)
        assertTrue(output.contains("\"report-session\";\"2026-10-13\""))
        assertTrue(output.contains("\"Alma\""))
        assertTrue(output.contains("\"DNF\""))
        assertFalse(output.contains("Elias"))
        assertFalse(output.contains("Private coaching note"))
        assertFalse(output.contains("Teknikfokus"))
    }
    @Test fun newSessionsPreserveArchivedEvidenceAndStartEmpty() {
        val original = demoTraining().copy(audit = listOf(AuditEntry("2026-10-15T10:00:00Z", AuditKind.LEGACY, "Rättelse")), pending = listOf("seed-0"))
        val next = original.startNewSession()
        assertNotEquals(original.sessionId, next.sessionId)
        assertTrue(next.attempts.isEmpty())
        assertTrue(next.pending.isEmpty())
        assertTrue(next.markers.isEmpty())
        assertTrue(next.audit.isEmpty())
        assertEquals(original.athletes, next.athletes)
        assertEquals(original.tags, next.tags)
        val archived = next.archivedSessions.single()
        assertEquals(original.attempts, archived.attempts)
        assertEquals(original.audit, archived.audit)
        assertEquals(original.markers, archived.markers)
        val later = next.ingest(SimulatorAdapter().next(1, 12)).startNewSession()
        assertEquals(listOf(next.sessionId, original.sessionId), later.archivedSessions.map { it.sessionId })
        assertTrue(later.archivedSessions.all { it.archivedSessions.isEmpty() })
    }
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
    @Test fun unknownBibRequiresReviewInsteadOfGuessing() {
        val old = demoTraining()
        val next = old.ingest(TimingObservation("unknown", 999, 18, 33000, true))
        assertEquals(old.attempts, next.attempts)
        assertEquals(1, next.reviewQueue().size)
    }
    @Test fun seededSimulatorIsDeterministic() {
        assertEquals(SimulatorAdapter().next(20, 12), SimulatorAdapter().next(20, 12))
    }
    @Test(expected = IllegalArgumentException::class) fun cannotInventFinishForIncompleteAttempt() {
        val s = demoTraining(); val run = s.attempts.first { it.durationMs == null }
        s.correct(run.id, run.athleteId, true, emptySet(), "")
    }
}
