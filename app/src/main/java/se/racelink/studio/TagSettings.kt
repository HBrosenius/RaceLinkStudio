package se.racelink.studio

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics

internal fun tagNameError(name: String, category: TagCategory, tags: List<CoachingTag>, id: Int): String? = when {
    name.isBlank() -> "Ange ett namn."
    tags.any { it.id != id && it.category == category && it.name.trim().equals(name.trim(), ignoreCase = true) } -> "Namnet finns redan i kategorin."
    else -> null
}

internal fun Training.updateTagSettings(teamName: String, updated: List<CoachingTag>): Training {
    require(closedAt == null) { "Passet är avslutat" }
    require(teamName.isNotBlank()) { "Ange ett lagnamn" }
    require(updated.map { it.id }.distinct().size == updated.size)
    require(updated.all { tagNameError(it.name, it.category, updated, it.id) == null }) { "Taggnamn måste vara unika inom varje kategori" }
    require(tags.all { old -> updated.any { it.id == old.id && (it.category == TagCategory.CHANGE) == (old.category == TagCategory.CHANGE) } })
    val revised = updated.map { it.copy(name = it.name.trim()) }
    if (team == teamName.trim() && tags == revised) return this
    return copy(team = teamName.trim(), tags = revised,
        audit = audit + "${java.time.Instant.now()} | Lag och taggar | $team / $tags -> ${teamName.trim()} / $revised")
}

@OptIn(ExperimentalLayoutApi::class)
@Composable internal fun Settings(s: Training, enabled: Boolean, close: () -> Unit, error: String? = null, save: (String, List<CoachingTag>) -> Unit) {
    var team by rememberSaveable { mutableStateOf(s.team) }
    var tags by rememberSaveable(stateSaver = TagListSaver) { mutableStateOf(s.tags) }
    var categoryName by rememberSaveable { mutableStateOf(TagCategory.RUN.name) }
    var editingId by rememberSaveable { mutableStateOf<Int?>(null) }
    var adding by rememberSaveable { mutableStateOf(false) }
    val category = TagCategory.valueOf(categoryName)
    val canEdit = enabled && s.closedAt == null
    val leave = draftClose(team != s.team || tags != s.tags, enabled, close)
    AppFullScreenDialog(leave) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Lag och coachningstaggar", style = MaterialTheme.typography.headlineSmall)
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Namn och kategorier ändras för alla åk i aktuellt pass, även tidigare åk. Sparade pass behåller sina uppgifter.", style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(team, { team = it }, enabled = canEdit, label = { Text("Lagnamn") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TagCategory.entries.forEach { item -> FilterChip(selected = category == item, onClick = { categoryName = item.name }, label = { Text("${item.label} (${tags.count { it.category == item }})") }) }
                    }
                    Text(if (category == TagCategory.CHANGE) "Beskriver en ändring av bana, underlag eller övning. Ändringstaggar behåller sin kategori."
                        else "Flera taggar kan väljas på varje åk. Redigera en tagg för att byta namn eller kategori.", style = MaterialTheme.typography.bodySmall)
                    val visible = tags.filter { it.category == category }
                    if (visible.isEmpty()) Text("Inga taggar i kategorin ännu.")
                    visible.forEach { tag ->
                        Card(Modifier.fillMaxWidth()) {
                            Row(Modifier.padding(horizontal = 12.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(tag.name, Modifier.weight(1f))
                                TextButton(enabled = canEdit, onClick = { editingId = tag.id }, modifier = Modifier.semantics { contentDescription = "Redigera taggen ${tag.name}" }) { Text("Redigera") }
                            }
                        }
                    }
                    OutlinedButton(enabled = canEdit, onClick = { adding = true }) { Text("Lägg till tagg") }
                }
                SaveFeedback(enabled, error)
                if (s.closedAt != null) Text("Passet är avslutat och skrivskyddat.")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(enabled = enabled, onClick = leave) { Text("Avbryt") }
                    Button(enabled = canEdit && team.isNotBlank(), onClick = { save(team.trim(), tags) }) { Text("Spara ändringar") }
                }
            }
        }
    }
    val original = if (adding) CoachingTag((tags.maxOfOrNull { it.id } ?: 0) + 1, "", category) else tags.find { it.id == editingId }
    original?.let { tag ->
        TagEditor(tag, tags, canEdit, { adding = false; editingId = null }) { updated ->
            tags = if (adding) tags + updated else tags.map { if (it.id == updated.id) updated else it }
            categoryName = updated.category.name
            adding = false; editingId = null
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable private fun TagEditor(original: CoachingTag, tags: List<CoachingTag>, enabled: Boolean, close: () -> Unit, save: (CoachingTag) -> Unit) {
    var name by rememberSaveable(original.id) { mutableStateOf(original.name) }
    var categoryName by rememberSaveable(original.id) { mutableStateOf(original.category.name) }
    val category = TagCategory.valueOf(categoryName)
    val validation = tagNameError(name, category, tags, original.id)
    val leave = draftClose(name != original.name || category != original.category, enabled, close)
    EditorDialog(onDismissRequest = leave, title = { Text(if (original.name.isBlank()) "Lägg till tagg" else "Redigera tagg", style = MaterialTheme.typography.headlineSmall) }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(name, { name = it }, enabled = enabled, label = { Text("Taggnamn") }, singleLine = true,
                isError = validation != null, supportingText = { validation?.let { Text(it) } }, modifier = Modifier.fillMaxWidth())
            if (original.category != TagCategory.CHANGE) FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(TagCategory.RUN, TagCategory.NEXT_FOCUS).forEach { item -> FilterChip(selected = item == category, enabled = enabled,
                    onClick = { categoryName = item.name }, label = { Text(item.label) }) }
            }
            Text("Ändringen gäller även tidigare åk i aktuellt pass. Åkens val behålls när taggen döps om eller flyttas.", style = MaterialTheme.typography.bodySmall)
            Text("Klar uppdaterar utkastet. Spara ändringar sparar hela listan.", style = MaterialTheme.typography.bodySmall)
        }
    }, confirmButton = { TextButton(enabled = enabled && validation == null, onClick = { save(original.copy(name = name.trim(), category = category)) }) { Text("Klar") } },
        dismissButton = { TextButton(enabled = enabled, onClick = leave) { Text("Avbryt") } })
}
