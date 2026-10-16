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
            closeLabel = "Tillbaka till passet")
        return
    }
    val back = { if (reviewHistory) reviewHistory = false else if (athleteId != null) athleteId = null else if (sessionId != null) sessionId = null else close() }
    BackHandler(enabled = embedded && (sessionId != null || reviewHistory)) { back() }
    ScreenContainer(embedded, back) {
        Surface(Modifier.fillMaxSize().safeDrawingPadding(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(if (reports) "Rapporter" else "Träningspass", Modifier.weight(1f), style = MaterialTheme.typography.headlineSmall)
                    if (session != null) TextButton(onClick = {
                        back()
                    }) { Text("Tillbaka") }
                    TextButton(onClick = close) { Text("Till aktuellt pass") }
                }
                if (session == null) {
                    OutlinedTextField(sessionQuery, { sessionQuery = it }, label = { Text("Sök pass, datum eller plats") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        if (current.archivedSessions.isEmpty()) Text("Inga tidigare pass ännu. Välj Passåtgärder → Starta träningspass när träningen är klar.")
                        val matches = sessions.filter { "${it.sessionLabel()} ${it.sessionDetails()}".contains(sessionQuery.trim(), ignoreCase = true) }
                        if (matches.isEmpty()) Text("Inga pass matchar sökningen.")
                        matches.forEach { item ->
                            Card(onClick = { sessionId = item.sessionId; athleteId = null }) {
                                Column(Modifier.fillMaxWidth().padding(16.dp)) {
                                    Text("${item.sessionLabel()} · ${if (item.sessionId == current.sessionId && item.closedAt == null) "Aktuellt pass" else "Sparat pass"}", style = MaterialTheme.typography.titleMedium)
                                    if (item.sessionDetails().isNotBlank()) Text(item.sessionDetails())
                                    Text("${item.team} · ${item.attempts.size} åk · ${item.attempts.eligible().size} fullföljda")
                                    Text("${item.athletes.size} åkare · ${item.audit.size} sparade ändringar", style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                    }
                } else {
                    Text("${session.sessionLabel()} · ${session.team} · ${if (session.sessionId == current.sessionId && session.closedAt == null) "Pågående" else "Avslutat"}")
                    Text("SIMULATOR · Påhittade namn och tider", color = MaterialTheme.colorScheme.primary)
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { scopedReports = true }) { Text("Öppna i Rapporter") }
                        OutlinedButton(onClick = { exportVm.begin(reportRuns(listOf(session))); exporting = true }) { Text("Exportera passets CSV") }
                        if (athlete != null) OutlinedButton(onClick = {
                            exportVm.begin(reportRuns(listOf(session), athleteId = athlete.id)); exporting = true
                        }) { Text("Exportera ${athlete.name}s CSV") }
                    }
                    exportVm.message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                    if (session.timingEvents.isNotEmpty()) TextButton(onClick = { reviewHistory = !reviewHistory }) {
                        Text(if (reviewHistory) "Visa åkrapport" else "Visa ursprungliga händelser och beslut")
                    }
                    if (reviewHistory) {
                        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            session.timingEvents.forEach { entry -> Card(Modifier.fillMaxWidth()) {
                                Column(Modifier.padding(16.dp)) {
                                    Text(entry.reason, style = MaterialTheme.typography.titleMedium)
                                    val event = entry.observation
                                    Text("${event.id} · Nummer ${event.bib} · ${event.durationMs?.let { seconds(it) + " s" } ?: "DNF"}")
                                    Text(when (entry.decision) {
                                        TimingDecision.ACCEPTED -> "Automatiskt matchad"
                                        TimingDecision.ASSIGNED -> "Tilldelad ${session.athletes.find { it.id == entry.athleteId }?.name}"
                                        TimingDecision.DUPLICATE -> "Dubblett av ${entry.attemptId}"
                                        TimingDecision.REJECTED -> "Avvisad"
                                        TimingDecision.WAITING -> "Väntar på granskning"
                                    })
                                    entry.decidedAt?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                                }
                            } }
                        }
                    } else if (session.race != null) {
                        RaceScreen(session, vm, Modifier.weight(1f).fillMaxWidth(), archive = true)
                    } else if (athlete != null) {
                        OutlinedButton(onClick = { pickSkier = true }) { Text("Visar ${athlete.bib} · ${athlete.name} · byt åkare") }
                        val runs = session.runs(athlete.id)
                        RunDashboard(session, athlete, runs, runs.lastOrNull(), runs.eligible(), improvement,
                            { improvement = it }, {}, Modifier.weight(1f).fillMaxWidth(), readOnly = true)
                    } else {
                        Text("Rapport per åkare", style = MaterialTheme.typography.titleLarge)
                        OutlinedTextField(skierQuery, { skierQuery = it }, label = { Text("Sök namn eller nummer") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            val matches = findSkiers(session.athletes, skierQuery)
                            if (matches.isEmpty()) Text("Inga åkare matchar sökningen.")
                            matches.forEach { item ->
                                val runs = session.runs(item.id)
                                val valid = runs.eligible()
                                Card(onClick = { athleteId = item.id }) {
                                    Column(Modifier.fillMaxWidth().padding(16.dp)) {
                                        Text("${item.bib} · ${item.name}", style = MaterialTheme.typography.titleMedium)
                                        Text("${valid.size} fullföljda · ${runs.size - valid.size} DNF")
                                        if (valid.isEmpty()) Text("Inga fullföljda åk") else {
                                            Text("Snabbast ${seconds(valid.minOf { it.durationMs!! })} s · Senast ${seconds(valid.last().durationMs!!)} s")
                                            if (valid.size > 1) Text("${difference(valid.last().durationMs!! - valid.first().durationMs!!)} jämfört med första fullföljda åket")
                                        }
                                        Text("Visa alla åk och utveckling", color = MaterialTheme.colorScheme.primary)
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
