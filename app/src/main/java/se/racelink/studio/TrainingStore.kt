package se.racelink.studio

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONArray
import org.json.JSONObject

// A transactional demo snapshot. Raw hardware journaling and Room migration are later work.
class TrainingStore(context: Context) : SQLiteOpenHelper(context, "racelink-demo.db", null, 2) {
    private val backupPreferences = context.getSharedPreferences("external-backup", Context.MODE_PRIVATE)
    internal fun backupReceipt(): BackupReceipt? {
        val time = backupPreferences.getString("time", null) ?: return null
        val fingerprint = backupPreferences.getString("fingerprint", null) ?: return null
        return BackupReceipt(time, fingerprint)
    }
    internal fun recordBackup(receipt: BackupReceipt) {
        check(backupPreferences.edit().putString("time", receipt.time).putString("fingerprint", receipt.fingerprint).commit()) { "Kunde inte spara backupstatus" }
    }
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE snapshot (id INTEGER PRIMARY KEY CHECK(id=1), payload TEXT NOT NULL)")
        db.execSQL("CREATE TABLE recovery (id INTEGER PRIMARY KEY CHECK(id=1), payload TEXT NOT NULL)")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) db.execSQL("CREATE TABLE recovery (id INTEGER PRIMARY KEY CHECK(id=1), payload TEXT NOT NULL)")
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
            db.execSQL("INSERT OR REPLACE INTO snapshot(id,payload) VALUES(1,?)", arrayOf(TrainingJson.encode(next).toString()))
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }
    fun read(): Training? = readableDatabase.rawQuery("SELECT payload FROM snapshot WHERE id=1", null).use {
        if (it.moveToFirst()) TrainingJson.decode(JSONObject(it.getString(0))) else null
    }
    fun save(state: Training) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.execSQL("INSERT OR REPLACE INTO snapshot(id,payload) VALUES(1,?)", arrayOf(TrainingJson.encode(state).toString()))
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }
}
