package se.racelink.studio

import org.json.JSONObject
import java.io.InputStream
import java.time.Instant
import java.time.LocalDate

internal const val MAX_BACKUP_BYTES = 16 * 1024 * 1024

internal fun readBackup(input: InputStream): Training {
    val output = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    while (true) {
        val count = input.read(buffer, 0, minOf(buffer.size, MAX_BACKUP_BYTES + 1 - output.size()))
        if (count < 0) break
        output.write(buffer, 0, count)
        require(output.size() <= MAX_BACKUP_BYTES) { "Säkerhetskopian är för stor (max 16 MB)" }
    }
    val bytes = output.toByteArray()
    require(bytes.size <= MAX_BACKUP_BYTES) { "Säkerhetskopian är för stor (max 16 MB)" }
    return decodeBackup(bytes.toString(Charsets.UTF_8))
}

internal fun encodeBackup(state: Training): String {
    validateBackupState(state)
    return JSONObject().put("format", "racelink-studio-demo").put("version", 8)
        .put("createdAt", Instant.now().toString()).put("state", TrainingJson.encode(state)).toString(2)
        .also { require(it.toByteArray(Charsets.UTF_8).size <= MAX_BACKUP_BYTES) { "Säkerhetskopian är för stor (max 16 MB)" } }
}

internal fun decodeBackup(text: String): Training {
    require(text.toByteArray(Charsets.UTF_8).size <= MAX_BACKUP_BYTES) { "Säkerhetskopian är för stor" }
    // Reject excessively nested JSON before passing it to the recursive parser.
    var depth = 0; var quoted = false; var escaped = false
    text.forEach { c ->
        if (quoted) {
            if (escaped) escaped = false else if (c == '\\') escaped = true else if (c == '"') quoted = false
        } else when (c) {
            '"' -> quoted = true
            '{', '[' -> { depth++; require(depth <= 32) { "Säkerhetskopian är för djupt nästlad" } }
            '}', ']' -> depth--
        }
    }
    val root = JSONObject(text)
    require(root.getString("format") == "racelink-studio-demo") { "Detta är inte en RaceLink-säkerhetskopia" }
    val version = root.getInt("version")
    require(version in 1..8) { "Säkerhetskopians version stöds inte" }
    val state = root.getJSONObject("state")
    val required = listOf("sessionId", "sessionDate", "sessionName", "location", "course", "trainingType", "roster", "participantIds", "archivedSessions")
    fun checkFields(o: JSONObject, archived: Boolean = false) {
        required.forEach { require(o.has(it) && !o.isNull(it)) { "Säkerhetskopian saknar $it" } }
        if (version >= 6) require(o.has("archivedRosterIds") && !o.isNull("archivedRosterIds")) { "Arkiveringsstatus saknas" }
        if (version >= 7) require(o.has("race")) { "Tävlingsläge saknas" }
        if (version >= 8) {
            val audit = o.getJSONArray("audit")
            (0 until audit.length()).forEach { require(audit.get(it) is JSONObject) { "Granskningsposter saknar typ" } }
        }
        if (version >= 5) {
            val markers = o.getJSONArray("markers")
            (0 until markers.length()).forEach { require(markers.getJSONObject(it).has("tags")) { "Ändringstaggar saknas" } }
        }
        if (version >= 4) require(o.has("closedAt")) { "Passstatus saknas" }
        if (version >= 2) require(o.has("timingEvents") && !o.isNull("timingEvents")) { "Säkerhetskopian saknar ursprungliga tidshändelser" }
        if (version >= 3) {
            val tags = o.getJSONArray("tags")
            (0 until tags.length()).forEach { require(tags.getJSONObject(it).has("category")) { "Taggkategori saknas" } }
        }
        val archives = o.getJSONArray("archivedSessions")
        require(!archived || archives.length() == 0) { "Nästlade arkiv stöds inte" }
        if (!archived) (0 until archives.length()).forEach { checkFields(archives.getJSONObject(it), true) }
    }
    checkFields(state)
    return TrainingJson.decode(state).also(::validateBackupState)
}

