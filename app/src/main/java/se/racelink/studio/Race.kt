package se.racelink.studio

enum class RaceFormat(val label: String, val scoreLabel: String) {
    TOTAL("Två åk · totaltid", "Totaltid"),
    DIFFERENCE("Differensslalom", "Tidsskillnad")
}

data class RaceConfig(val format: RaceFormat, val classes: List<String>, val athleteClasses: Map<Int, String>,
    val activeRound: Int = 1, val roundByAttempt: Map<String, Int> = emptyMap(), val unusedAttemptIds: Set<String> = emptySet())

internal fun Training.validateRace() {
    val r = race ?: return
    require(r.classes.isNotEmpty() && r.classes.all { it.isNotBlank() && it == it.trim() && it.length <= 80 }) { "Ange klassnamn (högst 80 tecken)." }
    require(r.classes.map { it.lowercase(java.util.Locale.ROOT) }.distinct().size == r.classes.size) { "Klassnamnen måste vara unika." }
    require(r.activeRound in 1..2) { "Välj åk 1 eller 2." }
    require(r.athleteClasses.keys == participantIds && r.athleteClasses.values.all { it in r.classes }) { "Alla deltagare måste tillhöra en klass." }
    require(attempts.all { it.athleteId in participantIds }) { "En åkare med resultat kan inte tas bort." }
    require(r.roundByAttempt.all { (id, round) -> attempts.any { it.id == id } && round in 1..2 }) { "Ogiltig åktilldelning." }
    val slots = r.roundByAttempt.map { (id, round) -> attempts.single { it.id == id }.athleteId to round }
    require(slots.distinct().size == slots.size) { "En åkare kan bara ha ett resultat per tävlingsåk." }
    require(r.unusedAttemptIds.all { id -> attempts.any { it.id == id } && id !in r.roundByAttempt }) { "Ogiltigt undantaget tävlingsresultat." }
}

internal fun Training.configureRace(config: RaceConfig): Training {
    require(closedAt == null) { "Tävlingen är avslutad." }
    require(race != null || attempts.isEmpty()) { "Ett träningspass med resultat kan inte omvandlas till en tävling." }
    require(attempts.isEmpty() || config.format == race?.format) { "Tävlingsformen låses efter första resultatet." }
    val next = copy(race = config)
    next.validateRace()
    return next.copy(audit = audit + "${java.time.Instant.now()} | Tävlingsupplägg | $race -> $config")
}

internal fun Training.selectRaceRound(round: Int): Training {
    require(closedAt == null && round in 1..2)
    val r = requireNotNull(race)
    if (r.activeRound == round) return this
    return copy(race = r.copy(activeRound = round), audit = audit + "${java.time.Instant.now()} | Aktivt tävlingsåk | ${r.activeRound} -> $round")
}

internal fun Training.raceRun(athleteId: Int, round: Int): Attempt? = race?.roundByAttempt?.entries
    ?.firstOrNull { it.value == round && attempts.any { run -> run.id == it.key && run.athleteId == athleteId } }
    ?.let { entry -> attempts.single { it.id == entry.key } }

// Simulation knows that each button press represents a new race run, unlike raw timer input.
internal fun Training.nextSimulatedRaceRound(athleteId: Int): Int? {
    val r = race ?: return null
    if (attempts.any { it.athleteId == athleteId && it.id !in r.roundByAttempt && it.id !in r.unusedAttemptIds }) return null
    return listOf(r.activeRound, 3 - r.activeRound).firstOrNull { raceRun(athleteId, it) == null }
}

// Additional accepted results remain visible but unassigned: never overwrite a race slot.
internal fun Training.attachNewRaceRun(id: String): Training {
    val r = race ?: return this
    val run = attempts.single { it.id == id }
    if (raceRun(run.athleteId, r.activeRound) != null) return this
    return copy(race = r.copy(roundByAttempt = r.roundByAttempt + (id to r.activeRound)))
}

internal fun Training.assignRaceRun(id: String, round: Int?): Training {
    require(closedAt == null) { "Tävlingen är avslutad." }
    val r = requireNotNull(race)
    val run = attempts.single { it.id == id }
    require(round == null || round in 1..2)
    require(round == null || raceRun(run.athleteId, round)?.id.let { it == null || it == id }) { "Åket har redan ett resultat. Ta bort den tilldelningen först." }
    val next = copy(race = r.copy(roundByAttempt = if (round == null) r.roundByAttempt - id else r.roundByAttempt + (id to round), unusedAttemptIds = r.unusedAttemptIds - id))
    next.validateRace()
    return next.copy(audit = audit + "${java.time.Instant.now()} | Tävlingsåk tilldelat | $id: ${r.roundByAttempt[id]} -> $round")
}

