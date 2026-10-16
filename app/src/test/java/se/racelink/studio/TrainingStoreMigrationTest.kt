package se.racelink.studio

import android.content.Context
import org.json.JSONArray
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Runs [TrainingStore] against real Android SQLite under Robolectric so the schema upgrade and the
 * normalized write/read path are actually executed rather than reasoned about. The on-device twin
 * is `app/src/androidTest/.../PersistenceRegressionRunner.kt`.
 *
 * Pinned to API 34: the SQLite schema/migration behaviour is not API-specific, and this keeps the
 * test independent of whether a given Robolectric release ships an Android 15 image.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TrainingStoreMigrationTest {
    private lateinit var context: Context
    private val databases = mutableListOf<String>()

    @Before fun setUp() { context = RuntimeEnvironment.getApplication() }
    @After fun tearDown() { databases.forEach { context.deleteDatabase(it) } }

    private fun disposable(name: String): String { databases += name; context.deleteDatabase(name); return name }

    /** Recreates what version 2 persisted: one whole-state blob plus a recovery blob. */
    private fun writeLegacyV2Database(database: String, payload: String) {
        context.openOrCreateDatabase(database, 0, null).use { db ->
            db.execSQL("CREATE TABLE snapshot (id INTEGER PRIMARY KEY, payload TEXT NOT NULL)")
            db.execSQL("CREATE TABLE recovery (id INTEGER PRIMARY KEY, payload TEXT NOT NULL)")
            db.execSQL("INSERT INTO snapshot VALUES (1,?)", arrayOf(payload))
            db.execSQL("INSERT INTO recovery VALUES (1,?)", arrayOf(payload))
            db.version = 2
        }
    }

    private fun TrainingStore.tables(): List<String> =
        readableDatabase.rawQuery("SELECT name FROM sqlite_master WHERE type='table'", null).use { c ->
            buildList { while (c.moveToNext()) add(c.getString(0)) }
        }

    private fun TrainingStore.count(sql: String, args: Array<String>? = null): Int =
        readableDatabase.rawQuery(sql, args).use { it.moveToFirst(); it.getInt(0) }

    @Test fun v2BlobMigratesToNormalizedStoreAndUpgradesLegacyAudit() {
        val database = disposable("migration.db")
        val legacyAudit = "2026-10-16T10:00:00Z | seed-0 | Tidigare rättning"
        val expected = demoTraining()
            .copy(audit = listOf(AuditEntry("2026-10-16T10:00:00Z", AuditKind.LEGACY, "Tidigare rättning", runId = "seed-0")))
            .startNewSession("Migrerat")
            .configureRace(RaceConfig(RaceFormat.TOTAL, listOf("Öppen"), (1..4).associateWith { "Öppen" }))
        // Encode as v2 would have: the archived session's audit was a plain string array.
        val json = TrainingJson.encode(expected)
        json.getJSONArray("archivedSessions").getJSONObject(0).put("audit", JSONArray(listOf(legacyAudit)))
        writeLegacyV2Database(database, json.toString())

        TrainingStore(context, database).use { store ->
            assertEquals("migration changed session data", expected, store.read())
            assertEquals("migration lost the recovery snapshot", expected, store.recovery())
            assertEquals("database should report schema 3 after upgrade", 3, store.readableDatabase.version)
            val tables = store.tables()
            assertFalse("legacy snapshot table should be dropped after a successful migration", "snapshot" in tables)
            assertTrue(tables.containsAll(listOf("app_state", "sessions", "journal", "recovery")))
            // The legacy prose entry was upgraded into a typed, queryable journal row.
            assertEquals(1, store.count("SELECT COUNT(*) FROM journal WHERE kind=?", arrayOf(AuditKind.LEGACY.name)))
            assertEquals(2, store.count("SELECT COUNT(*) FROM sessions"))
            assertEquals(1, store.count("SELECT COUNT(*) FROM app_state"))
        }
        // Reopening reads from the normalized tables, not from the old blob.
        TrainingStore(context, database).use { assertEquals(expected, it.read()) }
    }

    @Test fun freshStoreRoundTripsAndJournalIsAppendOnly() {
        val database = disposable("fresh.db")
        val initial = demoTraining().startNewSession("Träning")
        val first = initial.selectNextAthlete(1).simulateNext(SimulatorAdapter())
        val changed = first.correct(first.attempts.single().id, 1, false, setOf(1), "Sparad anteckning")
        TrainingStore(context, database).use { store ->
            assertNull("a fresh store has no saved state", store.read())
            store.save(initial)
            assertEquals(initial, store.read())
            store.save(first)
            assertEquals(first, store.read())
            store.save(changed)
            store.save(changed) // a repeated save must not duplicate journal rows
            assertEquals(changed, store.read())
            val expectedEntries = changed.audit.size + changed.archivedSessions.sumOf { it.audit.size }
            assertTrue("expected at least one audit entry to exercise the journal", expectedEntries > 0)
            assertEquals(expectedEntries, store.count("SELECT COUNT(*) FROM journal"))
        }
        TrainingStore(context, database).use { assertEquals(changed, it.read()) }
    }

    @Test fun archivePositionsStayContiguousAndReadOrderIsStable() {
        val database = disposable("order.db")
        var state = demoTraining()
        TrainingStore(context, database).use { store ->
            store.save(state)
            repeat(4) { index ->
                state = state.startNewSession("Pass $index")
                store.save(state)
                val positions = store.readableDatabase.rawQuery("SELECT position FROM sessions ORDER BY position", null).use { c ->
                    buildList { while (c.moveToNext()) add(c.getInt(0)) }
                }
                assertEquals("positions must stay contiguous after save $index", (0..state.archivedSessions.size).toList(), positions)
                assertEquals("read order changed after save $index", state, store.read())
            }
        }
        TrainingStore(context, database).use { assertEquals(state, it.read()) }
    }

    @Test fun restoreReplacesStateAndKeepsRecoveryPoint() {
        val database = disposable("restore.db")
        val initial = demoTraining().startNewSession("Träning")
        val changed = initial.selectNextAthlete(1).simulateNext(SimulatorAdapter())
        TrainingStore(context, database).use { store ->
            store.save(changed)
            store.restore(changed, initial)
            assertEquals(initial, store.read())
            assertEquals(changed, store.recovery())
        }
        TrainingStore(context, database).use {
            assertEquals(initial, it.read())
            assertEquals(changed, it.recovery())
        }
    }
}
