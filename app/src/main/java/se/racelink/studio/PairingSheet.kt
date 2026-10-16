package se.racelink.studio

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.AlertDialog
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics

/** The code always belongs to the skier shown on the card, independent of the next starter. */
@Composable internal fun SkierQrCode(sessionId: String, athlete: Athlete, serverUrl: String, publishingEnabled: Boolean = true) {
    val url = remember(sessionId, athlete.id, serverUrl) {
        personalPageUrl(serverUrl, PublishProjection.tokenFor(sessionId, athlete.id))
    }
    var enlarged by rememberSaveable(sessionId, athlete.id, serverUrl) { mutableStateOf(false) }
    val description = stringResource(R.string.pub_card_qr_description, athlete.name, athlete.bib)
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Surface(onClick = { enlarged = true }, color = Color.White, shape = RoundedCornerShape(8.dp),
            modifier = Modifier.semantics { contentDescription = description }) {
            QrCodeImage(url, Modifier.padding(4.dp).size(96.dp))
        }
        Text(stringResource(R.string.pub_card_qr_hint), style = MaterialTheme.typography.labelSmall)
        if (!publishingEnabled) Text(stringResource(R.string.pub_card_qr_off), style = MaterialTheme.typography.labelSmall)
    }
    val context = LocalContext.current
    if (enlarged) AlertDialog(onDismissRequest = { enlarged = false },
        title = { Text(stringResource(R.string.pub_pair_athlete, athlete.bib, athlete.name)) },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Surface(color = Color.White, shape = RoundedCornerShape(8.dp)) {
                    QrCodeImage(url, Modifier.padding(8.dp).size(240.dp).semantics { contentDescription = description })
                }
                Text(stringResource(R.string.pub_card_qr_scan))
                if (!publishingEnabled) Text(stringResource(R.string.pub_card_qr_off_detail))
            }
        },
        confirmButton = { TextButton(onClick = { enlarged = false }) { Text(stringResource(R.string.pub_close)) } },
        dismissButton = { TextButton(onClick = { copyLink(context, url) }) { Text(stringResource(R.string.pub_pair_copy)) } })
}

/** Draws [text] as a QR code, crisp at any size (nearest-neighbour scaling of one-pixel modules). */
@Composable
internal fun QrCodeImage(text: String, modifier: Modifier = Modifier) {
    val image = remember(text) {
        val matrix = encodeQr(text)
        val pixels = IntArray(matrix.width * matrix.height)
        for (y in 0 until matrix.height) {
            val row = y * matrix.width
            for (x in 0 until matrix.width) {
                pixels[row + x] = if (matrix.get(x, y)) android.graphics.Color.BLACK else android.graphics.Color.WHITE
            }
        }
        Bitmap.createBitmap(pixels, matrix.width, matrix.height, Bitmap.Config.ARGB_8888).asImageBitmap()
    }
    Canvas(modifier) {
        if (size.width > 0f && size.height > 0f) {
            drawImage(
                image,
                dstSize = IntSize(size.width.toInt().coerceAtLeast(1), size.height.toInt().coerceAtLeast(1)),
                filterQuality = FilterQuality.None,
            )
        }
    }
}

/**
 * One QR code per skier so each phone can bookmark its own personal page — the coach shows the
 * sheet, the skier scans, and the link keeps working for the whole session. Works offline: the code
 * is generated on the tablet and only needs the server address, not a live connection.
 */
@Composable
internal fun PairingSheet(state: Training, rawServerUrl: String, close: () -> Unit) {
    val context = LocalContext.current
    val serverUrl = rawServerUrl.trim().trimEnd('/')
    val links = remember(state.sessionId, state.athletes, serverUrl) {
        state.athletes.map { athlete ->
            athlete to personalPageUrl(serverUrl, PublishProjection.tokenFor(state.sessionId, athlete.id))
        }
    }
    var copiedId by remember { mutableStateOf<Int?>(null) }
    EditorDialog(
        onDismissRequest = close,
        title = { Text(stringResource(R.string.pub_pair_title), style = MaterialTheme.typography.headlineSmall) },
        text = {
            if (serverUrl.isBlank()) Text(stringResource(R.string.pub_pair_needs_server))
            else LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                item { Text(stringResource(R.string.pub_pair_note)) }
                items(links, key = { it.first.id }) { (athlete, url) ->
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Surface(color = Color.White, shape = RoundedCornerShape(8.dp)) {
                            QrCodeImage(url, Modifier.padding(6.dp).size(120.dp))
                        }
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(stringResource(R.string.pub_pair_athlete, athlete.bib, athlete.name), style = MaterialTheme.typography.titleMedium)
                            Text(url, style = MaterialTheme.typography.bodySmall)
                            TextButton(onClick = { copyLink(context, url); copiedId = athlete.id }) {
                                Text(stringResource(if (copiedId == athlete.id) R.string.pub_pair_copied else R.string.pub_pair_copy))
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = links.isNotEmpty() && serverUrl.isNotBlank(),
                onClick = { shareLinks(context, links) },
            ) { Text(stringResource(R.string.pub_pair_share)) }
        },
        dismissButton = { TextButton(onClick = close) { Text(stringResource(R.string.pub_close)) } },
    )
}

private fun copyLink(context: Context, url: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
    clipboard.setPrimaryClip(ClipData.newPlainText("RaceLink", url))
}

private fun shareLinks(context: Context, links: List<Pair<Athlete, String>>) {
    val body = links.joinToString("\n") { (athlete, url) -> "${athlete.name} (#${athlete.bib}): $url" }
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, body)
    }
    context.startActivity(Intent.createChooser(intent, context.getString(R.string.pub_pair_share)))
}
