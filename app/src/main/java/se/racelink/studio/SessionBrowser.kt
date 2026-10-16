package se.racelink.studio

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

@Composable internal fun SessionBrowser(current: Training, reports: Boolean, close: () -> Unit, embedded: Boolean = false, vm: TrainingViewModel = viewModel()) {
    if (reports) {
        Reports(current, close, embedded)
        return
    }
    var scopedReports by rememberSaveable { mutableStateOf(false) }
    var sessionQuery by rememberSaveable { mutableStateOf("") }
    var skierQuery by rememberSaveable { mutableStateOf("") }
    var pickSkier by rememberSaveable { mutableStateOf(false) }
    val exportVm: ReportExportViewModel = viewModel(key = "session-export")
    var exporting by rememberSaveable { mutableStateOf(false) }
    var sessionId by rememberSaveable { mutableStateOf<String?>(if (reports) current.sessionId else null) }
    var athleteId by rememberSaveable { mutableStateOf<Int?>(null) }
    var improvement by rememberSaveable { mutableStateOf(false) }
    var reviewHistory by rememberSaveable(sessionId) { mutableStateOf(false) }
    val sessions = listOf(current) + current.archivedSessions
    val session = sessions.find { it.sessionId == sessionId }
    val athlete = session?.athletes?.find { it.id == athleteId }
    if (scopedReports && session != null) {
        BackHandler { scopedReports = false }
        Reports(current, { scopedReports = false }, embedded,
            initialSelection = ReportSelection("sessions", setOf(session.sessionId), athleteId?.let { setOf(it) }),
            closeLabel = stringResource(R.string.dash_back_to_session))
        return
    }
    val back = { if (reviewHistory) reviewHistory = false else if (athleteId != null) athleteId = null else if (sessionId != null) sessionId = null else close() }
    BackHandler(enabled = embedded && (sessionId != null || reviewHistory)) { back() }
    ScreenContainer(embedded, back) {
        Surface(Modifier.fillMaxSize().safeDrawingPadding(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(if (reports) stringResource(R.string.dash_reports) else stringResource(R.string.dash_training_sessions), Modifier.weight(1f), style = MaterialTheme.typography.headlineSmall)
                    if (session != null) TextButton(onClick = {
                        back()
                    }) { Text(stringResource(R.string.dash_back)) }
                    TextButton(onClick = close) { Text(stringResource(R.string.dash_to_current_session)) }
                }
                if (session == null) {
                    OutlinedTextField(sessionQuery, { sessionQuery = it }, label = { Text(stringResource(R.string.dash_search_session_date_place)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        if (current.archivedSessions.isEmpty()) Text(stringResource(R.string.dash_no_previous_sessions))
                        val matches = sessions.filter { "${it.sessionLabel()} ${it.sessionDetails()}".contains(sessionQuery.trim(), ignoreCase = true) }
                        if (matches.isEmpty()) Text(stringResource(R.string.dash_no_sessions_match))
                        matches.forEach { item ->
                            Card(onClick = { sessionId = item.sessionId; athleteId = null }) {
                                Column(Modifier.fillMaxWidth().padding(16.dp)) {
                                    Text("${item.sessionLabel()} · ${if (item.sessionId == current.sessionId && item.closedAt == null) stringResource(R.string.dash_current_session) else stringResource(R.string.dash_saved_session)}", style = MaterialTheme.typography.titleMedium)
                                    if (item.sessionDetails().isNotBlank()) Text(item.sessionDetails())
                                    Text(stringResource(R.string.dash_session_summary_line, item.team, item.attempts.size, item.attempts.eligible().size))
                                    Text(stringResource(R.string.dash_athletes_audit, item.athletes.size, item.audit.size), style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                    }
                } else {
                    Text("${session.sessionLabel()} · ${session.team} · ${if (session.sessionId == current.sessionId && session.closedAt == null) stringResource(R.string.dash_ongoing_session) else stringResource(R.string.dash_closed_session)}")
                    Text(stringResource(R.string.dash_simulator_made_up), color = MaterialTheme.colorScheme.primary)
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { scopedReports = true }) { Text(stringResource(R.string.dash_open_in_reports)) }
                        OutlinedButton(onClick = { exportVm.begin(reportRuns(listOf(session))); exporting = true }) { Text(stringResource(R.string.dash_export_session_csv)) }
                        if (athlete != null) OutlinedButton(onClick = {
                            exportVm.begin(reportRuns(listOf(session), athleteId = athlete.id)); exporting = true
                        }) { Text(stringResource(R.string.dash_export_athlete_csv, athlete.name)) }
                    }
                    exportVm.message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                    if (session.timingEvents.isNotEmpty()) TextButton(onClick = { reviewHistory = !reviewHistory }) {
                        Text(if (reviewHistory) stringResource(R.string.dash_show_run_report) else stringResource(R.string.dash_show_original_events))
                    }
                    if (reviewHistory) {
                        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            session.timingEvents.forEach { entry -> Card(Modifier.fillMaxWidth()) {
                                Column(Modifier.padding(16.dp)) {
                                    Text(entry.reason, style = MaterialTheme.typography.titleMedium)
                                    val event = entry.observation
                                    Text("${event.id} · ${stringResource(R.string.dash_number)} ${event.bib} · ${event.durationMs?.let { seconds(it) + " s" } ?: "DNF"}")
                                    Text(when (entry.decision) {
                                        TimingDecision.ACCEPTED -> stringResource(R.string.dash_timing_accepted)
                                        TimingDecision.ASSIGNED -> stringResource(R.string.dash_timing_assigned, session.athletes.find { it.id == entry.athleteId }?.name.orEmpty())
                                        TimingDecision.DUPLICATE -> stringResource(R.string.dash_timing_duplicate, entry.attemptId.orEmpty())
                                        TimingDecision.REJECTED -> stringResource(R.string.dash_timing_rejected)
                                        TimingDecision.WAITING -> stringResource(R.string.dash_timing_waiting)
                                    })
                                    entry.decidedAt?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                                }
                            } }
                        }
                    } else if (session.race != null) {
                        RaceScreen(session, vm, Modifier.weight(1f).fillMaxWidth(), archive = true)
                    } else if (athlete != null) {
                        OutlinedButton(onClick = { pickSkier = true }) { Text(stringResource(R.string.dash_showing_athlete_prefix, athlete.bib, athlete.name)) }
                        val runs = session.runs(athlete.id)
                        RunDashboard(session, athlete, runs, runs.lastOrNull(), runs.eligible(), improvement,
                            { improvement = it }, {}, Modifier.weight(1f).fillMaxWidth(), readOnly = true)
                    } else {
                        Text(stringResource(R.string.dash_report_per_athlete), style = MaterialTheme.typography.titleLarge)
                        OutlinedTextField(skierQuery, { skierQuery = it }, label = { Text(stringResource(R.string.dash_search_name_or_number)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            val matches = findSkiers(session.athletes, skierQuery)
                            if (matches.isEmpty()) Text(stringResource(R.string.dash_no_athletes_match))
                            matches.forEach { item ->
                                val runs = session.runs(item.id)
                                val valid = runs.eligible()
                                Card(onClick = { athleteId = item.id }) {
                                    Column(Modifier.fillMaxWidth().padding(16.dp)) {
                                        Text("${item.bib} · ${item.name}", style = MaterialTheme.typography.titleMedium)
                                        Text(stringResource(R.string.dash_completed_dnf_count, valid.size, runs.size - valid.size))
                                        if (valid.isEmpty()) Text(stringResource(R.string.dash_no_completed_runs)) else {
                                            Text(stringResource(R.string.dash_fastest_latest, seconds(valid.minOf { it.durationMs!! }), seconds(valid.last().durationMs!!)))
                                            if (valid.size > 1) Text(stringResource(R.string.dash_compared_to_first, difference(valid.last().durationMs!! - valid.first().durationMs!!)))
                                        }
                                        Text(stringResource(R.string.dash_show_all_runs), color = MaterialTheme.colorScheme.primary)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    if (exporting) ReportExportDialog(exportVm, { exporting = false }, initialFormat = "CSV")
    if (pickSkier && session != null) SkierPicker(session.athletes, athleteId ?: -1, { pickSkier = false }) { athleteId = it }

}
