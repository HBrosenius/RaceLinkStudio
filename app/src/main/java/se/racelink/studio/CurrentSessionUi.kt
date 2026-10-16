package se.racelink.studio

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

internal data class RunNoteUi(val text: String, val dirty: Boolean, val enabled: Boolean, val error: String?,
    val change: (String) -> Unit, val save: () -> Unit, val cancel: () -> Unit)

@OptIn(ExperimentalLayoutApi::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable internal fun LatestResultCard(s: Training, athlete: Athlete, runs: List<Attempt>, latest: Attempt?, readOnly: Boolean,
    enabled: Boolean, toggleTag: (String, Int) -> Unit, setDnf: (String, Boolean) -> Unit,
    showLatest: () -> Unit, edit: (String) -> Unit, followingLatest: Boolean,
    selectRun: (String) -> Unit, editNote: (String) -> Unit, noteEditor: RunNoteUi? = null, personalServerUrl: String? = null, personalPublishingEnabled: Boolean = true) {
    val valid = runs.eligible()
    val noTagsLabel = stringResource(R.string.dash_no_tags)
    var categoryName by rememberSaveable(s.sessionId, athlete.id) { mutableStateOf<String?>(null) }
    val isLatest = latest?.id == runs.lastOrNull()?.id
    val noteView = remember { BringIntoViewRequester() }
    var noteFocused by remember { mutableStateOf(false) }
    val keyboardBottom = WindowInsets.ime.getBottom(LocalDensity.current)
    LaunchedEffect(noteFocused, keyboardBottom) { if (noteFocused) noteView.bringIntoView() }
    val cardShape = MaterialTheme.shapes.medium
    val currentResult = latest != null && isLatest && !readOnly
    Card(shape = cardShape, modifier = Modifier.fillMaxWidth().padding(4.dp).drawWithContent {
        drawContent()
        if (currentResult) {
            val outline = cardShape.createOutline(size, layoutDirection, this)
            val blue = Color(0xFF559DE8)
            // Layered translucent strokes soften the edge without blurring card text.
            for (layer in 8 downTo 1) drawOutline(outline, blue.copy(alpha = (9 - layer) * if (s.dark) .024f else .020f),
                style = Stroke(layer.dp.toPx()))
        }
    }) {
        BoxWithConstraints(Modifier.fillMaxWidth().padding(12.dp)) {
            val roomy = maxWidth >= 550.dp && androidx.compose.ui.platform.LocalDensity.current.fontScale < 1.5f
            val identity: @Composable () -> Unit = {
                Column {
                    Text("${athlete.name} · ${athlete.bib}", style = MaterialTheme.typography.titleMedium)
                    if (latest != null) {
                        val index = runs.indexOfFirst { it.id == latest.id }
                        Text(stringResource(R.string.dash_run_of_count, index + 1, runs.size,
                                if (followingLatest) stringResource(R.string.dash_following_latest) else stringResource(R.string.dash_selected_run)) +
                            if (!followingLatest && !isLatest) " · " + stringResource(R.string.dash_newer_runs_count, runs.lastIndex - index) else "", style = MaterialTheme.typography.labelLarge)
                    }
                    Text(if (latest == null) stringResource(R.string.dash_waiting_first_run) else if (!latest.complete) stringResource(R.string.dash_dnf_not_completed) else "${seconds(latest.durationMs!!)} s",
                        fontSize = if (latest == null) 22.sp else 32.sp, lineHeight = if (latest == null) 28.sp else 38.sp, fontWeight = FontWeight.Bold)
                }
            }
            val comparisons: @Composable () -> Unit = {
                Column {
                    val previous = latest?.let { runs.previous(it) }
                    when {
                        latest == null -> Text(if (readOnly) stringResource(R.string.dash_no_runs_for_athlete) else stringResource(R.string.dash_no_results_yet), style = MaterialTheme.typography.bodySmall)
                        !latest.complete -> Text(stringResource(R.string.dash_time_not_in_trend), style = MaterialTheme.typography.bodySmall)
                        else -> {
                            if (previous == null) Text(stringResource(R.string.dash_first_completed_run), style = MaterialTheme.typography.bodySmall)
                            else TimeComparison(stringResource(R.string.dash_previous_completed_run, runs.indexOf(previous) + 1), latest.durationMs!! - previous.durationMs!!, s.dark)
                            valid.minByOrNull { it.durationMs!! }?.let { best ->
                                TimeComparison(stringResource(R.string.dash_best_of_session, runs.indexOf(best) + 1), latest.durationMs!! - best.durationMs!!, s.dark, latest.durationMs == best.durationMs)
                            }
                        }
                    }
                }
            }
            val actions: @Composable () -> Unit = {
                if (!readOnly && latest != null) FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = !latest.complete, enabled = enabled && (latest.complete || latest.durationMs != null),
                        onClick = { setDnf(latest.id, latest.complete) },
                        label = { Text(if (latest.complete) stringResource(R.string.dash_mark_dnf) else if (latest.durationMs != null) stringResource(R.string.dash_restore_completed) else stringResource(R.string.dash_dnf_no_finish)) })
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (roomy) Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) { identity(); actions() }
                    Box(Modifier.weight(1.3f)) { comparisons() }
                    personalServerUrl?.takeIf { it.isNotBlank() }?.let { SkierQrCode(s.sessionId, athlete, it, personalPublishingEnabled) }
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.weight(1f)) { identity() }
                        personalServerUrl?.takeIf { it.isNotBlank() }?.let { SkierQrCode(s.sessionId, athlete, it, personalPublishingEnabled) }
                    }
                    comparisons(); actions()
                }
                if (latest != null) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(TagCategory.RUN, TagCategory.NEXT_FOCUS).forEach { category ->
                            val selectedTags = s.tags.filter { it.category == category && it.id in latest.tags }
                            FilterChip(selected = categoryName == category.name, onClick = { categoryName = if (categoryName == category.name) null else category.name },
                                label = { Text(stringResource(R.string.dash_label_count, category.label, selectedTags.size)) })
                        }
                        if (!followingLatest) TextButton(onClick = showLatest) { Text(stringResource(R.string.dash_follow_latest_run)) }
                    }
                    listOf(TagCategory.RUN, TagCategory.NEXT_FOCUS).forEach { category ->
                        if (categoryName == category.name) {
                            val options = s.tags.filter { it.category == category }
                            if (readOnly) Text(s.tagDescription(latest, category).ifBlank { noTagsLabel })
                            else {
                                Text(stringResource(R.string.dash_select_multiple_saved, runs.indexOfFirst { it.id == latest.id } + 1), style = MaterialTheme.typography.bodySmall)
                                if (options.isEmpty()) Text(stringResource(R.string.dash_no_tags_in_category))
                                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    options.forEach { tag -> FilterChip(selected = tag.id in latest.tags, enabled = enabled,
                                        onClick = { toggleTag(latest.id, tag.id) }, label = { Text(tag.name) }) }
                                }
                            }
                        }
                    }
                    s.tagDescription(latest, TagCategory.CHANGE).takeIf { it.isNotBlank() }?.let { Text(stringResource(R.string.dash_change_applies_to_run, it), style = MaterialTheme.typography.bodySmall) }
                    if (noteEditor != null) {
                        Column(Modifier.bringIntoViewRequester(noteView), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        OutlinedTextField(noteEditor.text, noteEditor.change, enabled = noteEditor.enabled,
                            label = { Text(stringResource(R.string.dash_note_for_run, runs.indexOfFirst { it.id == latest.id } + 1)) }, minLines = 2, maxLines = 4,
                            modifier = Modifier.fillMaxWidth().onFocusChanged { noteFocused = it.isFocused })
                        SaveFeedback(noteEditor.enabled, noteEditor.error)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(enabled = noteEditor.enabled, onClick = noteEditor.cancel) { Text(stringResource(R.string.dash_cancel_note)) }
                            Button(enabled = noteEditor.enabled && noteEditor.dirty, onClick = noteEditor.save) { Text(stringResource(R.string.dash_save_note)) }
                        }
                        }
                    } else if (latest.note.isNotBlank()) Text(stringResource(R.string.dash_note_prefix, latest.note), style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        val previous = adjacentCardRun(runs, latest.id, -1)
                        val next = adjacentCardRun(runs, latest.id, 1)
                        TextButton(enabled = previous != null, onClick = { previous?.let { selectRun(it.id) } }) { Text(stringResource(R.string.dash_previous_run)) }
                        TextButton(enabled = next != null, onClick = { next?.let { selectRun(it.id) } }) { Text(stringResource(R.string.dash_next_run)) }
                        if (!readOnly && noteEditor == null) TextButton(enabled = enabled, onClick = { editNote(latest.id) }) { Text(if (latest.note.isBlank()) stringResource(R.string.dash_add_note) else stringResource(R.string.dash_edit_note)) }
                        if (!readOnly) TextButton(enabled = enabled, onClick = { edit(latest.id) }) { Text(stringResource(R.string.dash_correct_athlete)) }
                        RunHistoryButton(s, latest, stringResource(R.string.dash_history))
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable internal fun CompactRunRow(s: Training, runs: List<Attempt>, item: ChartRun, selected: Boolean = false, open: () -> Unit) {
    val run = item.attempt
    val previous = runs.previous(run)
    val best = runs.eligible().minOfOrNull { it.durationMs!! }
    val status = if (run.complete) "${seconds(run.durationMs!!)} s" else stringResource(R.string.dash_dnf_not_completed)
    val tagSummary = listOf(TagCategory.RUN, TagCategory.NEXT_FOCUS).mapNotNull { category ->
        s.tagDescription(run, category).takeIf { it.isNotBlank() }?.let { stringResource(R.string.dash_label_colon_value, category.label, it) }
    }.joinToString(" · ")
    val changes = s.changesFor(run)
    Card(Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(onClickLabel = stringResource(R.string.dash_show_run_in_timecard, item.number), onClick = open)
        .semantics(mergeDescendants = true) { stateDescription = if (selected) "Visas i tidskortet" else ""; contentDescription = "${s.athletes.first { it.id == run.athleteId }.name}, åk ${item.number}, $status. ${s.tagDescription(run, TagCategory.RUN)}. ${s.tagDescription(run, TagCategory.NEXT_FOCUS)}${if (run.complete && run.durationMs == best) ". Passets bästa" else ""}${if (changes.isNotEmpty()) ". ${changes.size} ändringar gäller" else ""}${if (run.note.isNotBlank()) ". Har anteckning" else ""}" },
        colors = CardDefaults.cardColors(containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.dash_run_prefix, item.number, status) + if (run.complete && run.durationMs == best) stringResource(R.string.dash_best_suffix) else "", fontWeight = FontWeight.SemiBold)
                if (run.complete && previous != null) Text(stringResource(R.string.dash_against_run, difference(run.durationMs!! - previous.durationMs!!), runs.indexOf(previous) + 1), style = MaterialTheme.typography.bodySmall)
            }
            if (tagSummary.isNotBlank()) Text(tagSummary, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (run.note.isNotBlank() || changes.isNotEmpty()) Text(listOfNotNull(if (run.note.isNotBlank()) stringResource(R.string.dash_note) else null,
                if (changes.isNotEmpty()) stringResource(R.string.dash_changes_apply_count, changes.size) else null).joinToString(" · "), style = MaterialTheme.typography.labelSmall)
        }
    }
}

