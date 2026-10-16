package se.racelink.studio

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp

internal data class RunHistoryEntry(val time: String, val before: String?, val after: String?, val legacy: String? = null)

private fun Training.historyValue(run: Attempt): String = buildString {
    val skier = athletes.first { it.id == run.athleteId }
    append("Åkare: ${skier.bib} · ${skier.name}\n")
    append("Status: ${if (run.complete) "Fullföljt" else "DNF · ej fullföljt"}\n")
    append("Sparad tid: ${run.durationMs?.let { seconds(it) + " s" } ?: "Ingen måltid"}\n")
    listOf(TagCategory.RUN, TagCategory.NEXT_FOCUS).forEach { category ->
        append("${category.label}: ${tags.filter { it.id in run.tags && it.category == category }.joinToString { it.name }.ifBlank { "Inga taggar" }}\n")
    }
    append("Anteckning: ${run.note.ifBlank { "Ingen anteckning" }}")
}

internal fun Training.runEditRecord(before: Attempt, after: Attempt): AuditEntry =
    AuditEntry(java.time.Instant.now().toString(), AuditKind.RUN_EDITED, "", before.id, historyValue(before), historyValue(after))

internal fun Training.runHistory(id: String): List<RunHistoryEntry> = audit.filter { it.runId == id }
    .map { RunHistoryEntry(it.at, it.before, it.after, if (it.kind == AuditKind.LEGACY) it.detail else null) }

@Composable internal fun RunHistoryButton(s: Training, run: Attempt, label: String = stringResource(R.string.set_run_history_label)) {
    var open by rememberSaveable(run.id) { mutableStateOf(false) }
    val noFinishTime = stringResource(R.string.set_no_finish_time)
    TextButton(onClick = { open = true }) { Text(label) }
    if (open) AppFullScreenDialog({ open = false }) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.set_run_history_title), style = MaterialTheme.typography.headlineSmall)
                Text("${s.athletes.first { it.id == run.athleteId }.name} · ${run.durationMs?.let { seconds(it) + " s" } ?: noFinishTime}")
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    val entries = s.runHistory(run.id)
                    Text(stringResource(R.string.set_saved_corrections), style = MaterialTheme.typography.titleMedium)
                    if (entries.isEmpty()) Text(stringResource(R.string.set_no_saved_corrections))
                    entries.forEach { entry -> Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(displayStoredTime(entry.time), style = MaterialTheme.typography.labelMedium)
                            if (entry.legacy != null) {
                                Text(stringResource(R.string.set_legacy_entry_info))
                                Text(entry.legacy)
                            } else {
                                Text(stringResource(R.string.set_before), style = MaterialTheme.typography.titleSmall); Text(entry.before.orEmpty())
                                HorizontalDivider()
                                Text(stringResource(R.string.set_after), style = MaterialTheme.typography.titleSmall); Text(entry.after.orEmpty())
                            }
                        }
                    } }
                    Text(stringResource(R.string.set_original_results), style = MaterialTheme.typography.titleMedium)
                    val sources = s.timingEvents.filter { it.attemptId == run.id }
                    if (sources.isEmpty()) Text(stringResource(R.string.set_no_original_events))
                    sources.forEach { source ->
                        val event = source.observation
                        val eventTime = event.durationMs?.let { seconds(it) + " s" } ?: noFinishTime
                        val completeText = if (event.complete) stringResource(R.string.set_completed) else "DNF"
                        Text(stringResource(R.string.set_event_number_line, event.bib, eventTime, completeText))
                        Text(stringResource(R.string.set_event_detail, source.reason, event.id, event.sequence))
                        val decisionText = when (source.decision) {
                            TimingDecision.ACCEPTED -> stringResource(R.string.set_decision_accepted)
                            TimingDecision.ASSIGNED -> stringResource(R.string.set_decision_assigned, source.athleteId ?: 0)
                            TimingDecision.DUPLICATE -> stringResource(R.string.set_decision_duplicate)
                            TimingDecision.REJECTED -> stringResource(R.string.set_decision_rejected)
                            TimingDecision.WAITING -> stringResource(R.string.set_decision_waiting)
                        }
                        Text(decisionText)
                        source.decidedAt?.let { Text(it) }
                    }
                    Text(stringResource(R.string.set_run_history_footer), style = MaterialTheme.typography.bodySmall)
                }
                TextButton(onClick = { open = false }) { Text(stringResource(R.string.set_back_to_run)) }
            }
        }
    }
}
