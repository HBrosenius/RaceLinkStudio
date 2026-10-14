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
    var sessionId by rememberSaveable { mutableStateOf<String?>(null) }
    var athleteId by rememberSaveable { mutableStateOf<Int?>(null) }
    var chooseSession by remember { mutableStateOf(false) }
    // Archives are stored newest first; reports show oldest first and keep each run's session identity.
    val sessions = current.archivedSessions.reversed() + current
    val athletes = (listOf(current) + current.archivedSessions).flatMap { it.roster }.distinctBy { it.id }
    val rows = reportRuns(sessions, sessionId, athleteId)
    val complete = rows.count { it.attempt.complete && it.attempt.durationMs != null }
    val sessionLabel = sessions.find { it.sessionId == sessionId }?.let {
        "${it.sessionLabel()} · Pass ${sessions.indexOf(it) + 1}${if (it.sessionId == current.sessionId) " · Pågående" else ""}"
    } ?: "Alla pass · Alla åk"
    Dialog(onDismissRequest = close, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize().safeDrawingPadding(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Rapporter", Modifier.weight(1f), style = MaterialTheme.typography.headlineSmall)
                    TextButton(onClick = close) { Text("Till aktuellt pass") }
                }
                Box {
                    OutlinedButton(onClick = { chooseSession = true }) { Text("Träningspass: $sessionLabel") }
                    DropdownMenu(expanded = chooseSession, onDismissRequest = { chooseSession = false }) {
                        DropdownMenuItem(text = { Text("Alla pass · Alla åk") }, onClick = { sessionId = null; chooseSession = false })
                        sessions.forEachIndexed { index, session ->
                            DropdownMenuItem(text = { Text("${session.sessionLabel()} · Pass ${index + 1}${if (session.sessionId == current.sessionId) " · Pågående" else ""}") },
                                onClick = { sessionId = session.sessionId; chooseSession = false })
                        }
                    }
                }
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = athleteId == null, onClick = { athleteId = null }, label = { Text("Alla åkare") })
                    athletes.forEach { athlete -> FilterChip(selected = athleteId == athlete.id,
                        onClick = { athleteId = athlete.id }, label = { Text(athlete.name) }) }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("${rows.size} åk · $complete fullföljda · ${rows.size - complete} avbrutna", Modifier.weight(1f))
                    OutlinedButton(onClick = { export(reportCsv(rows)) }, enabled = rows.isNotEmpty()) { Text("Exportera vald rapport (CSV)") }
                }
                Text("SIMULATOR · Påhittade namn och tider. CSV inkluderar pass och datum, utan taggar och anteckningar.", style = MaterialTheme.typography.bodySmall)
                if (sessionId == null) Text("Åken visas per pass. Tider från olika banor och förhållanden jämförs inte automatiskt.", style = MaterialTheme.typography.bodySmall)
                LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (rows.isEmpty()) item { Text("Inga åk för valt pass och vald åkare.") }
                    sessions.filter { sessionId == null || it.sessionId == sessionId }.forEach { session ->
                        val sessionRows = rows.filter { it.session.sessionId == session.sessionId }
                        if (sessionRows.isNotEmpty()) {
                            item(key = "header-${session.sessionId}") {
                                Column {
                                    Text("${session.sessionLabel()} · Pass ${sessions.indexOf(session) + 1} · ${session.team}", style = MaterialTheme.typography.titleLarge)
                                    if (session.sessionDetails().isNotBlank()) Text(session.sessionDetails())
                                }
                            }
                            if (athleteId != null) item(key = "chart-${session.sessionId}") {
                                Card {
                                    Column(Modifier.fillMaxWidth().padding(16.dp)) {
                                        Text("${sessionRows.first().athlete.name} · Utveckling under passet", style = MaterialTheme.typography.titleMedium)
                                        Text("Tid i sekunder · lägre är snabbare", style = MaterialTheme.typography.bodySmall)
                                        TrendChart(session.runs(athleteId!!), session.markers, false)
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
                                        val tags = session.tags.filter { it.id in run.tags }.joinToString(" · ") { it.name }
                                        if (tags.isNotBlank()) Text(tags, color = MaterialTheme.colorScheme.primary)
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
