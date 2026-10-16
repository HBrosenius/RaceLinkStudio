package se.racelink.studio

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File

// A4 pages in PDF points, with measured wrapping and page breaks for long notes/names.
internal fun createReportPdf(paragraphs: List<String>, logo: Bitmap? = null): ByteArray {
    val doc = PdfDocument()
    try {
    val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    var page: PdfDocument.Page? = null
    var y = 0f
    var number = 0
    var sessionHeading = ""
    var skierHeading = ""
    fun finish() { page?.let { doc.finishPage(it) }; page = null }
    fun newPage() {
        finish()
        number++
        page = doc.startPage(PdfDocument.PageInfo.Builder(595, 842, number).create())
        paint.textSize = 9f; paint.isFakeBoldText = false; paint.color = Color.DKGRAY
        page!!.canvas.drawText("RaceLink Studio | SIMULATOR", 40f, 28f, paint)
        logo?.let { image ->
            val scale = minOf(55f / image.width, 30f / image.height)
            val width = image.width * scale; val height = image.height * scale
            page!!.canvas.drawBitmap(image, null, android.graphics.RectF(555f - width, 12f, 555f, 12f + height), Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
        }
        val continuation = listOf(sessionHeading, skierHeading).filter { it.isNotBlank() }.joinToString(" | ")
        if (continuation.isNotBlank()) {
            val length = paint.breakText(continuation, true, if (logo == null) 515f else 450f, null)
            page!!.canvas.drawText(continuation.take(length), 40f, 43f, paint)
        }
        page!!.canvas.drawText("Sida $number", 500f, 818f, paint)
        y = 68f
    }
    newPage()
    paragraphs.forEach { paragraph ->
        if (paragraph.startsWith(PDF_GRAPH_PREFIX)) {
            if (y + 200f > 790f) newPage()
            drawPdfGraph(page!!.canvas, pdfGraphPoints(paragraph), y)
            y += 200f
            return@forEach
        }
        val heading = paragraph.startsWith("# ") || paragraph.startsWith("## ")
        val fontSize = if (paragraph.startsWith("# ")) 17f else if (heading) 13f else 11f
        val text = paragraph.removePrefix("## ").removePrefix("# ")
        if (paragraph.startsWith("## ")) skierHeading = text
        else if (paragraph.startsWith("# ") && !text.startsWith("RaceLink Studio")) {
            sessionHeading = text; skierHeading = ""
        }
        if (heading && y > 730f) newPage()
        text.split('\n').forEach { rawLine ->
            var remaining = rawLine
            do {
                if (y + fontSize + 6f > 790f) newPage()
                paint.textSize = fontSize; paint.isFakeBoldText = heading
                paint.color = if (heading) Color.rgb(35, 70, 130) else Color.rgb(30, 30, 30)
                val count = paint.breakText(remaining, true, 515f, null).coerceAtLeast(1).coerceAtMost(remaining.length)
                val end = if (count < remaining.length) remaining.lastIndexOf(' ', count - 1).takeIf { it > 0 } ?: count else count
                page!!.canvas.drawText(remaining.take(end), 40f, y, paint)
                y += fontSize + 5f
                remaining = remaining.drop(end).trimStart()
            } while (remaining.isNotEmpty())
        }
        y += if (heading) 7f else 4f
    }
    finish()
    return ByteArrayOutputStream().use { output -> doc.writeTo(output); output.toByteArray() }
    } finally { doc.close() }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable internal fun PdfPreview(file: File, close: () -> Unit) {
    var index by rememberSaveable { mutableIntStateOf(0) }
    var count by remember { mutableIntStateOf(1) }
    var bitmap by remember { mutableStateOf<Bitmap?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(file.path, index) {
        bitmap = null; error = null
        try {
            val rendered = withContext(Dispatchers.IO) {
                ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
                    PdfRenderer(fd).use { renderer ->
                        renderer.openPage(index).use { page ->
                            val result = Bitmap.createBitmap(page.width * 2, page.height * 2, Bitmap.Config.ARGB_8888)
                            result.eraseColor(Color.WHITE)
                            page.render(result, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                            result to renderer.pageCount
                        }
                    }
                }
            }
            bitmap = rendered.first; count = rendered.second
        } catch (e: Exception) { error = "Förhandsvisning misslyckades: ${e.message}" }
    }
    AppFullScreenDialog(close) {
        Surface(Modifier.fillMaxSize().safeDrawingPadding()) {
            Column(Modifier.padding(16.dp)) {
                FlowRow {
                    Text(stringResource(R.string.rpt_pdf_page, index + 1, count))
                    TextButton(onClick = { index-- }, enabled = index > 0) { Text(stringResource(R.string.rpt_previous)) }
                    TextButton(onClick = { index++ }, enabled = index < count - 1) { Text(stringResource(R.string.rpt_next)) }
                    TextButton(onClick = close) { Text(stringResource(R.string.rpt_close)) }
                }
                error?.let { Text(it) }
                bitmap?.let { Image(it.asImageBitmap(), stringResource(R.string.rpt_pdf_page_desc, index + 1), Modifier.fillMaxWidth().weight(1f)) }
            }
        }
    }
}
