package se.racelink.studio

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { Studio() }
    }
}

private val Blue = Color(0xFF3266D5)
@Composable fun Studio(vm: TrainingViewModel = viewModel()) {
    val state = vm.state
    val colors = if (state?.dark == true) darkColorScheme(primary = Color(0xFFADC6FF))
        else lightColorScheme(primary = Blue, background = Color(0xFFF3F6FB), surface = Color.White)
    MaterialTheme(colorScheme = colors) {
        Surface(Modifier.fillMaxSize()) {
            if (state == null) Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                Column {
                    Text(vm.error ?: stringResource(R.string.dash_loading_sessions))
                    if (vm.error != null) TextButton(onClick = { vm.backupOpen = true }) { Text(stringResource(R.string.dash_restore_from_backup)) }
                }
            } else key(vm.revision) { Dashboard(state, vm) }
        }
        if (vm.backupOpen) BackupDialog(vm) { vm.backupOpen = false }
    }
}
