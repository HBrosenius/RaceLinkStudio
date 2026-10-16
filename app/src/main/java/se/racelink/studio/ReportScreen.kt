package se.racelink.studio

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
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

@OptIn(ExperimentalLayoutApi::class)
@Composable internal fun Reports(current: Training, close: () -> Unit, embedded: Boolean = false,
    initialSelection: ReportSelection = ReportSelection(), closeLabel: String = stringResource(R.string.set_close_to_current)) {
    var selection by rememberSaveable(stateSaver = ReportSelectionSaver) { mutableStateOf(initialSelection) }
    var filters by rememberSaveable { mutableStateOf(false) }
    var exporting by rememberSaveable { mutableStateOf(false) }
    var showGraphs by rememberSaveable { mutableStateOf(true) }
    val exportVm: ReportExportViewModel = viewModel(key = "report-export")
    val sessions = current.archivedSessions.reversed() + current
    val athletes = (listOf(current) + current.archivedSessions).flatMap { it.roster }.distinctBy { it.id }
    val filtered = runCatching { selection.rows(sessions) }
    val rows = filtered.getOrDefault(emptyList())
    val complete = rows.count { it.attempt.complete && it.attempt.durationMs != null }
    ScreenContainer(embedded, close) {
        Surface(Modifier.fillMaxSize().safeDrawingPadding(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.set_reports_title), Modifier.weight(1f), style = MaterialTheme.typography.headlineSmall)
                    TextButton(onClick = close) { Text(closeLabel) }
                }
                Text(reportScope(rows), style = MaterialTheme.typography.titleMedium)
                Text(selection.description(), style = MaterialTheme.typography.bodySmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { filters = true }) { Text(stringResource(R.string.set_filter)) }
                    OutlinedButton(enabled = rows.isNotEmpty(), onClick = { exportVm.begin(rows); exporting = true }) { Text(stringResource(R.string.set_export)) }
                    FilterChip(selected = showGraphs, onClick = { showGraphs = !showGraphs }, label = { Text(stringResource(R.string.set_show_charts)) })
                    if (selection != ReportSelection()) TextButton(onClick = { selection = ReportSelection() }) { Text(stringResource(R.string.set_reset_filters)) }
                }
                exportVm.message?.let { message -> Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(message, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = exportVm::clearMessage) { Text(stringResource(R.string.set_close_message)) }
                } }
                filtered.exceptionOrNull()?.let { Text(it.message ?: stringResource(R.string.set_invalid_selection), color = MaterialTheme.colorScheme.error) }
                Text(stringResource(R.string.set_report_summary, complete, rows.size - complete), style = MaterialTheme.typography.bodySmall)
                if (rows.map { it.session.sessionId }.distinct().size > 1) Text(stringResource(R.string.set_times_per_session), style = MaterialTheme.typography.bodySmall)
                LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (rows.isEmpty()) item {
                        Column {
                            Text(stringResource(R.string.set_no_report_runs))
                            TextButton(onClick = { filters = true }) { Text(stringResource(R.string.set_change_selection)) }
                            TextButton(onClick = { selection = ReportSelection() }) { Text(stringResource(R.string.set_show_all)) }
                        }
                    }
                    sessions.forEach { session ->
                        val sessionRows = rows.filter { it.session.sessionId == session.sessionId }
                        if (sessionRows.isNotEmpty()) {
                            item(key = "header-${session.sessionId}") {
                                Column {
                                    Text(stringResource(R.string.set_session_header, session.sessionLabel(), sessions.indexOf(session) + 1, session.team), style = MaterialTheme.typography.titleLarge)
                                    if (session.sessionDetails().isNotBlank()) Text(session.sessionDetails())
                                }
                            }
                            item(key = "summary-${session.sessionId}") {
                                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text(if (session.race == null) stringResource(R.string.set_summary_per_athlete) else stringResource(R.string.set_results_per_class, session.race.format.label), style = MaterialTheme.typography.titleMedium)
                                    if (session.race != null) session.raceStandings().filter { entry -> sessionRows.any { it.athlete.id == entry.athlete.id } }.forEach { entry ->
                                        val placeText = entry.place?.let { stringResource(R.string.set_placement, it) } ?: stringResource(R.string.set_no_placement)
                                        Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(12.dp)) {
                                            Text(stringResource(R.string.set_standings_line, entry.className, placeText, entry.athlete.name))
                                            Text(stringResource(R.string.set_run_pair, raceTime(entry.first), raceTime(entry.second)))
                                            Text("${session.race.format.scoreLabel}: ${entry.scoreMs?.let { raceSeconds(it) + " s" } ?: "–"} · ${entry.status}")
                                        } }
                                    } else sessionRows.groupBy { it.athlete.id }.values.forEach { skierRows ->
                                        val summary = summarizeRuns(skierRows)
                                        Card(Modifier.fillMaxWidth()) {
                                            Column(Modifier.padding(12.dp)) {
                                                Text(summary.name, style = MaterialTheme.typography.titleMedium)
                                                Text(summary.description())
                                            }
                                        }
                                    }
                                }
                            }
                            if (showGraphs && session.race == null) sessionRows.groupBy { it.athlete.id }.forEach { (skierId, skierRows) -> item(key = "chart-${session.sessionId}-$skierId") {
                                Card {
                                    Column(Modifier.fillMaxWidth().padding(16.dp)) {
                                        Text(stringResource(R.string.set_development_header, skierRows.first().athlete.name), style = MaterialTheme.typography.titleMedium)
                                        Text(stringResource(R.string.set_chart_axis_note), style = MaterialTheme.typography.bodySmall)
                                        TrendChart(session.runs(skierId), session.markers, false, session.tags, changeLabels = session.runs(skierId).associate { it.id to session.tagDescription(it, TagCategory.CHANGE) }, runSequences = session.runs(skierId).associate { it.id to session.originalSequence(it) })
                                    }
                                }
                            } }
                            items(if (session.race == null) sessionRows else emptyList(), key = { "${it.session.sessionId}/${it.attempt.id}" }) { row ->
                                val run = row.attempt
                                val skierRuns = session.runs(row.athlete.id)
                                val previous = skierRuns.previous(run)
                                Card(Modifier.fillMaxWidth()) {
                                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                        Text(stringResource(R.string.set_run_header, row.athlete.name, row.athlete.bib, row.number), style = MaterialTheme.typography.titleMedium)
                                        Text(if (run.complete) "${seconds(run.durationMs!!)} s" else "DNF")
                                        if (run.complete && previous != null) Text(stringResource(R.string.set_run_difference, difference(run.durationMs!! - previous.durationMs!!), skierRuns.indexOf(previous) + 1))
                                        TagCategory.entries.forEach { category ->
                                            val tags = session.tagDescription(run, category)
                                            if (tags.isNotBlank()) Text(stringResource(R.string.set_category_tags, category.label, tags), color = MaterialTheme.colorScheme.primary)
                                        }
                                        if (run.note.isNotBlank()) Text(run.note)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    if (filters) ReportFilters(selection, sessions, athletes, { filters = false }) { selection = it; filters = false }
    if (exporting) ReportExportDialog(exportVm, { exporting = false })

}
