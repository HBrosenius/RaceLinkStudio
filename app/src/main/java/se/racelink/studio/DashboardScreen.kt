package se.racelink.studio

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

@OptIn(ExperimentalLayoutApi::class)
@Composable internal fun Dashboard(s: Training, vm: TrainingViewModel) {
    var handoff by rememberSaveable(s.sessionId) { mutableStateOf(false) }
    var rosterOpen by rememberSaveable { mutableStateOf(false) }
    var skiersCollapsed by rememberSaveable { mutableStateOf(false) }
    var runsCollapsed by rememberSaveable { mutableStateOf(false) }
    var dismissedResultId by rememberSaveable(s.sessionId) { mutableStateOf<String?>(null) }
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
    var chooseNextSkier by rememberSaveable { mutableStateOf(false) }
    var newSession by rememberSaveable { mutableStateOf(false) }
    var editSession by rememberSaveable { mutableStateOf(false) }
    var editSessionParticipants by rememberSaveable { mutableStateOf(false) }
    var raceSetup by rememberSaveable { mutableStateOf(false) }
    var editingRace by rememberSaveable { mutableStateOf(false) }
    var focusedRunId by rememberSaveable(s.sessionId) { mutableStateOf<String?>(null) }
    var noteRunId by rememberSaveable(s.sessionId) { mutableStateOf<String?>(null) }
    var noteText by rememberSaveable(s.sessionId) { mutableStateOf("") }
    var noteOriginal by rememberSaveable(s.sessionId) { mutableStateOf("") }
    var pendingNoteAction by remember { mutableStateOf<(() -> Unit)?>(null) }
    val noteDirty = noteRunId != null && noteText != noteOriginal
    fun guardNote(action: () -> Unit) {
        if (noteRunId != null && (vm.busy || vm.backupBusy)) return
        if (noteDirty) pendingNoteAction = action
        else { noteRunId = null; action() }
    }
    fun openNote(id: String) = guardNote {
        s.attempts.find { it.id == id }?.let { run ->
            focusedRunId = id; noteRunId = id; noteText = run.note; noteOriginal = run.note
        }
    }
    val noteEditor = noteRunId?.let { id -> RunNoteUi(noteText, noteDirty, !vm.busy && !vm.backupBusy, vm.error,
        { noteText = it }, {
            val text = noteText
            val sessionId = s.sessionId
            vm.update(onCommitted = { vm.editorCommitted("note") }) { current ->
                require(current.sessionId == sessionId) { "Passet har ändrats. Anteckningen sparades inte." }
                current.setRunNote(id, text)
            }
        }, { guardNote { noteRunId = null } }) }
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
            "note" -> noteRunId = null
            "next-skier" -> chooseNextSkier = false
            "settings" -> settings = false
            "session-new" -> newSession = false
            "session-edit" -> editSession = false
            "race-setup" -> raceSetup = false
        }
        vm.consumeEditorCommit()
    }
    LaunchedEffect(vm.queuedRunToOpen) {
        vm.queuedRunToOpen?.let { id -> s.attempts.firstOrNull { it.id == id }?.let { run -> guardNote { selected = run.athleteId; focusedRunId = id } } }
        vm.consumeQueuedRun()
    }
    val athlete = s.athletes.firstOrNull { it.id == selected } ?: s.athletes.first()
    val runs = s.runs(athlete.id)
    val latest = runs.lastOrNull()
    val valid = runs.eligible()
    BackHandler(enabled = destination != "current" || noteRunId != null) { guardNote { destination = "current" } }
    StudioNavigation(destination, { target -> guardNote { destination = target } }) {
    if (destination == "current") {
    Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        FlowRow(Modifier.fillMaxWidth(), maxItemsInEachRow = if (LocalConfiguration.current.screenWidthDp < 600 || LocalDensity.current.fontScale >= 1.5f) 1 else 2,
            verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Column(Modifier.weight(1f)) {
                Text(s.sessionName, style = MaterialTheme.typography.titleLarge)
                Text("${s.sessionDate} · ${if (s.closedAt == null) stringResource(R.string.dash_ongoing) else stringResource(R.string.dash_closed_readonly)} · ${stringResource(R.string.dash_simulator)}", style = MaterialTheme.typography.bodySmall)
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            TextButton(onClick = { sessionDetails = !sessionDetails }) { Text(if (sessionDetails) stringResource(R.string.dash_hide_details) else stringResource(R.string.dash_session_info)) }
            Box {
                OutlinedButton(onClick = { menu = true }) { Text(if (s.race == null) stringResource(R.string.dash_session_actions) else stringResource(R.string.dash_race_actions)) }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    if (s.pending.isNotEmpty()) DropdownMenuItem(text = { Text(stringResource(R.string.dash_show_new_results_count, s.pending.size)) }, onClick = { dismissedResultId = null; menu = false })
                    vm.undoEdit?.let { undo -> DropdownMenuItem(text = { Text(stringResource(R.string.dash_undo_label, undo.label)) }, enabled = !vm.busy && !vm.backupBusy, onClick = { vm.undoLastEdit(); menu = false }) }
                    DropdownMenuItem(text = { Text(stringResource(R.string.dash_start_training_session)) }, enabled = !vm.busy && s.reviewQueue().isEmpty(),
                        onClick = { guardNote { newSession = true }; menu = false })
                    DropdownMenuItem(text = { Text(stringResource(R.string.dash_start_race)) }, enabled = !vm.busy && s.reviewQueue().isEmpty(),
                        onClick = { guardNote { editingRace = false; raceSetup = true }; menu = false })
                    if (s.race != null) DropdownMenuItem(text = { Text(stringResource(R.string.dash_edit_race)) }, enabled = !vm.busy && s.closedAt == null,
                        onClick = { editingRace = true; raceSetup = true; menu = false })
                    if (s.race == null) DropdownMenuItem(text = { Text(stringResource(R.string.dash_edit_current_session)) }, enabled = !vm.busy && s.closedAt == null,
                        onClick = { guardNote { editSessionParticipants = false; editSession = true }; menu = false })
                    DropdownMenuItem(text = { Text(if (s.race == null) stringResource(R.string.dash_close_training_session) else stringResource(R.string.dash_close_race)) }, enabled = !vm.busy && s.closedAt == null && s.reviewQueue().isEmpty(),
                        onClick = { guardNote { closeSession = true }; menu = false })
                    if (s.race == null) DropdownMenuItem(text = { Text(stringResource(R.string.dash_mark_change)) }, enabled = !vm.busy && s.closedAt == null, onClick = { changeDialog = true; menu = false })
                }
            }
            }
        }
        if (sessionDetails) {
            val noDetails = stringResource(R.string.dash_no_more_session_details)
            Text("${s.team} · ${s.sessionDetails().ifBlank { noDetails }}\n${stringResource(R.string.dash_simulator_no_timer)}", style = MaterialTheme.typography.bodySmall)
        }
        if (s.closedAt != null) OutlinedButton(onClick = { handoff = true }) { Text(if (s.race == null) stringResource(R.string.dash_session_summary) else stringResource(R.string.dash_race_summary)) }
        if (vm.error != null) Text(vm.error!!, Modifier.semantics { liveRegion = LiveRegionMode.Polite }, color = MaterialTheme.colorScheme.error)
        vm.undoEdit?.takeIf { it.showNotice }?.let { undo ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(undo.label, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                TextButton(enabled = !vm.busy, onClick = vm::undoLastEdit) { Text(stringResource(R.string.dash_undo)) }
                TextButton(onClick = vm::dismissUndo) { Text(stringResource(R.string.dash_close)) }
            }
        }
        if (vm.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (s.reviewQueue().isNotEmpty()) OutlinedButton(onClick = { review = true }) { Text(stringResource(R.string.dash_review_results_count, s.reviewQueue().size)) }
        if (s.pending.isNotEmpty() && s.pending.last() != dismissedResultId) {
            val arrived = s.nextPendingRun()
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                val hideNewResultsDesc = stringResource(R.string.dash_hide_new_results)
                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("${s.pending.size} nya resultat · ${s.athletes.firstOrNull { it.id == arrived?.athleteId }?.name.orEmpty()} · ${if (arrived?.complete == true) (if (s.race != null) raceSeconds(arrived.durationMs!!) else seconds(arrived.durationMs!!)) + " s" else stringResource(R.string.dash_dnf_not_completed)}", Modifier.weight(1f).semantics { liveRegion = LiveRegionMode.Polite })
                    TextButton(onClick = {
                        val first = s.nextPendingRun()
                        if (first != null) vm.update(onCommitted = { vm.queuedRunCommitted(first.id) }) { it.acknowledgePending(first.id) }
                    }, enabled = !vm.busy) { Text(stringResource(R.string.dash_show_next)) }
                    IconButton(onClick = { dismissedResultId = s.pending.lastOrNull() }, modifier = Modifier.semantics { contentDescription = hideNewResultsDesc }) { Text("×") }
                }
            }
        }
        if (s.race != null) RaceScreen(s, vm, Modifier.weight(1f), simulator = { simulator = true },
            editRun = { editingId = it }, editSetup = { editingRace = true; raceSetup = true }, highlightedRunId = focusedRunId)
        else BoxWithConstraints(Modifier.weight(1f)) {
            val wide = maxWidth >= 900.dp && androidx.compose.ui.platform.LocalDensity.current.fontScale < 1.5f
            if (wide) Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                if (skiersCollapsed) PanelHandle(left = true, expand = { skiersCollapsed = false })
                else AthleteList(s, selected, { id -> guardNote { selected = id; focusedRunId = null } }, Modifier.width(180.dp).fillMaxHeight(), collapse = { skiersCollapsed = true })
                RunDashboard(s, athlete, runs, latest, valid, improvement, { improvement = it }, { id -> guardNote { editingId = id } }, Modifier.weight(1f), readOnly = s.closedAt != null, tagEnabled = !vm.busy && !vm.backupBusy && s.closedAt == null, onSkierSwipe = { direction -> guardNote { selected = adjacentSkier(s.athletes, selected, direction); focusedRunId = null } }, setDnf = { id, dnf -> vm.quickDnf(id, dnf) }, toggleTag = vm::quickTag, focusedRunId = focusedRunId, selectRun = { id -> guardNote { focusedRunId = id } }, editNote = ::openNote, noteEditor = noteEditor, noteRunId = noteRunId, historyCollapsed = runsCollapsed, setHistoryCollapsed = { runsCollapsed = it },
                    openParticipants = { guardNote { editSessionParticipants = true; editSession = true } }, chooseNext = { chooseNextSkier = true }, openSimulator = { simulator = true }, editChange = { editingChange = it })
            } else Column {
                TextButton(onClick = { chooseSkier = true }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.dash_showing_athlete, athlete.bib, athlete.name)) }
                RunDashboard(s, athlete, runs, latest, valid, improvement, { improvement = it }, { id -> guardNote { editingId = id } }, Modifier.fillMaxWidth(), readOnly = s.closedAt != null, tagEnabled = !vm.busy && !vm.backupBusy && s.closedAt == null, onSkierSwipe = { direction -> guardNote { selected = adjacentSkier(s.athletes, selected, direction); focusedRunId = null } }, setDnf = { id, dnf -> vm.quickDnf(id, dnf) }, toggleTag = vm::quickTag, focusedRunId = focusedRunId, selectRun = { id -> guardNote { focusedRunId = id } }, editNote = ::openNote, noteEditor = noteEditor, noteRunId = noteRunId, historyCollapsed = runsCollapsed, setHistoryCollapsed = { runsCollapsed = it },
                    openParticipants = { guardNote { editSessionParticipants = true; editSession = true } }, chooseNext = { chooseNextSkier = true }, openSimulator = { simulator = true }, editChange = { editingChange = it })
            }
        }
    }
    } else when (destination) {
        "sessions", "reports" -> SessionBrowser(s, destination == "reports", { destination = "current" }, embedded = true)
        "settings" -> SettingsHome(s, vm, { settings = true }, { simulator = true }, { review = true }, { rosterOpen = true })
    }
    }
    pendingNoteAction?.let { action ->
        AlertDialog(onDismissRequest = { pendingNoteAction = null }, title = { Text(stringResource(R.string.dash_leave_note_title)) },
            text = { Text(stringResource(R.string.dash_leave_note_text)) },
            confirmButton = { TextButton(enabled = !vm.busy && !vm.backupBusy, onClick = { pendingNoteAction = null; noteRunId = null; action() }) { Text(stringResource(R.string.dash_discard_note)) } },
            dismissButton = { TextButton(onClick = { pendingNoteAction = null }) { Text(stringResource(R.string.dash_continue_editing)) } })
    }
    if (chooseSkier) SkierPicker(s.athletes, selected, { chooseSkier = false }, participants = s.participantIds) { id -> guardNote { selected = id; focusedRunId = null } }
    if (chooseNextSkier) SkierPicker(s.activeAthletes(), s.nextAthleteId ?: -1, { if (!vm.busy) chooseNextSkier = false },
        closeOnChoose = false, enabled = !vm.busy && !vm.backupBusy, error = vm.error, title = stringResource(R.string.dash_choose_next_athlete)) { id ->
        vm.update(onCommitted = { vm.editorCommitted("next-skier") }) { it.selectNextAthlete(id) }
    }
    if (handoff) SessionHandoff(s, vm, { handoff = false }, { handoff = false; newSession = true })
    if (rosterOpen) RosterScreen(s, vm, { rosterOpen = false })
    editingChange?.let { index -> s.markers.getOrNull(index)?.let { marker ->
        ChangeDialog(s, !vm.busy, { editingChange = null }, marker, remove = { vm.deleteChange(index) { vm.editorCommitted("change-edit") } }, error = vm.error) { tags, note ->
            vm.update(onCommitted = { vm.editorCommitted("change-edit") }) { it.editChange(index, tags, note) }
        }
    } }
    if (changeDialog) ChangeDialog(s, !vm.busy, { changeDialog = false }, error = vm.error) { tags, note ->
        vm.update(onCommitted = { vm.editorCommitted("change-new") }) { it.recordChange(tags, note) }
    }
    if (review) TimingReviewScreen(s, vm) { review = false }
    if (closeSession) AlertDialog(onDismissRequest = { closeSession = false }, title = { Text(if (s.race == null) stringResource(R.string.dash_close_training_session_q) else stringResource(R.string.dash_close_race_q)) },
        text = { Column { SaveFeedback(!vm.busy, vm.error); Text(stringResource(R.string.dash_close_session_text)) } },
        confirmButton = { TextButton(enabled = !vm.busy, onClick = { vm.update(onCommitted = { vm.editorCommitted("close") }) { it.closeSession() } }) { Text(if (s.race == null) stringResource(R.string.dash_close_session) else stringResource(R.string.dash_close_race)) } },
        dismissButton = { TextButton(onClick = { closeSession = false }) { Text(stringResource(R.string.dash_cancel)) } })
    if (simulator) AlertDialog(onDismissRequest = { simulator = false }, title = { Text(stringResource(R.string.dash_simulator)) }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("${vm.adapter.label} · ${stringResource(R.string.dash_made_up_names_times)}")
            Text(stringResource(R.string.dash_next_athlete), style = MaterialTheme.typography.titleMedium)
            s.activeAthletes().forEach { skier -> FilterChip(selected = s.nextAthleteId == skier.id, enabled = !vm.busy,
                onClick = { vm.update { it.selectNextAthlete(skier.id) } }, label = { Text("${skier.bib} · ${skier.name}") }) }
            Text(stringResource(R.string.dash_next_choice_kept), style = MaterialTheme.typography.bodySmall)
            val simulatedRound = s.nextAthleteId?.let { s.nextSimulatedRaceRound(it) }
            val canSimulate = !vm.busy && s.nextAthleteId != null && (s.race == null || simulatedRound != null)
            if (s.race != null && s.nextAthleteId != null) Text(if (simulatedRound != null)
                stringResource(R.string.dash_next_simulated_result, simulatedRound)
                else stringResource(R.string.dash_no_free_race_run))
            Button(onClick = { vm.simulate() }, enabled = canSimulate) { Text(stringResource(R.string.dash_simulate_finish)) }
            OutlinedButton(onClick = { vm.simulate(true) }, enabled = canSimulate) { Text(stringResource(R.string.dash_simulate_aborted)) }
            if (s.nextAthleteId != null) TextButton(onClick = { vm.update { it.selectNextAthlete(null) } }, enabled = !vm.busy) { Text(stringResource(R.string.dash_clear_choice)) }
            TextButton(onClick = { reset = true; simulator = false }, enabled = !vm.busy) { Text(stringResource(R.string.dash_reset_demo)) }
            vm.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }, confirmButton = { TextButton(onClick = { simulator = false }) { Text(stringResource(R.string.dash_close)) } })
    editingId?.let { id -> s.attempts.find { it.id == id }?.let { run ->
        EditRun(s, run, !vm.busy, { editingId = null }, error = vm.error) { a, complete, tags, note ->
            vm.update(onCommitted = { vm.editorCommitted("run") }) { it.correct(id, a, complete, tags, note) }
        }
    } }
    if (settings) Settings(s, !vm.busy, { settings = false }, error = vm.error) { team, tags ->
        vm.update(onCommitted = { vm.editorCommitted("settings") }) { it.updateTagSettings(team, tags) }
    }
    if (raceSetup) RaceSetup(s, !vm.busy && !vm.backupBusy, { raceSetup = false }, editingRace, vm.error) { next ->
        vm.update(onCommitted = { vm.editorCommitted("race-setup") }) { current ->
            require(current == s) { "Underlaget har ändrats. Öppna tävlingsupplägget igen." }
            next
        }
    }
    if (newSession) SessionSetup(s, !vm.busy, { newSession = false }, error = vm.error) { next ->
        vm.update(onCommitted = { vm.editorCommitted("session-new") }) { next }
    }
    if (editSession) SessionSetup(s, !vm.busy, { editSession = false }, editing = true, error = vm.error,
        initialSection = if (editSessionParticipants) "participants" else "details") { next ->
        vm.update(onCommitted = { vm.editorCommitted("session-edit") }) { next }
    }
    if (reset) AlertDialog(onDismissRequest = { reset = false }, title = { Text(stringResource(R.string.dash_reset_demo_data_q)) },
        text = { Text(stringResource(R.string.dash_reset_demo_data_text)) },
        confirmButton = { TextButton(onClick = { vm.update(onCommitted = { selected = 1; reset = false }) { demoTraining() } }) { Text(stringResource(R.string.dash_reset)) } },
        dismissButton = { TextButton(onClick = { reset = false }) { Text(stringResource(R.string.dash_cancel)) } })
}

