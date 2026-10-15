package se.racelink.studio

internal data class SkierDraft(val id: Int, val name: String, val bib: String)
internal data class SkierErrors(val name: String? = null, val bib: String? = null) {
    val valid get() = name == null && bib == null
}
internal fun skierErrors(skier: SkierDraft, roster: List<SkierDraft>): SkierErrors {
    val bib = skier.bib.toIntOrNull()
    return SkierErrors(if (skier.name.isBlank()) "Ange åkarens namn" else null,
        when { bib == null || bib <= 0 -> "Numret måste vara ett positivt heltal"
            roster.any { it.id != skier.id && it.bib.toIntOrNull() == bib } -> "Nummer $bib används av en annan åkare"
            else -> null })
}
