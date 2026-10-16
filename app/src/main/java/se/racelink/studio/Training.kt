package se.racelink.studio

import java.util.Locale

data class Athlete(val id: Int, val bib: Int, val name: String)
enum class TagCategory(val label: String) { RUN("Beskriv åket"), NEXT_FOCUS("Fokus nästa åk"), CHANGE("Ändring") }
data class CoachingTag(val id: Int, val name: String, val category: TagCategory = TagCategory.RUN)
fun List<CoachingTag>.withFocusDefaults(): List<CoachingTag> {
    if (any { it.category == TagCategory.NEXT_FOCUS }) return this
    val maxId = maxOfOrNull { it.id } ?: 0
    return this + listOf("Blick framåt", "Tidigare kantbyte", "Lugn överkropp")
        .mapIndexed { index, name -> CoachingTag(maxId + index + 1, name, TagCategory.NEXT_FOCUS) }
}
fun List<CoachingTag>.withChangeDefaults(): List<CoachingTag> {
    if (any { it.category == TagCategory.CHANGE }) return this
    val last = maxOfOrNull { it.id } ?: 0
    return this + listOf("Portarna flyttade", "Ändrad bana", "Ändrat underlag", "Ändrad övning")
        .mapIndexed { i, name -> CoachingTag(last + i + 1, name, TagCategory.CHANGE) }
}
fun Training.originalSequence(run: Attempt): Int = timingEvents.firstOrNull {
    it.attemptId == run.id && it.decision in listOf(TimingDecision.ACCEPTED, TimingDecision.ASSIGNED)
}?.observation?.sequence ?: run.sequence
fun Training.changesFor(run: Attempt): List<Marker> = markers.filter { it.afterSequence < originalSequence(run) }
fun Training.markerDescription(marker: Marker) = (listOf(marker.label).filter { it.isNotBlank() } +
    tags.filter { it.id in marker.tags }.map { it.name }).joinToString(" · ")
fun Training.tagDescription(run: Attempt, category: TagCategory): String =
    if (category == TagCategory.CHANGE) changesFor(run).joinToString("; ") { markerDescription(it) }
    else tags.filter { it.id in run.tags && it.category == category }.joinToString(" · ") { it.name }
fun Training.recordChange(tagIds: Set<Int>, description: String): Training {
    require(closedAt == null) { "Passet är avslutat" }
    require(tagIds.isNotEmpty() || description.isNotBlank()) { "Välj taggar eller beskriv ändringen" }
    require(tagIds.all { id -> tags.any { it.id == id && it.category == TagCategory.CHANGE } })
    val marker = Marker(nextTimingSequence() - 1, description.trim(), tagIds)
    return copy(markers = markers + marker, audit = audit + "${java.time.Instant.now()} · Ändring efter ${marker.afterSequence}: ${markerDescription(marker)}")
}

fun Training.editChange(index: Int, tagIds: Set<Int>, description: String): Training {
    require(closedAt == null) { "Passet är avslutat" }
    require(index in markers.indices) { "Ändringen finns inte" }
    require(tagIds.isNotEmpty() || description.isNotBlank()) { "Välj taggar eller beskriv ändringen" }
    require(tagIds.all { id -> tags.any { it.id == id && it.category == TagCategory.CHANGE } })
    val old = markers[index]
    val updated = old.copy(label = description.trim(), tags = tagIds)
    return copy(markers = markers.mapIndexed { i, marker -> if (i == index) updated else marker },
        audit = audit + "${java.time.Instant.now()} · Rättad ändring efter ${old.afterSequence}: ${markerDescription(old)} -> ${markerDescription(updated)}")
}

fun Training.removeChange(index: Int): Training {
    require(closedAt == null) { "Passet är avslutat" }
    require(index in markers.indices) { "Ändringen finns inte" }
    val removed = markers[index]
    return copy(markers = markers.filterIndexed { i, _ -> i != index },
        audit = audit + "${java.time.Instant.now()} · Borttagen ändring efter ${removed.afterSequence}: ${markerDescription(removed)}")
}

