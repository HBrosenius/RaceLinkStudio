package se.racelink.studio

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
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

@OptIn(ExperimentalLayoutApi::class)
@Composable internal fun ReportFilters(initial: ReportSelection, sessions: List<Training>, athletes: List<Athlete>, close: () -> Unit, apply: (ReportSelection) -> Unit) {
    var draft by rememberSaveable(stateSaver = ReportSelectionSaver) { mutableStateOf(initial) }
    var section by rememberSaveable { mutableStateOf("sessions") }
    var query by rememberSaveable { mutableStateOf("") }
    val result = runCatching { draft.rows(sessions) }
    val leave = draftClose(draft != initial, true, close)
    AppFullScreenDialog(leave) {
        Surface(Modifier.fillMaxSize().safeDrawingPadding().imePadding(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.set_filter_report_title), style = MaterialTheme.typography.headlineSmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = section == "sessions", onClick = { section = "sessions"; query = "" }, label = { Text(stringResource(R.string.set_sessions_and_dates)) })
                    FilterChip(selected = section == "athletes", onClick = { section = "athletes"; query = "" }, label = { Text(stringResource(R.string.set_athletes)) })
                    TextButton(onClick = { draft = ReportSelection(); query = "" }) { Text(stringResource(R.string.set_reset_filters)) }
                }
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (section == "sessions") {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(selected = draft.mode == "all", onClick = { draft = draft.copy(mode = "all") }, label = { Text(stringResource(R.string.set_all_sessions)) })
                            FilterChip(selected = draft.mode == "sessions", onClick = { draft = draft.copy(mode = "sessions") }, label = { Text(stringResource(R.string.set_choose_sessions)) })
                            FilterChip(selected = draft.mode == "dates", onClick = { draft = draft.copy(mode = "dates",
                                from = draft.from.ifBlank { sessions.minOf { it.sessionDate } }, through = draft.through.ifBlank { sessions.maxOf { it.sessionDate } }) }, label = { Text(stringResource(R.string.set_date_range)) })
                        }
                        if (draft.mode == "dates") {
                            DateInput(draft.from, { draft = draft.copy(from = it) }, stringResource(R.string.set_from_date))
                            DateInput(draft.through, { draft = draft.copy(through = it) }, stringResource(R.string.set_through_date))
                            Text(stringResource(R.string.set_dates_inclusive))
                        }
                        if (draft.mode == "sessions") {
                            OutlinedTextField(query, { query = it }, label = { Text(stringResource(R.string.set_search_sessions)) }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                            FlowRow {
                                TextButton(onClick = { draft = draft.copy(sessions = sessions.map { it.sessionId }.toSet()) }) { Text(stringResource(R.string.set_select_all_sessions)) }
                                TextButton(onClick = { draft = draft.copy(sessions = emptySet()) }) { Text(stringResource(R.string.set_deselect_all_sessions)) }
                            }
                            if (draft.sessions.isEmpty()) Text(stringResource(R.string.set_no_sessions_selected))
                            val matches = sessions.filter { query.isBlank() || "${it.sessionLabel()} ${it.location}".contains(query.trim(), true) }
                            if (matches.isEmpty()) Text(stringResource(R.string.set_no_session_matches))
                            matches.forEach { session ->
                                Card(onClick = { draft = draft.copy(sessions = if (session.sessionId in draft.sessions) draft.sessions - session.sessionId else draft.sessions + session.sessionId) }) {
                                    Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                        Checkbox(checked = session.sessionId in draft.sessions, onCheckedChange = null)
                                        Column(Modifier.weight(1f)) { Text(session.sessionLabel()); Text(stringResource(R.string.set_session_row, session.attempts.size, session.location), style = MaterialTheme.typography.bodySmall) }
                                    }
                                }
                            }
                        }
                    } else {
                        OutlinedTextField(query, { query = it }, label = { Text(stringResource(R.string.set_search_athletes)) }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                        FlowRow {
                            FilterChip(selected = draft.athletes == null, onClick = { draft = draft.copy(athletes = null) }, label = { Text(stringResource(R.string.set_all_athletes)) })
                            TextButton(onClick = { draft = draft.copy(athletes = emptySet()) }) { Text(stringResource(R.string.set_deselect_all_athletes)) }
                        }
                        if (draft.athletes?.isEmpty() == true) Text(stringResource(R.string.set_no_athletes_selected))
                        val matches = findSkiers(athletes, query)
                        if (matches.isEmpty()) Text(stringResource(R.string.set_no_athlete_matches))
                        matches.forEach { athlete ->
                            Card(onClick = {
                                val ids = draft.athletes
                                draft = draft.copy(athletes = if (ids == null) athletes.map { it.id }.toSet() - athlete.id else if (athlete.id in ids) ids - athlete.id else ids + athlete.id)
                            }) {
                                Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Checkbox(checked = draft.athletes == null || athlete.id in draft.athletes.orEmpty(), onCheckedChange = null)
                                    Text(stringResource(R.string.set_athlete_row, athlete.bib, athlete.name))
                                }
                            }
                        }
                    }
                }
                result.exceptionOrNull()?.let { Text(if (it is java.time.format.DateTimeParseException) stringResource(R.string.set_invalid_date) else it.message ?: stringResource(R.string.set_invalid_selection), color = MaterialTheme.colorScheme.error) }
                Text(result.getOrNull()?.let(::reportScope) ?: stringResource(R.string.set_fix_selection), style = MaterialTheme.typography.titleMedium)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = leave) { Text(stringResource(R.string.set_cancel)) }
                    Button(enabled = result.isSuccess, onClick = { apply(draft) }) { Text(stringResource(R.string.set_show_selection)) }
                }
            }
        }
    }
}
