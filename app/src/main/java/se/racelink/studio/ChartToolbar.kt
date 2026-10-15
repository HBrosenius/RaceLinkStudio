package se.racelink.studio

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun ChartModeSelector(improvement: Boolean, select: (Boolean) -> Unit) {
    val preferredWidth = if (LocalDensity.current.fontScale >= 1.5f) 320.dp else 224.dp
    BoxWithConstraints(Modifier.widthIn(max = preferredWidth)) {
        SingleChoiceSegmentedButtonRow(Modifier.width(maxWidth).heightIn(min = 48.dp)) {
            listOf("Tider", "Förändring").forEachIndexed { index, label ->
                SegmentedButton(selected = improvement == (index == 1), onClick = { select(index == 1) },
                    shape = SegmentedButtonDefaults.itemShape(index, 2)) { Text(label) }
            }
        }
    }
}
