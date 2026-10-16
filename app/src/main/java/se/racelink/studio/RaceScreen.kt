package se.racelink.studio

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
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
            Text(if (s.closedAt == null && !archive) stringResource(R.string.race_preliminary_results, r.activeRound) else stringResource(R.string.race_finished_readonly))
            Text(if (r.format == RaceFormat.TOTAL) stringResource(R.string.race_format_total_help) else stringResource(R.string.race_format_diff_help))
            Text(stringResource(R.string.race_dnf_note), style = MaterialTheme.typography.bodySmall)
            Text(stringResource(R.string.race_times_precision_note), style = MaterialTheme.typography.bodySmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!archive && s.closedAt == null) {
                    (1..2).forEach { round -> FilterChip(selected = r.activeRound == round, enabled = editable, onClick = { vm.update { it.selectRaceRound(round) } }, label = { Text(stringResource(R.string.race_register_run, round)) }) }
                    OutlinedButton(enabled = editable, onClick = { choosingNext = true }) { Text(stringResource(R.string.race_next_athlete, s.athletes.find { it.id == s.nextAthleteId }?.name ?: stringResource(R.string.race_choose))) }
                    OutlinedButton(enabled = editable, onClick = simulator) { Text(stringResource(R.string.race_open_simulator)) }
                    OutlinedButton(enabled = editable, onClick = editSetup) { Text(stringResource(R.string.race_edit_race)) }
                }
                OutlinedButton(enabled = s.attempts.isNotEmpty() && !export.working, onClick = { export.beginRace(s); exporting = true }) { Text(stringResource(R.string.race_export_all_classes)) }
                TextButton(onClick = { assignmentsOpen = !assignmentsOpen }) { Text(if (assignmentsOpen) stringResource(R.string.race_hide_recorded) else stringResource(R.string.race_manage_recorded_count, s.attempts.size)) }
            }
            if (unassigned.isNotEmpty()) StatusMessage(stringResource(R.string.race_unassigned_warning, unassigned.size), isError = true)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = selectedClass == null, onClick = { selectedClass = null }, label = { Text(stringResource(R.string.race_all_classes)) })
                r.classes.forEach { name -> FilterChip(selected = selectedClass == name, onClick = { selectedClass = name }, label = { Text(name) }) }
            }
        }
        if (!assignmentsOpen) {
            items(standings, key = { it.athlete.id }) { entry -> Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    val place = entry.place
                    val placeText = if (place != null) stringResource(R.string.race_placement, place) else stringResource(R.string.race_no_placement)
                    Text("${entry.className} · $placeText · ${entry.athlete.bib} · ${entry.athlete.name}", style = MaterialTheme.typography.titleMedium)
                    Text(stringResource(R.string.race_run_pair, raceTime(entry.first), raceTime(entry.second)))
                    Text("${r.format.scoreLabel}: ${entry.scoreMs?.let { raceSeconds(it) + " s" } ?: "–"}", style = MaterialTheme.typography.titleLarge)
                    Text(entry.status)
                    if (!archive) FlowRow { listOfNotNull(entry.first, entry.second).forEach { run ->
                        val round = r.roundByAttempt.getValue(run.id)
                        TextButton(enabled = editable && (run.complete || run.durationMs != null), onClick = { vm.quickDnf(run.id, run.complete) }) { Text(if (run.complete) stringResource(R.string.race_dnf_run, round, entry.athlete.name) else stringResource(R.string.race_reset_run, round, entry.athlete.name)) }
                    } }
                }
            } }
        } else items(s.attempts.sortedByDescending { it.sequence }, key = { it.id }) { run -> Card(Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = if (run.id == highlightedRunId) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh)) {
            Column(Modifier.padding(12.dp)) {
                val skier = s.athletes.single { it.id == run.athleteId }
                Text("${skier.bib} · ${skier.name} · ${raceTime(run)}", style = MaterialTheme.typography.titleMedium)
                val assignRound = r.roundByAttempt[run.id]
                val assignment = when {
                    assignRound != null -> stringResource(R.string.race_competition_run, assignRound)
                    run.id in r.unusedAttemptIds -> stringResource(R.string.race_excluded_result)
                    else -> stringResource(R.string.race_missing_assignment)
                }
                Text(stringResource(R.string.race_result_label, run.sequence, assignment))
                if (!archive) FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    (1..2).forEach { round -> OutlinedButton(enabled = editable && s.raceRun(run.athleteId, round)?.id.let { it == null || it == run.id }, onClick = { vm.update { it.assignRaceRun(run.id, round) } }) { Text(stringResource(R.string.race_assign_run, round, run.sequence)) } }
                    TextButton(enabled = editable && run.id in r.roundByAttempt, onClick = { vm.update { it.assignRaceRun(run.id, null) } }) { Text(stringResource(R.string.race_remove_assignment, run.sequence)) }
                    TextButton(enabled = editable && run.id !in r.roundByAttempt && run.id !in r.unusedAttemptIds, onClick = { vm.update { it.excludeRaceRun(run.id) } }) { Text(stringResource(R.string.race_exclude_result, run.sequence)) }
                    TextButton(enabled = editable, onClick = { editRun(run.id) }) { Text(stringResource(R.string.race_edit_athlete_result, run.sequence)) }
                }
            }
        } }
    }
    if (choosingNext) SkierPicker(s.activeAthletes(), s.nextAthleteId ?: -1, { choosingNext = false }, enabled = editable, closeOnChoose = false,
        error = vm.error, title = stringResource(R.string.race_choose_next_athlete)) { id -> vm.update(onCommitted = { choosingNext = false }) { it.selectNextAthlete(id) } }
    if (exporting) ReportExportDialog(export, { exporting = false })
}
