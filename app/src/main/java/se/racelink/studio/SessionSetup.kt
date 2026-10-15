package se.racelink.studio

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import org.json.JSONArray
import org.json.JSONObject
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

private val RosterDraftSaver = Saver<List<SkierDraft>, String>(
    save = { list -> JSONArray(list.map { JSONObject().put("id", it.id).put("name", it.name).put("bib", it.bib) }).toString() },
    restore = { text -> JSONArray(text).let { a -> (0 until a.length()).map { i -> a.getJSONObject(i).let { SkierDraft(it.getInt("id"), it.getString("name"), it.getString("bib")) } } } })

@OptIn(ExperimentalLayoutApi::class)
@Composable internal fun SessionSetup(current: Training, enabled: Boolean, close: () -> Unit, editing: Boolean = false, error: String? = null, start: (Training) -> Unit) {
    var name by rememberSaveable { mutableStateOf(if (editing) current.sessionName else "") }
    var date by rememberSaveable { mutableStateOf(if (editing) current.sessionDate else java.time.LocalDate.now().toString()) }
    var location by rememberSaveable { mutableStateOf(current.location) }
    var course by rememberSaveable { mutableStateOf(if (editing) current.course else "") }
    var type by rememberSaveable { mutableStateOf(current.trainingType) }
    var roster by rememberSaveable(stateSaver = RosterDraftSaver) { mutableStateOf(current.roster.map { SkierDraft(it.id, it.name, it.bib.toString()) }) }
    var selected by rememberSaveable(stateSaver = IdSetSaver) { mutableStateOf(current.participantIds) }
    val validDate = runCatching { java.time.LocalDate.parse(date) }.isSuccess
    var section by rememberSaveable { mutableStateOf("details") }
    var query by rememberSaveable { mutableStateOf("") }
    var editingSkier by rememberSaveable { mutableStateOf<Int?>(null) }
    var addingSkier by rememberSaveable { mutableStateOf(false) }
    var formError by rememberSaveable { mutableStateOf<String?>(null) }
    val validRoster = roster.all { skierErrors(it, roster).valid }
    val valid = name.isNotBlank() && validDate && validRoster && selected.isNotEmpty()
    val dirty = name != (if (editing) current.sessionName else "") || date != (if (editing) current.sessionDate else java.time.LocalDate.now().toString()) ||
        location != current.location || course != (if (editing) current.course else "") || type != current.trainingType ||
        roster != current.roster.map { SkierDraft(it.id, it.name, it.bib.toString()) } || selected != current.participantIds
    val leave = draftClose(dirty, enabled, close)
    AppFullScreenDialog(leave) {
        Surface(Modifier.fillMaxSize().safeDrawingPadding().imePadding(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(if (editing) "Redigera aktuellt pass" else "Starta träningspass", style = MaterialTheme.typography.headlineSmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = section == "details", onClick = { section = "details" }, label = { Text("Passuppgifter") })
                    FilterChip(selected = section == "participants", onClick = { section = "participants" }, label = { Text("Deltagare (${selected.size})") })
                }
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (section == "details") {
                        Text(if (editing) "Befintliga åk behålls. Ändringarna gäller aktuellt pass." else "Det aktuella passet sparas i historiken. Det nya passet börjar utan resultat eller ändringsmarkeringar.", style = MaterialTheme.typography.bodySmall)
                        OutlinedTextField(name, { name = it }, enabled = enabled, label = { Text("Passnamn *") }, isError = name.isBlank(),
                            supportingText = { Text(if (name.isBlank()) "Ange ett passnamn" else "Obligatoriskt") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                        DateInput(date, { date = it }, "Datum *", enabled)
                        OutlinedTextField(location, { location = it }, enabled = enabled, label = { Text("Plats (valfritt)") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                        OutlinedTextField(course, { course = it }, enabled = enabled, label = { Text("Backe / bana (valfritt)") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                        OutlinedTextField(type, { type = it }, enabled = enabled, label = { Text("Träningstyp (valfritt)") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                    } else {
                        Text("${selected.size} av ${roster.size} åkare deltar", style = MaterialTheme.typography.titleMedium)
                        Text("Åkarlistan återanvänds i kommande pass. Sparade pass behåller sina åkarnamn och nummer.", style = MaterialTheme.typography.bodySmall)
                        OutlinedTextField(query, { query = it }, enabled = enabled, label = { Text("Sök namn eller nummer") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(enabled = enabled, onClick = { selected = roster.map { it.id }.toSet() }) { Text("Välj alla") }
                            TextButton(enabled = enabled, onClick = { selected = emptySet() }) { Text("Avmarkera alla") }
                            OutlinedButton(enabled = enabled, onClick = { addingSkier = true }) { Text("Lägg till åkare") }
                        }
                        val matches = roster.filter { query.isBlank() || it.name.contains(query.trim(), true) || it.bib.contains(query.trim()) }
                        if (matches.isEmpty()) Text("Inga åkare matchar sökningen.")
                        matches.forEach { skier ->
                            val errors = skierErrors(skier, roster)
                            Card(Modifier.fillMaxWidth()) {
                                Column(Modifier.padding(8.dp)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Checkbox(checked = skier.id in selected, enabled = enabled, onCheckedChange = { checked -> selected = if (checked) selected + skier.id else selected - skier.id })
                                        Column(Modifier.weight(1f)) {
                                            Text("${skier.bib} · ${skier.name.ifBlank { "Namn saknas" }}", style = MaterialTheme.typography.titleMedium)
                                            if (!errors.valid) Text(listOfNotNull(errors.name, errors.bib).joinToString(" · "), color = MaterialTheme.colorScheme.error)
                                        }
                                        TextButton(enabled = enabled, onClick = { editingSkier = skier.id }) { Text("Redigera") }
                                    }
                                    if (current.roster.none { it.id == skier.id }) TextButton(enabled = enabled, onClick = {
                                        roster = roster.filter { it.id != skier.id }; selected = selected - skier.id
                                    }) { Text("Ta bort ny åkare") }
                                }
                            }
                        }
                    }
                }
                SaveFeedback(enabled, error ?: formError)
                if (!valid) Text(when {
                    name.isBlank() || !validDate -> "Fyll i giltigt passnamn och datum under Passuppgifter."
                    !validRoster -> "Rätta markerade namn eller nummer under Deltagare."
                    else -> "Välj minst en deltagare innan du sparar."
                }, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(enabled = enabled, onClick = leave) { Text("Avbryt") }
                    Button(enabled = enabled && valid, onClick = {
                        formError = null
                        runCatching {
                            val skiers = roster.map { Athlete(it.id, it.bib.toInt(), it.name.trim()) }
                            if (editing) current.editSession(name, date, location, course, type, skiers, selected)
                            else current.startNewSession(name, date, location, course, type, skiers, selected)
                        }.onSuccess(start).onFailure { formError = it.message ?: "Passet kunde inte sparas" }
                    }) { Text(if (editing) "Spara ändringar" else "Starta pass") }
                }
            }
        }
    }
    val editDraft = if (addingSkier) SkierDraft((roster.maxOfOrNull { it.id } ?: 0) + 1, "", "") else roster.firstOrNull { it.id == editingSkier }
    editDraft?.let { draft ->
        SkierDraftDialog(draft, roster, enabled, { editingSkier = null; addingSkier = false }) { updated ->
            if (addingSkier) { roster = roster + updated; selected = selected + updated.id }
            else roster = roster.map { if (it.id == updated.id) updated else it }
            editingSkier = null; addingSkier = false
        }
    }
}

@Composable private fun SkierDraftDialog(original: SkierDraft, roster: List<SkierDraft>, enabled: Boolean, close: () -> Unit, save: (SkierDraft) -> Unit) {
    var name by rememberSaveable(original.id) { mutableStateOf(original.name) }
    var bib by rememberSaveable(original.id) { mutableStateOf(original.bib) }
    val draft = original.copy(name = name, bib = bib)
    val errors = skierErrors(draft, roster)
    val leave = draftClose(draft != original, enabled, close)
    AlertDialog(onDismissRequest = leave, title = { Text(if (original.name.isBlank()) "Lägg till åkare" else "Redigera åkare") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            OutlinedTextField(name, { name = it }, enabled = enabled, label = { Text("Namn") }, singleLine = true,
                isError = errors.name != null, supportingText = { errors.name?.let { Text(it) } }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(bib, { bib = it }, enabled = enabled, label = { Text("Nummer") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), isError = errors.bib != null,
                supportingText = { errors.bib?.let { Text(it) } }, modifier = Modifier.fillMaxWidth())
        }
    }, confirmButton = { TextButton(enabled = enabled && errors.valid, onClick = { save(draft.copy(name = name.trim())) }) { Text("Klar") } },
        dismissButton = { TextButton(onClick = leave) { Text("Avbryt") } })
}
