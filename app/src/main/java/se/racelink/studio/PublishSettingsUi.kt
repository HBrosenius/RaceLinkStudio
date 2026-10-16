package se.racelink.studio

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp

/** Optional live publishing to skier phones. Off by default; a failed publish never blocks capture. */
@Composable
internal fun PublishSettingsSection(state: Training, vm: TrainingViewModel) {
    var serverUrl by remember(vm.publishServerUrl) { mutableStateOf(vm.publishServerUrl) }
    var token by remember(vm.publishToken) { mutableStateOf(vm.publishToken) }
    var enabled by remember(vm.publishEnabled) { mutableStateOf(vm.publishEnabled) }
    var bibOnly by remember(vm.publishBibOnly) { mutableStateOf(vm.publishBibOnly) }
    var indexPassword by rememberSaveable(state.sessionId) { mutableStateOf(vm.indexPasswordFor(state.sessionId)) }
    var showPassword by remember { mutableStateOf(false) }
    var pairing by remember { mutableStateOf(false) }
    val busy = vm.busy || vm.backupBusy
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.pub_section), style = MaterialTheme.typography.titleMedium)
        Text(stringResource(R.string.pub_note))
        OutlinedTextField(
            value = serverUrl, onValueChange = { serverUrl = it }, singleLine = true, enabled = !busy,
            label = { Text(stringResource(R.string.pub_server_url)) }, modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = token, onValueChange = { token = it }, singleLine = true, enabled = !busy,
            label = { Text(stringResource(R.string.pub_token)) }, modifier = Modifier.fillMaxWidth(),
        )
        FilterChip(
            selected = enabled, enabled = !busy, onClick = { enabled = !enabled },
            label = { Text(if (enabled) stringResource(R.string.pub_on) else stringResource(R.string.pub_off)) },
        )
        Text(stringResource(R.string.pub_name_policy), style = MaterialTheme.typography.titleSmall)
        FilterChip(
            selected = bibOnly, enabled = !busy, onClick = { bibOnly = !bibOnly },
            label = { Text(if (bibOnly) stringResource(R.string.pub_name_bib) else stringResource(R.string.pub_name_first)) },
        )
        Text(stringResource(R.string.pub_index_title), style = MaterialTheme.typography.titleSmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = indexPassword, onValueChange = { indexPassword = it }, singleLine = true, enabled = !busy,
                label = { Text(stringResource(R.string.pub_index_password)) },
                visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                modifier = Modifier.weight(1f),
            )
            FilterChip(
                selected = showPassword, enabled = !busy, onClick = { showPassword = !showPassword },
                label = { Text(stringResource(R.string.pub_index_show)) },
            )
        }
        Text(stringResource(R.string.pub_index_hint))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { vm.savePublishConfig(serverUrl, token, enabled, bibOnly, indexPassword) }, enabled = !busy) {
                Text(stringResource(R.string.pub_save))
            }
            Button(
                onClick = { vm.savePublishConfig(serverUrl, token, enabled, bibOnly, indexPassword); vm.publishNow() },
                enabled = !busy,
            ) { Text(stringResource(R.string.pub_save_and_publish)) }
        }
        OutlinedButton(
            onClick = { pairing = true },
            enabled = !busy && vm.publishServerUrl.isNotBlank() && state.athletes.isNotEmpty(),
        ) { Text(stringResource(R.string.pub_pair)) }
        if (vm.publishPending) {
            Text(stringResource(R.string.pub_pending))
            OutlinedButton(onClick = { vm.publishNow() }, enabled = !busy) { Text(stringResource(R.string.pub_retry)) }
        }
        vm.publishLastAt?.let { Text(stringResource(R.string.pub_last, it)) }
        StatusMessage(vm.publishMessage)
    }
    if (pairing) PairingSheet(state, vm.publishServerUrl) { pairing = false }
}
