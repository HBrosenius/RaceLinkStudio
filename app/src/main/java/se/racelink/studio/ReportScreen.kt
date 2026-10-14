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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

@Composable internal fun Reports(current: Training, close: () -> Unit, export: (String) -> Unit) {
    var mode by rememberSaveable { mutableStateOf("all") }
    var selectedSessions by rememberSaveable { mutableStateOf(arrayListOf<String>()) }
    var selectedAthletes by rememberSaveable { mutableStateOf(arrayListOf<Int>()) }
    var allAthletes by rememberSaveable { mutableStateOf(true) }
    var from by rememberSaveable { mutableStateOf("") }
    var through by rememberSaveable { mutableStateOf("") }
    var chooseSession by remember { mutableStateOf(false) }
    var includeTags by rememberSaveable { mutableStateOf(false) }
    var includeNotes by rememberSaveable { mutableStateOf(false) }
    // Archives are stored newest first; reports show oldest first and keep each run's session identity.
    val sessions = current.archivedSessions.reversed() + current
    val athletes = (listOf(current) + current.archivedSessions).flatMap { it.roster }.distinctBy { it.id }
    val athleteId = if (!allAthletes) selectedAthletes.singleOrNull() else null
    val filtered = runCatching {
        if (mode == "dates") require(from.isNotBlank() && through.isNotBlank()) { "Ange båda datumen (ÅÅÅÅ-MM-DD)" }
        filterReport(reportRuns(sessions), if (mode == "sessions") selectedSessions.toSet() else null,
            if (mode == "dates") from else null, if (mode == "dates") through else null,
            if (allAthletes) null else selectedAthletes.toSet())
    }
    val rows = filtered.getOrDefault(emptyList())
    val complete = rows.count { it.attempt.complete && it.attempt.durationMs != null }
    Dialog(onDismissRequest = close, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize().safeDrawingPadding(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Rapporter", Modifier.weight(1f), style = MaterialTheme.typography.headlineSmall)
                    TextButton(onClick = close) { Text("Till aktuellt pass") }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = mode == "all", onClick = { mode = "all" }, label = { Text("Alla pass") })
                    FilterChip(selected = mode == "sessions", onClick = { mode = "sessions"; chooseSession = true }, label = { Text("Välj pass (${selectedSessions.size})") })
                    FilterChip(selected = mode == "dates", onClick = {
                        mode = "dates"
                        if (from.isBlank()) from = sessions.minOf { it.sessionDate }
                        if (through.isBlank()) through = sessions.maxOf { it.sessionDate }
                    }, label = { Text("Datumintervall") })
                }
                if (mode == "dates") Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(from, { from = it }, label = { Text("Från (ÅÅÅÅ-MM-DD)") }, singleLine = true, modifier = Modifier.weight(1f), isError = filtered.isFailure)
                    OutlinedTextField(through, { through = it }, label = { Text("Till och med (ÅÅÅÅ-MM-DD)") }, singleLine = true, modifier = Modifier.weight(1f), isError = filtered.isFailure)
                }
                filtered.exceptionOrNull()?.let { Text(if (it is java.time.format.DateTimeParseException) "Ange giltiga datum som ÅÅÅÅ-MM-DD." else it.message ?: "Ogiltigt datumintervall", color = MaterialTheme.colorScheme.error) }
                if (mode == "sessions") Box {
                    OutlinedButton(onClick = { chooseSession = true }) { Text("${selectedSessions.size} valda pass · ändra urval") }
                    DropdownMenu(expanded = chooseSession, onDismissRequest = { chooseSession = false }) {
                        sessions.forEachIndexed { index, session ->
                            DropdownMenuItem(text = { Text("${session.sessionLabel()} · Pass ${index + 1}${if (session.sessionId == current.sessionId && session.closedAt == null) " · Pågående" else ""}") },
                                leadingIcon = { Checkbox(checked = session.sessionId in selectedSessions, onCheckedChange = null) },
                                onClick = { selectedSessions = ArrayList(if (session.sessionId in selectedSessions) selectedSessions - session.sessionId else selectedSessions + session.sessionId) })
                        }
                        DropdownMenuItem(text = { Text("Klart") }, onClick = { chooseSession = false })
                    }
                }
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = allAthletes, onClick = { allAthletes = true; selectedAthletes = arrayListOf() }, label = { Text("Alla åkare") })
                    athletes.forEach { athlete -> FilterChip(selected = !allAthletes && athlete.id in selectedAthletes,
                        onClick = {
                            selectedAthletes = ArrayList(if (allAthletes) listOf(athlete.id) else if (athlete.id in selectedAthletes) selectedAthletes - athlete.id else selectedAthletes + athlete.id)
                            allAthletes = false
                        }, label = { Text(athlete.name) }) }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("${rows.size} åk · $complete fullföljda · ${rows.size - complete} avbrutna", Modifier.weight(1f))
                    OutlinedButton(onClick = { export(reportCsv(rows)) }, enabled = rows.isNotEmpty()) { Text("Exportera vald rapport (CSV)") }
                    Spacer(Modifier.width(8.dp))
                    PdfExportButton(rows, includeTags, includeNotes)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = includeTags, onCheckedChange = { includeTags = it })
                    Text("Taggar i PDF")
                    Checkbox(checked = includeNotes, onCheckedChange = { includeNotes = it })
                    Text("Anteckningar i PDF")
                }
                Text("SIMULATOR · Påhittade namn och tider. CSV inkluderar pass och datum, utan taggar och anteckningar.", style = MaterialTheme.typography.bodySmall)
                if (rows.map { it.session.sessionId }.distinct().size > 1) Text("Åken visas per pass. Tider från olika banor och förhållanden jämförs inte automatiskt.", style = MaterialTheme.typography.bodySmall)
                LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (rows.isEmpty()) item { Text("Inga åk för valda pass/datum och åkare.") }
                    sessions.forEach { session ->
                        val sessionRows = rows.filter { it.session.sessionId == session.sessionId }
                        if (sessionRows.isNotEmpty()) {
                            item(key = "header-${session.sessionId}") {
                                Column {
                                    Text("${session.sessionLabel()} · Pass ${sessions.indexOf(session) + 1} · ${session.team}", style = MaterialTheme.typography.titleLarge)
                                    if (session.sessionDetails().isNotBlank()) Text(session.sessionDetails())
                                }
                            }
                            item(key = "summary-${session.sessionId}") {
                                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text("Sammanfattning per åkare", style = MaterialTheme.typography.titleMedium)
                                    sessionRows.groupBy { it.athlete.id }.values.forEach { skierRows ->
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
                            if (athleteId != null) item(key = "chart-${session.sessionId}") {
                                Card {
                                    Column(Modifier.fillMaxWidth().padding(16.dp)) {
                                        Text("${sessionRows.first().athlete.name} · Utveckling under passet", style = MaterialTheme.typography.titleMedium)
                                        Text("Tid i sekunder · lägre är snabbare", style = MaterialTheme.typography.bodySmall)
                                        TrendChart(session.runs(athleteId!!), session.markers, false, session.tags)
                                    }
                                }
                            }
                            items(sessionRows, key = { "${it.session.sessionId}/${it.attempt.id}" }) { row ->
                                val run = row.attempt
                                val skierRuns = session.runs(row.athlete.id)
                                val previous = skierRuns.previous(run)
                                Card(Modifier.fillMaxWidth()) {
                                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                        Text("${row.athlete.name} · Nummer ${row.athlete.bib} · Åk ${row.number}", style = MaterialTheme.typography.titleMedium)
                                        Text(if (run.complete) "${seconds(run.durationMs!!)} s" else "Avbrutet")
                                        if (run.complete && previous != null) Text("${difference(run.durationMs!! - previous.durationMs!!)} jämfört med åk ${skierRuns.indexOf(previous) + 1}")
                                        TagCategory.entries.forEach { category ->
                                            val tags = session.tagDescription(run, category)
                                            if (tags.isNotBlank()) Text("${category.label}: $tags", color = MaterialTheme.colorScheme.primary)
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
}
