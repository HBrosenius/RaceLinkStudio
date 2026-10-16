package se.racelink.studio

import org.json.JSONObject

/**
 * What an audit entry records. Typed so history, reports and the store never have to parse prose
 * out of a display string (previously the run history re-parsed JSON embedded in an audit string).
 */
enum class AuditKind {
    CHANGE_RECORDED, CHANGE_EDITED, CHANGE_REMOVED, CHANGE_UNDO,
    SESSION_CLOSED, SESSION_EDITED, TAGS_UPDATED,
    RUN_EDITED, TIMING_DECIDED,
    RACE_CONFIGURED, RACE_ROUND_SELECTED, RACE_RUN_ASSIGNED, RACE_RUN_EXCLUDED,
    ROSTER_IMPORTED, UNDO, LEGACY
}

/**
 * One immutable evidence record. [detail] is a human-readable summary; [runId]/[before]/[after]
 * carry the structured payload used by the run-history view.
 */
data class AuditEntry(
    val at: String,
    val kind: AuditKind,
    val detail: String,
    val runId: String? = null,
    val before: String? = null,
    val after: String? = null
) {
    companion object {
        fun now(kind: AuditKind, detail: String, runId: String? = null, before: String? = null, after: String? = null) =
            AuditEntry(java.time.Instant.now().toString(), kind, detail, runId, before, after)
    }
}

internal const val LEGACY_RUN_EDIT_PREFIX = "run-edit-v1 | "

/**
 * Reads an audit entry produced before the typed format: either a `run-edit-v1 | {...}` run-edit
 * record or the `time | runId | text` legacy shape. Anything else is kept verbatim as [AuditKind.LEGACY].
 */
internal fun parseLegacyAudit(text: String): AuditEntry {
    if (text.startsWith(LEGACY_RUN_EDIT_PREFIX)) {
        runCatching {
            val record = JSONObject(text.removePrefix(LEGACY_RUN_EDIT_PREFIX))
            return AuditEntry(record.getString("time"), AuditKind.RUN_EDITED, "", record.getString("runId"),
                record.getString("before"), record.getString("after"))
        }
    }
    val parts = text.split(" | ", limit = 3)
    return if (parts.size == 3) AuditEntry(parts[0], AuditKind.LEGACY, parts[2], runId = parts[1])
    else AuditEntry("", AuditKind.LEGACY, text)
}
