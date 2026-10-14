package se.racelink.studio

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale

@Composable internal fun TrendChart(runs: List<Attempt>, markers: List<Marker>, improvement: Boolean,
    tags: List<CoachingTag> = emptyList(), taggedOnly: Boolean = false, onRunClick: ((String) -> Unit)? = null) {
    BoxWithConstraints {
    val pageSize = (maxWidth.value / 95f).toInt().coerceIn(1, 12)
    val visible = chartRuns(runs, taggedOnly)
    var page by rememberSaveable(taggedOnly, pageSize) { mutableIntStateOf(0) }
    val lastPage = ((visible.size - 1).coerceAtLeast(0)) / pageSize
    val currentPage = page.coerceIn(0, lastPage)
    val shown = chartPage(visible, currentPage, pageSize)
    var detailId by rememberSaveable { mutableStateOf<String?>(null) }
    fun open(run: Attempt) { if (onRunClick != null) onRunClick(run.id) else detailId = run.id }
    val ink = MaterialTheme.colorScheme.onSurface
    val line = MaterialTheme.colorScheme.primary
    val density = LocalDensity.current
    val baseline = runs.eligible().firstOrNull()?.durationMs ?: 0L
    val values = shown.map { item ->
        val run = item.attempt
        if (run.complete && run.durationMs != null) (if (improvement) baseline - run.durationMs else run.durationMs) / 1000f else null
    }
    val low = (values.filterNotNull().minOrNull() ?: 0f) - .3f
    val high = (values.filterNotNull().maxOrNull() ?: 1f) + .3f
    fun points(width: Float, height: Float): List<ChartPoint> {
        val top = with(density) { 12.dp.toPx() }; val bottom = height - with(density) { 32.dp.toPx() }
        return values.mapIndexed { i, value -> ChartPoint(width / shown.size.coerceAtLeast(1) * (i + .5f),
            if (value == null) height - with(density) { 8.dp.toPx() } else bottom - (value - low) / (high - low) * (bottom - top)) }
    }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (visible.isEmpty()) Text("Inga åk att visa i diagrammet.") else {
            if (lastPage > 0) Row(Modifier.horizontalScroll(rememberScrollState())) {
                TextButton(enabled = currentPage > 0, onClick = { page = currentPage - 1 }) { Text("Föregående åk") }
                Text("${currentPage + 1} / ${lastPage + 1}", Modifier.align(Alignment.CenterVertically))
                TextButton(enabled = currentPage < lastPage, onClick = { page = currentPage + 1 }) { Text("Nästa åk") }
                TextButton(enabled = currentPage < lastPage, onClick = { page = lastPage }) { Text("Senaste åken") }
            }
            BoxWithConstraints {
                val viewportWidth = maxWidth
                val chartWidth = viewportWidth
                Column {
                    if (chartWidth > viewportWidth) Text("Svep i diagrammet för fler åk →", style = MaterialTheme.typography.labelSmall)
                    key(currentPage, taggedOnly) {
                    Column(Modifier.horizontalScroll(rememberScrollState())) {
                    Column(Modifier.width(chartWidth)) {
                        Canvas(Modifier.fillMaxWidth().height(180.dp).padding(top = 6.dp)
                            .pointerInput(shown, improvement, baseline, onRunClick) {
                                detectTapGestures { tap ->
                                    val index = chartHit(points(size.width.toFloat(), size.height.toFloat()), tap.x, tap.y, with(density) { 28.dp.toPx() })
                                    if (index != null) open(shown[index].attempt)
                                }
                            }) {
                            val plotted = points(size.width, size.height)
                            val top = 12.dp.toPx(); val bottom = size.height - 32.dp.toPx()
                            val paint = android.graphics.Paint().apply {
                                color = if (ink.red > .5f) android.graphics.Color.WHITE else android.graphics.Color.DKGRAY
                                textSize = 11.sp.toPx(); isAntiAlias = true
                            }
                            for (i in 0..3) {
                                val value = low + (high - low) * i / 3
                                val y = bottom - (value - low) / (high - low) * (bottom - top)
                                drawLine(ink.copy(alpha = .12f), Offset(plotted.first().x, y), Offset(plotted.last().x, y))
                                drawContext.canvas.nativeCanvas.drawText(String.format(Locale.forLanguageTag("sv-SE"), "%.1f", value), 0f, y, paint)
                            }
                            markers.forEach { marker ->
                                val index = shown.indexOfLast { it.attempt.sequence <= marker.afterSequence }
                                if (index >= 0 && index < shown.lastIndex) {
                                    val x = (plotted[index].x + plotted[index + 1].x) / 2
                                    drawLine(ink.copy(alpha = .4f), Offset(x, top), Offset(x, bottom), 1.dp.toPx())
                                }
                            }
                            plotted.forEachIndexed { i, point ->
                                if (values[i] != null) {
                                    if (i > 0 && values[i - 1] != null && shown[i].number == shown[i - 1].number + 1)
                                        drawLine(line, Offset(plotted[i - 1].x, plotted[i - 1].y), Offset(point.x, point.y), 3.dp.toPx())
                                    drawCircle(line, 6.dp.toPx(), Offset(point.x, point.y))
                                }
                                val label = "${shown[i].number}${if (values[i] == null) " ×" else ""}"
                                drawContext.canvas.nativeCanvas.drawText(label, point.x - paint.measureText(label) / 2, size.height - 8.dp.toPx(), paint)
                            }
                        }
                        Row(Modifier.fillMaxWidth()) {
                            shown.forEach { item ->
                                Column(Modifier.weight(1f).clickable { open(item.attempt) }.padding(horizontal = 4.dp, vertical = 4.dp), horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    Text("Åk ${item.number}${if (!item.attempt.complete) " · Avbrutet" else ""}", style = MaterialTheme.typography.labelLarge)
                                    if (shown.any { it.attempt.tags.isNotEmpty() }) TagCategory.entries.forEach { category ->
                                        val labels = tags.filter { it.id in item.attempt.tags && it.category == category }.joinToString(" · ") { it.name }
                                        if (labels.isNotBlank()) Text("${if (category == TagCategory.RUN) "Åket" else "Nästa"}: $labels", textAlign = TextAlign.Center, style = MaterialTheme.typography.bodySmall,
                                            color = if (category == TagCategory.RUN) line else MaterialTheme.colorScheme.tertiary)
                                    }
                                }
                            }
                        }
                    }
                    }
                    }
                }
            }
        }
    }
    detailId?.let { id -> runs.find { it.id == id }?.let { run ->
        AlertDialog(onDismissRequest = { detailId = null }, title = { Text("Åk ${runs.indexOf(run) + 1} · ${if (run.complete) seconds(run.durationMs!!) + " s" else "Avbrutet"}") },
            text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                TagCategory.entries.forEach { category -> Text("${category.label}: ${tags.filter { it.id in run.tags && it.category == category }.joinToString(" · ") { it.name }.ifBlank { "Inga taggar" }}") }
                Text(run.note.ifBlank { "Ingen anteckning" })
            } }, confirmButton = { TextButton(onClick = { detailId = null }) { Text("Stäng") } })
    } }
    }
}