@Composable internal fun AthleteList(s: Training, selected: Int, choose: (Int) -> Unit, modifier: Modifier, collapse: () -> Unit) {
    val hideLeftDesc = stringResource(R.string.dash_hide_athlete_list_left)
    Column(modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        var query by rememberSaveable { mutableStateOf("") }
        var participantsOnly by rememberSaveable { mutableStateOf(false) }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.dash_showing_athletes), Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
            IconButton(onClick = collapse, modifier = Modifier.semantics { contentDescription = hideLeftDesc }) { Text("‹", fontSize = 28.sp) }
        }
        OutlinedTextField(query, { query = it }, label = { Text(stringResource(R.string.dash_search_name_number)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
        FilterChip(selected = participantsOnly, onClick = { participantsOnly = !participantsOnly }, label = { Text(stringResource(R.string.dash_participants)) })
        val matches = findSkiers(if (participantsOnly) s.activeAthletes() else s.athletes, query)
        if (matches.isEmpty()) Text(stringResource(R.string.dash_no_athletes_match))
        matches.forEach { a ->
            Card(onClick = { choose(a.id) }, colors = CardDefaults.cardColors(containerColor =
                if (selected == a.id) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface)) {
                Column(Modifier.fillMaxWidth().padding(16.dp)) {
                    Text("${a.bib}  ${a.name}", fontWeight = FontWeight.Bold)
                    Text(stringResource(R.string.dash_runs_count, s.runs(a.id).size), style = MaterialTheme.typography.bodySmall)
                    if (a.id !in s.participantIds) Text(stringResource(R.string.dash_not_active_in_session), style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        Text(stringResource(R.string.dash_own_development_no_ranking), style = MaterialTheme.typography.bodySmall)
        Text(stringResource(R.string.dash_audit_count, s.audit.size), style = MaterialTheme.typography.bodySmall)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable internal fun RunDashboard(s: Training, athlete: Athlete, runs: List<Attempt>, latest: Attempt?, valid: List<Attempt>,
    improvement: Boolean, setMode: (Boolean) -> Unit, edit: (String) -> Unit, modifier: Modifier, readOnly: Boolean = false,
    tagEnabled: Boolean = true, onSkierSwipe: ((Int) -> Unit)? = null,
    setDnf: (String, Boolean) -> Unit = { _, _ -> }, toggleTag: (String, Int) -> Unit = { _, _ -> }, editChange: (Int) -> Unit = {},
    focusedRunId: String? = null, selectRun: ((String?) -> Unit)? = null,
    editNote: (String) -> Unit = {}, noteEditor: RunNoteUi? = null, noteRunId: String? = null,
    historyCollapsed: Boolean? = null, setHistoryCollapsed: ((Boolean) -> Unit)? = null,
    openParticipants: () -> Unit = {}, chooseNext: () -> Unit = {}, openSimulator: () -> Unit = {}) {
    var localHistoryCollapsed by rememberSaveable { mutableStateOf(false) }
    val hideHistory = historyCollapsed ?: localHistoryCollapsed
    fun collapseHistory(value: Boolean) { if (setHistoryCollapsed != null) setHistoryCollapsed(value) else localHistoryCollapsed = value }
    var localRunId by rememberSaveable(s.sessionId, athlete.id) { mutableStateOf<String?>(null) }
    val displayed = selectedCardRun(runs, if (selectRun != null) focusedRunId else localRunId)
    val cardScroll = rememberScrollState()
    val scope = rememberCoroutineScope()
    LaunchedEffect(focusedRunId) { if (selectRun != null) cardScroll.scrollTo(0) }
    fun chooseRun(id: String?) {
        if (selectRun != null) selectRun(id) else localRunId = id
        scope.launch { cardScroll.animateScrollTo(0) }
    }
    var taggedOnly by rememberSaveable(athlete.id) { mutableStateOf(false) }
    val visible = chartRuns(runs, taggedOnly)
    val emptySession = !readOnly && s.closedAt == null && s.attempts.isEmpty()
    val summary: @Composable (Dp) -> Unit = { plotHeight ->
        if (emptySession) EmptySessionGuide(s, tagEnabled, openParticipants, chooseNext, openSimulator)
        else {
        LatestResultCard(s, athlete, runs, displayed, readOnly, tagEnabled, toggleTag, setDnf, { chooseRun(null) }, edit,
            followingLatest = (if (selectRun != null) focusedRunId else localRunId) == null, selectRun = { chooseRun(it) }, editNote = editNote,
            noteEditor = noteEditor.takeIf { noteRunId == displayed?.id })
        Card {
            Column(Modifier.fillMaxWidth().padding(10.dp)) {
                Text(if (readOnly) stringResource(R.string.dash_development_during_session) else stringResource(R.string.dash_todays_development), style = MaterialTheme.typography.titleMedium)
                Text("${visible.size}/${runs.size} åk${if (taggedOnly) " · " + stringResource(R.string.dash_only_tagged) else ""} · " + if (improvement) stringResource(R.string.dash_won_seconds) else stringResource(R.string.dash_time_seconds_lower_faster), style = MaterialTheme.typography.bodySmall)
                key(s.sessionId, athlete.id) {
                    TrendChart(runs, s.markers, improvement, s.tags, taggedOnly,
                        onRunClick = { chooseRun(it) }, highlightedRunId = displayed?.id,
                        toolbar = { ChartModeSelector(improvement, setMode) }, setTaggedOnly = { taggedOnly = it }, plotHeight = plotHeight,
                        previousSkierEnabled = s.athletes.indexOf(athlete) > 0, nextSkierEnabled = s.athletes.indexOf(athlete) < s.athletes.lastIndex, onSkierSwipe = onSkierSwipe, changeLabels = runs.associate { it.id to s.tagDescription(it, TagCategory.CHANGE) }, runSequences = runs.associate { it.id to s.originalSequence(it) }, onChangeClick = if (!readOnly && tagEnabled) ({ marker -> editChange(s.markers.indexOfFirst { it === marker }) }) else null)
                }
            }
        }
        }
    }
    val history: @Composable () -> Unit = {
        if (visible.isEmpty()) Text(if (taggedOnly) stringResource(R.string.dash_no_tagged_runs) else stringResource(R.string.dash_no_runs_yet))
        visible.asReversed().forEach { item ->
            CompactRunRow(s, runs, item, selected = item.attempt.id == displayed?.id) { chooseRun(item.attempt.id) }
        }
    }
    BoxWithConstraints(modifier) {
        val sideHistory = maxWidth >= 800.dp && androidx.compose.ui.platform.LocalDensity.current.fontScale < 1.5f
        val density = LocalDensity.current
        val viewportHeight = maxHeight
        var nonPlotHeightPx by remember(s.sessionId, athlete.id, sideHistory) { mutableStateOf<Int?>(null) }
        val expandPlot = sideHistory && constraints.hasBoundedHeight && visible.isNotEmpty()
        val plotHeight = if (expandPlot && nonPlotHeightPx != null) {
            with(density) { (viewportHeight.roundToPx() - nonPlotHeightPx!!).coerceAtLeast(150.dp.roundToPx()).toDp() }
        } else 150.dp
        if (emptySession) Column(Modifier.fillMaxSize().verticalScroll(cardScroll)) { summary(150.dp) }
        else if (sideHistory) Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f).verticalScroll(cardScroll).onSizeChanged { size ->
                // Measure the content inside the scroll viewport, excluding the variable plot.
                // This lets the plot use spare room without pushing tags/notes off screen.
                nonPlotHeightPx = size.height - if (visible.isNotEmpty()) with(density) { plotHeight.roundToPx() } else 0
            }, verticalArrangement = Arrangement.spacedBy(8.dp)) { summary(plotHeight) }
            if (hideHistory) PanelHandle(left = false, expand = { collapseHistory(false) })
            else Column(Modifier.width(240.dp).fillMaxHeight()) {
                val hideHistoryRightDesc = stringResource(R.string.dash_hide_previous_runs_right)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(if (taggedOnly) stringResource(R.string.dash_tagged_runs) else stringResource(R.string.dash_previous_runs), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                    IconButton(onClick = { collapseHistory(true) }, modifier = Modifier.semantics { contentDescription = hideHistoryRightDesc }) { Text("›", fontSize = 28.sp) }
                }
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) { history() }
            }
        } else Column(Modifier.fillMaxSize().verticalScroll(cardScroll), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            summary(150.dp)
            TextButton(onClick = { collapseHistory(!hideHistory) }) { Text(if (hideHistory) stringResource(R.string.dash_show_previous_runs) else stringResource(R.string.dash_hide_previous_runs)) }
            if (!hideHistory) history()
        }
    }

}

@Composable private fun PanelHandle(left: Boolean, expand: () -> Unit) {
    val desc = if (left) stringResource(R.string.dash_show_athlete_list) else stringResource(R.string.dash_show_previous_runs)
    Column(Modifier.width(48.dp).fillMaxHeight()) {
        IconButton(onClick = expand, modifier = Modifier.semantics { contentDescription = desc }) {
            Text(if (left) "›" else "‹", fontSize = 28.sp)
        }
    }
}

