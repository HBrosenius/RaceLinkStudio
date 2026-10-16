package se.racelink.studio

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class TrainingViewModel(app: Application) : AndroidViewModel(app) {
    private val devicePreferences = app.getSharedPreferences("device-options", android.content.Context.MODE_PRIVATE)
    private val resultSound = ResultSound(app)
    var resultSoundEnabled by mutableStateOf(devicePreferences.getBoolean("result-sound", false)); private set
    var soundMessage by mutableStateOf<String?>(null); private set
    fun setResultSound(enabled: Boolean) {
        if (devicePreferences.edit().putBoolean("result-sound", enabled).commit()) {
            resultSoundEnabled = enabled; soundMessage = null
            if (!enabled) resultSound.close()
        } else soundMessage = "Ljudinställningen kunde inte sparas."
    }
    fun previewResultSound() { soundMessage = if (resultSound.play()) "Provljud spelades." else "Inget ljud spelades. Kontrollera enhetens ljudläge, aviseringsvolym och Stör ej." }
    private val store = TrainingStore(app)
    // Serializes committed writes so rapid commands queue instead of being silently dropped,
    // and each command is applied to the latest committed state rather than a stale snapshot.
    private val writeMutex = Mutex()
    private var inFlightWrites = 0
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
        inFlightWrites++
        busy = true
        viewModelScope.launch {
            try {
                writeMutex.withLock { withContext(Dispatchers.IO) { store.restore(previous, next) } }
                state = next; revision++; pendingRestore = null; error = null; undoEdit = null
                recoveryAvailable = previous != null || recoveryAvailable
                backupMessage = "Träningen har återställts."
            } catch (e: Exception) { backupMessage = "Återställningen misslyckades: ${e.message}. Sparad träning har inte ändrats." }
            finally { inFlightWrites--; if (inFlightWrites <= 0) { inFlightWrites = 0; busy = false } }
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
        if (state == null) return
        inFlightWrites++
        busy = true; error = null
        viewModelScope.launch {
            try {
                writeMutex.withLock {
                    val current = state ?: return@withLock
                    commitTraining(current, transform) { next -> withContext(Dispatchers.IO) { store.save(next) } }
                        .fold(onSuccess = { next ->
                            // Publish before unlocking: a waiting Main.immediate coroutine may
                            // resume during unlock and must observe this committed state.
                            state = next; error = null
                            if (resultSoundEnabled && hasNewCompletedResult(current, next)) resultSound.play()
                            if (next.sessionId != current.sessionId || next.closedAt != null) undoEdit = null
                            onCommitted()
                        }, onFailure = { error = "Ändringen sparades inte: ${it.message}" })
                }
            } finally {
                inFlightWrites--
                if (inFlightWrites <= 0) { inFlightWrites = 0; busy = false }
            }
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
        update(onCommitted = { undoEdit = null }) { current -> undo.apply(current).let { it.copy(audit = it.audit + AuditEntry.now(AuditKind.UNDO, "Ångra: ${undo.label}")) } }
    }
    fun dismissUndo() { undoEdit = null }
    fun simulate(incomplete: Boolean = false) = update { it.simulateNext(adapter, incomplete) }
    override fun onCleared() { resultSound.close(); store.close() }
}
