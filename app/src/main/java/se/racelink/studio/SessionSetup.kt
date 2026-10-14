package se.racelink.studio

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

private data class SkierDraft(val id: Int, val name: String, val bib: String)

@Composable internal fun SessionSetup(current: Training, enabled: Boolean, close: () -> Unit, editing: Boolean = false, start: (Training) -> Unit) {
    var name by remember { mutableStateOf(if (editing) current.sessionName else "") }
    var date by remember { mutableStateOf(if (editing) current.sessionDate else java.time.LocalDate.now().toString()) }
    var location by remember { mutableStateOf(current.location) }
    var course by remember { mutableStateOf(if (editing) current.course else "") }
    var type by remember { mutableStateOf(current.trainingType) }
    var roster by remember { mutableStateOf(current.roster.map { SkierDraft(it.id, it.name, it.bib.toString()) }) }
    var selected by remember { mutableStateOf(current.participantIds) }
    val validDate = runCatching { java.time.LocalDate.parse(date) }.isSuccess
    val validRoster = roster.all { it.name.isNotBlank() && (it.bib.toIntOrNull() ?: 0) > 0 } &&
        roster.mapNotNull { it.bib.toIntOrNull() }.distinct().size == roster.size
    val valid = name.isNotBlank() && validDate && validRoster && selected.isNotEmpty()
    Dialog(onDismissRequest = close, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize().safeDrawingPadding(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(if (editing) "Redigera aktuellt pass" else "Starta träningspass", Modifier.weight(1f), style = MaterialTheme.typography.headlineSmall)
                    TextButton(onClick = close) { Text("Avbryt") }
                    Button(enabled = enabled && valid, onClick = {
                        val skiers = roster.map { Athlete(it.id, it.bib.toInt(), it.name.trim()) }
                        start(if (editing) current.editSession(name, date, location, course, type, skiers, selected)
                            else current.startNewSession(name, date, location, course, type, skiers, selected))
                    }) { Text(if (editing) "Spara ändringar" else if (current.closedAt != null) "Starta pass" else "Spara aktuellt och starta") }
                }
                Text(if (editing) "Befintliga åk sparas även om en åkare avmarkeras. Bara markerade åkare får nya simulerade mål. Ändringar påverkar inte avslutade pass." else "Det aktuella passet sparas med sina resultat och åkarnamn. Ändringar i åkarlistan gäller det nya passet och framtida pass.")
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(name, { name = it }, label = { Text("Passnamn *") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                    OutlinedTextField(date, { date = it }, label = { Text("Datum (ÅÅÅÅ-MM-DD) *") }, isError = !validDate, singleLine = true)
                    OutlinedTextField(location, { location = it }, label = { Text("Plats") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                    OutlinedTextField(course, { course = it }, label = { Text("Backe / bana") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                    OutlinedTextField(type, { type = it }, label = { Text("Träningstyp, t.ex. slalom eller teknik") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                    Text("Deltagande åkare · ${selected.size} valda", style = MaterialTheme.typography.titleLarge)
                    Text("Markera vilka som deltar. Namn och nummer kan redigeras här; varje åkare måste ha ett unikt positivt nummer.")
                    Row {
                        TextButton(onClick = { selected = roster.map { it.id }.toSet() }) { Text("Välj alla") }
                        TextButton(onClick = { selected = emptySet() }) { Text("Avmarkera alla") }
                    }
                    roster.forEach { skier -> key(skier.id) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Checkbox(checked = skier.id in selected, onCheckedChange = { checked -> selected = if (checked) selected + skier.id else selected - skier.id })
                            OutlinedTextField(skier.name, { value -> roster = roster.map { if (it.id == skier.id) it.copy(name = value) else it } },
                                label = { Text("Namn") }, modifier = Modifier.weight(1f), singleLine = true, isError = skier.name.isBlank())
                            OutlinedTextField(skier.bib, { value -> roster = roster.map { if (it.id == skier.id) it.copy(bib = value) else it } },
                                label = { Text("Nummer") }, modifier = Modifier.width(130.dp), singleLine = true,
                                isError = (skier.bib.toIntOrNull() ?: 0) <= 0 || roster.count { it.bib.toIntOrNull() == skier.bib.toIntOrNull() } > 1)
                            if (current.roster.none { it.id == skier.id }) TextButton(onClick = {
                                roster = roster.filter { it.id != skier.id }; selected = selected - skier.id
                            }) { Text("Ta bort") }
                        }
                    } }
                    OutlinedButton(onClick = {
                        val id = (roster.maxOfOrNull { it.id } ?: 0) + 1
                        val bib = (roster.mapNotNull { it.bib.toIntOrNull() }.maxOrNull() ?: 0).toLong() + 1
                        roster = roster + SkierDraft(id, "", bib.coerceAtMost(Int.MAX_VALUE.toLong()).toString())
                        selected = selected + id
                    }) { Text("Lägg till åkare") }
                    if (!validRoster) Text("Fyll i namn och unika positiva nummer för alla i åkarlistan.", color = MaterialTheme.colorScheme.error)
                    if (selected.isEmpty()) Text("Välj minst en deltagande åkare.", color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}
