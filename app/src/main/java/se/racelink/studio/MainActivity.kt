package se.racelink.studio

import android.app.Application
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
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
    internal var backupReceipt by mutableStateOf<BackupReceipt?>(null); private set
    private var backupFingerprintToWrite: String? = null
    internal fun cancelBackup() { backupToWrite = null; backupFingerprintToWrite = null; backupMessage = "Säkerhetskopiering avbröts. Ingen ny kopia registrerades." }
    fun prepareBackup(ready: () -> Unit) {
        if (busy || backupBusy) return
        val snapshot = state ?: return
        backupBusy = true; backupMessage = null
        viewModelScope.launch {
            try {
                val prepared = withContext(Dispatchers.IO) { encodeBackup(snapshot) to snapshot.backupFingerprint() }
                backupToWrite = prepared.first; backupFingerprintToWrite = prepared.second
                ready()
            } catch (e: Exception) { backupMessage = "Säkerhetskopian kunde inte skapas: ${e.message}" }
            finally { backupBusy = false }
        }
    }
    fun writeBackup(uri: android.net.Uri) {
        val text = backupToWrite ?: run { backupMessage = "Skapa säkerhetskopian igen; exporten avbröts."; return }
        val fingerprint = backupFingerprintToWrite ?: run { cancelBackup(); return }
        backupBusy = true
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    requireNotNull(getApplication<Application>().contentResolver.openOutputStream(uri, "wt")).use { it.write(text.toByteArray(Charsets.UTF_8)) }
                }
                val receipt = BackupReceipt(java.time.Instant.now().toString(), fingerprint)
                val registered = runCatching { withContext(Dispatchers.IO) { store.recordBackup(receipt) } }
                if (registered.isSuccess) backupReceipt = receipt
                backupMessage = if (registered.isSuccess) "Säkerhetskopian sparades." else "Filen sparades, men backupstatus kunde inte registreras."
            } catch (e: Exception) { backupMessage = "Säkerhetskopian kunde inte sparas: ${e.message}" }
            finally { backupBusy = false; backupToWrite = null; backupFingerprintToWrite = null }
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
                state = next; revision++; pendingRestore = null; error = null; undoEdit = null
                recoveryAvailable = previous != null || recoveryAvailable
                backupMessage = "Träningen har återställts."
            } catch (e: Exception) { backupMessage = "Återställningen misslyckades: ${e.message}. Sparad träning har inte ändrats." }
            finally { busy = false }
        }
    }
    init {
        viewModelScope.launch {
            try {
                state = withContext(Dispatchers.IO) { store.read() ?: demoTraining().also(store::save) }
                backupReceipt = withContext(Dispatchers.IO) { store.backupReceipt() }
            }
            catch (e: Exception) { error = "Kunde inte läsa sparad träning. Data har inte återställts: ${e.message}" }
        }
    }
    internal var completedEditor by mutableStateOf<String?>(null); private set
    internal var queuedRunToOpen by mutableStateOf<String?>(null); private set
    fun editorCommitted(editor: String) { completedEditor = editor }
    fun consumeEditorCommit() { completedEditor = null }
    fun queuedRunCommitted(id: String) { queuedRunToOpen = id }
    fun consumeQueuedRun() { queuedRunToOpen = null }
    internal var undoEdit by mutableStateOf<UndoEdit?>(null); private set
    fun update(onCommitted: () -> Unit = {}, transform: (Training) -> Training) {
        if (busy || backupBusy) return
        val current = state ?: return
        busy = true; error = null
        viewModelScope.launch {
            val result = commitTraining(current, transform) { next -> withContext(Dispatchers.IO) { store.save(next) } }
            result.fold(onSuccess = { next ->
                state = next; error = null
                if (next.sessionId != current.sessionId || next.closedAt != null) undoEdit = null
                onCommitted()
            }, onFailure = { error = "Ändringen sparades inte: ${it.message}" })
            busy = false
        }
    }
    fun quickTag(runId: String, tagId: Int) {
        val undo = state?.let { quickTagUndo(it, runId, tagId) } ?: return
        update(onCommitted = { undoEdit = undo }) { it.toggleTag(runId, tagId) }
    }
    fun quickDnf(runId: String, dnf: Boolean) {
        val undo = state?.let { dnfUndo(it, runId) } ?: return
        update(onCommitted = { undoEdit = undo }) { it.setRunDnf(runId, dnf) }
    }
    fun deleteChange(index: Int, onCommitted: () -> Unit) {
        val undo = state?.let { removedChangeUndo(it, index) } ?: return
        update(onCommitted = { undoEdit = undo; onCommitted() }) { it.removeChange(index) }
    }
    fun undoLastEdit() {
        val undo = undoEdit ?: return
        update(onCommitted = { undoEdit = null }) { current -> undo.apply(current).let { it.copy(audit = it.audit + "${java.time.Instant.now()} · Ångra: ${undo.label}") } }
    }
    fun dismissUndo() { undoEdit = null }
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
    var handoff by rememberSaveable(s.sessionId) { mutableStateOf(false) }
    var selected by rememberSaveable(s.sessionId) { mutableIntStateOf(s.attempts.maxByOrNull { it.sequence }?.athleteId ?: s.athletes.first().id) }
    var improvement by rememberSaveable { mutableStateOf(false) }
    var editingId by rememberSaveable { mutableStateOf<String?>(null) }
    var settings by rememberSaveable { mutableStateOf(false) }
    var reset by remember { mutableStateOf(false) }
    var closeSession by rememberSaveable { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var destination by rememberSaveable { mutableStateOf("current") }
    var sessionDetails by rememberSaveable { mutableStateOf(false) }
    var chooseSkier by rememberSaveable { mutableStateOf(false) }
    var newSession by rememberSaveable { mutableStateOf(false) }
    var editSession by rememberSaveable { mutableStateOf(false) }
    var taggingId by rememberSaveable { mutableStateOf<String?>(null) }
    var editingChange by rememberSaveable { mutableStateOf<Int?>(null) }
    var changeDialog by rememberSaveable { mutableStateOf(false) }
    var simulator by remember { mutableStateOf(false) }
    var review by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(vm.completedEditor) {
        when (vm.completedEditor) {
            "change-edit" -> editingChange = null
            "change-new" -> changeDialog = false
            "close" -> { closeSession = false; handoff = true }
            "run" -> editingId = null
            "settings" -> settings = false
            "session-new" -> newSession = false
            "session-edit" -> editSession = false
        }
        vm.consumeEditorCommit()
    }
    LaunchedEffect(vm.queuedRunToOpen) {
        vm.queuedRunToOpen?.let { id -> s.attempts.firstOrNull { it.id == id }?.let { selected = it.athleteId; editingId = id } }
        vm.consumeQueuedRun()
    }
    val athlete = s.athletes.firstOrNull { it.id == selected } ?: s.athletes.first()
    val runs = s.runs(athlete.id)
    val latest = runs.lastOrNull()
    val valid = runs.eligible()
    BackHandler(enabled = destination != "current") { destination = "current" }
    StudioNavigation(destination, { destination = it }) {
    if (destination == "current") {
    Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(s.sessionName, style = MaterialTheme.typography.titleLarge)
                Text("${s.sessionDate} · ${if (s.closedAt == null) "Pågår" else "Avslutat · skrivskyddat"} · Demo", style = MaterialTheme.typography.bodySmall)
            }
            TextButton(onClick = { sessionDetails = !sessionDetails }) { Text(if (sessionDetails) "Dölj detaljer" else "Passinfo") }
            Box {
                OutlinedButton(onClick = { menu = true }) { Text("Passåtgärder") }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("Starta träningspass") }, enabled = !vm.busy && s.reviewQueue().isEmpty(),
                        onClick = { newSession = true; menu = false })
                    DropdownMenuItem(text = { Text("Redigera aktuellt pass") }, enabled = !vm.busy && s.closedAt == null,
                        onClick = { editSession = true; menu = false })
                    DropdownMenuItem(text = { Text("Avsluta träningspass") }, enabled = !vm.busy && s.closedAt == null && s.reviewQueue().isEmpty(),
                        onClick = { closeSession = true; menu = false })
                    DropdownMenuItem(text = { Text("Markera ändring") }, enabled = !vm.busy && s.closedAt == null, onClick = { changeDialog = true; menu = false })
                }
            }
        }
        if (sessionDetails) Text("${s.team} · ${s.sessionDetails().ifBlank { "Inga fler passuppgifter" }}", style = MaterialTheme.typography.bodySmall)
        if (s.closedAt != null) OutlinedButton(onClick = { handoff = true }) { Text("Passammanfattning · rapport och säkerhetskopia") }
        if (vm.error != null) Text(vm.error!!, color = MaterialTheme.colorScheme.error)
        vm.undoEdit?.let { undo ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(undo.label, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                TextButton(enabled = !vm.busy, onClick = vm::undoLastEdit) { Text("Ångra") }
                TextButton(onClick = vm::dismissUndo) { Text("Stäng") }
            }
        }
        if (vm.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (s.reviewQueue().isNotEmpty()) OutlinedButton(onClick = { review = true }) { Text("Granska resultat (${s.reviewQueue().size}) · krävs innan passet avslutas eller ett nytt startas") }
        if (s.pending.isNotEmpty()) {
            val arrived = s.nextPendingRun()
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("${s.pending.size} nya resultat · ${s.athletes.firstOrNull { it.id == arrived?.athleteId }?.name.orEmpty()} · ${if (arrived?.complete == true) seconds(arrived.durationMs!!) + " s" else "DNF"}", Modifier.weight(1f))
                    TextButton(onClick = {
                        val first = s.nextPendingRun()
                        if (first != null) vm.update(onCommitted = { vm.queuedRunCommitted(first.id) }) { it.acknowledgePending(first.id) }
                    }, enabled = !vm.busy) { Text("Visa nästa") }
                }
            }
        }
        BoxWithConstraints(Modifier.weight(1f)) {
            val wide = maxWidth >= 900.dp && androidx.compose.ui.platform.LocalDensity.current.fontScale < 1.5f
            if (wide) Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                AthleteList(s, selected, { selected = it }, Modifier.width(180.dp).fillMaxHeight())
                RunDashboard(s, athlete, runs, latest, valid, improvement, { improvement = it }, { editingId = it }, Modifier.weight(1f), readOnly = s.closedAt != null, tagEnabled = !vm.busy && s.closedAt == null, onSkierSwipe = { direction -> selected = adjacentSkier(s.athletes, selected, direction) }, setDnf = { id, dnf -> vm.quickDnf(id, dnf) }, tagLatest = { taggingId = it }, editChange = { editingChange = it })
            } else Column {
                TextButton(onClick = { chooseSkier = true }, modifier = Modifier.fillMaxWidth()) { Text("Visar åkare: ${athlete.bib} · ${athlete.name} · byt åkare") }
                RunDashboard(s, athlete, runs, latest, valid, improvement, { improvement = it }, { editingId = it }, Modifier.fillMaxWidth(), readOnly = s.closedAt != null, tagEnabled = !vm.busy && s.closedAt == null, onSkierSwipe = { direction -> selected = adjacentSkier(s.athletes, selected, direction) }, setDnf = { id, dnf -> vm.quickDnf(id, dnf) }, tagLatest = { taggingId = it }, editChange = { editingChange = it })
            }
        }
    }
    } else when (destination) {
        "sessions", "reports" -> SessionBrowser(s, destination == "reports", { destination = "current" }, embedded = true)
        "settings" -> SettingsHome(s, vm, { settings = true }, { simulator = true }, { review = true })
    }
    }
    if (chooseSkier) SkierPicker(s.athletes, selected, { chooseSkier = false }, participants = s.participantIds) { selected = it }
    if (handoff) SessionHandoff(s, vm, { handoff = false }, { handoff = false; newSession = true })
    editingChange?.let { index -> s.markers.getOrNull(index)?.let { marker ->
        ChangeDialog(s, !vm.busy, { editingChange = null }, marker, remove = { vm.deleteChange(index) { vm.editorCommitted("change-edit") } }, error = vm.error) { tags, note ->
            vm.update(onCommitted = { vm.editorCommitted("change-edit") }) { it.editChange(index, tags, note) }
        }
    } }
    if (changeDialog) ChangeDialog(s, !vm.busy, { changeDialog = false }, error = vm.error) { tags, note ->
        vm.update(onCommitted = { vm.editorCommitted("change-new") }) { it.recordChange(tags, note) }
    }
    if (review) TimingReviewScreen(s, vm) { review = false }
    if (closeSession) AlertDialog(onDismissRequest = { closeSession = false }, title = { Text("Avsluta träningspasset?") },
        text = { Column { SaveFeedback(!vm.busy, vm.error); Text("Passets resultat, taggar och anteckningar sparas och blir skrivskyddade. Inget nytt pass startas. Du kan fortfarande bläddra, skapa rapporter och säkerhetskopiera.") } },
        confirmButton = { TextButton(enabled = !vm.busy, onClick = { vm.update(onCommitted = { vm.editorCommitted("close") }) { it.closeSession() } }) { Text("Avsluta pass") } },
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
        EditRun(s, run, !vm.busy, { editingId = null }, error = vm.error) { a, complete, tags, note ->
            vm.update(onCommitted = { vm.editorCommitted("run") }) { it.correct(id, a, complete, tags, note) }
        }
    } }
    taggingId?.let { id -> s.attempts.find { it.id == id }?.let { run ->
        AlertDialog(onDismissRequest = { taggingId = null }, title = { Text("Tagga åk · ${s.athletes.first { it.id == run.athleteId }.name}") },
            text = { Column(Modifier.verticalScroll(rememberScrollState())) {
                Text("Åk ${s.runs(run.athleteId).indexOf(run) + 1} · ${if (run.complete) seconds(run.durationMs!!) + " s" else "DNF"}")
                SaveFeedback(!vm.busy, vm.error)
                QuickTags(s, run, !vm.busy) { tag -> vm.quickTag(id, tag) }
                Text("Tryck på en tagg för att lägga till eller ta bort. Sparas direkt.")
                vm.undoEdit?.let { undo -> TextButton(enabled = !vm.busy, onClick = vm::undoLastEdit) { Text("Ångra · ${undo.label}") } }
            } }, confirmButton = { TextButton(onClick = { taggingId = null }) { Text("Klart") } })
    } }
    if (settings) Settings(s, !vm.busy, { settings = false }, error = vm.error) { team, tags ->
        vm.update(onCommitted = { vm.editorCommitted("settings") }) { it.updateTagSettings(team, tags) }
    }
    if (newSession) SessionSetup(s, !vm.busy, { newSession = false }, error = vm.error) { next ->
        vm.update(onCommitted = { vm.editorCommitted("session-new") }) { next }
    }
    if (editSession) SessionSetup(s, !vm.busy, { editSession = false }, editing = true, error = vm.error) { next ->
        vm.update(onCommitted = { vm.editorCommitted("session-edit") }) { next }
    }
    if (reset) AlertDialog(onDismissRequest = { reset = false }, title = { Text("Återställ demodata?") },
        text = { Text("Alla pass, rapporter, ändringar, anteckningar och nya demoåk tas bort. Detta påverkar bara den här demoappen.") },
        confirmButton = { TextButton(onClick = { vm.update(onCommitted = { selected = 1; reset = false }) { demoTraining() } }) { Text("Återställ") } },
        dismissButton = { TextButton(onClick = { reset = false }) { Text("Avbryt") } })
}