internal fun validateBackupState(current: Training) {
    val sessions = listOf(current) + current.archivedSessions
    require(sessions.map { it.sessionId }.distinct().size == sessions.size) { "Dubbla träningspass" }
    require(current.archivedSessions.all { it.archivedSessions.isEmpty() }) { "Nästlade arkiv stöds inte" }
    require(current.archivedSessions.all { it.reviewQueue().isEmpty() }) { "Arkiverade pass innehåller ogranskade händelser" }
    sessions.forEach { s ->
        require(s.sessionId.isNotBlank() && s.sessionName.isNotBlank() && s.team.isNotBlank()) { "Passet saknar namn eller identitet" }
        LocalDate.parse(s.sessionDate)
        s.closedAt?.let { Instant.parse(it); require(s.reviewQueue().isEmpty() && s.nextAthleteId == null && s.pending.isEmpty()) { "Avslutat pass har aktiva händelser" } }
        fun validAthletes(list: List<Athlete>) = list.isNotEmpty() && list.all { it.id > 0 && it.bib > 0 && it.name.isNotBlank() } &&
            list.map { it.id }.distinct().size == list.size && list.map { it.bib }.distinct().size == list.size
        require(validAthletes(s.athletes) && validAthletes(s.roster)) { "Ogiltig åkarlista" }
        require(s.archivedRosterIds.all { id -> s.roster.any { it.id == id } }) { "Arkiverad åkare saknas i åkarlistan" }
        val athleteIds = s.athletes.map { it.id }.toSet()
        require(s.athletes.all { athlete -> s.roster.any { it == athlete } }) { "Åkarlistorna stämmer inte överens" }
        require(s.participantIds.isNotEmpty() && athleteIds.containsAll(s.participantIds)) { "Ogiltiga deltagare" }
        s.validateRace()
        require(s.nextAthleteId == null || s.nextAthleteId in s.participantIds) { "Nästa åkare saknas bland deltagarna" }
        val tagIds = s.tags.map { it.id }.toSet()
        require(tagIds.size == s.tags.size && s.tags.all { it.id > 0 && it.name.isNotBlank() }) { "Ogiltiga taggar" }
        require(s.attempts.map { it.id }.distinct().size == s.attempts.size && s.attempts.map { it.sequence }.distinct().size == s.attempts.size) { "Dubbla åk" }
        require(s.attempts.all { it.id.isNotBlank() && it.sequence > 0 && it.athleteId in athleteIds &&
            (it.durationMs == null || it.durationMs > 0) && (!it.complete || it.durationMs != null) && tagIds.containsAll(it.tags) }) { "Ogiltigt åk eller saknad åkare/tagg" }
        require(s.pending.distinct().size == s.pending.size && s.pending.all { id -> s.attempts.any { it.id == id } }) { "Ogiltig resultatkö" }
        require(s.timingEvents.map { it.id }.distinct().size == s.timingEvents.size) { "Dubbla granskningsposter" }
        s.timingEvents.forEach { r ->
            val event = r.observation
            require(r.id.isNotBlank() && r.reason.isNotBlank() && event.id.isNotBlank() && event.sequence > 0 &&
                (event.durationMs == null || event.durationMs > 0) && (!event.complete || event.durationMs != null)) { "Ogiltig ursprunglig tidshändelse" }
            when (r.decision) {
                TimingDecision.WAITING, TimingDecision.REJECTED -> require(r.athleteId == null && r.attemptId == null)
                TimingDecision.DUPLICATE -> require(r.athleteId == null && s.attempts.any { it.id == r.attemptId })
                TimingDecision.ACCEPTED, TimingDecision.ASSIGNED -> require(r.athleteId in athleteIds && s.attempts.any { it.id == r.attemptId })
            }
            require((r.decision == TimingDecision.WAITING) == (r.decidedAt == null)) { "Granskningsbeslut saknar datum" }
            r.decidedAt?.let { Instant.parse(it) }
        }
        val last = s.nextTimingSequence() - 1
        require(s.markers.all { it.afterSequence in 0..last && (it.label.isNotBlank() || it.tags.isNotEmpty()) && it.tags.all { id -> s.tags.any { tag -> tag.id == id && tag.category == TagCategory.CHANGE } } }) { "Ogiltig passmarkering" }
    }
}
