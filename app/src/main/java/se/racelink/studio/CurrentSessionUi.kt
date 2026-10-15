package se.racelink.studio

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@OptIn(ExperimentalLayoutApi::class)
@Composable internal fun LatestResultCard(s: Training, athlete: Athlete, runs: List<Attempt>, latest: Attempt?, readOnly: Boolean,
    enabled: Boolean, tag: (String) -> Unit, setDnf: (String, Boolean) -> Unit) {
    val valid = runs.eligible()
    Card {
        BoxWithConstraints(Modifier.fillMaxWidth().padding(12.dp)) {
            val roomy = maxWidth >= 550.dp && androidx.compose.ui.platform.LocalDensity.current.fontScale < 1.5f
            val identity: @Composable () -> Unit = {
                Column {
                    Text("${athlete.name} · ${athlete.bib}", style = MaterialTheme.typography.titleMedium)
                    Text(if (latest == null) "Väntar på första åket" else if (!latest.complete) "DNF · ej fullföljt" else "${seconds(latest.durationMs!!)} s",
                        fontSize = if (latest == null) 22.sp else 32.sp, lineHeight = if (latest == null) 28.sp else 38.sp, fontWeight = FontWeight.Bold)
                }
            }
            val comparisons: @Composable () -> Unit = {
                Column {
                    val previous = latest?.let { runs.previous(it) }
                    when {
                        latest == null -> Text("Registrera demoresultat via Inställningar → Demoverktyg → Simulator.", style = MaterialTheme.typography.bodySmall)
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
                    OutlinedButton(enabled = enabled, onClick = { tag(latest.id) }) { Text("Tagga senaste åket") }
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
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable internal fun CompactRunRow(s: Training, runs: List<Attempt>, item: ChartRun, open: () -> Unit) {
    val run = item.attempt
    val previous = runs.previous(run)
    val best = runs.eligible().minOfOrNull { it.durationMs!! }
    val status = if (run.complete) "${seconds(run.durationMs!!)} s" else "DNF · ej fullföljt"
    val selected = listOf(TagCategory.RUN, TagCategory.NEXT_FOCUS).mapNotNull { category ->
        s.tagDescription(run, category).takeIf { it.isNotBlank() }?.let { "${category.label}: $it" }
    }.joinToString(" · ")
    val changes = s.changesFor(run)
    Card(Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(onClickLabel = "Visa åk ${item.number}", onClick = open)
        .semantics(mergeDescendants = true) { contentDescription = "${s.athletes.first { it.id == run.athleteId }.name}, åk ${item.number}, $status. $selected${if (run.note.isNotBlank()) ". Har anteckning" else ""}" }) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Åk ${item.number} · $status${if (run.complete && run.durationMs == best) " · ★ Bästa" else ""}", fontWeight = FontWeight.SemiBold)
                if (run.complete && previous != null) Text("${difference(run.durationMs!! - previous.durationMs!!)} · mot åk ${runs.indexOf(previous) + 1}", style = MaterialTheme.typography.bodySmall)
            }
            if (selected.isNotBlank()) Text(selected, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (run.note.isNotBlank() || changes.isNotEmpty()) Text(listOfNotNull(if (run.note.isNotBlank()) "Anteckning" else null,
                if (changes.isNotEmpty()) "${changes.size} ändringar gäller" else null).joinToString(" · "), style = MaterialTheme.typography.labelSmall)
        }
    }
}

internal fun findSkiers(athletes: List<Athlete>, query: String): List<Athlete> = athletes.filter {
    query.isBlank() || it.name.contains(query.trim(), ignoreCase = true) || it.bib.toString().contains(query.trim())
}

@Composable internal fun SkierPicker(athletes: List<Athlete>, selected: Int, close: () -> Unit, participants: Set<Int>? = null, choose: (Int) -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    var participantsOnly by rememberSaveable { mutableStateOf(false) }
    AlertDialog(onDismissRequest = close, title = { Text("Välj åkare") }, text = {
        Column {
            OutlinedTextField(query, { query = it }, label = { Text("Sök namn eller nummer") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            if (participants != null) FilterChip(selected = participantsOnly, onClick = { participantsOnly = !participantsOnly }, label = { Text("Bara deltagare i passet") })
            Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
                val matches = findSkiers(if (participantsOnly && participants != null) athletes.filter { it.id in participants } else athletes, query)
                if (matches.isEmpty()) Text("Inga åkare matchar sökningen.")
                matches.forEach { athlete -> TextButton(onClick = { choose(athlete.id); close() }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    Text("${if (athlete.id == selected) "✓ " else ""}${athlete.bib} · ${athlete.name}")
                } }
            }
        }
    }, confirmButton = { TextButton(onClick = close) { Text("Stäng") } })
}
