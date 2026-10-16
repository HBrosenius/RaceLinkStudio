package se.racelink.studio

enum class TimingDecision { WAITING, ACCEPTED, ASSIGNED, DUPLICATE, REJECTED }
data class RecordedTiming(
    val id: String, val observation: TimingObservation, val reason: String,
    val decision: TimingDecision = TimingDecision.WAITING,
    val athleteId: Int? = null, val attemptId: String? = null, val decidedAt: String? = null
)
fun Training.reviewQueue() = timingEvents.filter { it.decision == TimingDecision.WAITING }
fun Training.nextTimingSequence() = Math.addExact(maxOf(attempts.maxOfOrNull { it.sequence } ?: 0,
    timingEvents.maxOfOrNull { it.observation.sequence } ?: 0), 1)

fun Training.ingest(event: TimingObservation, uncertain: Boolean = false): Training {
    require(closedAt == null) { "Passet är avslutat" }
    require(event.id.isNotBlank() && event.sequence > 0)
    require(event.durationMs == null || event.durationMs > 0)
    require(!event.complete || event.durationMs != null)
    if (timingEvents.any { it.observation == event }) return this
    // Older snapshots have no original observations; exact-ID replays must still be idempotent.
    if (timingEvents.none { it.observation.id == event.id } && attempts.any { it.id == event.id }) return this
    val athlete = activeAthletes().singleOrNull { it.bib == event.bib }
    val conflict = timingEvents.any { it.observation.id == event.id || it.observation.sequence == event.sequence } || attempts.any { it.id == event.id || it.sequence == event.sequence }
    val reason = when {
        conflict -> "Motstridigt resultat-ID eller ordningsnummer"
        uncertain -> "Osäker målgång · kräver manuell tilldelning"
        athlete == null -> "Okänt eller inaktivt nummer: ${event.bib}"
        else -> "Matchat nummer"
    }
    val recorded = RecordedTiming(java.util.UUID.randomUUID().toString(), event, reason,
        if (conflict || uncertain || athlete == null) TimingDecision.WAITING else TimingDecision.ACCEPTED,
        if (conflict || uncertain) null else athlete?.id,
        if (!conflict && !uncertain && athlete != null) event.id else null,
        if (!conflict && !uncertain && athlete != null) java.time.Instant.now().toString() else null)
    if (recorded.decision == TimingDecision.WAITING) return copy(timingEvents = timingEvents + recorded)
    return copy(timingEvents = timingEvents + recorded,
        attempts = attempts + Attempt(event.id, athlete!!.id, event.sequence, event.durationMs, event.complete), pending = pending + event.id).attachNewRaceRun(event.id)
}

fun Training.resolveTiming(id: String, decision: TimingDecision, athleteId: Int? = null, duplicateId: String? = null): Training {
    require(closedAt == null) { "Passet är avslutat" }
    val old = timingEvents.single { it.id == id }
    require(old.decision == TimingDecision.WAITING) { "Resultatet är redan granskat" }
    require(decision in setOf(TimingDecision.ASSIGNED, TimingDecision.DUPLICATE, TimingDecision.REJECTED))
    val event = old.observation
    var result = this
    var attemptId: String? = null
    when (decision) {
        TimingDecision.ASSIGNED -> {
            require(athletes.any { it.id == athleteId }) { "Välj en åkare i passet" }
            require(race == null || athleteId in participantIds) { "Välj en deltagare i tävlingen." }
            attemptId = if (attempts.none { it.id == event.id }) event.id else "review-${old.id}"
            val sequence = if (attempts.none { it.sequence == event.sequence }) event.sequence else nextTimingSequence()
            result = copy(attempts = attempts + Attempt(attemptId, athleteId!!, sequence, event.durationMs, event.complete), pending = pending + attemptId)
        }
        TimingDecision.DUPLICATE -> {
            require(attempts.any { it.id == duplicateId }) { "Välj vilket befintligt åk detta är en dubblett av" }
            attemptId = duplicateId
        }
        else -> Unit
    }
    val resolved = old.copy(decision = decision, athleteId = if (decision == TimingDecision.ASSIGNED) athleteId else null,
        attemptId = attemptId, decidedAt = java.time.Instant.now().toString())
    return result.copy(timingEvents = timingEvents.map { if (it.id == id) resolved else it },
        audit = audit + "${resolved.decidedAt} | Resultatgranskning | $old -> $resolved")
}

fun Training.simulateReview(ambiguous: Boolean): Training {
    val sequence = nextTimingSequence()
    val bib = if (ambiguous) activeAthletes().first().bib else -1
    return ingest(SimulatorAdapter().next(sequence, bib).copy(id = "review-demo-$sequence"), uncertain = ambiguous)
}
