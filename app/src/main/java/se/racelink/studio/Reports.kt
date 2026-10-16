package se.racelink.studio

data class ReportRun(val session: Training, val athlete: Athlete, val attempt: Attempt, val number: Int)
data class SkierSummary(val name: String, val total: Int, val completed: Int, val bestMs: Long?, val latestMs: Long?, val progressMs: Long?)
fun summarizeRuns(rows: List<ReportRun>): SkierSummary {
    require(rows.isNotEmpty())
    require(rows.map { it.athlete.id }.distinct().size == 1)
    val valid = rows.filter { it.attempt.complete && it.attempt.durationMs != null }
    return SkierSummary(rows.last().athlete.name, rows.size, valid.size, valid.minOfOrNull { it.attempt.durationMs!! },
        rows.last().attempt.takeIf { it.complete }?.durationMs,
        if (rows.map { it.session.sessionId }.distinct().size == 1 && valid.size > 1)
            valid.last().attempt.durationMs!! - valid.first().attempt.durationMs!! else null)
}
fun SkierSummary.description() = "$total åk · $completed fullföljda · ${total - completed} DNF\n" +
    "Snabbast ${bestMs?.let { seconds(it) + " s" } ?: "-"} · Senaste åket ${latestMs?.let { seconds(it) + " s" } ?: "DNF"}" +
    (progressMs?.let { "\n${difference(it)} jämfört med första fullföljda åket" } ?: "")

internal const val PDF_GRAPH_PREFIX = "\u001eGRAPH:"
data class PdfGraphPoint(val number: Int, val durationMs: Long?)
fun pdfGraphPoints(paragraph: String): List<PdfGraphPoint> = paragraph.removePrefix(PDF_GRAPH_PREFIX).split(';').map {
    val parts = it.split(':'); PdfGraphPoint(parts[0].toInt(), parts[1].takeUnless { value -> value == "x" }?.toLong())
}
fun reportParagraphs(rows: List<ReportRun>, includeTags: Boolean = false, includeNotes: Boolean = false, includeGraphs: Boolean = false): List<String> = buildList {
    add("# RaceLink Studio - Träningsrapport")
    add("SIMULATOR - Påhittade namn och tider")
    add("${rows.size} åk i ${rows.map { it.session.sessionId }.distinct().size} pass")
    add("Taggar: ${if (includeTags) "inkluderade" else "utelämnade"}. Anteckningar: ${if (includeNotes) "inkluderade" else "utelämnade"}.")
    rows.groupBy { it.session.sessionId }.values.forEach { sessionRows ->
        val session = sessionRows.first().session
        add("# ${session.sessionLabel()}")
        add("Pass-ID: ${session.sessionId}")
        add(session.team)
        if (session.sessionDetails().isNotBlank()) add(session.sessionDetails())
        if (session.race != null) {
            addAll(raceReportParagraphs(session, sessionRows.map { it.athlete.id }.toSet()))
            sessionRows.forEach { row ->
                if (includeTags) TagCategory.entries.forEach { category -> session.tagDescription(row.attempt, category).takeIf { it.isNotBlank() }?.let { add("${row.athlete.name} · ${category.label}: $it") } }
                if (includeNotes && row.attempt.note.isNotBlank()) add("${row.athlete.name} · Anteckning: ${row.attempt.note}")
            }
            return@forEach
        }
        sessionRows.groupBy { it.athlete.id }.values.forEach { skierRows ->
            val summary = summarizeRuns(skierRows)
            add("## ${summary.name} - Nummer ${skierRows.first().athlete.bib}")
            add(summary.description())
            if (includeGraphs) skierRows.chunked(16).forEach { chunk ->
                add(PDF_GRAPH_PREFIX + chunk.joinToString(";") { "${it.number}:${if (it.attempt.complete) it.attempt.durationMs.toString() else "x"}" })
            }
            skierRows.forEach { row ->
                add("Åk ${row.number}: ${if (row.attempt.complete) seconds(row.attempt.durationMs!!) + " s" else "DNF"}")
                if (includeTags) {
                    TagCategory.entries.forEach { category ->
                        val tags = session.tagDescription(row.attempt, category)
                        if (tags.isNotBlank()) add("${category.label}: $tags")
                    }
                }
                if (includeNotes && row.attempt.note.isNotBlank()) add("Anteckning: ${row.attempt.note}")
            }
        }
    }
}

fun reportRuns(sessions: List<Training>, sessionId: String? = null, athleteId: Int? = null): List<ReportRun> =
    sessions.filter { sessionId == null || it.sessionId == sessionId }.flatMap { session ->
        val numbers = session.athletes.flatMap { athlete ->
            session.runs(athlete.id).mapIndexed { index, run -> run.id to index + 1 }
        }.toMap()
        session.attempts.sortedBy { it.sequence }.filter { athleteId == null || it.athleteId == athleteId }.map { run ->
            ReportRun(session, session.athletes.first { it.id == run.athleteId }, run, numbers.getValue(run.id))
        }
    }

fun reportCsv(runs: List<ReportRun>): String {
    val racing = runs.any { it.session.race != null }
    val standings = runs.map { it.session }.distinctBy { it.sessionId }.filter { it.race != null }
        .flatMap { s -> s.raceStandings().map { (s.sessionId to it.athlete.id) to it } }.toMap()
    fun quote(value: String): String {
        val safe = if (value.trimStart().firstOrNull() in listOf('=', '+', '-', '@')) "'$value" else value
        return "\"${safe.replace("\"", "\"\"")}\""
    }
    return "\uFEFFKälla;Pass-ID;Datum;Passnamn;Plats;Bana;Träningstyp;Lag;Nummer;Namn;Åk;Resultat-ID;Tid (s);Status" +
        (if (racing) ";Tävlingsform;Klass;Tävlingsåk;Placering;Tävlingsresultat (s)" else "") + "\r\n" + runs.joinToString("\r\n") { row ->
        val run = row.attempt
        val fields = listOf("SIMULATOR", row.session.sessionId, row.session.sessionDate, row.session.sessionName,
            row.session.location, row.session.course, row.session.trainingType, row.session.team,
            row.athlete.bib.toString(), row.athlete.name, row.number.toString(), run.id,
            if (run.complete) (if (row.session.race != null) raceSeconds(run.durationMs!!) else seconds(run.durationMs!!)) else "", if (run.complete) "Fullföljt" else "DNF")
        val entry = standings[row.session.sessionId to row.athlete.id]
        (fields + if (racing) listOf(row.session.race?.format?.label.orEmpty(), entry?.className.orEmpty(), row.session.race?.roundByAttempt?.get(run.id)?.toString().orEmpty(),
            entry?.place?.toString().orEmpty(), entry?.scoreMs?.let(::raceSeconds).orEmpty()) else emptyList()).joinToString(";", transform = ::quote)
    }
}
