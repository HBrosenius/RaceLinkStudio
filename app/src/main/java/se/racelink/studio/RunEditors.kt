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

@OptIn(ExperimentalLayoutApi::class)
@Composable internal fun QuickTags(s: Training, run: Attempt, enabled: Boolean, toggle: (Int) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        TagCategory.entries.filter { it != TagCategory.CHANGE }.forEach { category ->
            Text(category.label, style = MaterialTheme.typography.labelLarge,
                color = if (category == TagCategory.RUN) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.tertiary)
            FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                s.tags.filter { it.category == category }.forEach { tag -> FilterChip(selected = tag.id in run.tags, enabled = enabled,
                    onClick = { toggle(tag.id) }, label = { Text(tag.name) }) }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable internal fun EditRun(s: Training, run: Attempt, enabled: Boolean, close: () -> Unit, error: String? = null,
    save: (Int, Boolean, Set<Int>, String) -> Unit) {
    var athlete by rememberSaveable(run.id) { mutableIntStateOf(run.athleteId) }
    var complete by rememberSaveable(run.id) { mutableStateOf(run.complete) }
    var tags by rememberSaveable(run.id, stateSaver = IdSetSaver) { mutableStateOf(run.tags) }
    var note by rememberSaveable(run.id) { mutableStateOf(run.note) }
    var corrections by rememberSaveable(run.id) { mutableStateOf(false) }
    var pickAthlete by rememberSaveable(run.id) { mutableStateOf(false) }
    val leave = draftClose(athlete != run.athleteId || complete != run.complete || tags != run.tags || note != run.note, enabled, close)
    EditorDialog(onDismissRequest = leave, title = {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(stringResource(R.string.dash_run_prefix, s.runs(run.athleteId).indexOfFirst { it.id == run.id } + 1, s.athletes.first { it.id == run.athleteId }.name))
            Text((run.durationMs?.let { "${seconds(it)} s" } ?: stringResource(R.string.dash_no_finish_time)) + if (!complete) stringResource(R.string.dash_dnf_suffix) else "",
                style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.primary)
        }
    }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            s.tagDescription(run, TagCategory.CHANGE).takeIf { it.isNotBlank() }?.let { Text(stringResource(R.string.dash_change_prefix, it)) }
            Text(if (complete) stringResource(R.string.dash_included_in_trend) else stringResource(R.string.dash_dnf_not_in_trend), style = MaterialTheme.typography.bodySmall)
            QuickTags(s, run.copy(tags = tags), enabled) { id -> tags = if (id in tags) tags - id else tags + id }
            OutlinedTextField(note, { note = it }, enabled = enabled, label = { Text(stringResource(R.string.dash_note)) }, modifier = Modifier.fillMaxWidth())
            RunHistoryButton(s, run)
            TextButton(onClick = { corrections = !corrections }) { Text(if (corrections) stringResource(R.string.dash_hide_corrections) else stringResource(R.string.dash_correct_athlete_or_dnf)) }
            if (corrections) {
                Text(stringResource(R.string.dash_corrections_saved), style = MaterialTheme.typography.titleSmall)
                OutlinedButton(enabled = enabled, onClick = { pickAthlete = true }) { Text(stringResource(R.string.dash_assign_athlete, s.athletes.first { it.id == athlete }.name)) }
                if (athlete != run.athleteId) Text(stringResource(R.string.dash_run_moves_to, s.athletes.first { it.id == athlete }.name))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = complete, enabled = enabled && run.durationMs != null, onClick = { complete = true }, label = { Text(stringResource(R.string.dash_completed)) })
                    FilterChip(selected = !complete, enabled = enabled, onClick = { complete = false }, label = { Text(stringResource(R.string.dash_dnf_not_completed)) })
                }
            }

        }
    }, confirmButton = { Column { SaveFeedback(enabled, error); TextButton(onClick = { save(athlete, complete, tags, note) }, enabled = enabled) { Text(stringResource(R.string.dash_save)) } } },
        dismissButton = { TextButton(enabled = enabled, onClick = leave) { Text(stringResource(R.string.dash_cancel)) } })
    if (pickAthlete) SkierPicker(s.athletes, athlete, { pickAthlete = false }) { athlete = it }

}

fun csv(s: Training): String = reportCsv(reportRuns(listOf(s)))

@OptIn(ExperimentalLayoutApi::class)
@Composable internal fun ChangeDialog(s: Training, enabled: Boolean, close: () -> Unit, marker: Marker? = null, remove: (() -> Unit)? = null, error: String? = null, save: (Set<Int>, String) -> Unit) {
    var selected by rememberSaveable(stateSaver = IdSetSaver) { mutableStateOf(marker?.tags ?: emptySet<Int>()) }
    var description by rememberSaveable { mutableStateOf(marker?.label.orEmpty()) }
    val leave = draftClose(selected != (marker?.tags ?: emptySet<Int>()) || description != marker?.label.orEmpty(), enabled, close)
    EditorDialog(onDismissRequest = leave, title = { Text(if (marker == null) stringResource(R.string.dash_mark_change) else stringResource(R.string.dash_edit_change)) }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(if (marker == null) stringResource(R.string.dash_change_scope_new) else stringResource(R.string.dash_change_scope_edit, marker.afterSequence))
            Text(stringResource(R.string.dash_change), style = MaterialTheme.typography.titleMedium)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                s.tags.filter { it.category == TagCategory.CHANGE }.forEach { tag ->
                    FilterChip(selected = tag.id in selected, enabled = enabled,
                        onClick = { selected = if (tag.id in selected) selected - tag.id else selected + tag.id }, label = { Text(tag.name) })
                }
            }
            OutlinedTextField(description, { description = it }, enabled = enabled, label = { Text(stringResource(R.string.dash_describe_change)) }, modifier = Modifier.fillMaxWidth())
            Text(stringResource(R.string.dash_change_tags_settings), style = MaterialTheme.typography.bodySmall)
            if (remove != null) TextButton(enabled = enabled, onClick = remove,
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text(stringResource(R.string.dash_remove_change)) }
        }
    }, confirmButton = { Column { SaveFeedback(enabled, error); TextButton(enabled = enabled && (selected.isNotEmpty() || description.isNotBlank()), onClick = { save(selected, description) }) { Text(stringResource(R.string.dash_save_change)) } } },
        dismissButton = { TextButton(enabled = enabled, onClick = leave) { Text(stringResource(R.string.dash_cancel)) } })
}
