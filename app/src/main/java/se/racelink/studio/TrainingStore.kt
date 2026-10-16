package se.racelink.studio

import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONObject

/**
 * Persists the running session index, one row per training/competition session, and an append-only
 * audit journal. An edit rewrites only the current session's row (unchanged archives are left alone)
 * and appends new audit entries, instead of rewriting the whole state blob on every change.
 */
class TrainingStore(context: Context, databaseName: String = "racelink-demo.db") : SQLiteOpenHelper(context, databaseName, null, 3) {
    private val backupPreferences = context.getSharedPreferences("external-backup", Context.MODE_PRIVATE)
    internal fun backupReceipt(): BackupReceipt? {
        val time = backupPreferences.getString("time", null) ?: return null
        val fingerprint = backupPreferences.getString("fingerprint", null) ?: return null
        return BackupReceipt(time, fingerprint)
    }
    internal fun recordBackup(receipt: BackupReceipt) {
        check(backupPreferences.edit().putString("time", receipt.time).putString("fingerprint", receipt.fingerprint).commit()) { "Kunde inte spara backupstatus" }
    }

    override fun onCreate(db: SQLiteDatabase) = createNormalized(db)

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) db.execSQL("CREATE TABLE IF NOT EXISTS recovery (id INTEGER PRIMARY KEY CHECK(id=1), payload TEXT NOT NULL)")
        if (oldVersion < 3) {
            createNormalized(db)
            // Migrate the legacy whole-state blob into the normalized tables; only drop it on success.
            val legacy = legacyPayload(db)
            if (legacy != null) {
                // Let SQLiteOpenHelper roll back the upgrade if decoding or writing fails.
                writeState(db, TrainingJson.decode(JSONObject(legacy)))
                db.execSQL("DROP TABLE IF EXISTS snapshot")
            }
        }
    }

    private fun createNormalized(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS app_state (id INTEGER PRIMARY KEY CHECK(id=1), current_session_id TEXT NOT NULL, archived_roster_ids TEXT NOT NULL)")
        db.execSQL("CREATE TABLE IF NOT EXISTS sessions (session_id TEXT PRIMARY KEY, payload TEXT NOT NULL, position INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE IF NOT EXISTS journal (entry_id INTEGER PRIMARY KEY AUTOINCREMENT, session_id TEXT NOT NULL, at TEXT NOT NULL, kind TEXT NOT NULL, detail TEXT NOT NULL, run_id TEXT NOT NULL DEFAULT '', before_text TEXT, after_text TEXT)")
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS journal_unique ON journal(session_id, at, kind, detail, run_id)")
        db.execSQL("CREATE TABLE IF NOT EXISTS recovery (id INTEGER PRIMARY KEY CHECK(id=1), payload TEXT NOT NULL)")
    }

    fun recovery(): Training? = readableDatabase.rawQuery("SELECT payload FROM recovery WHERE id=1", null).use {
        if (it.moveToFirst()) TrainingJson.decode(JSONObject(it.getString(0))) else null
    }

    fun restore(previous: Training?, next: Training) {
        validateBackupState(next)
        val db = writableDatabase
        db.beginTransaction()
        try {
            if (previous != null) db.execSQL("INSERT OR REPLACE INTO recovery(id,payload) VALUES(1,?)", arrayOf(TrainingJson.encode(previous).toString()))
            db.execSQL("DELETE FROM sessions")
            db.execSQL("DELETE FROM journal")
            writeState(db, next)
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    fun read(): Training? = readNormalized() ?: readLegacySnapshot()

    fun save(state: Training) {
        val db = writableDatabase
        db.beginTransaction()
        try { writeState(db, state); db.setTransactionSuccessful() } finally { db.endTransaction() }
    }

    private fun writeState(db: SQLiteDatabase, state: Training) {
        val sessions = listOf(state) + state.archivedSessions
        val stored = HashMap<String, String>()
        val positions = HashMap<String, Int>()
        db.rawQuery("SELECT session_id, payload, position FROM sessions", null).use { c ->
            while (c.moveToNext()) {
                stored[c.getString(0)] = c.getString(1)
                positions[c.getString(0)] = c.getInt(2)
            }
        }
        db.execSQL("INSERT OR REPLACE INTO app_state(id, current_session_id, archived_roster_ids) VALUES(1,?,?)",
            arrayOf(state.sessionId, state.archivedRosterIds.joinToString(",")))
        sessions.forEachIndexed { index, session ->
            // Per-session payload (no nested archive, no inline audit) so unchanged rows are skipped.
            val payload = TrainingJson.sessionPayload(session).toString()
            if (stored[session.sessionId] != payload) {
                db.execSQL("INSERT OR REPLACE INTO sessions(session_id, payload, position) VALUES(?,?,?)",
                    arrayOf<Any>(session.sessionId, payload, index))
            } else if (positions[session.sessionId] != index) {
                // New sessions shift unchanged archives too; leave their payloads untouched.
                db.execSQL("UPDATE sessions SET position=? WHERE session_id=?", arrayOf<Any>(index, session.sessionId))
            }
        }
        val keep = sessions.map { it.sessionId }.toSet()
        (stored.keys - keep).forEach { gone ->
            db.execSQL("DELETE FROM sessions WHERE session_id=?", arrayOf(gone))
            db.execSQL("DELETE FROM journal WHERE session_id=?", arrayOf(gone))
        }
        appendJournal(db, sessions)
    }

    private fun appendJournal(db: SQLiteDatabase, sessions: List<Training>) {
        val known = HashSet<String>()
        db.rawQuery("SELECT session_id, at, kind, detail, run_id FROM journal", null).use { c ->
            while (c.moveToNext()) known += journalKey(c.getString(0), c.getString(1), c.getString(2), c.getString(3), c.getString(4))
        }
        sessions.forEach { session ->
            session.audit.forEach { entry ->
                val key = journalKey(session.sessionId, entry.at, entry.kind.name, entry.detail, entry.runId.orEmpty())
                if (known.add(key)) db.execSQL(
                    "INSERT OR IGNORE INTO journal(session_id, at, kind, detail, run_id, before_text, after_text) VALUES(?,?,?,?,?,?,?)",
                    arrayOf(session.sessionId, entry.at, entry.kind.name, entry.detail, entry.runId.orEmpty(), entry.before, entry.after))
            }
        }
    }

    private fun journalKey(sessionId: String, at: String, kind: String, detail: String, runId: String) =
        "$sessionId\u0000$at\u0000$kind\u0000$detail\u0000$runId"

    private fun readNormalized(): Training? {
        val db = readableDatabase
        val app = db.rawQuery("SELECT current_session_id, archived_roster_ids FROM app_state WHERE id=1", null).use { c ->
            if (c.moveToFirst()) c.getString(0) to c.getString(1) else null
        } ?: return null
        val payloads = HashMap<String, String>()
        val positions = HashMap<String, Int>()
        db.rawQuery("SELECT session_id, payload, position FROM sessions", null).use { c ->
            while (c.moveToNext()) { payloads[c.getString(0)] = c.getString(1); positions[c.getString(0)] = c.getInt(2) }
        }
        val currentPayload = requireNotNull(payloads[app.first]) { "Det aktuella passet saknas i databasen." }
        val journal = readJournal(db)
        fun build(sessionId: String, payload: String) = TrainingJson.decode(JSONObject(payload)).copy(audit = journal[sessionId].orEmpty())
        val current = build(app.first, currentPayload).copy(archivedRosterIds = app.second.intSet())
        val archives = payloads.filterKeys { it != app.first }.entries.sortedBy { positions[it.key] ?: 0 }.map { build(it.key, it.value) }
        return current.copy(archivedSessions = archives)
    }

    private fun readJournal(db: SQLiteDatabase): Map<String, List<AuditEntry>> {
        val out = HashMap<String, MutableList<AuditEntry>>()
        db.rawQuery("SELECT session_id, at, kind, detail, run_id, before_text, after_text FROM journal ORDER BY entry_id", null).use { c ->
            while (c.moveToNext()) {
                val kind = runCatching { AuditKind.valueOf(c.getString(2)) }.getOrDefault(AuditKind.LEGACY)
                out.getOrPut(c.getString(0)) { mutableListOf() } += AuditEntry(
                    c.getString(1), kind, c.getString(3), c.getString(4).takeIf { it.isNotEmpty() }, c.getString(5), c.getString(6))
            }
        }
        return out
    }

    // Safety net: read the pre-v3 single-blob snapshot if the normalized tables are empty.
    private fun readLegacySnapshot(): Training? = legacyPayload(readableDatabase)?.let { TrainingJson.decode(JSONObject(it)) }

    private fun legacyPayload(db: SQLiteDatabase): String? {
        val exists = db.rawQuery("SELECT 1 FROM sqlite_master WHERE type='table' AND name='snapshot'", null).use { it.moveToFirst() }
        if (!exists) return null
        return db.rawQuery("SELECT payload FROM snapshot WHERE id=1", null).use { c: Cursor ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }

    private fun String.intSet() = if (isBlank()) emptySet() else split(",").map { it.toInt() }.toSet()
}