data class Attempt(
    val id: String, val athleteId: Int, val sequence: Int, val durationMs: Long?,
    val complete: Boolean = true, val tags: Set<Int> = emptySet(), val note: String = ""
)
data class Marker(val afterSequence: Int, val label: String, val tags: Set<Int> = emptySet())
data class Training(
    val athletes: List<Athlete>, val attempts: List<Attempt>, val tags: List<CoachingTag>,
    val markers: List<Marker> = emptyList(), val audit: List<String> = emptyList(),
    val team: String = "Fjällbackens skidklubb", val dark: Boolean = false,
    val pending: List<String> = emptyList(),
    val sessionId: String = java.util.UUID.randomUUID().toString(),
    val sessionDate: String = java.time.LocalDate.now().toString(),
    val archivedSessions: List<Training> = emptyList(),
    val sessionName: String = "Träningspass", val location: String = "",
    val course: String = "", val trainingType: String = "",
    val roster: List<Athlete> = athletes,
    val participantIds: Set<Int> = athletes.map { it.id }.toSet(),
    val nextAthleteId: Int? = null,
    val timingEvents: List<RecordedTiming> = emptyList(),
    val closedAt: String? = null,
    val archivedRosterIds: Set<Int> = emptySet(),
    val race: RaceConfig? = null
)
data class TimingObservation(val id: String, val bib: Int, val sequence: Int, val durationMs: Long?, val complete: Boolean)
interface TimingAdapter {
    val label: String
    fun next(sequence: Int, bib: Int, incomplete: Boolean = false): TimingObservation
}
class SimulatorAdapter : TimingAdapter {
    override val label = "SIMULATOR · Ingen hårdvara"
    override fun next(sequence: Int, bib: Int, incomplete: Boolean) = TimingObservation(
        "demo-$sequence", bib, sequence,
        if (incomplete) null else 31500L + ((sequence * 137 + bib * 83) % 4800), !incomplete
    )
}
fun Training.runs(athleteId: Int) = attempts.filter { it.athleteId == athleteId }.sortedBy { it.sequence }
fun Training.activeAthletes() = athletes.filter { it.id in participantIds }
fun Training.selectNextAthlete(id: Int?): Training {
    require(closedAt == null) { "Passet är avslutat" }
    require(id == null || activeAthletes().any { it.id == id }) { "Välj en deltagande åkare" }
    return copy(nextAthleteId = id)
}
fun Training.simulateNext(adapter: TimingAdapter, incomplete: Boolean = false): Training {
    require(closedAt == null) { "Passet är avslutat" }
    val athlete = activeAthletes().singleOrNull { it.id == nextAthleteId }
        ?: throw IllegalArgumentException("Välj nästa åkare först")
    val sequence = nextTimingSequence()
    val round = if (race == null) null else requireNotNull(nextSimulatedRaceRound(athlete.id)) {
        "Åkaren har redan två tävlingsåk eller ett extraresultat som behöver tilldelas först."
    }
    val event = adapter.next(sequence, athlete.bib, incomplete)
    val next = ingest(event)
    return if (round != null && next.race!!.roundByAttempt[event.id] != round) next.assignRaceRun(event.id, round) else next
}
fun Training.toggleTag(runId: String, tagId: Int): Training {
    require(tags.any { it.id == tagId && it.category != TagCategory.CHANGE })
    val run = attempts.single { it.id == runId }
    return correct(run.id, run.athleteId, run.complete,
        if (tagId in run.tags) run.tags - tagId else run.tags + tagId, run.note)
}
fun Training.setRunDnf(runId: String, dnf: Boolean): Training {
    val run = attempts.single { it.id == runId }
    return correct(run.id, run.athleteId, !dnf, run.tags, run.note)
}
fun List<Attempt>.eligible() = filter { it.complete && it.durationMs != null }
fun List<Attempt>.previous(run: Attempt) = eligible().lastOrNull { it.sequence < run.sequence }
fun seconds(ms: Long) = String.format(Locale.forLanguageTag("sv-SE"), "%.2f", ms / 1000.0)
fun difference(ms: Long) = when {
    ms < 0 -> "${seconds(-ms)} s snabbare"
    ms > 0 -> "${seconds(ms)} s långsammare"
    else -> "Samma tid"
}
fun Training.sessionLabel() = "$sessionDate · $sessionName"
fun Training.sessionDetails() = listOf(location, course, trainingType).filter { it.isNotBlank() }.joinToString(" · ")
fun Training.closeSession(): Training {
    if (closedAt != null) return this
    require(reviewQueue().isEmpty()) { "Granska väntande resultat innan passet avslutas" }
    require(race == null || attempts.all { it.id in race.roundByAttempt || it.id in race.unusedAttemptIds }) { "Tilldela eller undanta extraresultaten innan tävlingen avslutas." }
    val now = java.time.Instant.now().toString()
    return copy(closedAt = now, nextAthleteId = null, pending = emptyList(),
        audit = audit + "$now | Pass avslutat | $sessionId")
}
fun Training.startNewSession(
    name: String = "Träningspass", date: String = java.time.LocalDate.now().toString(),
    location: String = "", course: String = "", trainingType: String = "",
    roster: List<Athlete> = this.roster, participants: Set<Int> = participantIds - archivedRosterIds
): Training {
    require(reviewQueue().isEmpty()) { "Granska väntande resultat innan ett nytt pass startas" }
    require(race == null || attempts.all { it.id in race.roundByAttempt || it.id in race.unusedAttemptIds }) { "Tilldela eller undanta extraresultaten innan ett nytt pass startas." }
    require(name.isNotBlank()) { "Ange ett passnamn" }
    java.time.LocalDate.parse(date)
    require(roster.isNotEmpty() && roster.all { it.name.isNotBlank() && it.bib > 0 }) { "Åkare behöver namn och positivt nummer" }
    require(roster.map { it.id }.distinct().size == roster.size && roster.map { it.bib }.distinct().size == roster.size) { "Åkarnas nummer måste vara unika" }
    require(participants.isNotEmpty() && participants.all { id -> roster.any { it.id == id } }) { "Välj minst en åkare" }
    return copy(
        athletes = roster.filter { it.id in participants }, roster = roster, participantIds = participants, nextAthleteId = null,
        archivedRosterIds = archivedRosterIds.intersect(roster.map { it.id }.toSet()),
        sessionName = name.trim(), sessionDate = date, location = location.trim(), course = course.trim(), trainingType = trainingType.trim(),
        attempts = emptyList(), markers = emptyList(), audit = emptyList(), pending = emptyList(), timingEvents = emptyList(), race = null,
        sessionId = java.util.UUID.randomUUID().toString(), closedAt = null,
        archivedSessions = listOf(copy(archivedSessions = emptyList(), pending = emptyList(), nextAthleteId = null,
            closedAt = closedAt ?: java.time.Instant.now().toString())) + archivedSessions
    )
}
fun Training.editSession(name: String, date: String, location: String, course: String, trainingType: String,
    roster: List<Athlete>, participants: Set<Int>): Training {
    require(closedAt == null) { "Passet är avslutat" }
    val validated = copy(timingEvents = emptyList()).startNewSession(name, date, location, course, trainingType, roster, participants)
    require(this.roster.all { old -> roster.any { it.id == old.id } }) { "Befintliga åkare får inte tas bort ur åkarlistan" }
    val retainedIds = athletes.map { it.id }.toSet() + participants
    val revised = copy(sessionName = validated.sessionName, sessionDate = validated.sessionDate,
        location = validated.location, course = validated.course, trainingType = validated.trainingType,
        roster = validated.roster, participantIds = participants, athletes = roster.filter { it.id in retainedIds },
        nextAthleteId = nextAthleteId?.takeIf { it in participants })
    if (revised == this) return this
    return revised.copy(audit = audit + "${java.time.Instant.now()} | Passändring | ${sessionLabel()} / ${sessionDetails()} / $participantIds / ${this.roster} -> ${revised.sessionLabel()} / ${revised.sessionDetails()} / $participants / $roster")
}
fun Training.correct(id: String, athleteId: Int, complete: Boolean, tags: Set<Int>, note: String): Training {
    require(closedAt == null) { "Passet är avslutat" }
    require(athletes.any { it.id == athleteId })
    val old = attempts.single { it.id == id }
    require(!complete || old.durationMs != null) { "Ingen måltid finns för detta åk" }
    val revised = old.copy(athleteId = athleteId, complete = complete, tags = tags, note = note.trim())
    if (old == revised) return this
    val next = copy(attempts = attempts.map { if (it.id == id) revised else it },
        audit = audit + runEditRecord(old, revised))
    next.validateRace()
    return next
}
fun demoTraining(): Training {
    val athletes = listOf(Athlete(1, 12, "Alma"), Athlete(2, 24, "Elias"), Athlete(3, 31, "Maja"), Athlete(4, 45, "Noah"))
    val times = listOf(35620L, 36210L, 34800L, 37120L, 34980L, 35840L, 34320L, 36510L,
        0L, 35090L, 34620L, 36100L, 33840L, 34760L, 33910L, 35630L, 33420L)
    return Training(athletes, times.mapIndexed { index, time ->
        Attempt("seed-$index", index % 4 + 1, index + 1, time.takeIf { it > 0 }, time > 0,
            if (index == 4) setOf(1) else emptySet())
    }, listOf("Teknikfokus", "Bra känsla", "Misstag", "Ändrade förhållanden", "Sen")
        .mapIndexed { i, name -> CoachingTag(i + 1, name) }.withFocusDefaults().withChangeDefaults(), listOf(Marker(8, "Portarna flyttade")))
}
