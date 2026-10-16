package se.racelink.studio

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp

@Composable internal fun BackupDialog(vm: TrainingViewModel, close: () -> Unit) {
    val enabled = !vm.busy && !vm.backupBusy
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) vm.writeBackup(uri) else vm.cancelBackup()
    }
    val open = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.inspectBackup(uri)
    }
    LaunchedEffect(Unit) { vm.refreshRecovery() }
    AlertDialog(onDismissRequest = { if (enabled) { vm.cancelRestore(); close() } },
        title = { Text(stringResource(R.string.set_backup_title)) }, text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.set_backup_info))
                BackupStatus(vm)
                OutlinedButton(enabled = enabled && vm.state != null, onClick = {
                    vm.prepareBackup { save.launch("RaceLink-backup-${java.time.LocalDate.now()}.json") }
                }) { Text(stringResource(R.string.set_save_backup)) }
                OutlinedButton(enabled = enabled, onClick = { vm.cancelRestore(); open.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) }) { Text(stringResource(R.string.set_choose_backup)) }
                if (vm.recoveryAvailable) OutlinedButton(enabled = enabled, onClick = { vm.inspectRecovery() }) { Text(stringResource(R.string.set_undo_restore)) }
                if (!enabled) LinearProgressIndicator(Modifier.fillMaxWidth())
                vm.backupMessage?.let { Text(it) }
                vm.pendingRestore?.let { candidate ->
                    val sessions = listOf(candidate) + candidate.archivedSessions
                    HorizontalDivider()
                    Text(stringResource(R.string.set_selected_backup, candidate.team), style = MaterialTheme.typography.titleMedium)
                    Text(stringResource(R.string.set_backup_summary, sessions.size, sessions.sumOf { it.attempts.size }, candidate.roster.size))
                    Text(stringResource(R.string.set_current_session, candidate.sessionLabel()))
                    Text(stringResource(R.string.set_restore_warning))
                    Button(enabled = enabled, onClick = { vm.confirmRestore() }) { Text(stringResource(R.string.set_replace_local)) }
                    TextButton(enabled = enabled, onClick = { vm.cancelRestore() }) { Text(stringResource(R.string.set_cancel_restore)) }
                }
            }
        }, confirmButton = { TextButton(enabled = enabled, onClick = { vm.cancelRestore(); close() }) { Text(stringResource(R.string.set_close)) } })
}

@Composable internal fun BackupStatus(vm: TrainingViewModel) {
    val state = vm.state
    val fingerprint = remember(state) { state?.backupFingerprint() }
    Text(backupFreshness(vm.backupReceipt, fingerprint), style = MaterialTheme.typography.bodySmall)
    Text(stringResource(R.string.set_recovery_note), style = MaterialTheme.typography.bodySmall)
}