internal fun Training.excludeRaceRun(id: String): Training {
    require(closedAt == null)
    val r = requireNotNull(race)
    require(attempts.any { it.id == id } && id !in r.roundByAttempt) { "Ta först bort åktilldelningen." }
    return copy(race = r.copy(unusedAttemptIds = r.unusedAttemptIds + id), audit = audit + "${java.time.Instant.now()} | Extraresultat undantaget från tävling | $id")
}

internal data class RaceStanding(val athlete: Athlete, val className: String, val first: Attempt?, val second: Attempt?,
    val scoreMs: Long?, val status: String, val place: Int? = null)

internal fun Training.raceStandings(): List<RaceStanding> {
    val r = requireNotNull(race)
    return r.classes.flatMap { className ->
        val entries = athletes.filter { r.athleteClasses[it.id] == className }.map { athlete ->
            val a = raceRun(athlete.id, 1); val b = raceRun(athlete.id, 2)
            val valid = a?.complete == true && a.durationMs != null && b?.complete == true && b.durationMs != null
            val score = if (!valid) null else if (r.format == RaceFormat.TOTAL) runCatching { Math.addExact(a!!.durationMs!!, b!!.durationMs!!) }.getOrNull()
                else kotlin.math.abs(a!!.durationMs!! - b!!.durationMs!!)
            val status = when {
                listOfNotNull(a, b).any { !it.complete } -> "DNF · ingen placering"
                valid -> if (score == null) "Tiderna är för stora för beräkning" else "Två fullföljda åk"
                a == null && b == null -> if (closedAt == null) "Väntar på start" else "Inga tävlingsåk"
                a == null -> "Åk 1 saknas"
                else -> "Åk 2 saknas"
            }
            RaceStanding(athlete, className, a, b, score, status)
        }.sortedWith(compareBy<RaceStanding> { it.scoreMs == null }.thenBy { it.scoreMs }.thenBy { it.athlete.bib })
        var previousScore: Long? = null; var previousPlace: Int? = null
        entries.mapIndexed { index, entry ->
            val place = if (entry.scoreMs == null) null else if (entry.scoreMs == previousScore) previousPlace else index + 1
            previousScore = entry.scoreMs; previousPlace = place
            entry.copy(place = place)
        }
    }
}

internal fun raceSeconds(ms: Long) = "${ms / 1000},${(ms % 1000).toString().padStart(3, '0')}"
internal fun raceTime(run: Attempt?) = when { run == null -> "–"; !run.complete -> "DNF"; else -> raceSeconds(run.durationMs!!) + " s" }
internal fun raceReportParagraphs(s: Training, athleteIds: Set<Int>? = null): List<String> = buildList {
    val r = requireNotNull(s.race)
    add("# RaceLink Studio - Tävlingsresultat")
    add("SIMULATOR · ${if (s.closedAt == null) "Preliminära resultat" else "Avslutad tävling"}")
    add(s.sessionLabel()); add(s.team); add(r.format.label)
    add(if (r.format == RaceFormat.TOTAL) "Lägsta summan av två fullföljda åk vinner." else "Minsta absoluta tidsskillnaden mellan två fullföljda åk vinner.")
    add("Placering inom varje klass. Lika resultat delar placering. DNF eller saknat åk ger ingen slutplacering.")
    s.raceStandings().filter { athleteIds == null || it.athlete.id in athleteIds }.groupBy { it.className }.forEach { (name, entries) ->
        add("# Klass: $name")
        entries.forEach { entry ->
            add("${entry.place?.toString() ?: "–"}. ${entry.athlete.bib} · ${entry.athlete.name}")
            add("Åk 1: ${raceTime(entry.first)} · Åk 2: ${raceTime(entry.second)}")
            add("${r.format.scoreLabel}: ${entry.scoreMs?.let { raceSeconds(it) + " s" } ?: "–"} · ${entry.status}")
        }
    }
}

internal fun raceResultsCsv(s: Training): String {
    fun q(value: String): String {
        val safe = if (value.trimStart().firstOrNull() in listOf('=', '+', '-', '@')) "'$value" else value
        return "\"${safe.replace("\"", "\"\"")}\""
    }
    return "\uFEFFKälla;Tävling;Datum;Tävlingsform;Klass;Placering;Nummer;Namn;Åk 1 (s);Åk 2 (s);Resultat (s);Status\r\n" + s.raceStandings().joinToString("\r\n") { e ->
        listOf("SIMULATOR", s.sessionName, s.sessionDate, s.race!!.format.label, e.className, e.place?.toString().orEmpty(), e.athlete.bib.toString(), e.athlete.name,
            e.first?.takeIf { it.complete }?.durationMs?.let(::raceSeconds).orEmpty(), e.second?.takeIf { it.complete }?.durationMs?.let(::raceSeconds).orEmpty(),
            e.scoreMs?.let(::raceSeconds).orEmpty(), e.status).joinToString(";", transform = ::q)
    }
}
