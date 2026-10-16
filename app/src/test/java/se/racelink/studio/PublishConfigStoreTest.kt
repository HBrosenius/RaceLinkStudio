package se.racelink.studio

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Exercises the tablet's own copy of the publish settings against real SharedPreferences, because the
 * link-index password is per session and a mistake here would either lock the coach out or leak one
 * evening's links into the next.
 *
 * Assertions stay two-argument on purpose: JUnit 4 reads a leading string as the failure message, so a
 * message last would silently compare the wrong two values.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PublishConfigStoreTest {
    private lateinit var store: PublishConfigStore

    @Before fun setUp() {
        val context: Context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("publish", Context.MODE_PRIVATE).edit().clear().commit()
        store = PublishConfigStore(context)
    }

    @Test fun theIndexPasswordBelongsToOneSessionAndIsTrimmed() {
        store.writeIndexPassword("pass-1", "  kväll1  ")
        store.writeIndexPassword("pass-2", "annat")

        assertEquals("kväll1", store.indexPassword("pass-1"))
        assertEquals("annat", store.indexPassword("pass-2"))
        // A session that never set one starts clean rather than inheriting the previous evening's.
        assertEquals("", store.indexPassword("ett-nytt-pass"))
    }

    @Test fun aBlankPasswordClearsThisSessionsProtection() {
        store.writeIndexPassword("pass-1", "hemlig")
        store.writeIndexPassword("pass-1", "   ")

        assertEquals("", store.indexPassword("pass-1"))
    }

    @Test fun theServerSettingsSurviveAPasswordChange() {
        store.write(PublishConfig("http://10.0.2.2:8080", "tk", enabled = true, bibOnly = true))
        store.writeIndexPassword("pass-1", "hemlig")

        val config = store.read()
        assertEquals("http://10.0.2.2:8080", config.serverUrl)
        assertEquals("tk", config.token)
        assertTrue(config.enabled)
        assertTrue(config.bibOnly)
        assertEquals("hemlig", store.indexPassword("pass-1"))
    }
}
