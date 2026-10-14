package se.racelink.studio

import android.app.Application
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

class TrainingViewModel(app: Application) : AndroidViewModel(app) {
    private val store = TrainingStore(app)
    val adapter: TimingAdapter = SimulatorAdapter()
    var state by mutableStateOf<Training?>(null); private set
    var busy by mutableStateOf(false); private set
    var error by mutableStateOf<String?>(null)
    init {
        viewModelScope.launch {
            try { state = withContext(Dispatchers.IO) { store.read() ?: demoTraining().also(store::save) } }
            catch (e: Exception) { error = "Kunde inte läsa sparad träning. Data har inte återställts: ${e.message}" }
        }
    }
    fun update(transform: (Training) -> Training) {
        if (busy) return
        val current = state ?: return
        busy = true
        viewModelScope.launch {
            try {
                val next = transform(current)
                withContext(Dispatchers.IO) { store.save(next) }
                state = next
            } catch (e: Exception) { error = "Ändringen sparades inte: ${e.message}" }
            finally { busy = false }
        }
    }
    fun simulate(incomplete: Boolean = false) = update { s ->
        val sequence = (s.attempts.maxOfOrNull { it.sequence } ?: 0) + 1
        s.ingest(adapter.next(sequence, s.athletes[(sequence - 1) % s.athletes.size].bib, incomplete))
    }
    override fun onCleared() { store.close() }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { Studio() }
    }
}

private val Blue = Color(0xFF3266D5)
@Composable fun Studio(vm: TrainingViewModel = viewModel()) {
    val state = vm.state
    val colors = if (state?.dark == true) darkColorScheme(primary = Color(0xFFADC6FF))
        else lightColorScheme(primary = Blue, background = Color(0xFFF3F6FB), surface = Color.White)
    MaterialTheme(colorScheme = colors) {
        Surface(Modifier.fillMaxSize()) {
            if (state == null) Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                Text(vm.error ?: "Läser träningspass…")
            } else Dashboard(state, vm)
        }
    }
}

