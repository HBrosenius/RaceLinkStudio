package se.racelink.studio

import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import org.json.JSONArray
import org.json.JSONObject

internal val IdSetSaver = Saver<Set<Int>, ArrayList<Int>>(
    save = { ArrayList(it) }, restore = { it.toSet() })
internal val TagListSaver = Saver<List<CoachingTag>, String>(
    save = { tags -> JSONArray(tags.map { JSONObject().put("id", it.id).put("name", it.name).put("category", it.category.name) }).toString() },
    restore = { text -> JSONArray(text).let { a -> (0 until a.length()).map { i -> a.getJSONObject(i).let { CoachingTag(it.getInt("id"), it.getString("name"), TagCategory.valueOf(it.getString("category"))) } } } })

@Composable internal fun draftClose(dirty: Boolean, enabled: Boolean, close: () -> Unit): () -> Unit {
    var confirm by rememberSaveable { mutableStateOf(false) }
    if (confirm) AlertDialog(onDismissRequest = { confirm = false }, title = { Text(stringResource(R.string.dash_leave_without_saving)) },
        text = { Text(stringResource(R.string.dash_unsaved_changes)) },
        confirmButton = { TextButton(enabled = enabled, onClick = { confirm = false; close() }) { Text(stringResource(R.string.dash_discard_changes)) } },
        dismissButton = { TextButton(onClick = { confirm = false }) { Text(stringResource(R.string.dash_continue_editing)) } })
    return { if (enabled) { if (dirty) confirm = true else close() } }
}

@Composable internal fun SaveFeedback(enabled: Boolean, error: String?) {
    if (!enabled) Text(stringResource(R.string.dash_saving), Modifier.semantics { liveRegion = LiveRegionMode.Polite })
    error?.let { Text(it, Modifier.semantics { liveRegion = LiveRegionMode.Polite }, color = MaterialTheme.colorScheme.error) }
}

@Composable internal fun StatusMessage(message: String?, isError: Boolean = false) {
    message?.let { Text(it, Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface) }
}