@Composable private fun AthleteList(s: Training, selected: Int, choose: (Int) -> Unit, modifier: Modifier) {
    Column(modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        var query by rememberSaveable { mutableStateOf("") }
        var participantsOnly by rememberSaveable { mutableStateOf(false) }
        Text("Visar åkare", style = MaterialTheme.typography.labelLarge)
        OutlinedTextField(query, { query = it }, label = { Text("Sök namn / nr") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        FilterChip(selected = participantsOnly, onClick = { participantsOnly = !participantsOnly }, label = { Text("Deltagare") })
        val matches = findSkiers(if (participantsOnly) s.activeAthletes() else s.athletes, query)
        if (matches.isEmpty()) Text("Inga åkare matchar sökningen.")
        matches.forEach { a ->
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

@OptIn(ExperimentalLayoutApi::class)
@Composable internal fun RunDashboard(s: Training, athlete: Athlete, runs: List<Attempt>, latest: Attempt?, valid: List<Attempt>,
    improvement: Boolean, setMode: (Boolean) -> Unit, edit: (String) -> Unit, modifier: Modifier, readOnly: Boolean = false,
    tagEnabled: Boolean = true, onSkierSwipe: ((Int) -> Unit)? = null,
    setDnf: (String, Boolean) -> Unit = { _, _ -> }, tagLatest: (String) -> Unit = {}, editChange: (Int) -> Unit = {}) {
    var detailId by rememberSaveable(athlete.id) { mutableStateOf<String?>(null) }
    var taggedOnly by rememberSaveable(athlete.id) { mutableStateOf(false) }
    val visible = chartRuns(runs, taggedOnly)
    val summary: @Composable () -> Unit = {
        LatestResultCard(s, athlete, runs, latest, readOnly, tagEnabled, tagLatest, setDnf)
        Card {
            Column(Modifier.fillMaxWidth().padding(10.dp)) {
                Text(if (readOnly) "Utveckling under passet" else "Dagens utveckling", style = MaterialTheme.typography.titleMedium)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    FilterChip(selected = !improvement, onClick = { setMode(false) }, label = { Text("Tider") })
                    FilterChip(selected = improvement, onClick = { setMode(true) }, label = { Text("Förändring") })
                    FilterChip(selected = taggedOnly, onClick = { taggedOnly = !taggedOnly }, label = { Text("Bara taggade") })
                    if (onSkierSwipe != null) {
                        TextButton(enabled = s.athletes.indexOf(athlete) > 0, onClick = { onSkierSwipe(-1) }) { Text("← Föregående åkare") }
                        TextButton(enabled = s.athletes.indexOf(athlete) < s.athletes.lastIndex, onClick = { onSkierSwipe(1) }) { Text("Nästa åkare →") }
                    }
                }
                Text("${visible.size}/${runs.size} åk · " + if (improvement) "Vunna sekunder från första fullföljda åket" else "Tid i sekunder · lägre är snabbare", style = MaterialTheme.typography.bodySmall)
                key(s.sessionId, athlete.id) {
                    TrendChart(runs, s.markers, improvement, s.tags, taggedOnly,
                        onRunClick = if (!readOnly && tagEnabled) edit else null, onSkierSwipe = onSkierSwipe, changeLabels = runs.associate { it.id to s.tagDescription(it, TagCategory.CHANGE) }, runSequences = runs.associate { it.id to s.originalSequence(it) }, onChangeClick = if (!readOnly && tagEnabled) ({ marker -> editChange(s.markers.indexOfFirst { it === marker }) }) else null)
                }
            }
        }
    }
    val history: @Composable () -> Unit = {
        Text((if (taggedOnly) "Taggade åk" else "Alla åk") + " · senaste först", style = MaterialTheme.typography.titleMedium)
        if (visible.isEmpty()) Text(if (taggedOnly) "Inga taggade åk ännu. Stäng av filtret för att tagga ett åk." else "Inga åk ännu.")
        visible.asReversed().forEach { item ->
            CompactRunRow(s, runs, item) { if (!readOnly) edit(item.attempt.id) else detailId = item.attempt.id }
        }
    }
    BoxWithConstraints(modifier) {
        val sideHistory = maxWidth >= 800.dp && androidx.compose.ui.platform.LocalDensity.current.fontScale < 1.5f
        if (sideHistory) Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) { summary() }
            Column(Modifier.width(240.dp).fillMaxHeight().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) { history() }
        } else Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) { summary(); history() }
    }
    detailId?.let { id -> runs.firstOrNull { it.id == id }?.let { run ->
        AlertDialog(onDismissRequest = { detailId = null }, title = { Text("Åk ${runs.indexOf(run) + 1} · ${athlete.name} · ${if (run.complete) seconds(run.durationMs!!) + " s" else "DNF · ej fullföljt"}") },
            text = { Column(Modifier.verticalScroll(rememberScrollState())) {
                TagCategory.entries.forEach { category -> Text("${category.label}: ${s.tagDescription(run, category).ifBlank { "Inga taggar" }}") }
                Text(run.note.ifBlank { "Ingen anteckning" })
                RunHistoryButton(s, run)
            } }, confirmButton = { TextButton(onClick = { detailId = null }) { Text("Stäng") } })
    } }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable internal fun QuickTags(s: Training, run: Attempt, enabled: Boolean, toggle: (Int) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        TagCategory.entries.filter { it != TagCategory.CHANGE }.forEach { category ->
            Text(category.label, style = MaterialTheme.typography.labelLarge,
                color = if (category == TagCategory.RUN) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.tertiary)
            FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                s.tags.filter { it.category == category }.forEach { tag -> FilterChip(selected = tag.id in run.tags, enabled = enabled,
                    onClick = { toggle(tag.id) }, label = { Text(tag.name) }) }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable private fun EditRun(s: Training, run: Attempt, enabled: Boolean, close: () -> Unit, error: String? = null,
    save: (Int, Boolean, Set<Int>, String) -> Unit) {
    var athlete by rememberSaveable(run.id) { mutableIntStateOf(run.athleteId) }
    var complete by rememberSaveable(run.id) { mutableStateOf(run.complete) }
    var tags by rememberSaveable(run.id, stateSaver = IdSetSaver) { mutableStateOf(run.tags) }
    var note by rememberSaveable(run.id) { mutableStateOf(run.note) }
    var corrections by rememberSaveable(run.id) { mutableStateOf(false) }
    var pickAthlete by rememberSaveable(run.id) { mutableStateOf(false) }
    val leave = draftClose(athlete != run.athleteId || complete != run.complete || tags != run.tags || note != run.note, enabled, close)
    AlertDialog(onDismissRequest = leave, title = {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Åk ${s.runs(run.athleteId).indexOfFirst { it.id == run.id } + 1} · ${s.athletes.first { it.id == run.athleteId }.name}")
            Text((run.durationMs?.let { "${seconds(it)} s" } ?: "Ingen måltid") + if (!complete) " · DNF" else "",
                style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.primary)
        }
    }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            s.tagDescription(run, TagCategory.CHANGE).takeIf { it.isNotBlank() }?.let { Text("Ändring: $it") }
            Text(if (complete) "Ingår i trend och jämförelser." else "DNF visas i historiken men ingår inte i trend, jämförelser eller snabbaste tid.", style = MaterialTheme.typography.bodySmall)
            QuickTags(s, run.copy(tags = tags), enabled) { id -> tags = if (id in tags) tags - id else tags + id }
            OutlinedTextField(note, { note = it }, enabled = enabled, label = { Text("Anteckning") }, modifier = Modifier.fillMaxWidth())
            RunHistoryButton(s, run)
            TextButton(onClick = { corrections = !corrections }) { Text(if (corrections) "Dölj rättelser" else "Rätta åkare eller DNF-status") }
            if (corrections) {
                Text("Rättelser sparas med historik", style = MaterialTheme.typography.titleSmall)
                OutlinedButton(enabled = enabled, onClick = { pickAthlete = true }) { Text("Tilldela: ${s.athletes.first { it.id == athlete }.name} · byt åkare") }
                if (athlete != run.athleteId) Text("Åket flyttas till ${s.athletes.first { it.id == athlete }.name} när du sparar.")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = complete, enabled = enabled && run.durationMs != null, onClick = { complete = true }, label = { Text("Fullföljt") })
                    FilterChip(selected = !complete, enabled = enabled, onClick = { complete = false }, label = { Text("DNF · ej fullföljt") })
                }
            }

        }
    }, confirmButton = { Column { SaveFeedback(enabled, error); TextButton(onClick = { save(athlete, complete, tags, note) }, enabled = enabled) { Text("Spara") } } },
        dismissButton = { TextButton(enabled = enabled, onClick = leave) { Text("Avbryt") } })
    if (pickAthlete) SkierPicker(s.athletes, athlete, { pickAthlete = false }) { athlete = it }

}

