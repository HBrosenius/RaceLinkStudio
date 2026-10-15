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
    AppFullScreenDialog(close) {
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
                    items(rows, key = { it.id }) { row -> ReviewCard(s, row, !vm.busy, vm.error) { decision, athlete, duplicate ->
                        vm.update { it.resolveTiming(row.id, decision, athlete, duplicate) }
                    } }
                }
            }
        }
    }
}

@Composable private fun ReviewCard(s: Training, row: RecordedTiming, enabled: Boolean, error: String?,
    resolve: (TimingDecision, Int?, String?) -> Unit) {
    var athleteId by rememberSaveable(row.id) { mutableStateOf<Int?>(null) }
    var duplicateId by rememberSaveable(row.id) { mutableStateOf<String?>(null) }
    var evidence by rememberSaveable(row.id) { mutableStateOf(false) }
    var duplicateQuery by rememberSaveable(row.id) { mutableStateOf("") }
    var athleteMenu by remember { mutableStateOf(false) }
    var duplicateMenu by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf<TimingDecision?>(null) }
    val event = row.observation
    fun attemptLabel(id: String?): String {
        val run = s.attempts.find { it.id == id } ?: return "Välj befintligt åk"
        val skier = s.athletes.first { it.id == run.athleteId }
        return "${skier.bib} · ${skier.name} · Åk ${s.runs(skier.id).indexOf(run) + 1} · ${run.durationMs?.let { seconds(it) + " s" } ?: "DNF"}"
    }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(row.reason, style = MaterialTheme.typography.titleMedium)
            Text("Original: nummer ${event.bib} · ${if (event.complete) seconds(event.durationMs!!) + " s" else "DNF"}")
            TextButton(onClick = { evidence = !evidence }) { Text(if (evidence) "Dölj originaluppgifter" else "Visa originaluppgifter") }
            if (evidence) Text("Händelse ${event.id} · Ordningsnummer ${event.sequence}", style = MaterialTheme.typography.bodySmall)
            if (row.decision == TimingDecision.WAITING) {
                Text(if (row.reason.startsWith("Motstridigt")) "Kontrollera om resultatet redan finns. Välj ett befintligt åk om det är en dubblett."
                    else "Kontrollera vem som gjorde åket och tilldela rätt åkare. Avvisa om händelsen inte ska bli ett åk.",
                    style = MaterialTheme.typography.bodyMedium)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box {
                        OutlinedButton(enabled = enabled, onClick = { athleteMenu = true }) { Text(s.athletes.find { it.id == athleteId }?.let { "${it.bib} · ${it.name}" } ?: "Välj åkare") }

                    }
                    Button(enabled = enabled && athleteId != null, onClick = { confirm = TimingDecision.ASSIGNED }) { Text("Tilldela åkare") }
                }
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box {
                        OutlinedButton(enabled = enabled && s.attempts.isNotEmpty(), onClick = { duplicateMenu = true }) { Text(attemptLabel(duplicateId)) }

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
    if (athleteMenu) SkierPicker(s.athletes, athleteId ?: -1, { athleteMenu = false }) { athleteId = it }
    if (duplicateMenu) AlertDialog(onDismissRequest = { duplicateMenu = false }, title = { Text("Välj befintligt åk") }, text = {
        Column {
            OutlinedTextField(duplicateQuery, { duplicateQuery = it }, label = { Text("Såk namn, nummer eller åk") }, singleLine = true)
            val matches = s.attempts.sortedByDescending { it.sequence }.filter { attemptLabel(it.id).contains(duplicateQuery.trim(), ignoreCase = true) }
            LazyColumn(Modifier.heightIn(max = 360.dp)) {
                if (matches.isEmpty()) item { Text("Inga åk matchar såkningen.") }
                items(matches, key = { it.id }) { run -> TextButton(onClick = { duplicateId = run.id; duplicateMenu = false }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(attemptLabel(run.id)) } }
            }
        }
    }, confirmButton = { TextButton(onClick = { duplicateMenu = false }) { Text("Stäng") } })
    LaunchedEffect(row.decision) { if (row.decision != TimingDecision.WAITING) confirm = null }
    confirm?.let { decision ->
        val label = when (decision) {
            TimingDecision.ASSIGNED -> "Tilldela ${s.athletes.find { it.id == athleteId }?.name}?"
            TimingDecision.DUPLICATE -> "Dubblett av ${attemptLabel(duplicateId)}?"
            else -> "Avvisa händelsen?"
        }
        AlertDialog(onDismissRequest = { confirm = null }, title = { Text(label) }, text = {
            Column { SaveFeedback(enabled, error)
            Text(if (decision == TimingDecision.ASSIGNED) "Ett åk skapas med den ursprungliga tiden. Händelsen och beslutet sparas i historiken."
                else if (decision == TimingDecision.DUPLICATE) "Händelsen kopplas till ${attemptLabel(duplicateId)}. Inget nytt åk skapas."
                else "Inget åk skapas. Ursprungshändelsen och beslutet sparas i historiken.") }
        }, confirmButton = { TextButton(enabled = enabled, onClick = { resolve(decision, athleteId, duplicateId) }) { Text("Bekräfta") } },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("Avbryt") } })
    }
}
