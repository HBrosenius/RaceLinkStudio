package se.racelink.studio

import androidx.compose.runtime.saveable.Saver
import org.json.JSONArray
import org.json.JSONObject

internal data class ReportSelection(val mode: String = "all", val sessions: Set<String> = emptySet(),
    val athletes: Set<Int>? = null, val from: String = "", val through: String = "") {
    fun rows(sessions: List<Training>): List<ReportRun> {
        require(mode in listOf("all", "sessions", "dates"))
        if (mode == "dates") require(from.isNotBlank() && through.isNotBlank()) { "Ange båda datumen" }
        return filterReport(reportRuns(sessions), if (mode == "sessions") this.sessions else null,
            if (mode == "dates") from else null, if (mode == "dates") through else null, athletes)
    }
    fun description(): String = (when (mode) {
        "sessions" -> "${sessions.size} valda pass"
        "dates" -> "$from – $through"
        else -> "Alla pass"
    }) + " · " + (athletes?.let { "${it.size} valda åkare" } ?: "Alla åkare")
}
internal fun reportScope(rows: List<ReportRun>): String = "${rows.map { it.session.sessionId }.distinct().size} pass · ${rows.map { it.athlete.id }.distinct().size} åkare · ${rows.size} åk"
internal val ReportSelectionSaver = Saver<ReportSelection, String>(save = {
    JSONObject().put("mode", it.mode).put("sessions", JSONArray(it.sessions.toList()))
        .put("athletes", it.athletes?.let { ids -> JSONArray(ids.toList()) } ?: JSONObject.NULL)
        .put("from", it.from).put("through", it.through).toString()
}, restore = { text -> JSONObject(text).let { o ->
    val sessions = o.getJSONArray("sessions")
    val athletes = o.optJSONArray("athletes")
    ReportSelection(o.getString("mode"), (0 until sessions.length()).map { sessions.getString(it) }.toSet(),
        athletes?.let { a -> (0 until a.length()).map { a.getInt(it) }.toSet() }, o.getString("from"), o.getString("through"))
} })
