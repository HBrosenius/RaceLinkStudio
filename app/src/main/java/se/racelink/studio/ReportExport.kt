package se.racelink.studio

import android.app.Application
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

internal data class ExportArtifact(val file: File, val format: String, val name: String, val scope: String)

internal class ReportExportViewModel(app: Application) : AndroidViewModel(app) {
    var rows by mutableStateOf<List<ReportRun>?>(null); private set
    var busy by mutableStateOf(false); private set
    var awaitingPicker by mutableStateOf(false); private set
    var message by mutableStateOf<String?>(null); private set
    var ready by mutableStateOf<ExportArtifact?>(null); private set
    private var pending: ExportArtifact? = null
    var previewFile by mutableStateOf<File?>(null); private set
    var previewOpen by mutableStateOf(false)
    val working get() = busy || awaitingPicker || ready != null
    fun begin(selection: List<ReportRun>) { if (!working) { rows = selection.toList(); message = null; previewFile = null } }
    fun close() { if (!working) { rows = null; previewOpen = false } }
    fun clearMessage() { message = null }
    fun prepare(format: String, tags: Boolean, notes: Boolean, graphs: Boolean, preview: Boolean = false) {
        if (working) return
        val selection = rows?.takeIf { it.isNotEmpty() } ?: run { message = "Öppna exporten igen och välj åk."; return }
        busy = true; message = null
        viewModelScope.launch {
            try {
                val artifact = withContext(Dispatchers.IO) {
                    val file = File(getApplication<Application>().cacheDir, "report-${UUID.randomUUID()}.${format.lowercase()}")
                    val bytes = if (format == "PDF") createReportPdf(reportParagraphs(selection, tags, notes, graphs)) else reportCsv(selection).toByteArray(Charsets.UTF_8)
                    file.writeBytes(bytes)
                    ExportArtifact(file, format, "RaceLink-rapport.${format.lowercase()}", reportScope(selection))
                }
                if (format == "PDF") previewFile = artifact.file
                if (preview) previewOpen = true else { pending = artifact; ready = artifact }
            } catch (e: Exception) { message = "Rapporten kunde inte skapas: ${e.message}" }
            finally { busy = false }
        }
    }
    fun pickerLaunched() { ready = null; awaitingPicker = true }
    fun pickerFailed(message: String?) { ready = null; pending = null; awaitingPicker = false; this.message = "Filväljaren kunde inte öppnas: $message" }
    fun write(uri: Uri?) {
        awaitingPicker = false
        if (uri == null) { pending = null; message = "Exporten avbröts. Ingen fil sparades."; return }
        val artifact = pending ?: run { message = "Exportunderlaget saknas. Skapa rapporten igen."; return }
        pending = null; busy = true
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    requireNotNull(getApplication<Application>().contentResolver.openOutputStream(uri, "wt")).use { output -> artifact.file.inputStream().use { it.copyTo(output) } }
                }
                message = "${artifact.format} sparad · ${artifact.scope}." + if (artifact.format == "CSV") " Taggar och anteckningar är inte inkluderade." else ""
            } catch (e: Exception) { message = "${artifact.format} kunde inte sparas: ${e.message}" }
            finally { busy = false }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable internal fun ReportExportDialog(vm: ReportExportViewModel, close: () -> Unit, initialFormat: String = "PDF") {
    var format by rememberSaveable { mutableStateOf(initialFormat) }
    var tags by rememberSaveable { mutableStateOf(false) }
    var notes by rememberSaveable { mutableStateOf(false) }
    var graphs by rememberSaveable { mutableStateOf(true) }
    val pdf = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf"), vm::write)
    val csv = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv"), vm::write)
    LaunchedEffect(vm.ready) {
        vm.ready?.let { artifact ->
            vm.pickerLaunched()
            try { if (artifact.format == "PDF") pdf.launch(artifact.name) else csv.launch(artifact.name) }
            catch (e: Exception) { vm.pickerFailed(e.message) }
        }
    }
    val leave = { if (!vm.working) { vm.close(); close() } }
    AlertDialog(onDismissRequest = leave, title = { Text("Exportera rapport") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(vm.rows?.let(::reportScope) ?: "Urvalet saknas. Stäng och öppna exporten igen.", style = MaterialTheme.typography.titleMedium)
            Text("Urvalet fryses när exportfönstret öppnas. Nya resultat ändrar inte den här exporten.", style = MaterialTheme.typography.bodySmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = format == "PDF", enabled = !vm.working, onClick = { format = "PDF" }, label = { Text("PDF") })
                FilterChip(selected = format == "CSV", enabled = !vm.working, onClick = { format = "CSV" }, label = { Text("CSV") })
            }
            if (format == "PDF") {
                ExportOption("Inkludera diagram", graphs, !vm.working) { graphs = it }
                ExportOption("Inkludera coachningstaggar och ändringar", tags, !vm.working) { tags = it }
                ExportOption("Inkludera anteckningar", notes, !vm.working) { notes = it }
                Text("Taggar och anteckningar är avstängda som standard.", style = MaterialTheme.typography.bodySmall)
                OutlinedButton(enabled = !vm.working && !vm.rows.isNullOrEmpty(), onClick = { vm.prepare("PDF", tags, notes, graphs, preview = true) }) { Text("Förhandsvisa PDF") }
            } else Text("CSV innehåller pass, datum, åkare och resultat. Taggar, anteckningar och diagram inkluderas inte.")
            if (vm.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            vm.message?.let { Text(it) }
        }
    }, confirmButton = { TextButton(enabled = !vm.working && !vm.rows.isNullOrEmpty(), onClick = { vm.prepare(format, tags, notes, graphs) }) { Text("Spara $format") } },
        dismissButton = { TextButton(enabled = !vm.working, onClick = leave) { Text("Stäng") } })
    if (vm.previewOpen) vm.previewFile?.let { file -> PdfPreview(file) { vm.previewOpen = false } }
}

@Composable private fun ExportOption(label: String, selected: Boolean, enabled: Boolean, change: (Boolean) -> Unit) {
    androidx.compose.foundation.layout.Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(value = selected, enabled = enabled, role = Role.Checkbox, onValueChange = change),
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
    ) {
        Checkbox(checked = selected, enabled = enabled, onCheckedChange = null)
        Text(label, Modifier.weight(1f))
    }
}