@Composable private fun Dashboard(s: Training, vm: TrainingViewModel) {
    var selected by rememberSaveable { mutableIntStateOf(s.attempts.maxByOrNull { it.sequence }?.athleteId ?: s.athletes.first().id) }
    var improvement by rememberSaveable { mutableStateOf(false) }
    var editingId by rememberSaveable { mutableStateOf<String?>(null) }
    var settings by rememberSaveable { mutableStateOf(false) }
    var reset by remember { mutableStateOf(false) }
    var exportText by rememberSaveable { mutableStateOf("") }
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    var exportMessage by remember { mutableStateOf<String?>(null) }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        if (uri != null) scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    requireNotNull(context.contentResolver.openOutputStream(uri)).use { it.write(exportText.toByteArray(Charsets.UTF_8)) }
                }
                exportMessage = "CSV sparad. Taggar och anteckningar är inte inkluderade."
            } catch (e: Exception) { exportMessage = "Exporten misslyckades: ${e.message}" }
        }
    }
    val athlete = s.athletes.first { it.id == selected }
    val runs = s.runs(selected)
    val latest = runs.lastOrNull()
    val valid = runs.eligible()
    Column(Modifier.fillMaxSize().safeDrawingPadding().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("RACELINK STUDIO", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
                Text(s.team, style = MaterialTheme.typography.titleMedium)
            }
            TextButton(onClick = { vm.update { it.copy(dark = !it.dark) } }, enabled = !vm.busy) { Text(if (s.dark) "Ljust" else "Mörkt") }
            TextButton(onClick = { settings = true }) { Text("Inställningar") }
        }
        Text("${vm.adapter.label}  •  Träningspass med påhittade namn och tider", color = MaterialTheme.colorScheme.primary)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { vm.simulate() }, enabled = !vm.busy) { Text("Simulera mål") }
            OutlinedButton(onClick = { vm.simulate(true) }, enabled = !vm.busy) { Text("Simulera avbrutet") }
            OutlinedButton(onClick = { vm.update { it.copy(markers = it.markers + Marker(it.attempts.maxOf { a -> a.sequence }, "Ändrad övning")) } }, enabled = !vm.busy) { Text("Markera ändring") }
            OutlinedButton(onClick = { exportText = csv(s); export.launch("RaceLink-demo.csv") }) { Text("Exportera CSV") }
            TextButton(onClick = { reset = true }, enabled = !vm.busy) { Text("Återställ demo") }
        }
        if (vm.error != null) Text(vm.error!!, color = MaterialTheme.colorScheme.error)
        exportMessage?.let { Text(it) }
        if (vm.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (s.pending.isNotEmpty()) {
            val arrived = s.attempts.firstOrNull { it.id == s.pending.last() }
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("${s.pending.size} nya resultat · ${s.athletes.firstOrNull { it.id == arrived?.athleteId }?.name.orEmpty()} · ${arrived?.durationMs?.let { seconds(it) + " s" } ?: "Avbrutet"}", Modifier.weight(1f))
                    TextButton(onClick = {
                        val first = s.attempts.firstOrNull { it.id == s.pending.first() }
                        if (first != null) selected = first.athleteId
                        vm.update { it.copy(pending = it.pending.drop(1)) }
                    }, enabled = !vm.busy) { Text("Visa nästa") }
                }
            }
        }
        BoxWithConstraints(Modifier.weight(1f)) {
            val wide = maxWidth >= 750.dp
            if (wide) Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                AthleteList(s, selected, { selected = it }, Modifier.width(180.dp).fillMaxHeight())
                RunDashboard(s, athlete, runs, latest, valid, improvement, { improvement = it }, { editingId = it }, Modifier.weight(1f))
            } else Column {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    s.athletes.forEach { a -> FilterChip(selected = selected == a.id, onClick = { selected = a.id }, label = { Text("${a.bib} ${a.name}") }) }
                }
                RunDashboard(s, athlete, runs, latest, valid, improvement, { improvement = it }, { editingId = it }, Modifier.fillMaxWidth())
            }
        }
    }
    editingId?.let { id -> s.attempts.find { it.id == id }?.let { run ->
        EditRun(s, run, !vm.busy, { editingId = null }) { a, complete, tags, note ->
            vm.update { it.correct(id, a, complete, tags, note) }; editingId = null
        }
    } }
    if (settings) Settings(s, !vm.busy, { settings = false }) { team, tags ->
        vm.update { it.copy(team = team, tags = tags) }; settings = false
    }
    if (reset) AlertDialog(onDismissRequest = { reset = false }, title = { Text("Återställ demodata?") },
        text = { Text("Alla ändringar, anteckningar och nya demoåk tas bort. Detta påverkar bara den här demoappen.") },
        confirmButton = { TextButton(onClick = { vm.update { demoTraining() }; selected = 1; reset = false }) { Text("Återställ") } },
        dismissButton = { TextButton(onClick = { reset = false }) { Text("Avbryt") } })
}

