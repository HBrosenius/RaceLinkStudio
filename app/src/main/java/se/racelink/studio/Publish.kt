package se.racelink.studio

import se.racelink.publish.PublishAthlete
import se.racelink.publish.PublishEnvelope
import se.racelink.publish.PublishFormat
import se.racelink.publish.PublishMarker
import se.racelink.publish.PublishMode
import se.racelink.publish.PublishRace
import se.racelink.publish.PublishRun
import se.racelink.publish.PublishSession
import se.racelink.publish.PublishStanding
import se.racelink.publish.RunStatus
import java.security.MessageDigest

/**
 * Builds the read-only publishing projection sent to the server. This is *not* the backup format:
 * it deliberately omits the audit journal, coaching notes, tags, pending review events and the
 * session archive. Private coaching feedback has its own authenticated projection. Publishing never feeds back into
 * the domain, so a failed publish cannot affect timing.
 */
internal object PublishProjection {
    fun coaching(state: Training) = se.racelink.publish.CoachProjection(
        athletes = state.athletes.map {
            se.racelink.publish.CoachAthlete(it.id, it.name, it.id in state.participantIds)
        },
        runs = state.attempts.map { run ->
            fun labels(category: TagCategory) = state.tags.filter { it.id in run.tags && it.category == category }.map { it.name }
            se.racelink.publish.CoachRun(run.id, labels(TagCategory.RUN), labels(TagCategory.NEXT_FOCUS),
                state.changesFor(run).map { state.markerDescription(it) }, run.note)
        },
    )

    /**
     * Stable, unguessable per-athlete link token derived from the (random) session id, so the same
     * phone can bookmark one URL for the whole session without the server ever issuing ids.
     */
    fun tokenFor(sessionId: String, athleteId: Int): String =
        MessageDigest.getInstance("SHA-256")
            .digest("$sessionId:$athleteId:racelink".toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
            .take(32)

    fun of(
        state: Training,
        revision: Long,
        publishedAt: String,
        displayName: (Athlete) -> String = { it.name.substringBefore(' ') },
    ): PublishEnvelope = PublishEnvelope(
        revision = revision,
        publishedAt = publishedAt,
        session = PublishSession(
            id = state.sessionId,
            name = state.sessionName,
            date = state.sessionDate,
            mode = if (state.race != null) PublishMode.RACE else PublishMode.TRAINING,
            closed = state.closedAt != null,
            location = state.location,
            course = state.course,
            type = state.trainingType,
            team = state.team,
        ),
        race = state.race?.let { PublishRace(PublishFormat.valueOf(it.format.name), it.classes) },
        athletes = state.athletes.map { athlete ->
            PublishAthlete(
                id = athlete.id,
                bib = athlete.bib,
                displayName = displayName(athlete),
                token = tokenFor(state.sessionId, athlete.id),
                className = state.race?.athleteClasses?.get(athlete.id),
            )
        },
        runs = state.attempts.sortedBy { it.sequence }.map { run ->
            PublishRun(
                id = run.id,
                athleteId = run.athleteId,
                sequence = run.sequence,
                status = statusOf(run),
                durationMs = run.durationMs,
                round = state.race?.roundByAttempt?.get(run.id),
            )
        },
        standings = state.race?.let {
            state.raceStandings().map { s -> PublishStanding(s.athlete.id, s.className, s.status, s.place, s.scoreMs) }
        } ?: emptyList(),
        markers = state.markers.map { PublishMarker(it.afterSequence, state.markerDescription(it)) },
    )

    private fun statusOf(run: Attempt): RunStatus = when {
        run.complete && run.durationMs != null -> RunStatus.COMPLETED
        run.durationMs != null -> RunStatus.DNF
        else -> RunStatus.INCOMPLETE
    }
}
