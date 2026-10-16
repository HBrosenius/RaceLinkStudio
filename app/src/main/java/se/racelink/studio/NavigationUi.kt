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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

private val destinations = listOf("current" to "Aktuellt", "sessions" to "Historik", "reports" to "Rapporter", "settings" to "Inställningar")

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
        Text(stringResource(R.string.dash_settings), style = MaterialTheme.typography.headlineSmall)
        Text(s.team, style = MaterialTheme.typography.titleMedium)
        OutlinedButton(onClick = roster) { Text(stringResource(R.string.dash_manage_team_roster)) }
        Text(stringResource(R.string.dash_team_coaching_tags), style = MaterialTheme.typography.titleMedium)
        Text(if (s.closedAt == null) stringResource(R.string.dash_tags_current_session_note) else stringResource(R.string.dash_tags_closed_session_note))
        OutlinedButton(onClick = editTags, enabled = !vm.busy && s.closedAt == null) { Text(stringResource(R.string.dash_edit_team_tags)) }
        HorizontalDivider()
        Text(stringResource(R.string.dash_appearance), style = MaterialTheme.typography.titleMedium)
        Row {
            FilterChip(selected = !s.dark, enabled = !vm.busy, onClick = { vm.update { it.copy(dark = false) } }, label = { Text(stringResource(R.string.dash_light)) })
            Spacer(Modifier.width(8.dp))
            FilterChip(selected = s.dark, enabled = !vm.busy, onClick = { vm.update { it.copy(dark = true) } }, label = { Text(stringResource(R.string.dash_dark)) })
        }
        HorizontalDivider()
        Text(stringResource(R.string.dash_backup_section), style = MaterialTheme.typography.titleMedium)
        BackupStatus(vm)
        OutlinedButton(onClick = { vm.backupOpen = true }) { Text(stringResource(R.string.dash_backup_and_restore)) }
        HorizontalDivider()
        Text(stringResource(R.string.dash_result_sound), style = MaterialTheme.typography.titleMedium)
        Text(stringResource(R.string.dash_result_sound_note))
        FilterChip(selected = vm.resultSoundEnabled, onClick = { vm.setResultSound(!vm.resultSoundEnabled) }, label = { Text(if (vm.resultSoundEnabled) stringResource(R.string.dash_result_sound_on) else stringResource(R.string.dash_result_sound_off)) })
        OutlinedButton(onClick = vm::previewResultSound) { Text(stringResource(R.string.dash_try_result_sound)) }
        StatusMessage(vm.soundMessage)
        ReportBrandingSettings()
        HorizontalDivider()
        Text(stringResource(R.string.dash_demo_tools), style = MaterialTheme.typography.titleMedium)
        Text(stringResource(R.string.dash_simulator_no_timer_full))
        OutlinedButton(onClick = simulator, enabled = s.closedAt == null) { Text(stringResource(R.string.dash_open_simulator)) }
        OutlinedButton(onClick = review) { Text(stringResource(R.string.dash_result_review, s.reviewQueue().size)) }
        SaveFeedback(!vm.busy, vm.error)
    }
}
