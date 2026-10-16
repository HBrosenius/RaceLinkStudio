package se.racelink.studio

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import java.util.Locale

@OptIn(ExperimentalLayoutApi::class)
@Composable internal fun TrendChart(runs: List<Attempt>, markers: List<Marker>, improvement: Boolean,
    tags: List<CoachingTag> = emptyList(), taggedOnly: Boolean = false, onRunClick: ((String) -> Unit)? = null,
    onSkierSwipe: ((Int) -> Unit)? = null, changeLabels: Map<String, String> = emptyMap(), runSequences: Map<String, Int> = emptyMap(), onChangeClick: ((Marker) -> Unit)? = null,
    highlightedRunId: String? = null, toolbar: (@Composable () -> Unit)? = null,
    setTaggedOnly: ((Boolean) -> Unit)? = null, previousSkierEnabled: Boolean = true, nextSkierEnabled: Boolean = true,
    plotHeight: Dp = 150.dp) {
    var zoom by rememberSaveable { mutableFloatStateOf(1f) }
    var pan by rememberSaveable { mutableFloatStateOf(0f) }
    var showTags by rememberSaveable { mutableStateOf(false) }
    BoxWithConstraints(Modifier.clipToBounds()) {
    val viewportWidth = maxWidth
    val viewportPx = with(LocalDensity.current) { maxWidth.toPx() }
    val leftInset = with(LocalDensity.current) { 48.dp.toPx() }
    val rightInset = with(LocalDensity.current) { 24.dp.toPx() }
    fun plotWidth(width: Float) = (width - leftInset - rightInset).coerceAtLeast(1f)
    val plotWidthDp = with(LocalDensity.current) { plotWidth(viewportPx).toDp() }
    fun boundedPan(value: Float, scale: Float) = value.coerceIn(-plotWidth(viewportPx) * (scale - 1f), 0f)
    fun xAt(fraction: Float, width: Float = viewportPx) = leftInset + plotWidth(width) * zoom * fraction + boundedPan(pan, zoom)
    val gestureModifier = Modifier.pointerInput(viewportPx, onSkierSwipe) {
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false)
            var pinched = false
            var distance = 0f
            var dragging = false
            do {
                val event = awaitPointerEvent()
                val fingers = event.changes.count { it.pressed }
                if (fingers >= 2) {
                    pinched = true
                    val factor = event.calculateZoom()
                    val center = event.calculateCentroid(useCurrent = false).x - leftInset
                    val next = (zoom * factor).coerceIn(1f, maxOf(8f, runs.size / 4f))
                    pan = boundedPan(center - (center - pan) * (next / zoom) + event.calculatePan().x, next)
                    zoom = next
                    event.changes.forEach { it.consume() }
                } else if (!pinched && fingers == 1) {
                    val delta = event.calculatePan().x
                    distance += delta
                    if (kotlin.math.abs(distance) > viewConfiguration.touchSlop) dragging = true
                    if (dragging) {
                        if (zoom > 1f) pan = boundedPan(pan + delta, zoom)
                        event.changes.forEach { it.consume() }
                    }
                }
            } while (event.changes.any { it.pressed })
            if (!pinched && zoom <= 1f && kotlin.math.abs(distance) >= 64.dp.toPx())
                onSkierSwipe?.invoke(if (distance < 0) -1 else 1)
        }
    }
    val visible = chartRuns(runs, taggedOnly)
    val shown = visible
    fun sequence(run: Attempt) = runSequences[run.id] ?: run.sequence
    val boundaries = markers.map { marker ->
        val following = visible.indexOfFirst { sequence(it.attempt) > marker.afterSequence }
        marker to (if (following < 0) shown.size else following).toFloat() / shown.size.coerceAtLeast(1)
    }
    fun markerLabel(marker: Marker) = (listOf(marker.label).filter { it.isNotBlank() } +
        tags.filter { it.id in marker.tags }.map { it.name }).joinToString(" · ")
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(highlightedRunId) { if (highlightedRunId != null) selectedId = highlightedRunId }
    var options by remember { mutableStateOf(false) }
    var chooseRun by remember { mutableStateOf(false) }
    var changeGroup by remember { mutableStateOf<List<Marker>?>(null) }
    fun scaleTo(nextScale: Float) {
        val next = nextScale.coerceIn(1f, maxOf(8f, runs.size / 4f))
        pan = boundedPan((viewportPx / 2 - leftInset) - (viewportPx / 2 - leftInset - boundedPan(pan, zoom)) * next / zoom, next)
        zoom = next
    }
    var detailId by rememberSaveable { mutableStateOf<String?>(null) }
    fun open(run: Attempt) { selectedId = run.id; if (onRunClick != null) onRunClick(run.id) else detailId = run.id }
    val ink = MaterialTheme.colorScheme.onSurface
    val line = MaterialTheme.colorScheme.primary
    val bestColor = MaterialTheme.colorScheme.tertiary
    val bestTime = runs.eligible().minOfOrNull { it.durationMs!! }
    fun isBest(run: Attempt) = run.complete && run.durationMs != null && run.durationMs == bestTime
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
        return values.mapIndexed { i, value -> ChartPoint(xAt((i + .5f) / shown.size.coerceAtLeast(1), width),
            if (value == null) height - with(density) { 8.dp.toPx() } else bottom - (value - low) / (high - low) * (bottom - top)) }
    }
    @Composable fun ChartToolbar() {
        Surface(color = MaterialTheme.colorScheme.surface, shape = MaterialTheme.shapes.medium) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                FlowRow(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                toolbar?.invoke()
                Row(Modifier.heightIn(min = 48.dp).toggleable(value = showTags, role = Role.Switch, onValueChange = { showTags = it }), verticalAlignment = Alignment.CenterVertically) {
                    Text("Visa taggar", style = MaterialTheme.typography.labelLarge)
                    Spacer(Modifier.width(8.dp))
                    Switch(checked = showTags, onCheckedChange = null)
                }
                }
                Box {
                    IconButton(onClick = { options = true }, modifier = Modifier.semantics { contentDescription = "Diagramval" }) { Text("⋮", fontSize = 24.sp) }
                    DropdownMenu(expanded = options, onDismissRequest = { options = false }) {
                        if (setTaggedOnly != null) DropdownMenuItem(text = { Text("Bara taggade åk") },
                            trailingIcon = { Checkbox(checked = taggedOnly, onCheckedChange = null) },
                            onClick = { setTaggedOnly(!taggedOnly); options = false })
                        DropdownMenuItem(text = { Text("Välj åk") }, enabled = shown.isNotEmpty(), onClick = { options = false; chooseRun = true })
                        HorizontalDivider()
                        DropdownMenuItem(text = { Text("Zooma in") }, enabled = zoom < maxOf(8f, runs.size / 4f), onClick = { scaleTo(zoom * 1.5f); options = false })
                        DropdownMenuItem(text = { Text("Zooma ut") }, enabled = zoom > 1f, onClick = { scaleTo(zoom / 1.5f); options = false })
                        DropdownMenuItem(text = { Text("Visa alla åk") }, enabled = zoom > 1f, onClick = { zoom = 1f; pan = 0f; options = false })
                        if (zoom > 1f) {
                            DropdownMenuItem(text = { Text("Flytta till tidigare åk") }, enabled = boundedPan(pan, zoom) < 0f,
                                onClick = { pan = boundedPan(pan + viewportPx / 2, zoom); options = false })
                            DropdownMenuItem(text = { Text("Flytta till senare åk") }, enabled = boundedPan(pan, zoom) > -plotWidth(viewportPx) * (zoom - 1f),
                                onClick = { pan = boundedPan(pan - viewportPx / 2, zoom); options = false })
                        }
                        if (onSkierSwipe != null) {
                            HorizontalDivider()
                            DropdownMenuItem(text = { Text("Föregående åkare") }, enabled = previousSkierEnabled, onClick = { options = false; onSkierSwipe(-1) })
                            DropdownMenuItem(text = { Text("Nästa åkare") }, enabled = nextSkierEnabled, onClick = { options = false; onSkierSwipe(1) })
                        }
                    }
                }
            }
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            ChartToolbar()
        if (visible.isEmpty()) Text("Inga åk att visa i diagrammet.") else {
            Column(gestureModifier.fillMaxWidth().clipToBounds()) {
                        Canvas(Modifier.fillMaxWidth().height(plotHeight.coerceAtLeast(150.dp)).padding(top = 6.dp)
                            .semantics { contentDescription = "Utvecklingsdiagram med ${shown.size} åk. Tryck på ett åk eller använd Diagramval för åkval och zoom." }
                            .pointerInput(shown, improvement, baseline, onRunClick, onChangeClick, zoom, pan) {
                                detectTapGestures { tap ->
                                    val index = chartHit(points(size.width.toFloat(), size.height.toFloat()), tap.x, tap.y, with(density) { 28.dp.toPx() })
                                    val boundary = boundaries.minByOrNull { (_, fraction) -> kotlin.math.abs(xAt(fraction, size.width.toFloat()) - tap.x) }
                                    if (onChangeClick != null && boundary != null && kotlin.math.abs(xAt(boundary.second, size.width.toFloat()) - tap.x) <= 12.dp.toPx() && (index == null || tap.y < 24.dp.toPx())) onChangeClick(boundary.first)
                                    else if (index != null) open(shown[index].attempt)
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
                            var lastLabelRight = -Float.MAX_VALUE
                            boundaries.forEach { (_, fraction) ->
                                val x = xAt(fraction, size.width.toFloat())
                                if (x in leftInset..(size.width - rightInset)) drawLine(ink.copy(alpha = .55f), Offset(x, top), Offset(x, bottom), 1.dp.toPx())
                            }
                            plotted.forEachIndexed { i, point ->
                                if (values[i] != null) {
                                    clipRect(left = leftInset, right = size.width - rightInset) {
                                    if (i > 0 && values[i - 1] != null && shown[i].number == shown[i - 1].number + 1)
                                        drawLine(line, Offset(plotted[i - 1].x, plotted[i - 1].y), Offset(point.x, point.y), 3.dp.toPx())
                                    if (isBest(shown[i].attempt)) {
                                        drawCircle(bestColor, 10.dp.toPx(), Offset(point.x, point.y), style = Stroke(2.dp.toPx()))
                                        drawCircle(bestColor, 6.dp.toPx(), Offset(point.x, point.y))
                                    } else drawCircle(line, 6.dp.toPx(), Offset(point.x, point.y))
                                    }
                                }
                                val label = "${shown[i].number}${if (values[i] == null) " ×" else ""}"
                                if (shown[i].attempt.id == (highlightedRunId ?: selectedId)) clipRect(left = leftInset, right = size.width - rightInset) {
                                    drawCircle(ink, 14.dp.toPx(), Offset(point.x, point.y), style = Stroke(2.dp.toPx()))
                                }
                                val labelWidth = paint.measureText(label)
                                val labelLeft = point.x - labelWidth / 2
                                if (labelLeft >= leftInset && labelLeft >= lastLabelRight + 6.dp.toPx() && point.x + labelWidth / 2 <= size.width) {
                                    drawContext.canvas.nativeCanvas.drawText(label, labelLeft, size.height - 8.dp.toPx(), paint)
                                    lastLabelRight = point.x + labelWidth / 2
                                }
                            }
                        }
                val labelGap = with(density) { 170.dp.toPx() }
                val groups = mutableListOf<MutableList<Pair<Marker, Float>>>()
                boundaries.map { (marker, fraction) -> marker to (xAt(fraction)) }
                    .filter { it.second in leftInset..(viewportPx - rightInset) }.sortedBy { it.second }.forEach { item ->
                        if (groups.isEmpty() || item.second - groups.last().first().second >= labelGap) groups.add(mutableListOf(item)) else groups.last().add(item)
                    }
                if (groups.isNotEmpty()) Box(Modifier.fillMaxWidth().height(48.dp)) {
                    groups.forEach { group ->
                        val labelWidth = minOf(165.dp, viewportWidth)
                        val x = group.map { it.second }.average().toFloat()
                        val position = (with(density) { x.toDp() } - labelWidth / 2).coerceIn(0.dp, viewportWidth - labelWidth)
                        val label = if (group.size == 1) "◆ ${markerLabel(group.first().first)}" else "◆ ${group.size} ändringar · visa"
                        Text(label, Modifier.offset(x = position).width(labelWidth).heightIn(min = 48.dp)
                            .clickable(onClickLabel = "Visa ändring") {
                                if (group.size == 1 && onChangeClick != null) onChangeClick(group.first().first)
                                else changeGroup = group.map { it.first }
                            }, maxLines = 2, overflow = TextOverflow.Ellipsis,
                            textAlign = TextAlign.Center, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.tertiary)
                    }
                }
                val slotWidth = plotWidthDp * zoom / shown.size.coerceAtLeast(1)
                if (showTags && slotWidth >= 75.dp) Box(Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    Row(Modifier.wrapContentWidth(Alignment.Start, unbounded = true).requiredWidth(plotWidthDp * zoom).offset(x = with(density) { (leftInset + boundedPan(pan, zoom)).toDp() })) {
                        shown.forEach { item ->
                            Column(Modifier.width(slotWidth).heightIn(min = 48.dp).clickable { open(item.attempt) }.padding(horizontal = 3.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                Text("Åk ${item.number}${if (isBest(item.attempt)) " ★" else ""}${if (!item.attempt.complete) " · DNF" else ""}", style = MaterialTheme.typography.labelSmall)
                                listOf(TagCategory.RUN, TagCategory.NEXT_FOCUS).forEach { category ->
                                    val labels = tags.filter { it.id in item.attempt.tags && it.category == category }.joinToString(" · ") { it.name }
                                    if (labels.isNotBlank()) Text(labels, textAlign = TextAlign.Center,
                                        style = MaterialTheme.typography.labelSmall, color = if (category == TagCategory.RUN) line else bestColor)
                                }
                            }
                        }
                    }
                }
                if (showTags && slotWidth < 75.dp) {
                    Text("Taggar per åk", style = MaterialTheme.typography.labelLarge)
                    val tagged = shown.filter { item -> tags.any { it.id in item.attempt.tags && it.category != TagCategory.CHANGE } }
                    if (tagged.isEmpty()) Text("Inga taggar på åken ännu.", style = MaterialTheme.typography.bodySmall)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        tagged.forEach { item -> OutlinedCard(onClick = { open(item.attempt) }, modifier = Modifier.widthIn(max = 260.dp)) {
                            Column(Modifier.padding(8.dp)) {
                                Text("Åk ${item.number}", style = MaterialTheme.typography.labelLarge)
                                listOf(TagCategory.RUN, TagCategory.NEXT_FOCUS).forEach { category ->
                                    val names = tags.filter { it.id in item.attempt.tags && it.category == category }.joinToString(" · ") { it.name }
                                    if (names.isNotBlank()) Text("${category.label}: $names", style = MaterialTheme.typography.labelSmall,
                                        color = if (category == TagCategory.RUN) line else bestColor)
                                }
                            }
                        } }
                    }
                }
            }

        }
    Text((if (onRunClick == null) "Tryck på ett åk för detaljer" else "Tryck på ett åk för tidskortet") +
        if (zoom > 1f) " · ${"%.1f".format(zoom)}× zoom" else " · nyp för att zooma", style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    if (chooseRun) AlertDialog(onDismissRequest = { chooseRun = false }, title = { Text("Välj åk") }, text = {
        Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
            shown.forEach { item -> TextButton(onClick = { chooseRun = false; open(item.attempt) }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                Text("Åk ${item.number} · ${item.attempt.durationMs?.let { seconds(it) + " s" } ?: "DNF"}${if (!item.attempt.complete && item.attempt.durationMs != null) " · DNF" else ""}")
            } }
        }
    }, confirmButton = { TextButton(onClick = { chooseRun = false }) { Text("Stäng") } })
    changeGroup?.let { group -> AlertDialog(onDismissRequest = { changeGroup = null }, title = { Text("Ändringar i diagrammet") },
        text = { Column(Modifier.verticalScroll(rememberScrollState())) { group.forEach { marker ->
            TextButton(onClick = { if (onChangeClick != null) { changeGroup = null; onChangeClick(marker) } }) { Text("Efter resultat ${marker.afterSequence}: ${markerLabel(marker)}") }
        } } }, confirmButton = { TextButton(onClick = { changeGroup = null }) { Text("Stäng") } }) }
    detailId?.let { id -> runs.find { it.id == id }?.let { run ->
        AlertDialog(onDismissRequest = { detailId = null }, title = { Text("Åk ${runs.indexOf(run) + 1} · ${if (run.complete) seconds(run.durationMs!!) + " s" else "DNF"}") },
            text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                TagCategory.entries.forEach { category -> Text("${category.label}: ${(if (category == TagCategory.CHANGE) changeLabels[run.id].orEmpty() else tags.filter { it.id in run.tags && it.category == category }.joinToString(" · ") { it.name }).ifBlank { "Inga taggar" }}") }
                Text(run.note.ifBlank { "Ingen anteckning" })
            } }, confirmButton = { TextButton(onClick = { detailId = null }) { Text("Stäng") } })
    } }
    }
}
