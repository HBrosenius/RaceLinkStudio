package se.racelink.studio

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

@Composable internal fun TimingReviewScreen(s: Training, vm: TrainingViewModel, close: () -> Unit) {
    var history by rememberSaveable { mutableStateOf(false) }
    val rows = if (history) s.timingEvents.filter { it.decision != TimingDecision.WAITING && it.decision != TimingDecision.ACCEPTED }.reversed() else s.reviewQueue()
    Dialog(onDismissRequest = close, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize().safeDrawingPadding(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row {
                    Text("Resultatgranskning · ${s.reviewQueue().size} väntande", Modifier.weight(1f), style = MaterialTheme.typography.headlineSmall)
                    TextButton(onClick = close) { Text("Till aktuellt pass") }
                }
                Text("Ogranskade händelser ingår inte i åkarnas resultat eller rapporter. Ursprungliga uppgifter sparas även efter beslut.")
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = !history, onClick = { history = false }, label = { Text("Att granska") })
                    FilterChip(selected = history, onClick = { history = true }, label = { Text("Beslutshistorik") })
                    OutlinedButton(enabled = !vm.busy && s.closedAt == null, onClick = { vm.update { it.simulateReview(false) }; history = false }) { Text("Simulera okänt nummer") }
                    OutlinedButton(enabled = !vm.busy && s.closedAt == null, onClick = { vm.update { it.simulateReview(true) }; history = false }) { Text("Simulera osäker målgång") }
                }
                vm.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                if (vm.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (rows.isEmpty()) item { Text(if (history) "Inga granskningsbeslut ännu." else "Inga resultat väntar på granskning.") }
                    items(rows, key = { it.id }) { row -> ReviewCard(s, row, !vm.busy) { decision, athlete, duplicate ->
                        vm.update { it.resolveTiming(row.id, decision, athlete, duplicate) }
                    } }
                }
            }
        }
    }
}

@Composable private fun ReviewCard(s: Training, row: RecordedTiming, enabled: Boolean,
    resolve: (TimingDecision, Int?, String?) -> Unit) {
    var athleteId by rememberSaveable(row.id) { mutableStateOf<Int?>(null) }
    var duplicateId by rememberSaveable(row.id) { mutableStateOf<String?>(null) }
    var athleteMenu by remember { mutableStateOf(false) }
    var duplicateMenu by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf<TimingDecision?>(null) }
    val event = row.observation
    fun attemptLabel(id: String?): String {
        val run = s.attempts.find { it.id == id } ?: return "Välj befintligt åk"
        val skier = s.athletes.first { it.id == run.athleteId }
        return "${skier.name} · Åk ${s.runs(skier.id).indexOf(run) + 1} · ${run.durationMs?.let { seconds(it) + " s" } ?: "Avbrutet"}"
    }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(row.reason, style = MaterialTheme.typography.titleMedium)
            Text("Original: nummer ${event.bib} · ${if (event.complete) seconds(event.durationMs!!) + " s" else "Avbrutet"}")
            Text("Händelse ${event.id} · Ordningsnummer ${event.sequence}", style = MaterialTheme.typography.bodySmall)
            if (row.decision == TimingDecision.WAITING) {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box {
                        OutlinedButton(enabled = enabled, onClick = { athleteMenu = true }) { Text(s.athletes.find { it.id == athleteId }?.let { "${it.bib} · ${it.name}" } ?: "Välj åkare") }
                        DropdownMenu(expanded = athleteMenu, onDismissRequest = { athleteMenu = false }) {
                            s.athletes.forEach { skier -> DropdownMenuItem(text = { Text("${skier.bib} · ${skier.name}") }, onClick = { athleteId = skier.id; athleteMenu = false }) }
                        }
                    }
                    Button(enabled = enabled && athleteId != null, onClick = { confirm = TimingDecision.ASSIGNED }) { Text("Tilldela åkare") }
                }
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box {
                        OutlinedButton(enabled = enabled && s.attempts.isNotEmpty(), onClick = { duplicateMenu = true }) { Text(attemptLabel(duplicateId)) }
                        DropdownMenu(expanded = duplicateMenu, onDismissRequest = { duplicateMenu = false }) {
                            s.attempts.sortedByDescending { it.sequence }.forEach { run -> DropdownMenuItem(text = { Text(attemptLabel(run.id)) }, onClick = { duplicateId = run.id; duplicateMenu = false }) }
                        }
                    }
                    OutlinedButton(enabled = enabled && duplicateId != null, onClick = { confirm = TimingDecision.DUPLICATE }) { Text("Markera dubblett") }
                    TextButton(enabled = enabled, onClick = { confirm = TimingDecision.REJECTED }) { Text("Avvisa händelse") }
                }
            } else {
                Text(when (row.decision) {
                    TimingDecision.ASSIGNED -> "Tilldelad: ${s.athletes.find { it.id == row.athleteId }?.name} · ${attemptLabel(row.attemptId)}"
                    TimingDecision.DUPLICATE -> "Dubblett av: ${attemptLabel(row.attemptId)}"
                    TimingDecision.REJECTED -> "Avvisad · inget åk skapat"
                    else -> "Automatiskt matchad"
                })
                Text("Beslut: ${row.decidedAt.orEmpty()}", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
    confirm?.let { decision ->
        val label = when (decision) {
            TimingDecision.ASSIGNED -> "Tilldela ${s.athletes.find { it.id == athleteId }?.name}?"
            TimingDecision.DUPLICATE -> "Markera som dubblett?"
            else -> "Avvisa händelsen?"
        }
        AlertDialog(onDismissRequest = { confirm = null }, title = { Text(label) }, text = {
            Text(if (decision == TimingDecision.ASSIGNED) "Ett åk skapas med den ursprungliga tiden. Händelsen och beslutet sparas i historiken."
                else if (decision == TimingDecision.DUPLICATE) "Händelsen kopplas till ${attemptLabel(duplicateId)}. Inget nytt åk skapas."
                else "Inget åk skapas. Ursprungshändelsen och beslutet sparas i historiken.")
        }, confirmButton = { TextButton(enabled = enabled, onClick = { resolve(decision, athleteId, duplicateId); confirm = null }) { Text("Bekräfta") } },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("Avbryt") } })
    }
}
