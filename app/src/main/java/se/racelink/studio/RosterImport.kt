package se.racelink.studio

import java.util.Locale

internal data class RosterImportRow(val line: Int, val bib: Int?, val name: String, val error: String? = null, val existing: Boolean = false)
internal data class RosterImportPreview(val rows: List<RosterImportRow>, val error: String? = null) {
    val additions get() = rows.filter { it.error == null && !it.existing }
    val canApply get() = error == null && rows.none { it.error != null } && additions.isNotEmpty()
}

// RFC-style quoted fields; accept Swedish spreadsheet separators and UTF-8 BOM.
internal fun previewRosterImport(text: String, roster: List<Athlete>): RosterImportPreview {
    if (text.toByteArray(Charsets.UTF_8).size > 1_048_576) return RosterImportPreview(emptyList(), "Filen är för stor (max 1 MB).")
    val source = text.removePrefix("\uFEFF")
    val header = source.lineSequence().firstOrNull().orEmpty()
    val separator = if (';' in header) ';' else ','
    val records = mutableListOf<Pair<Int, List<String>>>()
    var line = 1; var start = 1; var quoted = false; var closedQuote = false
    val field = StringBuilder(); val fields = mutableListOf<String>()
    fun endField() { fields += field.toString(); field.clear(); closedQuote = false }
    fun endRow() { endField(); if (fields.any { it.isNotBlank() }) records += start to fields.toList(); fields.clear() }
    var i = 0
    while (i < source.length) {
        val c = source[i]
        if (quoted) {
            if (c == '"') {
                if (i + 1 < source.length && source[i + 1] == '"') { field.append('"'); i++ }
                else { quoted = false; closedQuote = true }
            } else { field.append(c); if (c == '\n') line++ }
        } else when {
            c == '"' && field.isEmpty() && !closedQuote -> quoted = true
            c == separator -> endField()
            c == '\n' || c == '\r' -> {
                endRow()
                if (c == '\r' && source.getOrNull(i + 1) == '\n') i++
                line++; start = line
            }
            closedQuote && c.isWhitespace() -> Unit
            closedQuote || c == '"' -> return RosterImportPreview(emptyList(), "Felaktiga citattecken på rad $line.")
            else -> field.append(c)
        }
        i++
    }
    if (quoted) return RosterImportPreview(emptyList(), "Oavslutat citerat fält på rad $start.")
    if (fields.isNotEmpty() || field.isNotEmpty() || closedQuote) endRow()
    if (records.isEmpty()) return RosterImportPreview(emptyList(), "Filen är tom.")
    if (records.size > 5001) return RosterImportPreview(emptyList(), "Högst 5000 åkare kan importeras åt gången.")
    val labels = records.first().second.map { it.trim().lowercase(Locale.ROOT) }
    if (labels.size != 2 || labels.toSet() != setOf("nummer", "namn")) return RosterImportPreview(emptyList(), "Rubrikerna ska vara Nummer;Namn (även komma accepteras).")
    val bibIndex = labels.indexOf("nummer"); val nameIndex = labels.indexOf("namn")
    fun key(name: String) = name.trim().lowercase(Locale.ROOT).replace(Regex("\\s+"), " ")
    val parsed = records.drop(1).map { (row, values) ->
        val bib = values.getOrNull(bibIndex)?.trim()?.toIntOrNull()
        val name = values.getOrNull(nameIndex)?.trim().orEmpty()
        val reason = when {
            values.size != 2 -> "Förväntar två kolumner."
            bib == null || bib <= 0 -> "Numret måste vara ett positivt heltal."
            name.isBlank() -> "Namn saknas."
            name.length > 120 || name.any { it.isISOControl() } -> "Namnet är för långt eller innehåller radbrytningar/kontrolltecken."
            else -> null
        }
        RosterImportRow(row, bib, name, reason)
    }
    val bibCounts = parsed.groupingBy { it.bib }.eachCount()
    val nameCounts = parsed.groupingBy { key(it.name) }.eachCount()
    val rosterByBib = roster.associateBy { it.bib }
    val rosterByName = roster.groupBy { key(it.name) }
    val rows = parsed.map { row ->
        val byBib = rosterByBib[row.bib]
        val sameName = rosterByName[key(row.name)].orEmpty()
        val reason = row.error ?: when {
            bibCounts.getValue(row.bib) > 1 -> "Numret upprepas i filen."
            nameCounts.getValue(key(row.name)) > 1 -> "Namnet upprepas i filen; kontrollera identiteten."
            byBib != null && key(byBib.name) != key(row.name) -> "Numret används redan av ${byBib.name}."
            sameName.any { it.bib != row.bib } -> "Namnet finns med annat nummer; ingen automatisk sammanslagning."
            else -> null
        }
        row.copy(error = reason, existing = reason == null && byBib != null)
    }
    return RosterImportPreview(rows, if (rows.isEmpty()) "Filen innehåller inga åkare." else null)
}

internal fun Training.importRosterCsv(text: String): Training {
    val preview = previewRosterImport(text, roster)
    require(preview.canApply) { preview.error ?: "Rätta felen i filen innan import." }
    val maxId = (roster + athletes + archivedSessions.flatMap { it.roster + it.athletes }).maxOfOrNull { it.id } ?: 0
    require(maxId.toLong() + preview.additions.size <= Int.MAX_VALUE) { "Åkar-ID kan inte skapas." }
    val added = preview.additions.mapIndexed { i, row -> Athlete(maxId + i + 1, row.bib!!, row.name) }
    return copy(roster = roster + added, audit = audit + "${java.time.Instant.now()} | Åkarlista importerad | ${added.size} nya åkare; aktuella deltagare och historik behålls")
}
