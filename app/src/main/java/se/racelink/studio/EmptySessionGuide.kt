package se.racelink.studio

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalLayoutApi::class)
@Composable internal fun EmptySessionGuide(s: Training, enabled: Boolean, participants: () -> Unit, chooseNext: () -> Unit, simulator: () -> Unit) {
    val next = s.activeAthletes().find { it.id == s.nextAthleteId }
    val nextLabel = if (next == null) stringResource(R.string.dash_no_next_athlete) else stringResource(R.string.dash_next_athlete_is, next.bib, next.name)
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.dash_ready_first_run), style = MaterialTheme.typography.headlineSmall)
            Text(stringResource(R.string.dash_simulator_no_timer), color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.dash_session_empty_demo))
            HorizontalDivider()
            val first: @Composable () -> Unit = {
                Text(stringResource(R.string.dash_step1_check_participants), style = MaterialTheme.typography.titleMedium)
                Text(stringResource(R.string.dash_participants_selected_note, s.participantIds.size))
                OutlinedButton(enabled = enabled, onClick = participants) { Text(stringResource(R.string.dash_edit_participants)) }
            }
            val second: @Composable () -> Unit = {
                Text(stringResource(R.string.dash_step2_choose_next), style = MaterialTheme.typography.titleMedium)
                Text(nextLabel)
                OutlinedButton(enabled = enabled, onClick = chooseNext) { Text(if (next == null) stringResource(R.string.dash_choose_next_athlete) else stringResource(R.string.dash_change_next_athlete)) }
            }
            val third: @Composable () -> Unit = {
                Text(stringResource(R.string.dash_step3_try_first), style = MaterialTheme.typography.titleMedium)
                Text(stringResource(R.string.dash_simulate_hint))
                if (next == null) Text(stringResource(R.string.dash_choose_next_to_enable), style = MaterialTheme.typography.bodySmall)
                Button(enabled = enabled && next != null, onClick = simulator) { Text(stringResource(R.string.dash_open_simulator)) }
            }
            BoxWithConstraints {
                if (maxWidth >= 700.dp && LocalDensity.current.fontScale < 1.5f) Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) { first() }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) { second() }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) { third() }
                } else Column(verticalArrangement = Arrangement.spacedBy(12.dp)) { first(); second(); third() }
            }
            Text(stringResource(R.string.dash_next_athlete_kept), style = MaterialTheme.typography.bodySmall)
        }
    }
}
