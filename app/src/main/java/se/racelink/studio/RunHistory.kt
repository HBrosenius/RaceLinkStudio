package se.racelink.studio

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.json.JSONObject

private const val RUN_EDIT = "run-edit-v1 | "
internal data class RunHistoryEntry(val time: String, val before: String?, val after: String?, val legacy: String? = null)

private fun Training.historyValue(run: Attempt): String = buildString {
    val skier = athletes.first { it.id == run.athleteId }
    append("Åkare: ${skier.bib} · ${skier.name}\n")
    append("Status: ${if (run.complete) "Fullföljt" else "DNF · ej fullföljt"}\n")
    append("Sparad tid: ${run.durationMs?.let { seconds(it) + " s" } ?: "Ingen måltid"}\n")
    listOf(TagCategory.RUN, TagCategory.NEXT_FOCUS).forEach { category ->
        append("${category.label}: ${tags.filter { it.id in run.tags && it.category == category }.joinToString { it.name }.ifBlank { "Inga taggar" }}\n")
    }
    append("Anteckning: ${run.note.ifBlank { "Ingen anteckning" }}")
}

internal fun Training.runEditRecord(before: Attempt, after: Attempt): String = RUN_EDIT + JSONObject()
    .put("runId", before.id).put("time", java.time.Instant.now().toString())
    .put("before", historyValue(before)).put("after", historyValue(after)).toString()

internal fun Training.runHistory(id: String): List<RunHistoryEntry> = audit.mapNotNull { entry ->
    if (entry.startsWith(RUN_EDIT)) runCatching {
        val record = JSONObject(entry.removePrefix(RUN_EDIT))
        if (record.getString("runId") == id) RunHistoryEntry(record.getString("time"), record.getString("before"), record.getString("after")) else null
    }.getOrNull()
    else entry.split(" | ", limit = 3).takeIf { it.size == 3 && it[1] == id }?.let {
        RunHistoryEntry(it[0], null, null, it[2])
    }
}

@Composable internal fun RunHistoryButton(s: Training, run: Attempt, label: String = "Ändringshistorik och originalresultat") {
    var open by rememberSaveable(run.id) { mutableStateOf(false) }
    TextButton(onClick = { open = true }) { Text(label) }
    if (open) AppFullScreenDialog({ open = false }) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Åkets ändringshistorik", style = MaterialTheme.typography.headlineSmall)
                Text("${s.athletes.first { it.id == run.athleteId }.name} · ${run.durationMs?.let { seconds(it) + " s" } ?: "Ingen måltid"}")
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    val entries = s.runHistory(run.id)
                    Text("Sparade rättelser · äldst först", style = MaterialTheme.typography.titleMedium)
                    if (entries.isEmpty()) Text("Inga sparade rättelser för detta åk.")
                    entries.forEach { entry -> Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(displayStoredTime(entry.time), style = MaterialTheme.typography.labelMedium)
                            if (entry.legacy != null) {
                                Text("Äldre post: visar den sparade texten. Namn och taggnamn vid ändringen saknas.")
                                Text(entry.legacy)
                            } else {
                                Text("Före", style = MaterialTheme.typography.titleSmall); Text(entry.before.orEmpty())
                                HorizontalDivider()
                                Text("Efter", style = MaterialTheme.typography.titleSmall); Text(entry.after.orEmpty())
                            }
                        }
                    } }
                    Text("Originalresultat och beslut", style = MaterialTheme.typography.titleMedium)
                    val sources = s.timingEvents.filter { it.attemptId == run.id }
                    if (sources.isEmpty()) Text("Detta äldre åk har inga sparade originalhändelser.")
                    sources.forEach { source ->
                        val event = source.observation
                        Text("Nummer ${event.bib} · ${event.durationMs?.let { seconds(it) + " s" } ?: "Ingen måltid"} · ${if (event.complete) "Fullföljt" else "DNF"}")
                        Text("${source.reason}\nHändelse ${event.id} · Ordningsnummer ${event.sequence}")
                        Text(when (source.decision) {
                            TimingDecision.ACCEPTED -> "Automatiskt matchad"
                            TimingDecision.ASSIGNED -> "Tilldelad åkare ${source.athleteId}"
                            TimingDecision.DUPLICATE -> "Dubblett · inget nytt åk skapat"
                            TimingDecision.REJECTED -> "Avvisad"
                            TimingDecision.WAITING -> "Väntar på granskning"
                        })
                        source.decidedAt?.let { Text(it) }
                    }
                    Text("Listan visar rättelser av åkets åkare, status, taggval och anteckning. Gemensamma banändringar och namnändringar ingår inte i denna lista.", style = MaterialTheme.typography.bodySmall)
                }
                TextButton(onClick = { open = false }) { Text("Tillbaka till åket") }
            }
        }
    }
}
