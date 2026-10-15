package se.racelink.studio

import org.json.JSONArray
import org.json.JSONObject

internal object TrainingJson {
    fun encode(s: Training): JSONObject = JSONObject().apply {
        put("team", s.team); put("dark", s.dark)
        put("sessionId", s.sessionId); put("sessionDate", s.sessionDate)
        put("participantIds", JSONArray(s.participantIds.toList()))
        put("archivedRosterIds", JSONArray(s.archivedRosterIds.toList()))
        put("nextAthleteId", s.nextAthleteId ?: JSONObject.NULL)
        put("closedAt", s.closedAt ?: JSONObject.NULL)
        put("timingEvents", JSONArray(s.timingEvents.map { r -> JSONObject().apply {
            put("id", r.id); put("reason", r.reason); put("decision", r.decision.name)
            put("athleteId", r.athleteId ?: JSONObject.NULL); put("attemptId", r.attemptId ?: JSONObject.NULL); put("decidedAt", r.decidedAt ?: JSONObject.NULL)
            put("observation", JSONObject().put("id", r.observation.id).put("bib", r.observation.bib).put("sequence", r.observation.sequence)
                .put("duration", r.observation.durationMs ?: JSONObject.NULL).put("complete", r.observation.complete))
        } }))
        put("sessionName", s.sessionName); put("location", s.location); put("course", s.course); put("trainingType", s.trainingType)
        put("roster", JSONArray(s.roster.map { JSONObject().put("id", it.id).put("bib", it.bib).put("name", it.name) }))
        put("archivedSessions", JSONArray(s.archivedSessions.map { encode(it) }))
        put("athletes", JSONArray(s.athletes.map { JSONObject().put("id", it.id).put("bib", it.bib).put("name", it.name) }))
        put("tags", JSONArray(s.tags.map { JSONObject().put("id", it.id).put("name", it.name).put("category", it.category.name) }))
        put("attempts", JSONArray(s.attempts.map { JSONObject().put("id", it.id).put("athlete", it.athleteId)
            .put("sequence", it.sequence).put("duration", it.durationMs ?: JSONObject.NULL).put("complete", it.complete)
            .put("tags", JSONArray(it.tags.toList())).put("note", it.note) }))
        put("markers", JSONArray(s.markers.map { JSONObject().put("after", it.afterSequence).put("label", it.label).put("tags", JSONArray(it.tags.toList())) }))
        put("audit", JSONArray(s.audit)); put("pending", JSONArray(s.pending))
    }
    fun decode(o: JSONObject, depth: Int = 0): Training {
        require(depth <= 1) { "Nästlade arkiv stöds inte" }
        fun objects(key: String) = o.getJSONArray(key).let { a -> (0 until a.length()).map { a.getJSONObject(it) } }
        fun strings(key: String) = o.getJSONArray(key).let { a -> (0 until a.length()).map { a.getString(it) } }
        return Training(objects("athletes").map { Athlete(it.getInt("id"), it.getInt("bib"), it.getString("name")) },
            objects("attempts").map { a -> Attempt(a.getString("id"), a.getInt("athlete"), a.getInt("sequence"),
                if (a.isNull("duration")) null else a.getLong("duration"), a.getBoolean("complete"),
                a.getJSONArray("tags").let { t -> (0 until t.length()).map { t.getInt(it) }.toSet() }, a.getString("note")) },
            objects("tags").map { CoachingTag(it.getInt("id"), it.getString("name"), TagCategory.valueOf(it.optString("category", "RUN"))) }
                .let { tags -> if (objects("tags").none { it.has("category") }) tags.withFocusDefaults() else tags }.withChangeDefaults(),
            objects("markers").map { Marker(it.getInt("after"), it.getString("label"), it.optJSONArray("tags")?.let { a -> (0 until a.length()).map { a.getInt(it) }.toSet() } ?: emptySet()) }, strings("audit"),
            o.getString("team"), o.getBoolean("dark"), strings("pending"),
            o.optString("sessionId", "legacy-demo"),
            o.optString("sessionDate", java.time.LocalDate.now().toString()),
            o.optJSONArray("archivedSessions")?.let { a ->
                (0 until a.length()).map { decode(a.getJSONObject(it), depth + 1) }
            } ?: emptyList(),
            o.optString("sessionName", "Träningspass"), o.optString("location", ""), o.optString("course", ""), o.optString("trainingType", ""),
            objects(if (o.has("roster")) "roster" else "athletes").map { Athlete(it.getInt("id"), it.getInt("bib"), it.getString("name")) },
            o.optJSONArray("participantIds")?.let { a -> (0 until a.length()).map { a.getInt(it) }.toSet() }
                ?: objects("athletes").map { it.getInt("id") }.toSet(),
            if (o.isNull("nextAthleteId")) null else o.getInt("nextAthleteId"),
            o.optJSONArray("timingEvents")?.let { a -> (0 until a.length()).map { index ->
                val r = a.getJSONObject(index); val event = r.getJSONObject("observation")
                RecordedTiming(r.getString("id"), TimingObservation(event.getString("id"), event.getInt("bib"), event.getInt("sequence"),
                    if (event.isNull("duration")) null else event.getLong("duration"), event.getBoolean("complete")),
                    r.getString("reason"), TimingDecision.valueOf(r.getString("decision")),
                    if (r.isNull("athleteId")) null else r.getInt("athleteId"), if (r.isNull("attemptId")) null else r.getString("attemptId"),
                    if (r.isNull("decidedAt")) null else r.getString("decidedAt"))
            } } ?: emptyList(), if (o.isNull("closedAt")) null else o.getString("closedAt"),
            o.optJSONArray("archivedRosterIds")?.let { a -> (0 until a.length()).map { a.getInt(it) }.toSet() } ?: emptySet())
    }
}
