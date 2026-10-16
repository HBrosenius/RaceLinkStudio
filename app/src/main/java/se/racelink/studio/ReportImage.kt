package se.racelink.studio

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

// Keep individual exports to one skier/session. PDF remains available for broader scopes.
internal fun individualImageAllowed(rows: List<ReportRun>) = rows.isNotEmpty() && rows.map { it.session.sessionId }.distinct().size == 1 && rows.map { it.athlete.id }.distinct().size == 1

internal fun createReportImage(pdf: File, output: File) {
    ParcelFileDescriptor.open(pdf, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
        PdfRenderer(descriptor).use { renderer ->
            require(renderer.pageCount <= 6) { "Bildrapporten blir för lång. Minska urvalet/anteckningarna eller välj PDF (max 6 sidor i en bild)." }
            val bitmap = Bitmap.createBitmap(1190, 1684 * renderer.pageCount, Bitmap.Config.ARGB_8888)
            try {
                bitmap.eraseColor(Color.WHITE)
                val canvas = Canvas(bitmap)
                for (index in 0 until renderer.pageCount) {
                    val pageBitmap = Bitmap.createBitmap(1190, 1684, Bitmap.Config.ARGB_8888)
                    try {
                        pageBitmap.eraseColor(Color.WHITE)
                        renderer.openPage(index).use { it.render(pageBitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY) }
                        canvas.drawBitmap(pageBitmap, 0f, index * 1684f, null)
                    } finally { pageBitmap.recycle() }
                }
                output.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            } finally { bitmap.recycle() }
        }
    }
}

@Composable internal fun ReportImagePreview(file: File, close: () -> Unit) {
    var bitmap by remember(file.path) { mutableStateOf<Bitmap?>(null) }
    var error by remember(file.path) { mutableStateOf<String?>(null) }
    LaunchedEffect(file.path) {
        try { bitmap = withContext(Dispatchers.IO) {
            val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
            android.graphics.BitmapFactory.decodeFile(file.path, bounds)
            requireNotNull(android.graphics.BitmapFactory.decodeFile(file.path, android.graphics.BitmapFactory.Options().apply {
                inSampleSize = if (bounds.outWidth.toLong() * bounds.outHeight > 4_000_000) 2 else 1
            }))
        } }
        catch (e: Exception) { error = "Bilden kunde inte visas: ${e.message}" }
    }
    AppFullScreenDialog(close) {
        Surface(Modifier.fillMaxSize()) { Column(Modifier.padding(16.dp)) {
            Text(stringResource(R.string.rpt_preview_image_title), style = MaterialTheme.typography.titleMedium)
            TextButton(onClick = close) { Text(stringResource(R.string.rpt_close)) }
            error?.let { Text(it) }
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                bitmap?.let { Image(it.asImageBitmap(), stringResource(R.string.rpt_image_desc), Modifier.fillMaxWidth().aspectRatio(it.width.toFloat() / it.height)) }
            }
        } }
    }
}
