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
    selectRun: (String) -> Unit, editNote: (String) -> Unit, noteEditor: RunNoteUi? = null) {
    val valid = runs.eligible()
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
                        Text("Åk ${index + 1} av ${runs.size} · ${if (followingLatest) "Följer senaste" else "Valt åk"}" +
                            if (!followingLatest && !isLatest) " · ${runs.lastIndex - index} nyare åk" else "", style = MaterialTheme.typography.labelLarge)
                    }
                    Text(if (latest == null) "Väntar på första åket" else if (!latest.complete) "DNF · ej fullföljt" else "${seconds(latest.durationMs!!)} s",
                        fontSize = if (latest == null) 22.sp else 32.sp, lineHeight = if (latest == null) 28.sp else 38.sp, fontWeight = FontWeight.Bold)
                }
            }
            val comparisons: @Composable () -> Unit = {
                Column {
                    val previous = latest?.let { runs.previous(it) }
                    when {
                        latest == null -> Text(if (readOnly) "Inga åk för den här åkaren i passet." else "Den här åkaren har inga resultat ännu. Välj en åkare med resultat i åkarlistan, eller använd Visa nästa i resultatmeddelandet.", style = MaterialTheme.typography.bodySmall)
                        !latest.complete -> Text("Tiden ingår inte i trend eller jämförelser.", style = MaterialTheme.typography.bodySmall)
                        else -> {
                            if (previous == null) Text("Första fullföljda åket", style = MaterialTheme.typography.bodySmall)
                            else TimeComparison("Föregående fullföljda · åk ${runs.indexOf(previous) + 1}", latest.durationMs!! - previous.durationMs!!, s.dark)
                            valid.minByOrNull { it.durationMs!! }?.let { best ->
                                TimeComparison("Passets bästa · åk ${runs.indexOf(best) + 1}", latest.durationMs!! - best.durationMs!!, s.dark, latest.durationMs == best.durationMs)
                            }
                        }
                    }
                }
            }
            val actions: @Composable () -> Unit = {
                if (!readOnly && latest != null) FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = !latest.complete, enabled = enabled && (latest.complete || latest.durationMs != null),
                        onClick = { setDnf(latest.id, latest.complete) },
                        label = { Text(if (latest.complete) "Markera DNF" else if (latest.durationMs != null) "Återställ fullföljt" else "DNF · ingen måltid") })
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (roomy) Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) { identity(); actions() }
                    Box(Modifier.weight(1.3f)) { comparisons() }
                } else { identity(); comparisons(); actions() }
                if (latest != null) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(TagCategory.RUN, TagCategory.NEXT_FOCUS).forEach { category ->
                            val selectedTags = s.tags.filter { it.category == category && it.id in latest.tags }
                            FilterChip(selected = categoryName == category.name, onClick = { categoryName = if (categoryName == category.name) null else category.name },
                                label = { Text("${category.label} (${selectedTags.size})") })
                        }
                        if (!followingLatest) TextButton(onClick = showLatest) { Text("Följ senaste åket") }
                    }
                    listOf(TagCategory.RUN, TagCategory.NEXT_FOCUS).forEach { category ->
                        if (categoryName == category.name) {
                            val options = s.tags.filter { it.category == category }
                            if (readOnly) Text(s.tagDescription(latest, category).ifBlank { "Inga taggar" })
                            else {
                                Text("Välj flera · sparas direkt på åk ${runs.indexOfFirst { it.id == latest.id } + 1}", style = MaterialTheme.typography.bodySmall)
                                if (options.isEmpty()) Text("Inga taggar i kategorin. Lägg till i Inställningar.")
                                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    options.forEach { tag -> FilterChip(selected = tag.id in latest.tags, enabled = enabled,
                                        onClick = { toggleTag(latest.id, tag.id) }, label = { Text(tag.name) }) }
                                }
                            }
                        }
                    }
                    s.tagDescription(latest, TagCategory.CHANGE).takeIf { it.isNotBlank() }?.let { Text("Ändring som gäller åket: $it", style = MaterialTheme.typography.bodySmall) }
                    if (noteEditor != null) {
                        Column(Modifier.bringIntoViewRequester(noteView), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        OutlinedTextField(noteEditor.text, noteEditor.change, enabled = noteEditor.enabled,
                            label = { Text("Anteckning för åk ${runs.indexOfFirst { it.id == latest.id } + 1}") }, minLines = 2, maxLines = 4,
                            modifier = Modifier.fillMaxWidth().onFocusChanged { noteFocused = it.isFocused })
                        SaveFeedback(noteEditor.enabled, noteEditor.error)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(enabled = noteEditor.enabled, onClick = noteEditor.cancel) { Text("Avbryt anteckning") }
                            Button(enabled = noteEditor.enabled && noteEditor.dirty, onClick = noteEditor.save) { Text("Spara anteckning") }
                        }
                        }
                    } else if (latest.note.isNotBlank()) Text("Anteckning: ${latest.note}", style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        val previous = adjacentCardRun(runs, latest.id, -1)
                        val next = adjacentCardRun(runs, latest.id, 1)
                        TextButton(enabled = previous != null, onClick = { previous?.let { selectRun(it.id) } }) { Text("← Föregående åk") }
                        TextButton(enabled = next != null, onClick = { next?.let { selectRun(it.id) } }) { Text("Nästa åk →") }
                        if (!readOnly && noteEditor == null) TextButton(enabled = enabled, onClick = { editNote(latest.id) }) { Text(if (latest.note.isBlank()) "Lägg till anteckning" else "Redigera anteckning") }
                        if (!readOnly) TextButton(enabled = enabled, onClick = { edit(latest.id) }) { Text("Rätta åkare") }
                        RunHistoryButton(s, latest, "Historik")
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
    val status = if (run.complete) "${seconds(run.durationMs!!)} s" else "DNF · ej fullföljt"
    val tagSummary = listOf(TagCategory.RUN, TagCategory.NEXT_FOCUS).mapNotNull { category ->
        s.tagDescription(run, category).takeIf { it.isNotBlank() }?.let { "${category.label}: $it" }
    }.joinToString(" · ")
    val changes = s.changesFor(run)
    Card(Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(onClickLabel = "Visa åk ${item.number} i tidskortet", onClick = open)
        .semantics(mergeDescendants = true) { stateDescription = if (selected) "Visas i tidskortet" else ""; contentDescription = "${s.athletes.first { it.id == run.athleteId }.name}, åk ${item.number}, $status. ${s.tagDescription(run, TagCategory.RUN)}. ${s.tagDescription(run, TagCategory.NEXT_FOCUS)}${if (run.complete && run.durationMs == best) ". Passets bästa" else ""}${if (changes.isNotEmpty()) ". ${changes.size} ändringar gäller" else ""}${if (run.note.isNotBlank()) ". Har anteckning" else ""}" },
        colors = CardDefaults.cardColors(containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Åk ${item.number} · $status${if (run.complete && run.durationMs == best) " · ★ Bästa" else ""}", fontWeight = FontWeight.SemiBold)
                if (run.complete && previous != null) Text("${difference(run.durationMs!! - previous.durationMs!!)} · mot åk ${runs.indexOf(previous) + 1}", style = MaterialTheme.typography.bodySmall)
            }
            if (tagSummary.isNotBlank()) Text(tagSummary, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (run.note.isNotBlank() || changes.isNotEmpty()) Text(listOfNotNull(if (run.note.isNotBlank()) "Anteckning" else null,
                if (changes.isNotEmpty()) "${changes.size} ändringar gäller" else null).joinToString(" · "), style = MaterialTheme.typography.labelSmall)
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
            OutlinedTextField(query, { query = it }, enabled = enabled, label = { Text("Sök namn eller nummer") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            if (participants != null) FilterChip(selected = participantsOnly, enabled = enabled, onClick = { participantsOnly = !participantsOnly }, label = { Text("Bara deltagare i passet") })
            val matches = findSkiers(if (participantsOnly && participants != null) athletes.filter { it.id in participants } else athletes, query)
            LazyColumn(Modifier.weight(1f)) {
                if (matches.isEmpty()) item { Text("Inga åkare matchar sökningen.") }
                items(matches, key = { it.id }) { athlete -> TextButton(enabled = enabled, onClick = { choose(athlete.id); if (closeOnChoose) close() }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    Text("${if (athlete.id == selected) "✓ " else ""}${athlete.bib} · ${athlete.name}")
                } }
            }
            SaveFeedback(enabled, error)
            TextButton(enabled = enabled, onClick = close) { Text("Stäng") }
        }
        }
    }
}
