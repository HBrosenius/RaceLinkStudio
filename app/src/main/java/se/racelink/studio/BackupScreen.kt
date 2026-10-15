package se.racelink.studio

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
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
        title = { Text("Säkerhetskopiera och återställ") }, text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Spara alla pass, åkare, taggar, anteckningar och ändringshistorik i en fil. Säkerhetskopian innehåller även privata coachningsanteckningar.")
                BackupStatus(vm)
                OutlinedButton(enabled = enabled && vm.state != null, onClick = {
                    vm.prepareBackup { save.launch("RaceLink-backup-${java.time.LocalDate.now()}.json") }
                }) { Text("Spara säkerhetskopia") }
                OutlinedButton(enabled = enabled, onClick = { vm.cancelRestore(); open.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) }) { Text("Välj säkerhetskopia") }
                if (vm.recoveryAvailable) OutlinedButton(enabled = enabled, onClick = { vm.inspectRecovery() }) { Text("Ångra senaste återställning") }
                if (!enabled) LinearProgressIndicator(Modifier.fillMaxWidth())
                vm.backupMessage?.let { Text(it) }
                vm.pendingRestore?.let { candidate ->
                    val sessions = listOf(candidate) + candidate.archivedSessions
                    HorizontalDivider()
                    Text("Vald kopia: ${candidate.team}", style = MaterialTheme.typography.titleMedium)
                    Text("${sessions.size} pass · ${sessions.sumOf { it.attempts.size }} åk · ${candidate.roster.size} åkare i åkarlistan")
                    Text("Aktuellt pass: ${candidate.sessionLabel()}")
                    Text("Återställning ersätter alla lokala pass och inställningar. En återställningspunkt sparas automatiskt för nuvarande träning.")
                    Button(enabled = enabled, onClick = { vm.confirmRestore() }) { Text("Ersätt lokal träning") }
                    TextButton(enabled = enabled, onClick = { vm.cancelRestore() }) { Text("Avbryt återställning") }
                }
            }
        }, confirmButton = { TextButton(enabled = enabled, onClick = { vm.cancelRestore(); close() }) { Text("Stäng") } })
}

@Composable internal fun BackupStatus(vm: TrainingViewModel) {
    val state = vm.state
    val fingerprint = remember(state) { state?.backupFingerprint() }
    Text(backupFreshness(vm.backupReceipt, fingerprint), style = MaterialTheme.typography.bodySmall)
    Text("Lokal återställningspunkt räknas inte som en extern säkerhetskopia.", style = MaterialTheme.typography.bodySmall)
}
