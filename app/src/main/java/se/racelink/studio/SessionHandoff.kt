package se.racelink.studio

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable internal fun SessionHandoff(s: Training, vm: TrainingViewModel, close: () -> Unit, start: () -> Unit) {
    var report by rememberSaveable { mutableStateOf(false) }
    if (report) {
        Reports(s, { report = false }, initialSelection = ReportSelection("sessions", setOf(s.sessionId)), closeLabel = "Till passammanfattningen")
        return
    }
    AlertDialog(onDismissRequest = close, title = { Text(if (s.race == null) "Avslutat pass · sammanfattning" else "Avslutad tävling · sammanfattning") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(s.sessionLabel(), style = MaterialTheme.typography.titleMedium)
            Text("${s.attempts.size} åk · ${s.attempts.eligible().size} fullföljda · ${s.attempts.size - s.attempts.eligible().size} DNF")
            Text("${s.reviewQueue().size} resultat väntar på granskning · ${s.pending.size} nya resultat har inte visats.")
            Text(if (s.race == null) "Passet är skrivskyddat. Rapporter och säkerhetskopiering är tillgängliga." else "Tävlingen är skrivskyddad. Placeringar gäller inom respektive klass; deltagare med DNF/saknat åk saknar slutplacering.")
            OutlinedButton(onClick = { report = true }) { Text("Öppna passets rapport") }
            BackupStatus(vm)
            OutlinedButton(onClick = { vm.backupOpen = true }) { Text("Säkerhetskopiera alla pass") }
            Button(enabled = !vm.busy && !vm.backupBusy, onClick = start) { Text("Starta nytt träningspass") }
        }
    }, confirmButton = { TextButton(onClick = close) { Text(if (s.race == null) "Tillbaka till diagrammet" else "Tillbaka till resultatlistan") } })
}
