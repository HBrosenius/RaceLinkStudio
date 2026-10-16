package se.racelink.studio

import android.app.Application
import android.net.Uri
import android.content.Intent
import android.content.ClipData
import android.graphics.Bitmap
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.content.FileProvider
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

internal data class ExportArtifact(val file: File, val format: String, val name: String, val scope: String, val share: Boolean = false)

internal class ReportExportViewModel(app: Application) : AndroidViewModel(app) {
    var raceSnapshot by mutableStateOf<Training?>(null); private set
    val scope get() = raceSnapshot?.let { "${it.race!!.classes.size} klasser · ${it.participantIds.size} deltagare" } ?: rows?.let(::reportScope)
    var rows by mutableStateOf<List<ReportRun>?>(null); private set
    var busy by mutableStateOf(false); private set
    var awaitingPicker by mutableStateOf(false); private set
    var message by mutableStateOf<String?>(null); private set
    var ready by mutableStateOf<ExportArtifact?>(null); private set
    private var pending: ExportArtifact? = null
    var previewFile by mutableStateOf<File?>(null); private set
    var previewOpen by mutableStateOf(false)
    var previewFormat by mutableStateOf("PDF"); private set
    val working get() = busy || awaitingPicker || ready != null
    fun begin(selection: List<ReportRun>) { if (!working) { raceSnapshot = null; rows = selection.toList(); message = null; previewFile = null } }
    fun beginRace(s: Training) { if (!working) { begin(reportRuns(listOf(s))); raceSnapshot = s } }
    fun close() { if (!working) { rows = null; raceSnapshot = null; previewOpen = false } }
    fun clearMessage() { message = null }
    fun prepare(format: String, tags: Boolean, notes: Boolean, graphs: Boolean, preview: Boolean = false, logo: Bitmap? = null, share: Boolean = false) {
        if (working) return
        val selection = rows?.takeIf { it.isNotEmpty() } ?: run { message = "Öppna exporten igen och välj åk."; return }
        val raceSelection = raceSnapshot
        val exportScope = scope ?: reportScope(selection)
        if (format == "PNG" && raceSelection != null) { message = "Välj PDF eller CSV för tävlingens resultatlista."; return }
        if (format == "PNG" && !individualImageAllowed(selection)) { message = "Välj ett pass och en åkare i rapportfiltret för en personlig bild."; return }
        busy = true; message = null
        viewModelScope.launch {
            try {
                val artifact = withContext(Dispatchers.IO) {
                    val directory = File(getApplication<Application>().cacheDir, "reports").apply { mkdirs() }
                    val file = File(directory, "report-${UUID.randomUUID()}.${format.lowercase()}")
                    if (format == "PNG") {
                        val pdf = File(directory, "source-${UUID.randomUUID()}.pdf")
                        try {
                            pdf.writeBytes(createReportPdf(reportParagraphs(selection, tags, notes, graphs), logo))
                            createReportImage(pdf, file)
                        } finally { pdf.delete() }
                    } else file.writeBytes(if (format == "PDF") createReportPdf(raceSelection?.let { raceReportParagraphs(it) } ?: reportParagraphs(selection, tags, notes, graphs), logo)
                        else (raceSelection?.let(::raceResultsCsv) ?: reportCsv(selection)).toByteArray(Charsets.UTF_8))
                    ExportArtifact(file, format, "RaceLink-rapport.${format.lowercase()}", exportScope, share)
                }
                if (format != "CSV") { previewFile = artifact.file; previewFormat = format }
                if (preview) previewOpen = true else { pending = artifact; ready = artifact }
            } catch (e: Exception) { message = "Rapporten kunde inte skapas: ${e.message}" }
            finally { busy = false }
        }
    }
    fun pickerLaunched() { ready = null; awaitingPicker = true }
    fun shareOpened() { ready = null; pending = null; message = "Delningsväljaren öppnades. Välj själv mottagare eller avbryt; inget skickas automatiskt." }
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
@Composable internal fun ReportExportDialog(vm: ReportExportViewModel, close: () -> Unit, initialFormat: String = "PDF", branding: ReportBrandingViewModel = androidx.lifecycle.viewmodel.compose.viewModel()) {
    var format by rememberSaveable { mutableStateOf(initialFormat) }
    var tags by rememberSaveable { mutableStateOf(false) }
    var notes by rememberSaveable { mutableStateOf(false) }
    var graphs by rememberSaveable { mutableStateOf(true) }
    var includeLogo by rememberSaveable { mutableStateOf(false) }
    val context = LocalContext.current
    val png = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("image/png"), vm::write)
    val pdf = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf"), vm::write)
    val csv = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv"), vm::write)
    LaunchedEffect(vm.ready) {
        vm.ready?.let { artifact ->
            if (artifact.share) {
                try {
                    val uri = FileProvider.getUriForFile(context, "${context.packageName}.reports", artifact.file)
                    val intent = Intent(Intent.ACTION_SEND).apply {
                        type = when (artifact.format) { "PNG" -> "image/png"; "PDF" -> "application/pdf"; else -> "text/csv" }
                        putExtra(Intent.EXTRA_STREAM, uri)
                        clipData = ClipData.newRawUri("RaceLink-rapport", uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    context.startActivity(Intent.createChooser(intent, "Dela rapport"))
                    vm.shareOpened()
                } catch (e: Exception) { vm.pickerFailed(e.message) }
                return@LaunchedEffect
            }
            vm.pickerLaunched()
            try { when (artifact.format) { "PDF" -> pdf.launch(artifact.name); "PNG" -> png.launch(artifact.name); else -> csv.launch(artifact.name) } }
            catch (e: Exception) { vm.pickerFailed(e.message) }
        }
    }
    val leave = { if (!vm.working) { vm.close(); close() } }
    EditorDialog(onDismissRequest = leave, title = { Text(stringResource(R.string.rpt_export_title), style = MaterialTheme.typography.headlineSmall) }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(vm.scope ?: stringResource(R.string.rpt_scope_missing), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.rpt_export_frozen), style = MaterialTheme.typography.bodySmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = format == "PDF", enabled = !vm.working, onClick = { format = "PDF" }, label = { Text("PDF") })
                FilterChip(selected = format == "CSV", enabled = !vm.working, onClick = { format = "CSV" }, label = { Text("CSV") })
                if (vm.raceSnapshot == null) FilterChip(selected = format == "PNG", enabled = !vm.working, onClick = { format = "PNG" }, label = { Text(stringResource(R.string.rpt_image_png)) })
            }
            val imageAllowed = format != "PNG" || individualImageAllowed(vm.rows.orEmpty())
            if (format == "PNG") Text(if (imageAllowed) stringResource(R.string.rpt_image_personal) else stringResource(R.string.rpt_image_choose_scope))
            if (format != "CSV") {
                if (vm.raceSnapshot == null) {
                ExportOption(stringResource(R.string.rpt_include_graphs), graphs, !vm.working) { graphs = it }
                ExportOption(stringResource(R.string.rpt_include_tags), tags, !vm.working) { tags = it }
                ExportOption(stringResource(R.string.rpt_include_notes), notes, !vm.working) { notes = it }
                } else Text(stringResource(R.string.rpt_race_export_note))
                ExportOption(stringResource(R.string.rpt_include_logo), includeLogo, !vm.working && !branding.busy && branding.logo != null) { includeLogo = it }
                if (branding.logo == null) Text(stringResource(R.string.rpt_logo_hint), style = MaterialTheme.typography.bodySmall)
                if (vm.raceSnapshot == null) Text(stringResource(R.string.rpt_tags_off_default), style = MaterialTheme.typography.bodySmall)
                OutlinedButton(enabled = !vm.working && !vm.rows.isNullOrEmpty() && imageAllowed && !branding.busy, onClick = { vm.prepare(format, tags, notes, graphs, preview = true, logo = branding.logo.takeIf { includeLogo }) }) { Text(if (format == "PNG") stringResource(R.string.rpt_preview_image_label) else stringResource(R.string.rpt_preview_pdf_label)) }
            } else Text(if (vm.raceSnapshot != null) stringResource(R.string.rpt_csv_race_note) else stringResource(R.string.rpt_csv_note))
            OutlinedButton(enabled = !vm.working && !vm.rows.isNullOrEmpty() && imageAllowed && !branding.busy, onClick = { vm.prepare(format, tags, notes, graphs, logo = branding.logo.takeIf { includeLogo }, share = true) }) { Text(stringResource(R.string.rpt_share_format, format)) }
            if (vm.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            StatusMessage(vm.message)
        }
    }, confirmButton = { TextButton(enabled = !vm.working && !vm.rows.isNullOrEmpty() && !branding.busy && (format != "PNG" || individualImageAllowed(vm.rows.orEmpty())), onClick = { vm.prepare(format, tags, notes, graphs, logo = branding.logo.takeIf { includeLogo }) }) { Text(stringResource(R.string.rpt_save_format, format)) } },
        dismissButton = { TextButton(enabled = !vm.working, onClick = leave) { Text(stringResource(R.string.rpt_close)) } })
    if (vm.previewOpen) vm.previewFile?.let { file -> if (vm.previewFormat == "PNG") ReportImagePreview(file) { vm.previewOpen = false } else PdfPreview(file) { vm.previewOpen = false } }
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
