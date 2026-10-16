package se.racelink.studio

import androidx.compose.animation.core.*
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable internal fun SessionStatusBadge(closed: Boolean) {
    if (closed) Text(stringResource(R.string.dash_closed_readonly), style = MaterialTheme.typography.bodySmall)
    else {
        val transition = rememberInfiniteTransition(label = "ongoing-session")
        val alpha by transition.animateFloat(1f, .5f,
            infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "ongoing-pulse")
        Surface(Modifier.graphicsLayer { this.alpha = alpha }, shape = RoundedCornerShape(50),
            color = Color(0xFFD1FAE5), contentColor = Color(0xFF065F46)) {
            Text(stringResource(R.string.dash_ongoing), Modifier.padding(horizontal = 10.dp, vertical = 3.dp),
                style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
        }
    }
}
