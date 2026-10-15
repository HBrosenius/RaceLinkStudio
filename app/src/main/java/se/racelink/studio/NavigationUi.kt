package se.racelink.studio

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.DialogWindowProvider
import android.view.WindowManager
import android.graphics.Rect
import android.view.ViewTreeObserver
import androidx.core.view.WindowCompat
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

private val destinations = listOf("current" to "Aktuellt pass", "sessions" to "Träningspass", "reports" to "Rapporter", "settings" to "Inställningar")

@Composable internal fun StudioNavigation(selected: String, select: (String) -> Unit, content: @Composable () -> Unit) {
    val screens = rememberSaveableStateHolder()
    var barHeightPx by remember { mutableIntStateOf(0) }
    BoxWithConstraints(Modifier.fillMaxSize().safeDrawingPadding()) {
        val wide = maxWidth >= 1000.dp && LocalDensity.current.fontScale < 1.5f
        val barHeight = if (barHeightPx == 0) 80.dp else with(LocalDensity.current) { barHeightPx.toDp() }
        // The screen has one composition position across rail/bar transitions.
        // Its saved-state registry is also retained when switching destinations.
        Box(Modifier.fillMaxSize().padding(start = if (wide) 80.dp else 0.dp, bottom = if (wide) 0.dp else barHeight)) {
            screens.SaveableStateProvider(selected) { content() }
        }
        if (wide) NavigationRail(Modifier.align(Alignment.TopStart).width(80.dp).fillMaxHeight()) {
            destinations.forEachIndexed { index, (id, label) -> NavigationRailItem(selected = selected == id,
                onClick = { select(id) }, icon = { Text(listOf("◉", "▤", "▥", "⚙")[index]) }, label = { Text(label) }) }
        } else NavigationBar(Modifier.align(Alignment.BottomCenter).fillMaxWidth().onSizeChanged { barHeightPx = it.height }) {
            destinations.forEachIndexed { index, (id, label) -> NavigationBarItem(selected = selected == id,
                onClick = { select(id) }, icon = { Text(listOf("◉", "▤", "▥", "⚙")[index]) }, label = { Text(label) }) }
        }
    }
}

@Suppress("DEPRECATION")
@Composable internal fun AppFullScreenDialog(close: () -> Unit, content: @Composable () -> Unit) {
    Dialog(onDismissRequest = close, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        val view = LocalView.current
        val density = LocalDensity.current
        var usableHeight by remember { mutableIntStateOf(0) }
        var topInset by remember { mutableIntStateOf(0) }
        DisposableEffect(view) {
            val window = (view.parent as? DialogWindowProvider)?.window
            window?.let {
                WindowCompat.setDecorFitsSystemWindows(it, false)
                it.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT)
                it.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
            }
            // Dialogs can retain activity-sized constraints after rotation or IME changes.
            // Bound their content to the actual visible frame, rather than to those constraints.
            val update = {
                val frame = Rect()
                val position = IntArray(2)
                view.getWindowVisibleDisplayFrame(frame)
                view.getLocationOnScreen(position)
                val height = frame.bottom - position[1]
                if (height > 0) {
                    usableHeight = height
                    topInset = (frame.top - position[1]).coerceAtLeast(0)
                }
            }
            val listener = ViewTreeObserver.OnGlobalLayoutListener { update() }
            view.viewTreeObserver.addOnGlobalLayoutListener(listener)
            update()
            onDispose { if (view.viewTreeObserver.isAlive) view.viewTreeObserver.removeOnGlobalLayoutListener(listener) }
        }
        val size = if (usableHeight > 0) Modifier.fillMaxWidth().height(with(density) { usableHeight.toDp() }) else Modifier.fillMaxSize()
        Box(size.padding(top = with(density) { topInset.toDp() })
            .consumeWindowInsets(WindowInsets.safeDrawing.union(WindowInsets.ime))) { content() }
    }
}

@Composable internal fun ScreenContainer(embedded: Boolean, close: () -> Unit, content: @Composable () -> Unit) {
    if (embedded) content() else AppFullScreenDialog(close, content)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable internal fun EditorDialog(onDismissRequest: () -> Unit, title: @Composable () -> Unit,
    text: @Composable () -> Unit, confirmButton: @Composable () -> Unit, dismissButton: @Composable () -> Unit) {
    AppFullScreenDialog(onDismissRequest) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                title()
                Box(Modifier.weight(1f).fillMaxWidth()) { text() }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { dismissButton(); confirmButton() }
            }
        }
    }
}

@Composable internal fun SettingsHome(s: Training, vm: TrainingViewModel, editTags: () -> Unit, simulator: () -> Unit, review: () -> Unit, roster: () -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Inställningar", style = MaterialTheme.typography.headlineSmall)
        Text(s.team, style = MaterialTheme.typography.titleMedium)
        OutlinedButton(onClick = roster) { Text("Hantera lagets åkarlista") }
        Text("Lag och coachningstaggar", style = MaterialTheme.typography.titleMedium)
        Text(if (s.closedAt == null) "Gäller aktuellt pass. Sparade pass behåller sina taggar." else "Passet är avslutat. Dess lag och taggar är skrivskyddade.")
        OutlinedButton(onClick = editTags, enabled = !vm.busy && s.closedAt == null) { Text("Redigera lag och taggar") }
        HorizontalDivider()
        Text("Utseende", style = MaterialTheme.typography.titleMedium)
        Row {
            FilterChip(selected = !s.dark, enabled = !vm.busy, onClick = { vm.update { it.copy(dark = false) } }, label = { Text("Ljust") })
            Spacer(Modifier.width(8.dp))
            FilterChip(selected = s.dark, enabled = !vm.busy, onClick = { vm.update { it.copy(dark = true) } }, label = { Text("Mörkt") })
        }
        HorizontalDivider()
        Text("Säkerhetskopiering", style = MaterialTheme.typography.titleMedium)
        BackupStatus(vm)
        OutlinedButton(onClick = { vm.backupOpen = true }) { Text("Säkerhetskopiera och återställ") }
        HorizontalDivider()
        Text("Demoverktyg", style = MaterialTheme.typography.titleMedium)
        Text("Simulator · ingen timer är ansluten. Simulerade resultat sparas i aktuellt pass.")
        OutlinedButton(onClick = simulator, enabled = s.closedAt == null) { Text("Öppna simulator") }
        OutlinedButton(onClick = review) { Text("Resultatgranskning (${s.reviewQueue().size})") }
        SaveFeedback(!vm.busy, vm.error)
    }
}
