package se.racelink.studio

import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import org.json.JSONArray
import org.json.JSONObject

internal val IdSetSaver = Saver<Set<Int>, ArrayList<Int>>(
    save = { ArrayList(it) }, restore = { it.toSet() })
internal val TagListSaver = Saver<List<CoachingTag>, String>(
    save = { tags -> JSONArray(tags.map { JSONObject().put("id", it.id).put("name", it.name).put("category", it.category.name) }).toString() },
    restore = { text -> JSONArray(text).let { a -> (0 until a.length()).map { i -> a.getJSONObject(i).let { CoachingTag(it.getInt("id"), it.getString("name"), TagCategory.valueOf(it.getString("category"))) } } } })

@Composable internal fun draftClose(dirty: Boolean, enabled: Boolean, close: () -> Unit): () -> Unit {
    var confirm by rememberSaveable { mutableStateOf(false) }
    if (confirm) AlertDialog(onDismissRequest = { confirm = false }, title = { Text("Lämna utan att spara?") },
        text = { Text("Du har osparade ändringar.") },
        confirmButton = { TextButton(enabled = enabled, onClick = { confirm = false; close() }) { Text("Kasta ändringar") } },
        dismissButton = { TextButton(onClick = { confirm = false }) { Text("Fortsätt redigera") } })
    return { if (enabled) { if (dirty) confirm = true else close() } }
}

@Composable internal fun SaveFeedback(enabled: Boolean, error: String?) {
    if (!enabled) Text("Sparar…")
    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
}
