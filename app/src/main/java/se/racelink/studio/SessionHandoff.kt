package se.racelink.studio

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp

@Composable internal fun SessionHandoff(s: Training, vm: TrainingViewModel, close: () -> Unit, start: () -> Unit) {
    var report by rememberSaveable { mutableStateOf(false) }
    if (report) {
        Reports(s, { report = false }, initialSelection = ReportSelection("sessions", setOf(s.sessionId)), closeLabel = stringResource(R.string.dash_back_to_session_summary))
        return
    }
    AlertDialog(onDismissRequest = close, title = { Text(if (s.race == null) stringResource(R.string.dash_closed_session_summary) else stringResource(R.string.dash_closed_race_summary)) }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(s.sessionLabel(), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.dash_session_stats, s.attempts.size, s.attempts.eligible().size, s.attempts.size - s.attempts.eligible().size))
            Text(stringResource(R.string.dash_review_pending, s.reviewQueue().size, s.pending.size))
            Text(if (s.race == null) stringResource(R.string.dash_session_readonly_note) else stringResource(R.string.dash_race_readonly_note))
            OutlinedButton(onClick = { report = true }) { Text(stringResource(R.string.dash_open_session_report)) }
            BackupStatus(vm)
            OutlinedButton(onClick = { vm.backupOpen = true }) { Text(stringResource(R.string.dash_backup_all_sessions)) }
            Button(enabled = !vm.busy && !vm.backupBusy, onClick = start) { Text(stringResource(R.string.dash_start_new_training_session)) }
        }
    }, confirmButton = { TextButton(onClick = close) { Text(if (s.race == null) stringResource(R.string.dash_back_to_chart) else stringResource(R.string.dash_back_to_results)) } })
}
