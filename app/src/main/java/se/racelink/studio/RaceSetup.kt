package se.racelink.studio

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.Saver
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.json.JSONObject

private val RaceClassesSaver = Saver<Map<Int, String>, String>(save = { JSONObject(it.mapKeys { e -> e.key.toString() }).toString() },
    restore = { text -> JSONObject(text).let { o -> o.keys().asSequence().associate { it.toInt() to o.getString(it) } } })

@OptIn(ExperimentalLayoutApi::class)
@Composable internal fun RaceSetup(current: Training, enabled: Boolean, close: () -> Unit, editing: Boolean,
    error: String?, save: (Training) -> Unit) {
    val original = current.race.takeIf { editing }
    val initialParticipants = if (editing) current.participantIds else current.participantIds - current.archivedRosterIds
    var name by rememberSaveable { mutableStateOf(if (editing) current.sessionName else "") }
    var date by rememberSaveable { mutableStateOf(if (editing) current.sessionDate else java.time.LocalDate.now().toString()) }
    var formatName by rememberSaveable { mutableStateOf((original?.format ?: RaceFormat.TOTAL).name) }
    var classesText by rememberSaveable { mutableStateOf(original?.classes?.joinToString("\n") ?: "Öppen") }
    var participants by rememberSaveable(stateSaver = IdSetSaver) { mutableStateOf(initialParticipants) }
    var assignments by rememberSaveable(stateSaver = RaceClassesSaver) { mutableStateOf(original?.athleteClasses ?: initialParticipants.associateWith { "Öppen" }) }
    var query by rememberSaveable { mutableStateOf("") }
    var menuId by remember { mutableStateOf<Int?>(null) }
    var formError by rememberSaveable { mutableStateOf<String?>(null) }
    val classes = classesText.lines().map { it.trim() }.filter { it.isNotBlank() }
    val format = RaceFormat.valueOf(formatName)
    val valid = name.isNotBlank() && runCatching { java.time.LocalDate.parse(date) }.isSuccess && participants.isNotEmpty() &&
        classes.isNotEmpty() && classes.all { it.length <= 80 } && classes.map { it.lowercase(java.util.Locale.ROOT) }.distinct().size == classes.size &&
        participants.all { assignments[it] in classes }
    val dirty = name != (if (editing) current.sessionName else "") || participants != initialParticipants ||
        date != (if (editing) current.sessionDate else java.time.LocalDate.now().toString()) || format != (original?.format ?: RaceFormat.TOTAL) ||
        classesText != (original?.classes?.joinToString("\n") ?: "Öppen") || assignments != (original?.athleteClasses ?: initialParticipants.associateWith { "Öppen" })
    val leave = draftClose(dirty, enabled, close)
    EditorDialog(leave, title = { Text(if (editing) stringResource(R.string.race_edit_race) else stringResource(R.string.race_start_race), style = MaterialTheme.typography.headlineSmall) }, text = {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(stringResource(R.string.race_simulator_note))
            OutlinedTextField(name, { name = it }, enabled = enabled, label = { Text(stringResource(R.string.race_competition_name)) }, modifier = Modifier.fillMaxWidth())
            DateInput(date, { date = it }, stringResource(R.string.race_competition_date))
            Text(stringResource(R.string.race_competition_format), style = MaterialTheme.typography.titleMedium)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { RaceFormat.entries.forEach { f ->
                FilterChip(selected = format == f, enabled = enabled && (!editing || current.attempts.isEmpty()), onClick = { formatName = f.name }, label = { Text(f.label) })
            } }
            Text(if (format == RaceFormat.TOTAL) stringResource(R.string.race_total_help) else stringResource(R.string.race_diff_help))
            Text(stringResource(R.string.race_two_runs_required))
            OutlinedTextField(classesText, { classesText = it }, enabled = enabled, label = { Text(stringResource(R.string.race_classes_label)) }, modifier = Modifier.fillMaxWidth())
            Text(stringResource(R.string.race_participants_note))
            OutlinedTextField(query, { query = it }, label = { Text(stringResource(R.string.race_search_participants)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
            findSkiers(current.roster.filter { it.id !in current.archivedRosterIds || it.id in participants }, query).forEach { skier ->
                Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(12.dp)) {
                    FilterChip(selected = skier.id in participants, enabled = enabled && (!editing || current.attempts.none { it.athleteId == skier.id }), onClick = {
                        if (skier.id in participants) participants = participants - skier.id
                        else { participants = participants + skier.id; if (assignments[skier.id] !in classes && classes.size == 1) assignments = assignments + (skier.id to classes.single()) }
                    }, label = { Text("${skier.bib} · ${skier.name}") })
                    if (skier.id in participants) Box {
                        val classLabel = assignments[skier.id]?.takeIf { it in classes } ?: stringResource(R.string.race_choose_class)
                        OutlinedButton(enabled = enabled, onClick = { menuId = skier.id }) { Text(stringResource(R.string.race_class_label, classLabel)) }
                        DropdownMenu(expanded = menuId == skier.id, onDismissRequest = { menuId = null }) { classes.forEach { name ->
                            DropdownMenuItem(text = { Text(name) }, onClick = { assignments = assignments + (skier.id to name); menuId = null })
                        } }
                    }
                } }
            }
            if (!valid) Text(stringResource(R.string.race_validation_hint))
            SaveFeedback(enabled, error ?: formError)
        }
    }, confirmButton = { Button(enabled = enabled && valid, onClick = {
        runCatching {
            val base = if (editing) current.editSession(name, date, current.location, current.course, current.trainingType, current.roster, participants)
                else current.startNewSession(name, date, current.location, current.course, current.trainingType, current.roster, participants)
            base.configureRace(RaceConfig(format, classes, assignments.filterKeys { it in participants }, original?.activeRound ?: 1,
                original?.roundByAttempt.orEmpty(), original?.unusedAttemptIds.orEmpty()))
        }.onSuccess(save).onFailure { formError = it.message }
    }) { Text(if (editing) stringResource(R.string.race_save_race) else stringResource(R.string.race_start_race)) } }, dismissButton = { TextButton(enabled = enabled, onClick = leave) { Text(stringResource(R.string.race_cancel)) } })
}
