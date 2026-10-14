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
    var backupOpen by mutableStateOf(false)
    var backupBusy by mutableStateOf(false); private set
    var backupMessage by mutableStateOf<String?>(null)
    var pendingRestore by mutableStateOf<Training?>(null); private set
    var recoveryAvailable by mutableStateOf(false); private set
    var revision by mutableIntStateOf(0); private set
    var backupToWrite: String? = null
    fun prepareBackup(ready: () -> Unit) {
        if (busy || backupBusy) return
        val snapshot = state ?: return
        backupBusy = true; backupMessage = null
        viewModelScope.launch {
            try {
                backupToWrite = withContext(Dispatchers.IO) { encodeBackup(snapshot) }
                ready()
            } catch (e: Exception) { backupMessage = "Säkerhetskopian kunde inte skapas: ${e.message}" }
            finally { backupBusy = false }
        }
    }
    fun writeBackup(uri: android.net.Uri) {
        val text = backupToWrite ?: run { backupMessage = "Skapa säkerhetskopian igen; exporten avbröts."; return }
        backupBusy = true
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    requireNotNull(getApplication<Application>().contentResolver.openOutputStream(uri)).use { it.write(text.toByteArray(Charsets.UTF_8)) }
                }
                backupMessage = "Säkerhetskopian sparades."
            } catch (e: Exception) { backupMessage = "Säkerhetskopian kunde inte sparas: ${e.message}" }
            finally { backupBusy = false; backupToWrite = null }
        }
    }
    fun inspectBackup(uri: android.net.Uri) {
        if (busy || backupBusy) return
        pendingRestore = null; backupMessage = null; backupBusy = true
        viewModelScope.launch {
            try {
                pendingRestore = withContext(Dispatchers.IO) {
                    requireNotNull(getApplication<Application>().contentResolver.openInputStream(uri)).use(::readBackup)
                }
            } catch (e: Exception) { backupMessage = "Filen kunde inte läsas: ${e.message}. Sparad träning har inte ändrats." }
            finally { backupBusy = false }
        }
    }
    fun refreshRecovery() {
        viewModelScope.launch {
            recoveryAvailable = runCatching { withContext(Dispatchers.IO) { store.recovery() != null } }.getOrDefault(false)
        }
    }
    fun inspectRecovery() {
        if (busy || backupBusy) return
        backupBusy = true; backupMessage = null
        viewModelScope.launch {
            try { pendingRestore = withContext(Dispatchers.IO) { requireNotNull(store.recovery()).also(::validateBackupState) } }
            catch (e: Exception) { backupMessage = "Återställningspunkten kunde inte läsas: ${e.message}" }
            finally { backupBusy = false }
        }
    }
    fun cancelRestore() { pendingRestore = null }
    fun confirmRestore() {
        if (busy || backupBusy) return
        val next = pendingRestore ?: return
        val previous = state
        busy = true
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { store.restore(previous, next) }
                state = next; revision++; pendingRestore = null; error = null
                recoveryAvailable = previous != null || recoveryAvailable
                backupMessage = "Träningen har återställts."
            } catch (e: Exception) { backupMessage = "Återställningen misslyckades: ${e.message}. Sparad träning har inte ändrats." }
            finally { busy = false }
        }
    }
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
    fun simulate(incomplete: Boolean = false) = update { it.simulateNext(adapter, incomplete) }
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
                Column {
                    Text(vm.error ?: "Läser träningspass…")
                    if (vm.error != null) TextButton(onClick = { vm.backupOpen = true }) { Text("Återställ från säkerhetskopia") }
                }
            } else key(vm.revision) { Dashboard(state, vm) }
        }
        if (vm.backupOpen) BackupDialog(vm) { vm.backupOpen = false }
    }
}

