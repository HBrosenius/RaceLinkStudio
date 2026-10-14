package se.racelink.studio

data class ReportRun(val session: Training, val athlete: Athlete, val attempt: Attempt, val number: Int)

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
    fun quote(value: String): String {
        val safe = if (value.trimStart().firstOrNull() in listOf('=', '+', '-', '@')) "'$value" else value
        return "\"${safe.replace("\"", "\"\"")}\""
    }
    return "\uFEFFKälla;Pass-ID;Datum;Passnamn;Plats;Bana;Träningstyp;Lag;Nummer;Namn;Åk;Resultat-ID;Tid (s);Status\r\n" + runs.joinToString("\r\n") { row ->
        val run = row.attempt
        listOf("SIMULATOR", row.session.sessionId, row.session.sessionDate, row.session.sessionName,
            row.session.location, row.session.course, row.session.trainingType, row.session.team,
            row.athlete.bib.toString(), row.athlete.name, row.number.toString(), run.id,
            if (run.complete) seconds(run.durationMs!!) else "", if (run.complete) "Fullföljt" else "Avbrutet")
            .joinToString(";") { quote(it) }
    }
}
