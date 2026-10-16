package se.racelink.publish

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The single wire contract between the authoritative Android tablet and the read-only publishing
 * server. It is deliberately a *projection*, not the backup/repository format: no audit journal,
 * no coaching notes, no resolved pending events and no nested session archive in the public envelope.
 * Private coach feedback travels separately in PublishRequest.coaching.
 *
 * One envelope describes one session at one [PublishEnvelope.revision]. Publishing is idempotent:
 * the server keeps the highest revision it has seen per session id and rejects older ones, so a
 * retry after a flaky link can never move the public page backwards.
 */
@Serializable
enum class PublishMode { TRAINING, RACE }

/** Completed attempts have a time; DNF keeps a retained time; INCOMPLETE has no usable time. */
@Serializable
enum class RunStatus { COMPLETED, DNF, INCOMPLETE }

@Serializable
enum class PublishFormat { TOTAL, DIFFERENCE }

@Serializable
data class PublishSession(
    val id: String,
    val name: String,
    val date: String,
    val mode: PublishMode,
    val closed: Boolean,
    val location: String = "",
    val course: String = "",
    val type: String = "",
    val team: String = "",
)

@Serializable
data class PublishRace(val format: PublishFormat, val classes: List<String>)

@Serializable
data class PublishAthlete(
    val id: Int,
    val bib: Int,
    /** Coach-controlled display name. Defaults to the first name to limit exposure of minors. */
    val displayName: String,
    /** Unguessable per-athlete link key; the phone bookmarks /a/{token}. */
    val token: String,
    val className: String? = null,
)

@Serializable
data class PublishRun(
    val id: String,
    val athleteId: Int,
    val sequence: Int,
    val status: RunStatus,
    val durationMs: Long? = null,
    /** Race round (1 or 2) when the run is assigned to the competition; null in training. */
    val round: Int? = null,
)

@Serializable
data class PublishStanding(
    val athleteId: Int,
    val className: String,
    val status: String,
    val place: Int? = null,
    val scoreMs: Long? = null,
)

@Serializable
data class PublishMarker(val afterSequence: Int, val label: String)

@Serializable
data class PublishEnvelope(
    val revision: Long,
    val publishedAt: String,
    val session: PublishSession,
    val schemaVersion: Int = SCHEMA_VERSION,
    val race: PublishRace? = null,
    val athletes: List<PublishAthlete> = emptyList(),
    val runs: List<PublishRun> = emptyList(),
    val standings: List<PublishStanding> = emptyList(),
    val markers: List<PublishMarker> = emptyList(),
    /** Optional bounded PNG branding; older tablets omit it. No remote image URLs. */
    val logoPngBase64: String? = null,
) {
    companion object { const val SCHEMA_VERSION = 1 }
}

/** The server's reply to a publish: which revision it now holds, and whether this one was accepted. */
@Serializable
data class PublishAck(val sessionId: String, val revision: Long, val accepted: Boolean)

/**
 * The body of a publish call: the [envelope] plus the one piece of operator state that has to travel
 * with it. The link-index password is not a result, so it stays out of the envelope — the envelope is
 * what a page is rendered from and what any public read returns, so a credential in there would leak
 * twice over.
 *
 * [indexPassword] is the password the coach set on the tablet for this session. `null` leaves whatever
 * the server already holds (an older tablet omits the field entirely); an empty string clears it, which
 * is how the coach turns the protection off again.
 */
@Serializable
data class PublishRequest(
    val envelope: PublishEnvelope,
    val indexPassword: String? = null,
    /** Private finish-line feedback, never part of the public envelope or personal API. */
    val coaching: CoachProjection? = null,
)

@Serializable
data class CoachAthlete(val id: Int, val name: String, val participating: Boolean = true)

@Serializable
data class CoachRun(
    val runId: String,
    val observations: List<String> = emptyList(),
    val nextFocus: List<String> = emptyList(),
    val changes: List<String> = emptyList(),
    val comment: String = "",
)

@Serializable
data class CoachProjection(val athletes: List<CoachAthlete> = emptyList(), val runs: List<CoachRun> = emptyList())

/** Swedish race/result time, three decimals with a comma, matching the tablet's reports. */
fun formatSeconds(ms: Long): String = "${ms / 1000},${(ms % 1000).toString().padStart(3, '0')}"

/** Signed personal change, e.g. "0,42 s snabbare". Neutral wording; lower is faster. */
fun formatDifference(ms: Long): String = when {
    ms < 0 -> "${formatSeconds(-ms)} s snabbare"
    ms > 0 -> "${formatSeconds(ms)} s långsammare"
    else -> "Samma tid"
}

object PublishJson {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun encode(value: PublishEnvelope): String = json.encodeToString(PublishEnvelope.serializer(), value)
    fun decode(text: String): PublishEnvelope = json.decodeFromString(PublishEnvelope.serializer(), text)

    fun encode(request: PublishRequest): String = json.encodeToString(PublishRequest.serializer(), request)
    fun decodeRequest(text: String): PublishRequest = json.decodeFromString(PublishRequest.serializer(), text)
}
