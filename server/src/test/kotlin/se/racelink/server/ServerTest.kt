package se.racelink.server

import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import java.time.Duration
import java.time.Instant
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import se.racelink.publish.PublishAthlete
import se.racelink.publish.PublishEnvelope
import se.racelink.publish.PublishFormat
import se.racelink.publish.PublishJson
import se.racelink.publish.PublishMode
import se.racelink.publish.PublishRace
import se.racelink.publish.PublishRequest
import se.racelink.publish.PublishRun
import se.racelink.publish.PublishSession
import se.racelink.publish.PublishStanding
import se.racelink.publish.RunStatus

class ServerTest {
    private val token = "test-token"

    private fun training(revision: Long, runs: List<PublishRun>) = PublishEnvelope(
        revision = revision,
        publishedAt = "2026-10-16T09:12:0$revision.000Z",
        session = PublishSession("s-1", "Onsdagsträning", "2026-10-16", PublishMode.TRAINING, closed = false, team = "Fjällbackens skidklubb"),
        athletes = listOf(PublishAthlete(1, 12, "Alma", "tok-alma"), PublishAthlete(2, 24, "Elias", "tok-elias")),
        runs = runs,
    )

    private fun withRuns(mode: PublishMode) = PublishEnvelope(
        revision = 1,
        publishedAt = "2026-10-16T09:12:01.000Z",
        session = PublishSession("s-racing", "Klubbcupen", "2026-10-16", mode, closed = false),
        race = PublishRace(PublishFormat.TOTAL, listOf("Öppen")),
        athletes = listOf(PublishAthlete(1, 12, "Alma", "tok-alma", "Öppen")),
        runs = listOf(
            PublishRun("a1", 1, 1, RunStatus.COMPLETED, 35000, round = 1),
            PublishRun("a2", 1, 2, RunStatus.COMPLETED, 36000, round = 2),
        ),
        standings = listOf(PublishStanding(1, "Öppen", "Två fullföljda åk", place = 1, scoreMs = 71000)),
    )

    @Test
    fun publishRequiresToken() = testApplication {
        application { module(ServerConfig(token)) }
        val response = client.post("/api/v1/publish") {
            contentType(ContentType.Application.Json)
            setBody(PublishJson.encode(PublishRequest(training(1, emptyList()))))
        }
        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun newerRevisionWinsAndStaleRevisionIsRejected() = testApplication {
        application { module(ServerConfig(token)) }
        suspend fun publish(env: PublishEnvelope) = client.post("/api/v1/publish") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody(PublishJson.encode(PublishRequest(env)))
        }

        val accepted = publish(training(2, listOf(PublishRun("r1", 1, 1, RunStatus.COMPLETED, 35620))))
        assertEquals(HttpStatusCode.Accepted, accepted.status)
        assertTrue(accepted.bodyAsText().contains("\"accepted\":true"))

        val stale = publish(training(1, emptyList()))
        assertTrue(stale.bodyAsText().contains("\"accepted\":false"))

        val snapshot = client.get("/api/v1/sessions/s-1")
        assertEquals(HttpStatusCode.OK, snapshot.status)
        assertTrue(snapshot.bodyAsText().contains("35620"), "the newer revision must remain stored")
    }

    @Test
    fun personalPageShowsLatestTimeAndDelta() = testApplication {
        application { module(ServerConfig(token)) }
        publish(
            training(
                1,
                listOf(
                    PublishRun("r1", 1, 1, RunStatus.COMPLETED, 35620),
                    PublishRun("r2", 1, 2, RunStatus.COMPLETED, 36040),
                    PublishRun("r3", 1, 3, RunStatus.INCOMPLETE),
                ),
            ),
        )

        val page = client.get("/a/tok-alma")
        assertEquals(HttpStatusCode.OK, page.status)
        val html = page.bodyAsText()
        assertTrue(html.contains("36,040"), "latest completed time is shown")
        assertTrue(html.contains("0,420 s långsammare"), "neutral delta vs previous run")
        assertTrue(html.contains("noindex"), "pages must not be indexed")
        assertFalse(html.contains("Elias"), "a skier's page must not expose other skiers")
    }

    @Test
    fun racePageShowsPlaceAndScore() = testApplication {
        application { module(ServerConfig(token)) }
        publish(withRuns(PublishMode.RACE))

        val html = client.get("/a/tok-alma").bodyAsText()
        assertTrue(html.contains("Placering 1"), "race place is rendered")
        assertTrue(html.contains("71,000"), "race score is rendered")
        assertTrue(html.contains("Totaltid"), "total-time format label is rendered")
    }