@Composable private fun AthleteList(s: Training, selected: Int, choose: (Int) -> Unit, modifier: Modifier) {
    Column(modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("DAGENS ÅKARE", style = MaterialTheme.typography.labelLarge)
        s.athletes.forEach { a ->
            Card(onClick = { choose(a.id) }, colors = CardDefaults.cardColors(containerColor =
                if (selected == a.id) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface)) {
                Column(Modifier.fillMaxWidth().padding(16.dp)) {
                    Text("${a.bib}  ${a.name}", fontWeight = FontWeight.Bold)
                    Text("${s.runs(a.id).size} åk", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        Text("Egen utveckling\nIngen ranking", style = MaterialTheme.typography.bodySmall)
        Text("${s.audit.size} sparade ändringar", style = MaterialTheme.typography.bodySmall)
    }
}

@Composable private fun RunDashboard(s: Training, athlete: Athlete, runs: List<Attempt>, latest: Attempt?, valid: List<Attempt>,
    improvement: Boolean, setMode: (Boolean) -> Unit, edit: (String) -> Unit, modifier: Modifier) {
    Column(modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Card {
            Column(Modifier.fillMaxWidth().padding(20.dp)) {
                Text("${athlete.name}  ·  Nummer ${athlete.bib}", style = MaterialTheme.typography.headlineSmall)
                Text(if (latest == null) "Väntar på första åket" else if (!latest.complete) "Avbrutet" else "${seconds(latest.durationMs!!)} s",
                    fontSize = 48.sp, fontWeight = FontWeight.Bold)
                val previous = latest?.let { runs.previous(it) }
                Text(when {
                    latest == null -> "Resultaten visas här efter målgång."
                    !latest.complete -> "Synligt i historiken · ingår inte i jämförelsen"
                    previous == null -> "Första fullföljda åket"
                    else -> "${difference(latest.durationMs!! - previous.durationMs!!)} jämfört med åk ${runs.indexOf(previous) + 1}"
                })
                if (valid.isNotEmpty()) Text("Snabbast ${seconds(valid.minOf { it.durationMs!! })} s" +
                    if (valid.size > 1) "   ·   Långsammast ${seconds(valid.maxOf { it.durationMs!! })} s" else "", Modifier.padding(top = 8.dp))
            }
        }
        Card {
            Column(Modifier.fillMaxWidth().padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Dagens utveckling", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                    TextButton(onClick = { setMode(!improvement) }) { Text(if (improvement) "Visa tider" else "Visa förbättring") }
                }
                Text(if (improvement) "Sekunder vunna från första fullföljda åket · högre är snabbare" else "Tid i sekunder · lägre är snabbare", style = MaterialTheme.typography.bodySmall)
                TrendChart(runs, s.markers, improvement)
                s.markers.forEach { Text("⋮ ${it.label} · efter passets resultat ${it.afterSequence}", style = MaterialTheme.typography.bodySmall) }
            }
        }
        Text("Alla åk · tryck för anteckning eller rättelse", style = MaterialTheme.typography.titleMedium)
        runs.forEachIndexed { index, run ->
            val previous = runs.previous(run)
            val highlight = if (!run.complete || valid.size < 2) "" else when (run.durationMs) {
                valid.minOfOrNull { it.durationMs!! } -> " · Snabbast"
                valid.maxOfOrNull { it.durationMs!! } -> " · Långsammast"
                else -> ""
            }
            Card(Modifier.fillMaxWidth().clickable { edit(run.id) }) {
                Column(Modifier.padding(14.dp)) {
                    Text("Åk ${index + 1}     ${if (run.complete) seconds(run.durationMs!!) + " s" else "Avbrutet"}$highlight", fontWeight = FontWeight.SemiBold)
                    if (run.complete && previous != null) Text("${difference(run.durationMs!! - previous.durationMs!!)} · mot åk ${runs.indexOf(previous) + 1}", style = MaterialTheme.typography.bodySmall)
                    val tags = s.tags.filter { it.id in run.tags }.joinToString(" · ") { it.name }
                    if (tags.isNotBlank()) Text(tags, color = MaterialTheme.colorScheme.primary)
                    if (run.note.isNotBlank()) Text(run.note)
                }
            }
        }
    }
}

@Composable private fun TrendChart(runs: List<Attempt>, markers: List<Marker>, improvement: Boolean) {
    val ink = MaterialTheme.colorScheme.onSurface
    val line = MaterialTheme.colorScheme.primary
    val baseline = runs.eligible().firstOrNull()?.durationMs ?: 0L
    val values = runs.map { if (it.complete && it.durationMs != null) (if (improvement) baseline - it.durationMs else it.durationMs) / 1000f else null }
    Canvas(Modifier.fillMaxWidth().height(230.dp).padding(top = 16.dp)) {
        val left = 52.dp.toPx(); val right = size.width - 18.dp.toPx(); val bottom = size.height - 32.dp.toPx()
        val top = 12.dp.toPx()
        val low = (values.filterNotNull().minOrNull() ?: 0f) - .3f
        val high = (values.filterNotNull().maxOrNull() ?: 1f) + .3f
        fun x(i: Float) = left + (right - left) * i / (runs.size - 1).coerceAtLeast(1)
        fun y(v: Float) = bottom - (v - low) / (high - low) * (bottom - top)
        val paint = android.graphics.Paint().apply { color = if (ink.red > .5f) android.graphics.Color.WHITE else android.graphics.Color.DKGRAY; textSize = 11.sp.toPx(); isAntiAlias = true }
        for (i in 0..3) {
            val value = low + (high - low) * i / 3
            drawLine(ink.copy(alpha = .12f), Offset(left, y(value)), Offset(right, y(value)))
            drawContext.canvas.nativeCanvas.drawText(String.format(Locale.forLanguageTag("sv-SE"), "%.1f", value), 0f, y(value), paint)
        }
        markers.forEach { marker ->
            val index = runs.indexOfLast { it.sequence <= marker.afterSequence }
            if (index >= 0 && index < runs.lastIndex) drawLine(ink.copy(alpha = .4f), Offset(x(index + .5f), top), Offset(x(index + .5f), bottom), 1.dp.toPx())
        }
        values.forEachIndexed { i, value ->
            if (value != null) {
                if (i > 0 && values[i - 1] != null) drawLine(line, Offset(x((i - 1).toFloat()), y(values[i - 1]!!)), Offset(x(i.toFloat()), y(value)), 3.dp.toPx())
                drawCircle(line, 5.dp.toPx(), Offset(x(i.toFloat()), y(value)))
            }
            drawContext.canvas.nativeCanvas.drawText("${i + 1}${if (value == null) " ×" else ""}", x(i.toFloat()) - 4.dp.toPx(), size.height - 8.dp.toPx(), paint)
        }
    }
}

@Composable private fun EditRun(s: Training, run: Attempt, enabled: Boolean, close: () -> Unit,
    save: (Int, Boolean, Set<Int>, String) -> Unit) {
    var athlete by remember(run.id) { mutableIntStateOf(run.athleteId) }
    var complete by remember(run.id) { mutableStateOf(run.complete) }
    var tags by remember(run.id) { mutableStateOf(run.tags) }
    var note by remember(run.id) { mutableStateOf(run.note) }
    AlertDialog(onDismissRequest = close, title = { Text("Anteckning och rättelse") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Tilldela åkare · ändringar sparas med historik")
            s.athletes.forEach { a -> FilterChip(selected = athlete == a.id, onClick = { athlete = a.id }, label = { Text("${a.bib} ${a.name}") }) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = complete, onCheckedChange = { complete = it }, enabled = run.durationMs != null)
                Text("Fullföljt · ingår i jämförelsen")
            }
            s.tags.forEach { tag -> FilterChip(selected = tag.id in tags, onClick = { tags = if (tag.id in tags) tags - tag.id else tags + tag.id }, label = { Text(tag.name) }) }
            OutlinedTextField(note, { note = it }, label = { Text("Anteckning") }, modifier = Modifier.fillMaxWidth())
        }
    }, confirmButton = { TextButton(onClick = { save(athlete, complete, tags, note) }, enabled = enabled) { Text("Spara") } },
        dismissButton = { TextButton(onClick = close) { Text("Avbryt") } })
}

@Composable private fun Settings(s: Training, enabled: Boolean, close: () -> Unit, save: (String, List<CoachingTag>) -> Unit) {
    var team by remember { mutableStateOf(s.team) }
    var tags by remember { mutableStateOf(s.tags) }
    AlertDialog(onDismissRequest = close, title = { Text("Lag och coachningstaggar") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(team, { team = it }, label = { Text("Lagnamn") })
            Text("Namnbyten gäller även tidigare åk. Logotyp kommer i en senare version.")
            tags.forEach { tag -> OutlinedTextField(tag.name, { name -> tags = tags.map { if (it.id == tag.id) it.copy(name = name) else it } }, label = { Text("Tagg") }) }
            TextButton(onClick = { tags = tags + CoachingTag((tags.maxOfOrNull { it.id } ?: 0) + 1, "Ny tagg") }) { Text("Lägg till tagg") }
        }
    }, confirmButton = { TextButton(onClick = { save(team.trim(), tags.map { it.copy(name = it.name.trim()) }) },
        enabled = enabled && team.isNotBlank() && tags.all { it.name.isNotBlank() }) { Text("Spara") } },
        dismissButton = { TextButton(onClick = close) { Text("Avbryt") } })
}

fun csv(s: Training): String {
    fun quote(value: String): String {
        val safe = if (value.trimStart().firstOrNull() in listOf('=', '+', '-', '@')) "'$value" else value
        return "\"${safe.replace("\"", "\"\"")}\""
    }
    return "\uFEFFKälla;Lag;Nummer;Namn;Resultat-ID;Tid (s);Status\r\n" + s.attempts.sortedBy { it.sequence }.joinToString("\r\n") { run ->
        val athlete = s.athletes.first { it.id == run.athleteId }
        listOf("SIMULATOR", s.team, athlete.bib.toString(), athlete.name, run.id,
            if (run.complete) seconds(run.durationMs!!) else "", if (run.complete) "Fullföljt" else "Avbrutet").joinToString(";") { quote(it) }
    }
}