internal fun findSkiers(athletes: List<Athlete>, query: String): List<Athlete> = athletes.filter {
    query.isBlank() || it.name.contains(query.trim(), ignoreCase = true) || it.bib.toString().contains(query.trim())
}

@Composable internal fun SkierPicker(athletes: List<Athlete>, selected: Int, close: () -> Unit, participants: Set<Int>? = null,
    closeOnChoose: Boolean = true, enabled: Boolean = true, error: String? = null, title: String = "Välj åkare", choose: (Int) -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    var participantsOnly by rememberSaveable { mutableStateOf(false) }
    AppFullScreenDialog(close) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.headlineSmall)
            OutlinedTextField(query, { query = it }, enabled = enabled, label = { Text(stringResource(R.string.dash_search_name_or_number)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
            if (participants != null) FilterChip(selected = participantsOnly, enabled = enabled, onClick = { participantsOnly = !participantsOnly }, label = { Text(stringResource(R.string.dash_only_session_participants)) })
            val matches = findSkiers(if (participantsOnly && participants != null) athletes.filter { it.id in participants } else athletes, query)
            LazyColumn(Modifier.weight(1f)) {
                if (matches.isEmpty()) item { Text(stringResource(R.string.dash_no_athletes_match)) }
                items(matches, key = { it.id }) { athlete -> TextButton(enabled = enabled, onClick = { choose(athlete.id); if (closeOnChoose) close() }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    Text("${if (athlete.id == selected) "✓ " else ""}${athlete.bib} · ${athlete.name}")
                } }
            }
            SaveFeedback(enabled, error)
            TextButton(enabled = enabled, onClick = close) { Text(stringResource(R.string.dash_close)) }
        }
        }
    }
}

@Composable internal fun TimeComparison(label: String, deltaMs: Long, dark: Boolean, best: Boolean = false) {
    val positive = if (dark) Color(0xFF81C784) else Color(0xFF256B35)
    val negative = if (dark) Color(0xFFFFB4AB) else Color(0xFFAC2925)
    val color = when { best || deltaMs < 0 -> positive; deltaMs > 0 -> negative; else -> MaterialTheme.colorScheme.onSurfaceVariant }
    val symbol = when { best -> "★"; deltaMs < 0 -> "↓"; deltaMs > 0 -> "↑"; else -> "=" }
    val text = if (best) stringResource(R.string.dash_best_time) else difference(deltaMs)
    Surface(color = color.copy(alpha = .10f), shape = MaterialTheme.shapes.small, modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Row(Modifier.padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(symbol, color = color, fontSize = 23.sp, fontWeight = FontWeight.Bold)
            Column {
                Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(text, color = color, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}
