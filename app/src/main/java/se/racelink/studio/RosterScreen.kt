package se.racelink.studio

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics

internal fun Training.archiveRosterMember(id: Int, archived: Boolean): Training {
    require(roster.any { it.id == id }) { "Åkaren saknas i åkarlistan" }
    return copy(archivedRosterIds = if (archived) archivedRosterIds + id else archivedRosterIds - id)
}

@Composable internal fun SessionTemplatePicker(sessions: List<Training>, close: () -> Unit, choose: (String) -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    AppFullScreenDialog(close) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.race_fetch_template), style = MaterialTheme.typography.headlineSmall)
                OutlinedTextField(query, { query = it }, label = { Text(stringResource(R.string.race_search_sessions)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                val matches = sessions.filter { "${it.sessionLabel()} ${it.sessionDetails()}".contains(query.trim(), ignoreCase = true) }
                LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (matches.isEmpty()) item { Text(stringResource(R.string.race_no_matching_sessions)) }
                    items(matches, key = { it.sessionId }) { session -> Card(onClick = { choose(session.sessionId) }) {
                        Column(Modifier.fillMaxWidth().padding(16.dp)) {
                            Text(session.sessionLabel(), style = MaterialTheme.typography.titleMedium)
                            Text(session.sessionDetails())
                            Text(stringResource(R.string.race_participants_choose_template, session.participantIds.size))
                        }
                    } }
                }
                TextButton(onClick = close) { Text(stringResource(R.string.race_cancel)) }
            }
        }
    }
}

internal data class SessionTemplate(val name: String, val location: String, val course: String, val type: String, val participants: Set<Int>, val omitted: Int)
internal fun Training.templateFrom(source: Training): SessionTemplate {
    val eligible = roster.map { it.id }.toSet() - archivedRosterIds
    val participants = source.participantIds.intersect(eligible)
    return SessionTemplate(source.sessionName, source.location, source.course, source.trainingType, participants, source.participantIds.size - participants.size)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable internal fun RosterScreen(s: Training, vm: TrainingViewModel, close: () -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    var archived by rememberSaveable { mutableStateOf(false) }
    var confirmId by rememberSaveable { mutableStateOf<Int?>(null) }
    var importing by rememberSaveable { mutableStateOf(false) }
    AppFullScreenDialog(close) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.race_roster_title), style = MaterialTheme.typography.headlineSmall)
                OutlinedButton(enabled = !vm.busy && !vm.backupBusy, onClick = { importing = true }) { Text(stringResource(R.string.race_import_csv)) }
                OutlinedTextField(query, { query = it }, label = { Text(stringResource(R.string.race_search_name_number)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = !archived, onClick = { archived = false }, label = { Text(stringResource(R.string.race_active_count, s.roster.size - s.archivedRosterIds.size)) })
                    FilterChip(selected = archived, onClick = { archived = true }, label = { Text(stringResource(R.string.race_archived_count, s.archivedRosterIds.size)) })
                }
                val matches = findSkiers(s.roster.filter { (it.id in s.archivedRosterIds) == archived }, query)
                LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (query.isBlank()) item { Text(stringResource(R.string.race_archive_note), style = MaterialTheme.typography.bodySmall) }
                    if (matches.isEmpty()) item { Text(stringResource(R.string.race_no_athletes_selection)) }
                    items(matches, key = { it.id }) { skier -> Card(Modifier.fillMaxWidth()) {
                        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("${skier.bib} · ${skier.name}", style = MaterialTheme.typography.titleMedium)
                                if (skier.id in s.participantIds) Text(stringResource(R.string.race_participates_current), style = MaterialTheme.typography.bodySmall)
                            }
                            val archiveDesc = stringResource(if (archived) R.string.race_reactivate_action_desc else R.string.race_archive_action_desc, skier.name, skier.bib)
                            TextButton(enabled = !vm.busy && !vm.backupBusy, onClick = { confirmId = skier.id },
                                modifier = Modifier.semantics { contentDescription = archiveDesc }) { Text(stringResource(if (archived) R.string.race_reactivate else R.string.race_archive)) }
                        }
                    } }
                }
                SaveFeedback(!vm.busy, vm.error)
                TextButton(onClick = close) { Text(stringResource(R.string.race_back_to_settings)) }
            }
        }
    }
    if (importing) RosterImportDialog(s, vm, { importing = false })
    confirmId?.let { id -> s.roster.find { it.id == id }?.let { skier ->
        val wasArchived = id in s.archivedRosterIds
        AlertDialog(onDismissRequest = { if (!vm.busy) confirmId = null }, title = { Text(stringResource(if (wasArchived) R.string.race_reactivate_question else R.string.race_archive_question, skier.name)) }, text = {
            Column {
                Text(stringResource(R.string.race_archive_confirm_note))
                SaveFeedback(!vm.busy, vm.error)
            }
        }, confirmButton = { TextButton(enabled = !vm.busy && !vm.backupBusy, onClick = {
            vm.update(onCommitted = { confirmId = null }) { it.archiveRosterMember(id, !wasArchived) }
        }) { Text(stringResource(R.string.race_confirm)) } }, dismissButton = { TextButton(enabled = !vm.busy, onClick = { confirmId = null }) { Text(stringResource(R.string.race_cancel)) } })
    } }
}