@Composable private fun Dashboard(s: Training, vm: TrainingViewModel) {
    var selected by rememberSaveable(s.sessionId) { mutableIntStateOf(s.attempts.maxByOrNull { it.sequence }?.athleteId ?: s.athletes.first().id) }
    var improvement by rememberSaveable { mutableStateOf(false) }
    var editingId by rememberSaveable { mutableStateOf<String?>(null) }
    var settings by rememberSaveable { mutableStateOf(false) }
    var reset by remember { mutableStateOf(false) }
    var closeSession by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var browser by rememberSaveable { mutableStateOf<String?>(null) }
    var newSession by remember { mutableStateOf(false) }
    var editSession by remember { mutableStateOf(false) }
    var taggingId by rememberSaveable { mutableStateOf<String?>(null) }
    var simulator by remember { mutableStateOf(false) }
    var review by rememberSaveable { mutableStateOf(false) }
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
    Column(Modifier.fillMaxSize().safeDrawingPadding().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("RACELINK STUDIO", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
                Text(s.team, style = MaterialTheme.typography.titleMedium)
                Text("${s.sessionLabel()} · ${if (s.closedAt == null) "Pågår" else "Avslutat"} · Demo${s.sessionDetails().takeIf { it.isNotBlank() }?.let { " · $it" }.orEmpty()}", style = MaterialTheme.typography.bodySmall)
            }
            TextButton(onClick = { vm.update { it.copy(dark = !it.dark) } }, enabled = !vm.busy) { Text(if (s.dark) "Ljust" else "Mörkt") }
            TextButton(onClick = { settings = true }, enabled = s.closedAt == null) { Text("Inställningar") }
            Box {
                OutlinedButton(onClick = { menu = true }) { Text("Meny") }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("Träningspass") }, onClick = { browser = "sessions"; menu = false })
                    DropdownMenuItem(text = { Text("Rapporter") }, onClick = { browser = "reports"; menu = false })
                    DropdownMenuItem(text = { Text("Resultatgranskning (${s.reviewQueue().size})") }, onClick = { review = true; menu = false })
                    DropdownMenuItem(text = { Text("Säkerhetskopiera och återställ") }, onClick = { vm.backupOpen = true; menu = false })
                    DropdownMenuItem(text = { Text("Starta träningspass") }, enabled = !vm.busy && s.reviewQueue().isEmpty(),
                        onClick = { newSession = true; menu = false })
                    DropdownMenuItem(text = { Text("Redigera aktuellt pass") }, enabled = !vm.busy && s.closedAt == null,
                        onClick = { editSession = true; menu = false })
                    DropdownMenuItem(text = { Text("Avsluta träningspass") }, enabled = !vm.busy && s.closedAt == null && s.reviewQueue().isEmpty(),
                        onClick = { closeSession = true; menu = false })
                    DropdownMenuItem(text = { Text("Simulator") }, enabled = s.closedAt == null, onClick = { simulator = true; menu = false })
                }
            }
        }
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (s.closedAt == null) {
                OutlinedButton(onClick = { taggingId = s.attempts.maxByOrNull { it.sequence }?.id }, enabled = !vm.busy && s.attempts.isNotEmpty()) { Text("Tagga senaste åket") }
                OutlinedButton(onClick = { vm.update { it.copy(markers = it.markers + Marker(it.attempts.maxOf { a -> a.sequence }, "Ändrad övning")) } }, enabled = !vm.busy && s.attempts.isNotEmpty()) { Text("Markera ändring") }
            } else OutlinedButton(onClick = { newSession = true }, enabled = !vm.busy) { Text("Starta nytt träningspass") }
            OutlinedButton(onClick = { exportText = csv(s); export.launch("RaceLink-demo.csv") }) { Text("Exportera CSV") }
        }
        if (vm.error != null) Text(vm.error!!, color = MaterialTheme.colorScheme.error)
        exportMessage?.let { Text(it) }
        if (vm.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (s.reviewQueue().isNotEmpty()) OutlinedButton(onClick = { review = true }) { Text("${s.reviewQueue().size} resultat att granska · granska innan nytt pass") }
        if (s.pending.isNotEmpty()) {
            val arrived = s.attempts.firstOrNull { it.id == s.pending.last() }
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
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
            val wide = maxWidth >= 900.dp
            if (wide) Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                AthleteList(s, selected, { selected = it }, Modifier.width(180.dp).fillMaxHeight())
                RunDashboard(s, athlete, runs, latest, valid, improvement, { improvement = it }, { editingId = it }, Modifier.weight(1f), readOnly = s.closedAt != null, tagEnabled = !vm.busy && s.closedAt == null, toggleTag = { id, tag -> vm.update { it.toggleTag(id, tag) } })
            } else Column {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    s.athletes.forEach { a -> FilterChip(selected = selected == a.id, onClick = { selected = a.id }, label = { Text("${a.bib} ${a.name}") }) }
                }
                RunDashboard(s, athlete, runs, latest, valid, improvement, { improvement = it }, { editingId = it }, Modifier.fillMaxWidth(), readOnly = s.closedAt != null, tagEnabled = !vm.busy && s.closedAt == null, toggleTag = { id, tag -> vm.update { it.toggleTag(id, tag) } })
            }
        }
    }
    if (review) TimingReviewScreen(s, vm) { review = false }
    if (closeSession) AlertDialog(onDismissRequest = { closeSession = false }, title = { Text("Avsluta träningspasset?") },
        text = { Text("Passets resultat, taggar och anteckningar sparas och blir skrivskyddade. Inget nytt pass startas. Du kan fortfarande bläddra, skapa rapporter och säkerhetskopiera.") },
        confirmButton = { TextButton(enabled = !vm.busy, onClick = { vm.update { it.closeSession() }; closeSession = false }) { Text("Avsluta pass") } },
        dismissButton = { TextButton(onClick = { closeSession = false }) { Text("Avbryt") } })
    if (simulator) AlertDialog(onDismissRequest = { simulator = false }, title = { Text("Simulator") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("${vm.adapter.label} · Påhittade namn och tider")
            Text("Nästa åkare", style = MaterialTheme.typography.titleMedium)
            s.activeAthletes().forEach { skier -> FilterChip(selected = s.nextAthleteId == skier.id, enabled = !vm.busy,
                onClick = { vm.update { it.selectNextAthlete(skier.id) } }, label = { Text("${skier.bib} · ${skier.name}") }) }
            Text("Valet behålls tills du byter eller rensar åkare.", style = MaterialTheme.typography.bodySmall)
            Button(onClick = { vm.simulate() }, enabled = !vm.busy && s.nextAthleteId != null) { Text("Simulera mål") }
            OutlinedButton(onClick = { vm.simulate(true) }, enabled = !vm.busy && s.nextAthleteId != null) { Text("Simulera avbrutet") }
            if (s.nextAthleteId != null) TextButton(onClick = { vm.update { it.selectNextAthlete(null) } }, enabled = !vm.busy) { Text("Rensa val") }
            TextButton(onClick = { reset = true; simulator = false }, enabled = !vm.busy) { Text("Återställ demo") }
            vm.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }, confirmButton = { TextButton(onClick = { simulator = false }) { Text("Stäng") } })
    editingId?.let { id -> s.attempts.find { it.id == id }?.let { run ->
        EditRun(s, run, !vm.busy, { editingId = null }) { a, complete, tags, note ->
            vm.update { it.correct(id, a, complete, tags, note) }; editingId = null
        }
    } }
    taggingId?.let { id -> s.attempts.find { it.id == id }?.let { run ->
        AlertDialog(onDismissRequest = { taggingId = null }, title = { Text("Tagga åk · ${s.athletes.first { it.id == run.athleteId }.name}") },
            text = { Column(Modifier.verticalScroll(rememberScrollState())) {
                Text("Åk ${s.runs(run.athleteId).indexOf(run) + 1} · ${run.durationMs?.let { seconds(it) + " s" } ?: "Avbrutet"}")
                QuickTags(s, run, !vm.busy) { tag -> vm.update { it.toggleTag(id, tag) } }
                Text("Tryck på en tagg för att lägga till eller ta bort. Sparas direkt.")
            } }, confirmButton = { TextButton(onClick = { taggingId = null }) { Text("Klart") } })
    } }
    if (settings) Settings(s, !vm.busy, { settings = false }) { team, tags ->
        vm.update { it.copy(team = team, tags = tags) }; settings = false
    }
    browser?.let { page -> SessionBrowser(s, page == "reports", { browser = null }, { report ->
        exportText = report
        export.launch("RaceLink-rapport.csv")
    }) { session ->
        exportText = csv(session)
        export.launch("RaceLink-${session.sessionDate}-${session.sessionId.take(8)}.csv")
    } }
    if (newSession) SessionSetup(s, !vm.busy, { newSession = false }) { next ->
        vm.update { next }; newSession = false
    }
    if (editSession) SessionSetup(s, !vm.busy, { editSession = false }, editing = true) { next ->
        vm.update { next }; editSession = false
    }
    if (reset) AlertDialog(onDismissRequest = { reset = false }, title = { Text("Återställ demodata?") },
        text = { Text("Alla pass, rapporter, ändringar, anteckningar och nya demoåk tas bort. Detta påverkar bara den här demoappen.") },
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
                    if (a.id !in s.participantIds) Text("Ej aktiv i passet", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        Text("Egen utveckling\nIngen ranking", style = MaterialTheme.typography.bodySmall)
        Text("${s.audit.size} sparade ändringar", style = MaterialTheme.typography.bodySmall)
    }
}

