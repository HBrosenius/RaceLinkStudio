package se.racelink.studio

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONArray
import org.json.JSONObject

// A transactional demo snapshot. Raw hardware journaling and Room migration are later work.
class TrainingStore(context: Context) : SQLiteOpenHelper(context, "racelink-demo.db", null, 1) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE snapshot (id INTEGER PRIMARY KEY CHECK(id=1), payload TEXT NOT NULL)")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
    fun read(): Training? = readableDatabase.rawQuery("SELECT payload FROM snapshot WHERE id=1", null).use {
        if (it.moveToFirst()) decode(JSONObject(it.getString(0))) else null
    }
    fun save(state: Training) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.execSQL("INSERT OR REPLACE INTO snapshot(id,payload) VALUES(1,?)", arrayOf(encode(state).toString()))
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }
    private fun encode(s: Training): JSONObject = JSONObject().apply {
        put("team", s.team); put("dark", s.dark)
        put("sessionId", s.sessionId); put("sessionDate", s.sessionDate)
        put("sessionName", s.sessionName); put("location", s.location); put("course", s.course); put("trainingType", s.trainingType)
        put("roster", JSONArray(s.roster.map { JSONObject().put("id", it.id).put("bib", it.bib).put("name", it.name) }))
        put("archivedSessions", JSONArray(s.archivedSessions.map { encode(it) }))
        put("athletes", JSONArray(s.athletes.map { JSONObject().put("id", it.id).put("bib", it.bib).put("name", it.name) }))
        put("tags", JSONArray(s.tags.map { JSONObject().put("id", it.id).put("name", it.name) }))
        put("attempts", JSONArray(s.attempts.map { JSONObject().put("id", it.id).put("athlete", it.athleteId)
            .put("sequence", it.sequence).put("duration", it.durationMs ?: JSONObject.NULL).put("complete", it.complete)
            .put("tags", JSONArray(it.tags.toList())).put("note", it.note) }))
        put("markers", JSONArray(s.markers.map { JSONObject().put("after", it.afterSequence).put("label", it.label) }))
        put("audit", JSONArray(s.audit)); put("pending", JSONArray(s.pending))
    }
    private fun decode(o: JSONObject): Training {
        fun objects(key: String) = o.getJSONArray(key).let { a -> (0 until a.length()).map { a.getJSONObject(it) } }
        fun strings(key: String) = o.getJSONArray(key).let { a -> (0 until a.length()).map { a.getString(it) } }
        return Training(objects("athletes").map { Athlete(it.getInt("id"), it.getInt("bib"), it.getString("name")) },
            objects("attempts").map { a -> Attempt(a.getString("id"), a.getInt("athlete"), a.getInt("sequence"),
                if (a.isNull("duration")) null else a.getLong("duration"), a.getBoolean("complete"),
                a.getJSONArray("tags").let { t -> (0 until t.length()).map { t.getInt(it) }.toSet() }, a.getString("note")) },
            objects("tags").map { CoachingTag(it.getInt("id"), it.getString("name")) },
            objects("markers").map { Marker(it.getInt("after"), it.getString("label")) }, strings("audit"),
            o.getString("team"), o.getBoolean("dark"), strings("pending"),
            o.optString("sessionId", "legacy-demo"),
            o.optString("sessionDate", java.time.LocalDate.now().toString()),
            o.optJSONArray("archivedSessions")?.let { a ->
                (0 until a.length()).map { decode(a.getJSONObject(it)) }
            } ?: emptyList(),
            o.optString("sessionName", "Träningspass"), o.optString("location", ""), o.optString("course", ""), o.optString("trainingType", ""),
            objects(if (o.has("roster")) "roster" else "athletes").map { Athlete(it.getInt("id"), it.getInt("bib"), it.getString("name")) })
    }
}
