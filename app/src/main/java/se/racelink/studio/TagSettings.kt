package se.racelink.studio

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.res.stringResource
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
        audit = audit + AuditEntry.now(AuditKind.TAGS_UPDATED, "Lag och taggar | $team / $tags -> ${teamName.trim()} / $revised"))
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
                Text(stringResource(R.string.set_settings_title), style = MaterialTheme.typography.headlineSmall)
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.set_settings_info), style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(team, { team = it }, enabled = canEdit, label = { Text(stringResource(R.string.set_team_name)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TagCategory.entries.forEach { item -> FilterChip(selected = category == item, onClick = { categoryName = item.name }, label = { Text(stringResource(R.string.set_category_count, item.label, tags.count { it.category == item })) }) }
                    }
                    Text(if (category == TagCategory.CHANGE) stringResource(R.string.set_change_category_hint)
                        else stringResource(R.string.set_tag_list_hint), style = MaterialTheme.typography.bodySmall)
                    val visible = tags.filter { it.category == category }
                    if (visible.isEmpty()) Text(stringResource(R.string.set_no_tags_in_category))
                    visible.forEach { tag ->
                        Card(Modifier.fillMaxWidth()) {
                            Row(Modifier.padding(horizontal = 12.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(tag.name, Modifier.weight(1f))
                                val editDescription = stringResource(R.string.set_edit_tag_description, tag.name)
                                TextButton(enabled = canEdit, onClick = { editingId = tag.id }, modifier = Modifier.semantics { contentDescription = editDescription }) { Text(stringResource(R.string.set_edit)) }
                            }
                        }
                    }
                    OutlinedButton(enabled = canEdit, onClick = { adding = true }) { Text(stringResource(R.string.set_add_tag)) }
                }
                SaveFeedback(enabled, error)
                if (s.closedAt != null) Text(stringResource(R.string.set_session_closed))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(enabled = enabled, onClick = leave) { Text(stringResource(R.string.set_cancel)) }
                    Button(enabled = canEdit && team.isNotBlank(), onClick = { save(team.trim(), tags) }) { Text(stringResource(R.string.set_save_changes)) }
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
    EditorDialog(onDismissRequest = leave, title = { Text(if (original.name.isBlank()) stringResource(R.string.set_add_tag) else stringResource(R.string.set_edit_tag), style = MaterialTheme.typography.headlineSmall) }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(name, { name = it }, enabled = enabled, label = { Text(stringResource(R.string.set_tag_name)) }, singleLine = true,
                isError = validation != null, supportingText = { validation?.let { Text(it) } }, modifier = Modifier.fillMaxWidth())
            if (original.category != TagCategory.CHANGE) FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(TagCategory.RUN, TagCategory.NEXT_FOCUS).forEach { item -> FilterChip(selected = item == category, enabled = enabled,
                    onClick = { categoryName = item.name }, label = { Text(item.label) }) }
            }
            Text(stringResource(R.string.set_edit_tag_hint), style = MaterialTheme.typography.bodySmall)
            Text(stringResource(R.string.set_editor_flow_hint), style = MaterialTheme.typography.bodySmall)
        }
    }, confirmButton = { TextButton(enabled = enabled && validation == null, onClick = { save(original.copy(name = name.trim(), category = category)) }) { Text(stringResource(R.string.set_done)) } },
        dismissButton = { TextButton(enabled = enabled, onClick = leave) { Text(stringResource(R.string.set_cancel)) } })
}