    @Test
    fun unknownTokenAndUnknownSessionReturn404() = testApplication {
        application { module(ServerConfig(token)) }
        assertEquals(HttpStatusCode.NotFound, client.get("/a/nope").status)
        assertEquals(HttpStatusCode.NotFound, client.get("/api/v1/sessions/nope").status)
    }

    private suspend fun io.ktor.server.testing.ApplicationTestBuilder.publish(
        envelope: PublishEnvelope,
        indexPassword: String? = null,
    ) {
        client.post("/api/v1/publish") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody(PublishJson.encode(PublishRequest(envelope, indexPassword)))
        }
    }

    @Test
    fun deleteRequiresTokenAndRemovesTheSessionAndItsLinks() = testApplication {
        application { module(ServerConfig(token)) }
        publish(training(1, listOf(PublishRun("r1", 1, 1, RunStatus.COMPLETED, 35000))))

        assertEquals(HttpStatusCode.Unauthorized, client.delete("/api/v1/sessions/s-1").status)
        assertEquals(
            HttpStatusCode.NoContent,
            client.delete("/api/v1/sessions/s-1") { header(HttpHeaders.Authorization, "Bearer $token") }.status,
        )
        assertEquals(HttpStatusCode.NotFound, client.get("/api/v1/sessions/s-1").status)
        assertEquals(HttpStatusCode.NotFound, client.get("/a/tok-alma").status, "the skier's link stops working")
        assertEquals(
            HttpStatusCode.NotFound,
            client.delete("/api/v1/sessions/s-1") { header(HttpHeaders.Authorization, "Bearer $token") }.status,
            "deleting an unknown session is a 404",
        )
    }

    @Test
    fun expiredSessionsArePurgedAndTheirLinksStopWorking() = testApplication {
        var clock = Instant.parse("2026-10-16T09:00:00Z")
        application { module(ServerConfig(token, retentionHours = 24), ProjectionStore { clock }) }
        publish(training(1, listOf(PublishRun("r1", 1, 1, RunStatus.COMPLETED, 35000))))
        assertEquals(HttpStatusCode.OK, client.get("/a/tok-alma").status)

        clock = clock.plus(Duration.ofHours(25))

        assertEquals(HttpStatusCode.NotFound, client.get("/a/tok-alma").status, "an expired page is gone, not stale")
        assertEquals(HttpStatusCode.NotFound, client.get("/api/v1/sessions/s-1").status)
    }

    @Test
    fun bibNamePolicyHidesTheSkierName() = testApplication {
        application { module(ServerConfig(token, namePolicy = NamePolicy.BIB)) }
        publish(training(1, listOf(PublishRun("r1", 1, 1, RunStatus.COMPLETED, 35000))))

        val html = client.get("/a/tok-alma").bodyAsText()
        assertTrue(html.contains("nr 12"), "the bib stays visible")
        assertFalse(html.contains("Alma"), "the name is hidden under the bib policy")
    }

    @Test
    fun localIndexListsThePerSkierLinksAndEscapesNames() {
        val html = renderIndex(listOf(training(1, emptyList())))
        assertTrue(html.contains("/a/tok-alma"))
        assertTrue(html.contains("/a/tok-elias"))
        assertTrue(html.contains("Alma"))
        assertTrue(html.contains("nr 12"))

        val hostile = training(1, emptyList())
            .copy(athletes = listOf(PublishAthlete(1, 12, "<script>alert(1)</script>", "tok-x")))
        val escaped = renderIndex(listOf(hostile))
        assertFalse(escaped.contains("<script>"), "a name is never injected as markup")
        assertTrue(escaped.contains("&lt;script&gt;"))
    }

    @Test
    fun theIndexIsOnlyServedToThisMachine() {
        assertTrue(isLoopbackHost("127.0.0.1"))
        assertTrue(isLoopbackHost("::1"))
        assertTrue(isLoopbackHost("0:0:0:0:0:0:0:1"))
        assertFalse(isLoopbackHost("192.168.0.162"), "a phone on the Wi-Fi gets the status line instead")
        assertFalse(isLoopbackHost("10.0.2.2"))
        assertFalse(isLoopbackHost(null))
    }

    private fun basic(user: String, password: String) =
        "Basic " + Base64.getEncoder().encodeToString("$user:$password".toByteArray())

    @Test
    fun theIndexNeedsTheOperatorPasswordWhenOneIsSet() = testApplication {
        application { module(ServerConfig(token, adminPassword = "hemlig")) }
        publish(training(1, emptyList()))

        assertEquals(HttpStatusCode.Unauthorized, client.get("/").status, "no password offered")
        assertEquals(
            HttpStatusCode.Unauthorized,
            client.get("/") { header(HttpHeaders.Authorization, basic("coach", "fel")) }.status,
            "wrong password",
        )
        assertEquals(
            HttpStatusCode.Unauthorized,
            client.get("/") { header(HttpHeaders.Authorization, "Basic not-base64-!") }.status,
            "unparseable header",
        )

        val accepted = client.get("/") { header(HttpHeaders.Authorization, basic("coach", "hemlig")) }
        assertEquals(HttpStatusCode.OK, accepted.status)
        assertTrue(accepted.bodyAsText().contains("/a/tok-alma"), "the coach sees the links")
    }

    @Test
    fun thePasswordTheCoachSetsOnTheTabletLocksTheIndex() = testApplication {
        application { module(ServerConfig(token)) }
        publish(training(1, emptyList()), indexPassword = "kväll1")

        assertEquals(HttpStatusCode.Unauthorized, client.get("/").status, "a session password locks it for everyone")
        assertEquals(
            HttpStatusCode.Unauthorized,
            client.get("/") { header(HttpHeaders.Authorization, basic("coach", "fel")) }.status,
        )

        // Only the password is checked, and it arrives as UTF-8 through the base64 of Basic auth, so a
        // Swedish password works. The username is whatever the coach's browser happened to remember.
        val accepted = client.get("/") { header(HttpHeaders.Authorization, basic("vem-som-helst", "kväll1")) }
        assertEquals(HttpStatusCode.OK, accepted.status)
        assertTrue(accepted.bodyAsText().contains("/a/tok-alma"), "the coach sees the links")
    }

    @Test
    fun anEmptyPasswordTurnsTheProtectionOffAndAMissingOneLeavesItAlone() = testApplication {
        application { module(ServerConfig(token)) }
        publish(training(1, emptyList()), indexPassword = "hemlig")
        assertEquals(HttpStatusCode.Unauthorized, client.get("/").status)

        publish(training(2, emptyList()), indexPassword = null)
        assertEquals(HttpStatusCode.Unauthorized, client.get("/").status, "a tablet that omits the field changes nothing")

        publish(training(3, emptyList()), indexPassword = "")
        val cleared = client.get("/")
        assertEquals(HttpStatusCode.OK, cleared.status, "clearing the field hands the index back to the server rules")
        assertTrue(cleared.bodyAsText().contains("/a/tok-alma"), "and it opens again from the server machine")
    }

    @Test
    fun anyLiveSessionsPasswordOpensTheIndexForTheCoach() = testApplication {
        application { module(ServerConfig(token)) }
        publish(training(1, emptyList()), indexPassword = "kväll1")

        val other = training(1, emptyList()).copy(
            session = PublishSession("s-2", "Torsdagsträning", "2026-10-17", PublishMode.TRAINING, closed = false),
            athletes = listOf(PublishAthlete(1, 12, "Alma", "tok-andra")),
        )
        publish(other, indexPassword = "nästa-gäng")

        assertEquals(
            HttpStatusCode.OK,
            client.get("/") { header(HttpHeaders.Authorization, basic("coach", "nästa-gäng")) }.status,
            "any live session's password opens the index, so the coach is not locked out between passes",
        )
        assertEquals(
            HttpStatusCode.OK,
            client.get("/") { header(HttpHeaders.Authorization, basic("coach", "kväll1")) }.status,
            "and the earlier session still works while it is live",
        )
    }

    @Test
    fun aSkierPageNeverLinksToTheIndex() = testApplication {
        application { module(ServerConfig(token, adminPassword = "hemlig")) }
        publish(training(1, listOf(PublishRun("r1", 1, 1, RunStatus.COMPLETED, 35000))))

        val html = client.get("/a/tok-alma").bodyAsText()
        assertFalse(html.contains("href=\"/\""), "the skier page must not offer a way into the index")
    }

    @Test
    fun theTrendHoldsOnlyCompletedRunsInOrder() {
        val envelope = training(
            1,
            listOf(
                PublishRun("r1", 1, 1, RunStatus.COMPLETED, 35000),
                PublishRun("r2", 1, 2, RunStatus.DNF, 40000),
                PublishRun("r3", 1, 3, RunStatus.COMPLETED, 33000),
            ),
        )
        val view = personalView(envelope, envelope.athletes.first())

        assertEquals(listOf(1, 2), view.trend.map { it.run }, "the skier's own runs are numbered from 1")
        assertEquals(listOf(35000L, 33000L), view.trend.map { it.ms }, "a DNF takes no slot and has no point")
    }

    @Test
    fun thePageDrawsThisSkiersOwnTrend() = testApplication {
        application { module(ServerConfig(token)) }
        publish(
            training(
                1,
                listOf(
                    PublishRun("r1", 1, 1, RunStatus.COMPLETED, 35000),
                    PublishRun("r2", 1, 2, RunStatus.COMPLETED, 34000),
                    PublishRun("r3", 1, 3, RunStatus.COMPLETED, 33000),
                ),
            ),
        )

        val html = client.get("/a/tok-alma").bodyAsText()
        assertTrue(html.contains("<svg"), "the page draws a chart")
        assertTrue(html.contains("<polyline"), "with the run line")
        assertTrue(html.contains(">3</text>"), "the third run is labelled on the axis")
        assertFalse(html.contains("tok-elias"), "never another skier's link")

        val chart = html.substringBefore("<script>")
        assertEquals(3, Regex("class=\"tlabel\"").findAll(chart).count(), "every point carries its own time")
        assertTrue(chart.contains(">35,000</text>"), "run 1's time")
        assertTrue(chart.contains(">34,000</text>"), "run 2's time")
        assertTrue(chart.contains(">33,000</text>"), "run 3's time")
        assertFalse(chart.contains("Dra i grafen"), "three points fit without any scrolling")
    }

    @Test
    fun aLongSessionWidensTheChartInsteadOfCrowdingIt() = testApplication {
        application { module(ServerConfig(token)) }
        val runs = (1..13).map { PublishRun("r$it", 1, it, RunStatus.COMPLETED, 30_000L + it * 100) }
        publish(training(1, runs))

        val html = client.get("/a/tok-alma").bodyAsText()
        val chart = html.substringBefore("<script>")
        assertTrue(chart.contains("min-width:598.0px"), "thirteen points each get their own column")
        assertEquals(13, Regex("class=\"tlabel\"").findAll(chart).count(), "no time is dropped")
        assertTrue(chart.contains("Dra i grafen för att se alla tider."), "a chart wider than the card says it scrolls")
    }

    @Test
    fun oneRunSaysTheTrendNeedsTwo() = testApplication {
        application { module(ServerConfig(token)) }
        publish(training(1, listOf(PublishRun("r1", 1, 1, RunStatus.COMPLETED, 35000))))

        val html = client.get("/a/tok-alma").bodyAsText()
        assertTrue(html.contains("Trenden visas när"), "not enough runs to plot")
    }

    @Test
    fun theApiCarriesTheTrendSoTheChartStaysLive() = testApplication {
        application { module(ServerConfig(token)) }
        publish(
            training(
                1,
                listOf(
                    PublishRun("r1", 1, 1, RunStatus.COMPLETED, 35000),
                    PublishRun("r2", 1, 2, RunStatus.COMPLETED, 34000),
                ),
            ),
        )

        val json = client.get("/api/v1/athletes/tok-alma").bodyAsText()
        assertTrue(json.contains("\"trend\""))
        assertTrue(json.contains("34,000"), "the refreshed point carries its formatted time")
    }

    @Test
    fun theOperatorPasswordComesFromTheEnvironment() {
        val configured = ServerConfig.fromEnv(
            mapOf(
                "RACELINK_PUBLISH_TOKEN" to "t",
                "RACELINK_ADMIN_PASSWORD" to "hemlig",
            ),
        )
        assertEquals("hemlig", configured.adminPassword)

        val bare = ServerConfig.fromEnv(mapOf("RACELINK_PUBLISH_TOKEN" to "t"))
        assertNull(bare.adminPassword, "an unconfigured install keeps the loopback-only index")

        assertNull(
            ServerConfig.fromEnv(mapOf("RACELINK_ADMIN_PASSWORD" to "   ")).adminPassword,
            "a blank password counts as unset rather than as an empty one",
        )
    }
}
