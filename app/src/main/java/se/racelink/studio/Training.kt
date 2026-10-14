package se.racelink.studio

import java.util.Locale

data class Athlete(val id: Int, val bib: Int, val name: String)
data class CoachingTag(val id: Int, val name: String)
data class Attempt(
    val id: String, val athleteId: Int, val sequence: Int, val durationMs: Long?,
    val complete: Boolean = true, val tags: Set<Int> = emptySet(), val note: String = ""
)
data class Marker(val afterSequence: Int, val label: String)
data class Training(
    val athletes: List<Athlete>, val attempts: List<Attempt>, val tags: List<CoachingTag>,
    val markers: List<Marker> = emptyList(), val audit: List<String> = emptyList(),
    val team: String = "Fjällbackens skidklubb", val dark: Boolean = false,
    val pending: List<String> = emptyList()
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
fun Training.ingest(event: TimingObservation): Training {
    if (attempts.any { it.id == event.id }) return this
    val athlete = athletes.singleOrNull { it.bib == event.bib }
        ?: throw IllegalArgumentException("Okänt eller tvetydigt nummer: ${event.bib}")
    require(event.durationMs == null || event.durationMs > 0)
    require(!event.complete || event.durationMs != null)
    return copy(attempts = attempts + Attempt(event.id, athlete.id, event.sequence, event.durationMs, event.complete),
        pending = pending + event.id)
}
fun Training.runs(athleteId: Int) = attempts.filter { it.athleteId == athleteId }.sortedBy { it.sequence }
fun List<Attempt>.eligible() = filter { it.complete && it.durationMs != null }
fun List<Attempt>.previous(run: Attempt) = eligible().lastOrNull { it.sequence < run.sequence }
fun seconds(ms: Long) = String.format(Locale.forLanguageTag("sv-SE"), "%.2f", ms / 1000.0)
fun difference(ms: Long) = when {
    ms < 0 -> "${seconds(-ms)} s kortare"
    ms > 0 -> "${seconds(ms)} s längre"
    else -> "Samma tid"
}
fun Training.correct(id: String, athleteId: Int, complete: Boolean, tags: Set<Int>, note: String): Training {
    require(athletes.any { it.id == athleteId })
    val old = attempts.single { it.id == id }
    require(!complete || old.durationMs != null) { "Ingen måltid finns för detta åk" }
    val revised = old.copy(athleteId = athleteId, complete = complete, tags = tags, note = note.trim())
    if (old == revised) return this
    return copy(attempts = attempts.map { if (it.id == id) revised else it },
        audit = audit + "${java.time.Instant.now()} | $id | $old -> $revised")
}
fun demoTraining(): Training {
    val athletes = listOf(Athlete(1, 12, "Alma"), Athlete(2, 24, "Elias"), Athlete(3, 31, "Maja"), Athlete(4, 45, "Noah"))
    val times = listOf(35620L, 36210L, 34800L, 37120L, 34980L, 35840L, 34320L, 36510L,
        0L, 35090L, 34620L, 36100L, 33840L, 34760L, 33910L, 35630L, 33420L)
    return Training(athletes, times.mapIndexed { index, time ->
        Attempt("seed-$index", index % 4 + 1, index + 1, time.takeIf { it > 0 }, time > 0,
            if (index == 4) setOf(1) else emptySet())
    }, listOf("Teknikfokus", "Bra känsla", "Misstag", "Ändrade förhållanden", "Sen")
        .mapIndexed { i, name -> CoachingTag(i + 1, name) }, listOf(Marker(8, "Portarna flyttade")))
}
