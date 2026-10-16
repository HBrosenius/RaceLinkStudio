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
import androidx.compose.ui.res.stringResource
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
                    Text(stringResource(R.string.race_review_header, s.reviewQueue().size), Modifier.weight(1f), style = MaterialTheme.typography.headlineSmall)
                    TextButton(onClick = close) { Text(stringResource(R.string.race_to_current_session)) }
                }
                Text(stringResource(R.string.race_review_note))
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = !history, onClick = { history = false }, label = { Text(stringResource(R.string.race_to_review)) })
                    FilterChip(selected = history, onClick = { history = true }, label = { Text(stringResource(R.string.race_decision_history)) })
                    OutlinedButton(enabled = !vm.busy && s.closedAt == null, onClick = { vm.update { it.simulateReview(false) }; history = false }) { Text(stringResource(R.string.race_simulate_unknown)) }
                    OutlinedButton(enabled = !vm.busy && s.closedAt == null, onClick = { vm.update { it.simulateReview(true) }; history = false }) { Text(stringResource(R.string.race_simulate_uncertain)) }
                }
                vm.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                if (vm.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (rows.isEmpty()) item { Text(if (history) stringResource(R.string.race_no_decisions) else stringResource(R.string.race_no_pending_review)) }
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
            val originalTime = if (event.complete) seconds(event.durationMs!!) + " s" else "DNF"
            Text(stringResource(R.string.race_original_number, event.bib, originalTime))
            TextButton(onClick = { evidence = !evidence }) { Text(if (evidence) stringResource(R.string.race_hide_original) else stringResource(R.string.race_show_original)) }
            if (evidence) Text(stringResource(R.string.race_event_detail, event.id, event.sequence), style = MaterialTheme.typography.bodySmall)
            if (row.decision == TimingDecision.WAITING) {
                Text(if (row.reason.startsWith("Motstridigt")) stringResource(R.string.race_check_duplicate)
                    else stringResource(R.string.race_check_assign),
                    style = MaterialTheme.typography.bodyMedium)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box {
                        val chosen = s.athletes.find { it.id == athleteId }
                        OutlinedButton(enabled = enabled, onClick = { athleteMenu = true }) { Text(if (chosen != null) "${chosen.bib} · ${chosen.name}" else stringResource(R.string.race_choose_athlete)) }

                    }
                    Button(enabled = enabled && athleteId != null, onClick = { confirm = TimingDecision.ASSIGNED }) { Text(stringResource(R.string.race_assign_athlete)) }
                }
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box {
                        OutlinedButton(enabled = enabled && s.attempts.isNotEmpty(), onClick = { duplicateMenu = true }) { Text(attemptLabel(duplicateId)) }

                    }
                    OutlinedButton(enabled = enabled && duplicateId != null, onClick = { confirm = TimingDecision.DUPLICATE }) { Text(stringResource(R.string.race_mark_duplicate)) }
                    TextButton(enabled = enabled, onClick = { confirm = TimingDecision.REJECTED }) { Text(stringResource(R.string.race_reject_event)) }
                }
            } else {
                Text(when (row.decision) {
                    TimingDecision.ASSIGNED -> stringResource(R.string.race_assigned, s.athletes.find { it.id == row.athleteId }?.name.orEmpty(), attemptLabel(row.attemptId))
                    TimingDecision.DUPLICATE -> stringResource(R.string.race_duplicate_of, attemptLabel(row.attemptId))
                    TimingDecision.REJECTED -> stringResource(R.string.race_rejected_no_run)
                    else -> stringResource(R.string.race_auto_matched)
                })
                Text(stringResource(R.string.race_decision_at, row.decidedAt.orEmpty()), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
    if (athleteMenu) SkierPicker(s.athletes, athleteId ?: -1, { athleteMenu = false }) { athleteId = it }
    if (duplicateMenu) AlertDialog(onDismissRequest = { duplicateMenu = false }, title = { Text(stringResource(R.string.race_choose_existing_run)) }, text = {
        Column {
            OutlinedTextField(duplicateQuery, { duplicateQuery = it }, label = { Text(stringResource(R.string.race_search_name_number_run)) }, singleLine = true)
            val matches = s.attempts.sortedByDescending { it.sequence }.filter { attemptLabel(it.id).contains(duplicateQuery.trim(), ignoreCase = true) }
            LazyColumn(Modifier.heightIn(max = 360.dp)) {
                if (matches.isEmpty()) item { Text(stringResource(R.string.race_no_matching_runs)) }
                items(matches, key = { it.id }) { run -> TextButton(onClick = { duplicateId = run.id; duplicateMenu = false }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(attemptLabel(run.id)) } }
            }
        }
    }, confirmButton = { TextButton(onClick = { duplicateMenu = false }) { Text(stringResource(R.string.race_close)) } })
    LaunchedEffect(row.decision) { if (row.decision != TimingDecision.WAITING) confirm = null }
    confirm?.let { decision ->
        val label = when (decision) {
            TimingDecision.ASSIGNED -> stringResource(R.string.race_confirm_assign, s.athletes.find { it.id == athleteId }?.name.orEmpty())
            TimingDecision.DUPLICATE -> stringResource(R.string.race_confirm_duplicate, attemptLabel(duplicateId))
            else -> stringResource(R.string.race_confirm_reject)
        }
        AlertDialog(onDismissRequest = { confirm = null }, title = { Text(label) }, text = {
            Column { SaveFeedback(enabled, error)
            Text(if (decision == TimingDecision.ASSIGNED) stringResource(R.string.race_assign_explanation)
                else if (decision == TimingDecision.DUPLICATE) stringResource(R.string.race_duplicate_explanation, attemptLabel(duplicateId))
                else stringResource(R.string.race_reject_explanation)) }
        }, confirmButton = { TextButton(enabled = enabled, onClick = { resolve(decision, athleteId, duplicateId) }) { Text(stringResource(R.string.race_confirm)) } },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text(stringResource(R.string.race_cancel)) } })
    }
}
