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

    fun savePublishConfig(serverUrl: String, token: String, enabled: Boolean, bibOnly: Boolean, indexPassword: String) {
        val config = PublishConfig(serverUrl.trim(), token.trim(), enabled, bibOnly)
        if (!publishSettings.write(config)) { publishMessage = "Inställningen kunde inte sparas."; return }
        val sessionId = state?.sessionId
        if (sessionId != null) {
            // Stored against the session, not the app: a new event must not inherit the previous
            // group's password, since the whole point is that the children cannot see each other.
            if (!publishSettings.writeIndexPassword(sessionId, indexPassword)) {
                publishMessage = "Inställningen kunde inte sparas."; return
            }
            indexPasswords[sessionId] = indexPassword.trim()
        }
        publishServerUrl = config.serverUrl; publishToken = config.token; publishEnabled = enabled
        publishBibOnly = bibOnly; publishMessage = null
    }
    fun publishNow() {
        val current = state ?: return
        publishMessage = null
        publishCommitted(current, force = true)
    }

    /**
     * Takes the pass off the web for good: the session and its per-skier links are deleted from the
     * server, and publishing is switched off in the same breath so the next committed run cannot put
     * them back. That is what makes consent withdrawal one tap rather than a call to whoever runs the
     * server, and turning publishing on again is a deliberate act.
     */
    fun withdrawFromWeb() {
        val current = state ?: return
        if (publishWithdrawing) return
        publishWithdrawing = true
        publishMessage = null
        viewModelScope.launch {
            try {
                publishMutex.withLock {
                    when (val result = withContext(Dispatchers.IO) { publisher.withdraw(current) }) {
                        is WithdrawResult.Removed -> {
                            publishSettings.write(PublishConfig(publishServerUrl, publishToken, false, publishBibOnly))
                            publishEnabled = false
                            // Nothing is outstanding any more: the page it was waiting for is gone.
                            publishPending = false
                            publishFailed = false
                            publishLastAt = null
                            publishMessage = result.message
                        }
                        is WithdrawResult.Failed -> publishMessage = result.message
                    }
                }
            } finally {
                publishWithdrawing = false
            }
        }
    }

    fun reportBrandingChanged() { state?.let { publishCommitted(it) } }

    /**
     * Re-reads the publish journal when the app returns to the foreground. A background retry may
     * have flushed an outstanding publish while this screen was away, which would otherwise leave a
     * stale "waiting" or failure line on screen; and if the publish is still outstanding it is
     * attempted again immediately, because a foreground attempt is far more timely than waiting for
     * the platform to run the background job.
     */
    fun refreshPublishStatus() {
        val current = state ?: return
        viewModelScope.launch {
            val pending = withContext(Dispatchers.IO) { publishJournal.isPending(current.sessionId) }
            publishPending = pending
            publishLastAt = withContext(Dispatchers.IO) { publishJournal.lastDeliveredAt(current.sessionId) }
            if (!pending && publishFailed) {
                publishFailed = false
                publishMessage = null
            }
            // Still outstanding and the app is open: attempt right now. A foreground attempt is
            // immediate and reliable, unlike the background job (which the platform may defer), and
            // a failure re-arms that job with a fresh backoff.
            if (pending && publishEnabled) publishCommitted(current, force = true)
        }
    }

    /** Network egress off the write lock; a no-op when publishing is off. Failures stay pending. */
    private fun publishCommitted(current: Training, force: Boolean = false) {
        if (!publishEnabled && !force) return
        viewModelScope.launch {
            publishMutex.withLock {
                val message = withContext(Dispatchers.IO) { publisher.publish(current, force) }
                if (message != null) publishMessage = message
                publishLastAt = withContext(Dispatchers.IO) { publishJournal.lastDeliveredAt(current.sessionId) }
                publishPending = withContext(Dispatchers.IO) { publishJournal.isPending(current.sessionId) }
                publishFailed = publishPending
            }
            // A failed publish is handed to the JobScheduler so it can flush once the link returns.
            if (publishPending) PublishScheduler.enqueue(appContext)
        }
    }
    private val store = TrainingStore(app)
    private val appContext = app.applicationContext
    private val publishSettings = PublishConfigStore(app)
    private val publishJournal = PublishStateStore(app)
    private val publisher = Publisher(publishSettings, publishJournal, HttpPublishTransport(), logo = { publishedReportLogo(app) })
    private val publishMutex = Mutex()
    /** Per-session link-index passwords, read back once so the settings field can be drawn synchronously. */
    private val indexPasswords = mutableMapOf<String, String>()

    /** The password stored for [sessionId]'s link index, or empty when the coach has not set one. */
    fun indexPasswordFor(sessionId: String): String = indexPasswords.getOrPut(sessionId) {
        publishSettings.indexPassword(sessionId)
    }
    var publishEnabled by mutableStateOf(false); private set
    var publishServerUrl by mutableStateOf(""); private set
    var publishToken by mutableStateOf(""); private set
    var publishMessage by mutableStateOf<String?>(null); private set
    var publishLastAt by mutableStateOf<String?>(null); private set
    var publishBibOnly by mutableStateOf(false); private set
    var publishPending by mutableStateOf(false); private set
    /** True while a withdrawal is in flight, so the button cannot be tapped twice. */
    var publishWithdrawing by mutableStateOf(false); private set
    /** True while the last attempt failed; used to drop a stale failure line once a retry lands. */
    var publishFailed by mutableStateOf(false); private set
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
                publishCommitted(next)
            } catch (e: Exception) { backupMessage = "Återställningen misslyckades: ${e.message}. Sparad träning har inte ändrats." }
            finally { inFlightWrites--; if (inFlightWrites <= 0) { inFlightWrites = 0; busy = false } }
        }
    }
    init {
        viewModelScope.launch {
            try {
                state = withContext(Dispatchers.IO) { store.read() ?: demoTraining().also(store::save) }
                backupReceipt = withContext(Dispatchers.IO) { store.backupReceipt() }
                val publish = withContext(Dispatchers.IO) { publisher.config() }
                publishEnabled = publish.enabled
                publishServerUrl = publish.serverUrl
                publishToken = publish.token
                publishBibOnly = publish.bibOnly
                state?.let { committed ->
                    publishLastAt = withContext(Dispatchers.IO) { publishJournal.lastDeliveredAt(committed.sessionId) }
                    publishPending = withContext(Dispatchers.IO) { publishJournal.isPending(committed.sessionId) }
                    publishFailed = publishPending
                    // Retry an outstanding publish from a previous run, or send the first snapshot.
                    publishCommitted(committed)
                }
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
                val committed = writeMutex.withLock {
                    val current = state ?: return@withLock null
                    commitTraining(current, transform) { next -> withContext(Dispatchers.IO) { store.save(next) } }
                        .fold(onSuccess = { next ->
                            // Publish before unlocking: a waiting Main.immediate coroutine may
                            // resume during unlock and must observe this committed state.
                            state = next; error = null
                            if (resultSoundEnabled && hasNewCompletedResult(current, next)) resultSound.play()
                            if (next.sessionId != current.sessionId || next.closedAt != null) undoEdit = null
                            onCommitted()
                            next
                        }, onFailure = { error = "Ändringen sparades inte: ${it.message}"; null })
                }
                // Network egress runs outside the write lock so capture is never blocked by it.
                committed?.let(::publishCommitted)
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
