package se.racelink.publish

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PublishJsonTest {
    private fun sample() = PublishEnvelope(
        revision = 7,
        publishedAt = "2026-10-16T09:12:33Z",
        session = PublishSession("s-1", "Onsdagsträning", "2026-10-16", PublishMode.TRAINING, closed = false, team = "Fjällbackens skidklubb"),
        athletes = listOf(PublishAthlete(12, 12, "Alma", "token-a")),
        runs = listOf(
            PublishRun("demo-1", 12, 1, RunStatus.COMPLETED, 35620),
            PublishRun("demo-2", 12, 2, RunStatus.INCOMPLETE),
        ),
        markers = listOf(PublishMarker(8, "Portarna flyttade")),
    )

    @Test fun roundTripsThroughJson() {
        assertEquals(sample(), PublishJson.decode(PublishJson.encode(sample())))
    }

    @Test fun decodesForwardCompatibleAdditions() {
        val text = PublishJson.encode(sample()).replaceFirst("{", "{\"futureField\":true,")
        assertTrue(PublishJson.decode(text).runs.isNotEmpty())
    }

    @Test fun aPublishRequestRoundTripsAndKeepsAbsentApart() {
        val withPassword = PublishJson.encode(PublishRequest(sample(), "kväll"))
        val request = PublishJson.decodeRequest(withPassword)
        assertEquals(sample(), request.envelope)
        assertEquals("kväll", request.indexPassword)

        // The three states have to stay distinguishable: null is "leave what the server already has",
        // the empty string is "clear it", and both survive a round trip. The field is always written out.
        assertEquals("", PublishJson.decodeRequest(PublishJson.encode(PublishRequest(sample(), ""))).indexPassword)
        assertNull(PublishJson.decodeRequest(PublishJson.encode(PublishRequest(sample()))).indexPassword)
        assertTrue(withPassword.contains("\"indexPassword\""))
    }

    @Test fun differenceWordingIsNeutralAndLowerIsFaster() {
        assertTrue(formatDifference(-420).startsWith("0,420") && formatDifference(-420).endsWith("snabbare"))
        assertTrue(formatDifference(1500).endsWith("långsammare"))
        assertEquals("Samma tid", formatDifference(0))
    }

    @Test fun raceSecondsUsesCommaAndThreeDecimals() {
        assertEquals("35,620", formatSeconds(35620))
        assertEquals("7,050", formatSeconds(7050))
    }
}
