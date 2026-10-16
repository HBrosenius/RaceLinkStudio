package se.racelink.studio

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.AtomicFile
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

internal class ReportBrandingViewModel(app: Application) : AndroidViewModel(app) {
    private val file = AtomicFile(File(app.filesDir, "report-logo.png"))
    var logo by mutableStateOf<Bitmap?>(null); private set
    var busy by mutableStateOf(false); private set
    var message by mutableStateOf<String?>(null); private set
    init { viewModelScope.launch { busy = true; try { logo = withContext(Dispatchers.IO) { if (file.baseFile.exists()) file.openRead().use { BitmapFactory.decodeStream(it) } else null } } catch (_: Exception) { message = "Den sparade logotypen kunde inte läsas. Rapporter kan skapas utan logotyp." } finally { busy = false } } }
    fun select(uri: Uri?, onChanged: () -> Unit = {}) {
        if (uri == null || busy) return
        busy = true; message = null
        viewModelScope.launch {
            try {
                val next = withContext(Dispatchers.IO) {
                    val bytes = requireNotNull(getApplication<Application>().contentResolver.openInputStream(uri)).use { it.readBytesBounded(5 * 1024 * 1024) }
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                    require(bounds.outWidth > 0 && bounds.outHeight > 0) { "Välj en PNG-, JPEG- eller WebP-bild." }
                    val options = BitmapFactory.Options().apply {
                        inSampleSize = 1
                        while (maxOf(bounds.outWidth, bounds.outHeight) / inSampleSize > 512) inSampleSize *= 2
                    }
                    val bitmap = requireNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)) { "Bilden kunde inte läsas." }
                    val output = file.startWrite()
                    try { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)); file.finishWrite(output) }
                    catch (e: Exception) { file.failWrite(output); throw e }
                    bitmap
                }
                logo = next; message = "Logotypen sparades på denna enhet."
                onChanged()
            } catch (e: Exception) { message = "Logotypen kunde inte sparas: ${e.message}" }
            finally { busy = false }
        }
    }
    fun remove(onChanged: () -> Unit = {}) {
        if (busy) return
        busy = true
        viewModelScope.launch {
            try { withContext(Dispatchers.IO) { file.delete() }; logo = null; message = "Logotypen togs bort."; onChanged() }
            catch (e: Exception) { message = "Logotypen kunde inte tas bort: ${e.message}" }
            finally { busy = false }
        }
    }
}

@Composable internal fun ReportBrandingSettings(vm: ReportBrandingViewModel = viewModel(), onChanged: () -> Unit = {}) {
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { vm.select(it, onChanged) }
    HorizontalDivider()
    Text(stringResource(R.string.rpt_branding_title), style = MaterialTheme.typography.titleMedium)
    Text(stringResource(R.string.rpt_branding_description))
    vm.logo?.let { Image(it.asImageBitmap(), stringResource(R.string.rpt_branding_logo_desc), Modifier.size(96.dp)) }
    OutlinedButton(enabled = !vm.busy, onClick = { picker.launch(arrayOf("image/png", "image/jpeg", "image/webp")) }) { Text(stringResource(R.string.rpt_choose_logo)) }
    if (vm.logo != null) TextButton(enabled = !vm.busy, onClick = { vm.remove(onChanged) }) { Text(stringResource(R.string.rpt_remove_logo)) }
    StatusMessage(vm.message)
}

/** Small, embedded PNG for personal webpages; image read failure never blocks publishing results. */
internal fun publishedReportLogo(context: android.content.Context): String? = runCatching {
    val file = AtomicFile(File(context.filesDir, "report-logo.png"))
    if (!file.baseFile.exists()) return null
    val source = file.openRead().use { input ->
        val bytes = input.readBytesBounded(2 * 1024 * 1024)
        require(bytes.take(8) == listOf(137, 80, 78, 71, 13, 10, 26, 10).map { it.toByte() })
        requireNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size))
    }
    val scale = minOf(1f, 256f / maxOf(source.width, source.height))
    val image = if (scale == 1f) source else Bitmap.createScaledBitmap(source,
        maxOf(1, (source.width * scale).toInt()), maxOf(1, (source.height * scale).toInt()), true)
    try {
        val bytes = java.io.ByteArrayOutputStream().use { output ->
            check(image.compress(Bitmap.CompressFormat.PNG, 100, output)); output.toByteArray()
        }
        require(bytes.size <= 300_000)
        android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
    } finally {
        if (image !== source) image.recycle()
        source.recycle()
    }
}.getOrNull()
