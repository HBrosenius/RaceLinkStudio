package se.racelink.studio

import android.app.Application
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

internal class RosterImportViewModel(app: Application) : AndroidViewModel(app) {
    var text by mutableStateOf<String?>(null); private set
    var busy by mutableStateOf(false); private set
    var error by mutableStateOf<String?>(null); private set
    fun clear() { text = null; error = null }
    fun read(uri: Uri?) {
        if (uri == null || busy) return
        busy = true; error = null; text = null
        viewModelScope.launch {
            try {
                text = withContext(Dispatchers.IO) {
                    val bytes = requireNotNull(getApplication<Application>().contentResolver.openInputStream(uri)).use { it.readBytesBounded(1_048_576) }
                    Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
                }
            } catch (e: Exception) { error = "CSV kunde inte läsas som UTF-8: ${e.message}" }
            finally { busy = false }
        }
    }
}

internal fun java.io.InputStream.readBytesBounded(limit: Int): ByteArray {
    val output = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    while (true) {
        val count = read(buffer)
        if (count < 0) break
        require(output.size() + count <= limit) { "Filen är för stor (max ${limit / 1024} kB)." }
        output.write(buffer, 0, count)
    }
    return output.toByteArray()
}

@Composable internal fun RosterImportDialog(s: Training, vm: TrainingViewModel, close: () -> Unit, importer: RosterImportViewModel = viewModel()) {
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument(), importer::read)
    val preview = remember(importer.text, s.roster) { importer.text?.let { previewRosterImport(it, s.roster) } }
    val enabled = !importer.busy && !vm.busy && !vm.backupBusy
    val leave = { if (enabled) { importer.clear(); close() } }
    EditorDialog(leave, title = { Text(stringResource(R.string.race_import_roster_title), style = MaterialTheme.typography.headlineSmall) }, text = {
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item {
                Text(stringResource(R.string.race_import_format_note))
                Text(stringResource(R.string.race_import_example))
                Text(stringResource(R.string.race_import_new_athletes_note))
                OutlinedButton(enabled = enabled, onClick = { picker.launch(arrayOf("text/*", "application/csv", "application/vnd.ms-excel", "application/octet-stream")) }) { Text(stringResource(R.string.race_choose_csv_file)) }
                StatusMessage(importer.error, isError = true)
                if (importer.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                preview?.let { p ->
                    StatusMessage(stringResource(R.string.race_import_preview, p.additions.size, p.rows.count { it.existing }, p.rows.count { it.error != null }))
                    StatusMessage(p.error, isError = true)
                    if (p.rows.any { it.error != null }) Text(stringResource(R.string.race_import_fix_errors))
                }
                SaveFeedback(!vm.busy, vm.error)
            }
            items(preview?.rows.orEmpty(), key = { it.line }) { row ->
                Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(12.dp)) {
                    Text(stringResource(R.string.race_import_row, row.line, row.bib?.toString() ?: "?", row.name))
                    val rowStatus = row.error ?: if (row.existing) stringResource(R.string.race_row_exists) else stringResource(R.string.race_row_new)
                    Text(rowStatus, color = if (row.error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
                } }
            }
        }
    }, confirmButton = { Button(enabled = enabled && preview?.canApply == true, onClick = {
        val source = importer.text ?: return@Button
        vm.update(onCommitted = { importer.clear(); close() }) { it.importRosterCsv(source) }
    }) { Text(stringResource(R.string.race_import_count, preview?.additions?.size ?: 0)) } }, dismissButton = { TextButton(enabled = enabled, onClick = leave) { Text(stringResource(R.string.race_cancel)) } })
}
