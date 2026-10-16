package se.racelink.server

import kotlinx.serialization.Serializable
import se.racelink.publish.PublishAthlete
import se.racelink.publish.PublishEnvelope
import se.racelink.publish.PublishFormat
import se.racelink.publish.PublishRun
import se.racelink.publish.RunStatus
import se.racelink.publish.formatDifference
import se.racelink.publish.formatSeconds

/**
 * Everything one skier is allowed to see: their own training progress, or their own race result.
 * Deliberately excludes other skiers' data, so the page can never become a leaderboard in training.
 */
@Serializable
data class PersonalView(
    val displayName: String,
    val bib: Int,
    val className: String? = null,
    val mode: String,
    val closed: Boolean,
    val team: String,
    val sessionName: String,
    val sessionDate: String,
    val latest: String? = null,
    val latestRun: Int? = null,
    val delta: String? = null,
    val best: String? = null,
    val bestRun: Int? = null,
    val run1: String? = null,
    val run2: String? = null,
    val score: String? = null,
    val scoreLabel: String? = null,
    val place: Int? = null,
    val standing: String? = null,
    /** This skier's own completed runs in order, for the trend chart. Never another skier's. */
    val trend: List<RunTrendPoint> = emptyList(),
    val updatedAt: String,
    val logoPngBase64: String? = null,
    /** Exact-time personal best, including ties; a later DNF/incomplete run is never celebrated. */
    val latestIsBest: Boolean = false,
    /** Includes DNF/incomplete arrivals; other skiers and ordinary corrections do not advance it. */
    val latestRecordedSequence: Int = 0,
)

/** One plotted run: this skier's own run order (1, 2, 3 …), its time, and that time formatted. */
@Serializable
data class RunTrendPoint(val run: Int, val ms: Long, val label: String)

private fun runLabel(run: PublishRun): String = when (run.status) {
    RunStatus.COMPLETED -> run.durationMs?.let(::formatSeconds) ?: "–"
    RunStatus.DNF -> "DNF"
    RunStatus.INCOMPLETE -> "–"
}

fun personalView(envelope: PublishEnvelope, athlete: PublishAthlete, namePolicy: NamePolicy = NamePolicy.FIRST): PersonalView {
    val own = envelope.runs.filter { it.athleteId == athlete.id }.sortedBy { it.sequence }
    val completed = own.filter { it.status == RunStatus.COMPLETED && it.durationMs != null }
    val latest = completed.lastOrNull()
    val previous = latest?.let { last -> completed.lastOrNull { it.sequence < last.sequence } }
    val best = completed.minByOrNull { it.durationMs!! }
    val standing = envelope.standings.firstOrNull { it.athleteId == athlete.id }
    val latestMs = latest?.durationMs
    val previousMs = previous?.durationMs
    return PersonalView(
        displayName = if (namePolicy == NamePolicy.BIB) "" else athlete.displayName,
        bib = athlete.bib,
        className = athlete.className,
        mode = envelope.session.mode.name.lowercase(),
        closed = envelope.session.closed,
        team = envelope.session.team,
        sessionName = envelope.session.name,
        sessionDate = envelope.session.date,
        latest = latest?.durationMs?.let(::formatSeconds),
        latestRun = latest?.sequence,
        delta = if (latestMs != null && previousMs != null) formatDifference(latestMs - previousMs) else null,
        best = best?.durationMs?.let(::formatSeconds),
        bestRun = best?.sequence,
        run1 = own.firstOrNull { it.round == 1 }?.let(::runLabel),
        run2 = own.firstOrNull { it.round == 2 }?.let(::runLabel),
        score = standing?.scoreMs?.let(::formatSeconds),
        scoreLabel = envelope.race?.format?.let { if (it == PublishFormat.TOTAL) "Totaltid" else "Tidsskillnad" },
        place = standing?.place,
        standing = standing?.status,
        trend = completed.mapIndexed { index, run ->
            // Number the skier's own runs 1, 2, 3 … rather than the session-wide sequence, which is a
            // coach's bookkeeping detail and reads as nonsense to the skier ("varför börjar jag på 6?").
            run.durationMs?.let { RunTrendPoint(index + 1, it, formatSeconds(it)) }
        }.filterNotNull(),
        updatedAt = envelope.publishedAt,
        logoPngBase64 = envelope.logoPngBase64?.takeIf(::validPublishLogo),
        latestIsBest = latest != null && own.lastOrNull()?.id == latest.id && latestMs == best?.durationMs,
        latestRecordedSequence = own.lastOrNull()?.sequence ?: 0,
    )
}

internal fun validPublishLogo(value: String): Boolean = value.length <= 400_000 && runCatching {
    val bytes = java.util.Base64.getDecoder().decode(value)
    bytes.size >= 8 && bytes.take(8) == listOf(137, 80, 78, 71, 13, 10, 26, 10).map { it.toByte() }
}.getOrDefault(false)
