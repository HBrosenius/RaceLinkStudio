package se.racelink.studio

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalLayoutApi::class)
@Composable internal fun EmptySessionGuide(s: Training, enabled: Boolean, participants: () -> Unit, chooseNext: () -> Unit, simulator: () -> Unit) {
    val next = s.activeAthletes().find { it.id == s.nextAthleteId }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Redo för första åket", style = MaterialTheme.typography.headlineSmall)
            Text("Simulator · ingen timer är ansluten", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.titleMedium)
            Text("Passet är tomt. Prova arbetsflödet med demoresultat från simulatorn; inga tider tas emot från en fysisk timer.")
            HorizontalDivider()
            val first: @Composable () -> Unit = {
                Text("1. Kontrollera deltagarna", style = MaterialTheme.typography.titleMedium)
                Text("${s.participantIds.size} åkare är valda för detta pass. Dagens deltagare väljs ur lagets åkarlista.")
                OutlinedButton(enabled = enabled, onClick = participants) { Text("Redigera deltagare") }
            }
            val second: @Composable () -> Unit = {
                Text("2. Välj nästa åkare", style = MaterialTheme.typography.titleMedium)
                Text(next?.let { "Nästa åkare: ${it.bib} · ${it.name}" } ?: "Ingen nästa åkare vald ännu.")
                OutlinedButton(enabled = enabled, onClick = chooseNext) { Text(if (next == null) "Välj nästa åkare" else "Byt nästa åkare") }
            }
            val third: @Composable () -> Unit = {
                Text("3. Prova första resultatet", style = MaterialTheme.typography.titleMedium)
                Text("Välj Simulera mål i simulatorn. Resultatet sparas i passet. Visa nästa i resultatmeddelandet öppnar rätt åk.")
                if (next == null) Text("Välj nästa åkare för att aktivera knappen.", style = MaterialTheme.typography.bodySmall)
                Button(enabled = enabled && next != null, onClick = simulator) { Text("Öppna simulator") }
            }
            BoxWithConstraints {
                if (maxWidth >= 700.dp && LocalDensity.current.fontScale < 1.5f) Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) { first() }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) { second() }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) { third() }
                } else Column(verticalArrangement = Arrangement.spacedBy(12.dp)) { first(); second(); third() }
            }
            Text("Valet av nästa åkare behålls tills du byter. Åkaren du tittar på i diagrammet väljs separat.", style = MaterialTheme.typography.bodySmall)
        }
    }
}
