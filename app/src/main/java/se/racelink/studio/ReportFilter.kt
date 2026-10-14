package se.racelink.studio

import java.time.LocalDate

fun filterReport(rows: List<ReportRun>, sessionIds: Set<String>? = null, from: String? = null,
    through: String? = null, athleteIds: Set<Int>? = null): List<ReportRun> {
    val start = from?.let(LocalDate::parse)
    val end = through?.let(LocalDate::parse)
    require(start == null || end == null || !start.isAfter(end)) { "Från-datum måste vara före eller samma som till-datum" }
    return rows.filter { row ->
        val date = LocalDate.parse(row.session.sessionDate)
        (sessionIds == null || row.session.sessionId in sessionIds) &&
            (athleteIds == null || row.athlete.id in athleteIds) &&
            (start == null || !date.isBefore(start)) && (end == null || !date.isAfter(end))
    }
}
