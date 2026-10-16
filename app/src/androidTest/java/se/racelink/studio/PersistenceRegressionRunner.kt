package se.racelink.studio

import android.app.Activity
import android.app.Instrumentation
import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.os.Bundle
import androidx.lifecycle.ViewModelStore
import org.json.JSONArray

/** Exercises real Android SQLite, including upgrades; uses dedicated disposable databases. */
class PersistenceRegressionRunner : Instrumentation() {
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); start() }

    override fun onStart() {
        val results = Bundle()
        val tests = listOf(
            "legacy migration" to ::legacyMigration,
            "archive ordering across saves and reopen" to ::archiveOrdering,
            "incremental journal and recovery" to ::journalAndRecovery,
            "rapid queued commands" to ::queuedCommands,
            "corrupt legacy migration preserves original data" to ::corruptLegacy,
            "missing current row is not an empty database" to ::missingCurrent
        )
        var failures = 0
        val output = StringBuilder()
        tests.forEachIndexed { index, (name, test) ->
            val database = "persistence-regression-$index.db"
            targetContext.deleteDatabase(database)
            try { test(database); output.append("PASS: $name\n") }
            catch (e: Throwable) { failures++; output.append("FAIL: $name\n${e.stackTraceToString()}\n") }
            finally { targetContext.deleteDatabase(database) }
        }
        results.putString("stream", output.toString())
        results.putInt("failures", failures)
        finish(if (failures == 0) Activity.RESULT_OK else Activity.RESULT_CANCELED, results)
    }

    private fun legacyMigration(database: String) {
        val raw = "2026-10-16T10:00:00Z | seed-0 | Tidigare rättning"
        val expected = demoTraining().copy(audit = listOf(parseLegacyAudit(raw))).startNewSession("Migrerat")
            .configureRace(RaceConfig(RaceFormat.TOTAL, listOf("Öppen"), (1..4).associateWith { "Öppen" }))
            .selectNextAthlete(1).simulateNext(SimulatorAdapter()).simulateNext(SimulatorAdapter())
        val json = TrainingJson.encode(expected)
        json.getJSONArray("archivedSessions").getJSONObject(0).put("audit", JSONArray(listOf(raw)))
        targetContext.openOrCreateDatabase(database, 0, null).use { db ->
            db.execSQL("CREATE TABLE snapshot (id INTEGER PRIMARY KEY, payload TEXT NOT NULL)")
            db.execSQL("CREATE TABLE recovery (id INTEGER PRIMARY KEY, payload TEXT NOT NULL)")
            db.execSQL("INSERT INTO snapshot VALUES (1,?)", arrayOf(json.toString()))
            db.execSQL("INSERT INTO recovery VALUES (1,?)", arrayOf(json.toString()))
            db.version = 2
        }
        TrainingStore(targetContext, database).use { store ->
            check(store.read() == expected) { "Migration changed session data" }
            check(store.recovery() == expected) { "Migration lost recovery snapshot" }
            check(store.readableDatabase.version == 3)
        }
        TrainingStore(targetContext, database).use { check(it.read() == expected) }
    }

    private fun archiveOrdering(database: String) {
        var state = demoTraining()
        TrainingStore(targetContext, database).use { store ->
            store.save(state)
            repeat(4) { index ->
                state = state.startNewSession("Pass $index")
                store.save(state)
                val positions = store.readableDatabase.rawQuery("SELECT position FROM sessions ORDER BY position", null).use { c ->
                    buildList { while (c.moveToNext()) add(c.getInt(0)) }
                }
                check(positions == (0..state.archivedSessions.size).toList()) { "Stale archive positions: $positions" }
                check(store.read() == state) { "Session order changed on read" }
            }
        }
        TrainingStore(targetContext, database).use { check(it.read() == state) }
    }

    private fun journalAndRecovery(database: String) {
        val initial = demoTraining().startNewSession("Träning")
        val first = initial.selectNextAthlete(1).simulateNext(SimulatorAdapter())
        val changed = first.correct(first.attempts.single().id, 1, false, setOf(1), "Sparad anteckning")
        TrainingStore(targetContext, database).use { store ->
            store.save(initial); store.save(first); store.save(changed); store.save(changed)
            check(store.read() == changed)
            val count = store.readableDatabase.rawQuery("SELECT COUNT(*) FROM journal", null).use { it.moveToFirst(); it.getInt(0) }
            check(count == changed.audit.size + changed.archivedSessions.sumOf { it.audit.size }) { "Journal duplicated entries" }
            store.restore(changed, initial)
            check(store.read() == initial)
            check(store.recovery() == changed)
        }
        TrainingStore(targetContext, database).use { store ->
            check(store.read() == initial); check(store.recovery() == changed)
        }
    }

    private fun queuedCommands(database: String) {
        val initial = demoTraining().startNewSession("Kötest")
        TrainingStore(targetContext, database).use { it.save(initial) }
        // Route this ViewModel to an isolated database and preferences, never the live app data.
        val app = object : Application() {
            init {
                attachBaseContext(object : ContextWrapper(targetContext) {
                    override fun getDatabasePath(name: String) = targetContext.getDatabasePath(database)
                    override fun getSharedPreferences(name: String, mode: Int) =
                        targetContext.getSharedPreferences("regression-$name", mode)
                })
            }
        }
        lateinit var vm: TrainingViewModel
        val owner = ViewModelStore()
        runOnMainSync { vm = TrainingViewModel(app); owner.put("test", vm) }
        fun await(condition: () -> Boolean) {
            val deadline = android.os.SystemClock.uptimeMillis() + 10_000
            do {
                var done = false
                runOnMainSync { done = condition() }
                if (done) return
                Thread.sleep(10)
            } while (android.os.SystemClock.uptimeMillis() < deadline)
            error("ViewModel commands did not finish")
        }
        try {
            await { vm.state != null }
            runOnMainSync {
                vm.update { error("Expected rejected command") }
                repeat(30) { vm.update { it.selectNextAthlete(1).simulateNext(SimulatorAdapter()) } }
            }
            await { !vm.busy }
            var error: String? = null
            var count = 0
            runOnMainSync { error = vm.error; count = vm.state!!.attempts.size }
            check(error == null) { "Queue did not recover from a rejected command: $error" }
            check(count == 30) { "Rapid commands were dropped: $count of 30 retained" }
            TrainingStore(targetContext, database).use { check(it.read()!!.attempts.size == 30) }
        } finally { runOnMainSync { owner.clear() } }
    }

    private fun corruptLegacy(database: String) {
        targetContext.openOrCreateDatabase(database, 0, null).use { db ->
            db.execSQL("CREATE TABLE snapshot (id INTEGER PRIMARY KEY, payload TEXT NOT NULL)")
            db.execSQL("INSERT INTO snapshot VALUES (1,'broken-json')")
            db.version = 2
        }
        TrainingStore(targetContext, database).use { store ->
            check(runCatching { store.read() }.isFailure) { "Corrupt data was treated as an empty install" }
        }
        targetContext.openOrCreateDatabase(database, 0, null).use { db ->
            check(db.version == 2) { "Failed migration did not roll back" }
            db.rawQuery("SELECT payload FROM snapshot WHERE id=1", null).use { c ->
                check(c.moveToFirst() && c.getString(0) == "broken-json")
            }
        }
    }

    private fun missingCurrent(database: String) {
        TrainingStore(targetContext, database).use { store ->
            store.save(demoTraining())
            store.writableDatabase.execSQL("DELETE FROM sessions")
            check(runCatching { store.read() }.isFailure) { "Missing current session was treated as an empty install" }
        }
    }
}
