package se.racelink.studio

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel

@OptIn(ExperimentalLayoutApi::class)
@Composable internal fun RaceScreen(s: Training, vm: TrainingViewModel, modifier: Modifier = Modifier,
    archive: Boolean = false, simulator: () -> Unit = {}, editRun: (String) -> Unit = {}, editSetup: () -> Unit = {}, highlightedRunId: String? = null) {
    val r = requireNotNull(s.race)
    val editable = !archive && s.closedAt == null && !vm.busy && !vm.backupBusy
    var selectedClass by rememberSaveable(s.sessionId) { mutableStateOf<String?>(null) }
    var assignmentsOpen by rememberSaveable(s.sessionId) { mutableStateOf(false) }
    var exporting by rememberSaveable(s.sessionId) { mutableStateOf(false) }
    var choosingNext by rememberSaveable { mutableStateOf(false) }
    val listState = rememberLazyListState()
    LaunchedEffect(highlightedRunId) {
        if (highlightedRunId != null) {
            selectedClass = null; assignmentsOpen = true
            withFrameNanos { }
            val index = s.attempts.sortedByDescending { it.sequence }.indexOfFirst { it.id == highlightedRunId }
            if (index >= 0) listState.animateScrollToItem(index + 1)
        }
    }
    val export: ReportExportViewModel = viewModel(key = "race-export-${s.sessionId}")
    val standings = s.raceStandings().filter { selectedClass == null || selectedClass == it.className }
    val unassigned = s.attempts.filter { it.id !in r.roundByAttempt && it.id !in r.unusedAttemptIds }
    LazyColumn(modifier.fillMaxSize(), state = listState, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Text(r.format.label, style = MaterialTheme.typography.headlineSmall)
            Text(if (s.closedAt == null && !archive) "Preliminära resultat · åk ${r.activeRound} aktivt" else "Avslutad tävling · skrivskyddad")
            Text(if (r.format == RaceFormat.TOTAL) "Lägsta totaltid från två fullföljda åk vinner inom varje klass." else "Minsta absoluta tidsskillnaden mellan två fullföljda åk vinner inom varje klass.")
            Text("DNF/saknat åk ger ingen slutplacering. Lika resultat delar placering.", style = MaterialTheme.typography.bodySmall)
            Text("Tider och placeringar använder lagrad millisekundprecision.", style = MaterialTheme.typography.bodySmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!archive && s.closedAt == null) {
                    (1..2).forEach { round -> FilterChip(selected = r.activeRound == round, enabled = editable, onClick = { vm.update { it.selectRaceRound(round) } }, label = { Text("Registrera åk $round") }) }
                    OutlinedButton(enabled = editable, onClick = { choosingNext = true }) { Text("Nästa åkare: ${s.athletes.find { it.id == s.nextAthleteId }?.name ?: "Välj"}") }
                    OutlinedButton(enabled = editable, onClick = simulator) { Text("Öppna simulator") }
                    OutlinedButton(enabled = editable, onClick = editSetup) { Text("Redigera tävling") }
                }
                OutlinedButton(enabled = s.attempts.isNotEmpty() && !export.working, onClick = { export.beginRace(s); exporting = true }) { Text("Exportera alla klasser") }
                TextButton(onClick = { assignmentsOpen = !assignmentsOpen }) { Text(if (assignmentsOpen) "Dölj registrerade resultat" else "Hantera registrerade resultat (${s.attempts.size})") }
            }
            if (unassigned.isNotEmpty()) StatusMessage("${unassigned.size} extraresultat saknar åktilldelning. Tilldela eller undanta dem under Hantera registrerade resultat före avslut.", isError = true)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = selectedClass == null, onClick = { selectedClass = null }, label = { Text("Alla klasser") })
                r.classes.forEach { name -> FilterChip(selected = selectedClass == name, onClick = { selectedClass = name }, label = { Text(name) }) }
            }
        }
        if (!assignmentsOpen) {
            items(standings, key = { it.athlete.id }) { entry -> Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("${entry.className} · ${entry.place?.let { "Placering $it" } ?: "Ingen slutplacering"} · ${entry.athlete.bib} · ${entry.athlete.name}", style = MaterialTheme.typography.titleMedium)
                    Text("Åk 1: ${raceTime(entry.first)}     Åk 2: ${raceTime(entry.second)}")
                    Text("${r.format.scoreLabel}: ${entry.scoreMs?.let { raceSeconds(it) + " s" } ?: "–"}", style = MaterialTheme.typography.titleLarge)
                    Text(entry.status)
                    if (!archive) FlowRow { listOfNotNull(entry.first, entry.second).forEach { run ->
                        val round = r.roundByAttempt.getValue(run.id)
                        TextButton(enabled = editable && (run.complete || run.durationMs != null), onClick = { vm.quickDnf(run.id, run.complete) }) { Text(if (run.complete) "DNF åk $round · ${entry.athlete.name}" else "Återställ åk $round · ${entry.athlete.name}") }
                    } }
                }
            } }
        } else items(s.attempts.sortedByDescending { it.sequence }, key = { it.id }) { run -> Card(Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = if (run.id == highlightedRunId) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh)) {
            Column(Modifier.padding(12.dp)) {
                val skier = s.athletes.single { it.id == run.athleteId }
                Text("${skier.bib} · ${skier.name} · ${raceTime(run)}", style = MaterialTheme.typography.titleMedium)
                Text("Resultat ${run.sequence} · ${r.roundByAttempt[run.id]?.let { "Tävlingsåk $it" } ?: if (run.id in r.unusedAttemptIds) "Undantaget extraresultat" else "Saknar åktilldelning"}")
                if (!archive) FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    (1..2).forEach { round -> OutlinedButton(enabled = editable && s.raceRun(run.athleteId, round)?.id.let { it == null || it == run.id }, onClick = { vm.update { it.assignRaceRun(run.id, round) } }) { Text("Tilldela åk $round · resultat ${run.sequence}") } }
                    TextButton(enabled = editable && run.id in r.roundByAttempt, onClick = { vm.update { it.assignRaceRun(run.id, null) } }) { Text("Ta bort tilldelning · resultat ${run.sequence}") }
                    TextButton(enabled = editable && run.id !in r.roundByAttempt && run.id !in r.unusedAttemptIds, onClick = { vm.update { it.excludeRaceRun(run.id) } }) { Text("Undanta extraresultat ${run.sequence}") }
                    TextButton(enabled = editable, onClick = { editRun(run.id) }) { Text("Rätta åkare · resultat ${run.sequence}") }
                }
            }
        } }
    }
    if (choosingNext) SkierPicker(s.activeAthletes(), s.nextAthleteId ?: -1, { choosingNext = false }, enabled = editable, closeOnChoose = false,
        error = vm.error, title = "Välj nästa tävlingsåkare") { id -> vm.update(onCommitted = { choosingNext = false }) { it.selectNextAthlete(id) } }
    if (exporting) ReportExportDialog(export, { exporting = false })
}