@Composable internal fun RunDashboard(s: Training, athlete: Athlete, runs: List<Attempt>, latest: Attempt?, valid: List<Attempt>,
    improvement: Boolean, setMode: (Boolean) -> Unit, edit: (String) -> Unit, modifier: Modifier, readOnly: Boolean = false,
    tagEnabled: Boolean = true, toggleTag: (String, Int) -> Unit = { _, _ -> }) {
    var taggedOnly by rememberSaveable(athlete.id) { mutableStateOf(false) }
    val visible = chartRuns(runs, taggedOnly)
    Column(modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Card {
            Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Column {
                Text("${athlete.name} · ${athlete.bib}", style = MaterialTheme.typography.titleMedium)
                Text(if (latest == null) "Väntar på första åket" else if (!latest.complete) "Avbrutet" else "${seconds(latest.durationMs!!)} s",
                    fontSize = 32.sp, fontWeight = FontWeight.Bold)
                }
                Column(Modifier.weight(1f)) {
                val previous = latest?.let { runs.previous(it) }
                Text(when {
                    latest == null -> "Resultaten visas här efter målgång."
                    !latest.complete -> "Synligt i historiken · ingår inte i jämförelsen"
                    previous == null -> "Första fullföljda åket"
                    else -> "${difference(latest.durationMs!! - previous.durationMs!!)} jämfört med åk ${runs.indexOf(previous) + 1}"
                })
                if (valid.isNotEmpty()) Text("Snabbast ${seconds(valid.minOf { it.durationMs!! })} s" +
                    if (valid.size > 1) "   ·   Långsammast ${seconds(valid.maxOf { it.durationMs!! })} s" else "", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        Card {
            Column(Modifier.fillMaxWidth().padding(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(if (readOnly) "Utveckling under passet" else "Dagens utveckling", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                    FilterChip(selected = taggedOnly, onClick = { taggedOnly = !taggedOnly }, label = { Text("Bara taggade") })
                    TextButton(onClick = { setMode(!improvement) }) { Text(if (improvement) "Visa tider" else "Visa förbättring") }
                }
                Text("${visible.size}/${runs.size} åk · " + if (improvement) "Vunna sekunder från första fullföljda åket" else "Tid i sekunder · lägre är snabbare", style = MaterialTheme.typography.bodySmall)
                TrendChart(runs, s.markers, improvement, s.tags, taggedOnly,
                    onRunClick = if (!readOnly && tagEnabled) edit else null)
                s.markers.forEach { Text("⋮ ${it.label} · efter passets resultat ${it.afterSequence}", style = MaterialTheme.typography.bodySmall) }
            }
        }
        Text((if (taggedOnly) "Taggade åk" else "Alla åk") + if (readOnly) "" else " · tryck för anteckning eller rättelse", style = MaterialTheme.typography.titleMedium)
        if (visible.isEmpty()) Text(if (taggedOnly) "Inga taggade åk ännu. Stäng av filtret för att tagga ett åk." else "Inga åk ännu.")
        visible.forEach { item ->
            val run = item.attempt
            val index = item.number - 1
            val previous = runs.previous(run)
            val highlight = if (!run.complete || valid.size < 2) "" else when (run.durationMs) {
                valid.minOfOrNull { it.durationMs!! } -> " · Snabbast"
                valid.maxOfOrNull { it.durationMs!! } -> " · Långsammast"
                else -> ""
            }
            Card(Modifier.fillMaxWidth().clickable(enabled = !readOnly) { edit(run.id) }) {
                Column(Modifier.padding(14.dp)) {
                    Text("Åk ${index + 1}     ${if (run.complete) seconds(run.durationMs!!) + " s" else "Avbrutet"}$highlight", fontWeight = FontWeight.SemiBold)
                    if (run.complete && previous != null) Text("${difference(run.durationMs!! - previous.durationMs!!)} · mot åk ${runs.indexOf(previous) + 1}", style = MaterialTheme.typography.bodySmall)
                    if (readOnly) TagCategory.entries.forEach { category ->
                        val labels = s.tagDescription(run, category)
                        if (labels.isNotBlank()) Text("${category.label}: $labels", color = MaterialTheme.colorScheme.primary)
                    }
                    if (!readOnly) QuickTags(s, run, tagEnabled) { tag -> toggleTag(run.id, tag) }
                    if (run.note.isNotBlank()) Text(run.note)
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable internal fun QuickTags(s: Training, run: Attempt, enabled: Boolean, toggle: (Int) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        TagCategory.entries.forEach { category ->
            Text(category.label, style = MaterialTheme.typography.labelLarge,
                color = if (category == TagCategory.RUN) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.tertiary)
            FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                s.tags.filter { it.category == category }.forEach { tag -> FilterChip(selected = tag.id in run.tags, enabled = enabled,
                    onClick = { toggle(tag.id) }, label = { Text(tag.name) }) }
            }
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
            QuickTags(s, run.copy(tags = tags), enabled) { id -> tags = if (id in tags) tags - id else tags + id }
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
            Text("Namn och kategorier gäller alla åk i aktuellt pass. Avslutade pass behåller sina taggar. Logotyp kommer i en senare version.")
            TagCategory.entries.forEach { category ->
                Text(category.label, style = MaterialTheme.typography.titleMedium)
                tags.filter { it.category == category }.forEach { tag ->
                    OutlinedTextField(tag.name, { name -> tags = tags.map { if (it.id == tag.id) it.copy(name = name) else it } }, label = { Text("Tagg") })
                    val other = if (category == TagCategory.RUN) TagCategory.NEXT_FOCUS else TagCategory.RUN
                    TextButton(onClick = { tags = tags.map { if (it.id == tag.id) it.copy(category = other) else it } }) { Text("Flytta till ${other.label}") }
                }
                TextButton(onClick = { tags = tags + CoachingTag((tags.maxOfOrNull { it.id } ?: 0) + 1, "Ny tagg", category) }) { Text("Lägg till · ${category.label}") }
            }
        }
    }, confirmButton = { TextButton(onClick = { save(team.trim(), tags.map { it.copy(name = it.name.trim()) }) },
        enabled = enabled && team.isNotBlank() && tags.all { it.name.isNotBlank() }) { Text("Spara") } },
        dismissButton = { TextButton(onClick = close) { Text("Avbryt") } })
}

fun csv(s: Training): String = reportCsv(reportRuns(listOf(s)))
