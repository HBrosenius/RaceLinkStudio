package se.racelink.studio

/** Publish only after durable storage accepts the entire new snapshot. */
internal suspend fun commitTraining(current: Training, transform: (Training) -> Training,
    persist: suspend (Training) -> Unit): Result<Training> = runCatching {
    transform(current).also { persist(it) }
}

fun Training.nextPendingRun(): Attempt? = pending.firstOrNull()?.let { id -> attempts.firstOrNull { it.id == id } }
fun Training.acknowledgePending(id: String): Training {
    require(pending.firstOrNull() == id) { "Resultatkön har ändrats" }
    return copy(pending = pending.drop(1))
}

internal data class UndoEdit(val label: String, val apply: (Training) -> Training)
internal fun quickTagUndo(before: Training, runId: String, tagId: Int): UndoEdit {
    val hadTag = tagId in before.attempts.single { it.id == runId }.tags
    return UndoEdit("Tagg ändrad") { current ->
        require(current.sessionId == before.sessionId) { "Passet har ändrats" }
        val run = current.attempts.single { it.id == runId }
        require((tagId in run.tags) != hadTag) { "Taggen har redan ändrats igen" }
        current.toggleTag(runId, tagId)
    }
}
internal fun dnfUndo(before: Training, runId: String): UndoEdit {
    val old = before.attempts.single { it.id == runId }
    return UndoEdit("Åkstatus ändrad") { current ->
        require(current.sessionId == before.sessionId) { "Passet har ändrats" }
        val run = current.attempts.single { it.id == runId }
        require(run.complete != old.complete) { "Åkstatus har redan ändrats igen" }
        current.setRunDnf(runId, !old.complete)
    }
}
internal fun removedChangeUndo(before: Training, index: Int): UndoEdit {
    val marker = before.markers[index]
    return UndoEdit("Ändring borttagen") { current ->
        require(current.sessionId == before.sessionId && current.closedAt == null) { "Passet har ändrats eller avslutats" }
        require(current.markers.count { it == marker } < before.markers.count { it == marker }) { "Ändringen har redan återställts" }
        val markers = current.markers.toMutableList().apply { add(index.coerceAtMost(size), marker) }
        current.copy(markers = markers, audit = current.audit + "${java.time.Instant.now()} · Ångrad borttagning: ${current.markerDescription(marker)}")
    }
}