fun csv(s: Training): String = reportCsv(reportRuns(listOf(s)))

@OptIn(ExperimentalLayoutApi::class)
@Composable private fun ChangeDialog(s: Training, enabled: Boolean, close: () -> Unit, marker: Marker? = null, remove: (() -> Unit)? = null, error: String? = null, save: (Set<Int>, String) -> Unit) {
    var selected by rememberSaveable(stateSaver = IdSetSaver) { mutableStateOf(marker?.tags ?: emptySet<Int>()) }
    var description by rememberSaveable { mutableStateOf(marker?.label.orEmpty()) }
    val leave = draftClose(selected != (marker?.tags ?: emptySet<Int>()) || description != marker?.label.orEmpty(), enabled, close)
    AlertDialog(onDismissRequest = leave, title = { Text(if (marker == null) "Markera ändring" else "Redigera ändring") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(if (marker == null) "Gäller alla åkare från nästa åk i detta pass. Tidigare åk påverkas inte. Flera taggar kan väljas." else "Gäller alla åkare efter passets resultat ${marker.afterSequence}. Tidpunkten behålls; taggar och beskrivning uppdateras för berörda åk.")
            Text("Ändring", style = MaterialTheme.typography.titleMedium)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                s.tags.filter { it.category == TagCategory.CHANGE }.forEach { tag ->
                    FilterChip(selected = tag.id in selected, enabled = enabled,
                        onClick = { selected = if (tag.id in selected) selected - tag.id else selected + tag.id }, label = { Text(tag.name) })
                }
            }
            OutlinedTextField(description, { description = it }, enabled = enabled, label = { Text("Beskriv ändringen (valfritt)") }, modifier = Modifier.fillMaxWidth())
            Text("Ändringstaggar kan läggas till och döpas om i Inställningar.", style = MaterialTheme.typography.bodySmall)
            if (remove != null) TextButton(enabled = enabled, onClick = remove,
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text("Ta bort ändring") }
        }
    }, confirmButton = { Column { SaveFeedback(enabled, error); TextButton(enabled = enabled && (selected.isNotEmpty() || description.isNotBlank()), onClick = { save(selected, description) }) { Text("Spara ändring") } } },
        dismissButton = { TextButton(enabled = enabled, onClick = leave) { Text("Avbryt") } })
}

@Composable internal fun TimeComparison(label: String, deltaMs: Long, dark: Boolean, best: Boolean = false) {
    val positive = if (dark) Color(0xFF81C784) else Color(0xFF256B35)
    val negative = if (dark) Color(0xFFFFB4AB) else Color(0xFFAC2925)
    val color = when { best || deltaMs < 0 -> positive; deltaMs > 0 -> negative; else -> MaterialTheme.colorScheme.onSurfaceVariant }
    val symbol = when { best -> "★"; deltaMs < 0 -> "↓"; deltaMs > 0 -> "↑"; else -> "=" }
    val text = if (best) "Bästa tid" else difference(deltaMs)
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
