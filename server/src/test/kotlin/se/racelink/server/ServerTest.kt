package se.racelink.server

import io.ktor.client.request.delete
import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.Parameters
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

    @Test fun chartMarksEveryTiedPersonalBestWithARingAndStar() = testApplication {
        application { module(ServerConfig(token)) }
        publish(training(1, listOf(
            PublishRun("one", 1, 1, RunStatus.COMPLETED, 35000),
            PublishRun("two", 1, 2, RunStatus.COMPLETED, 33000),
            PublishRun("three", 1, 3, RunStatus.COMPLETED, 33000)
        )))
        val chart = client.get("/a/tok-alma").bodyAsText().substringBefore("<script>")
        assertEquals(2, Regex("class=\"best-run\"").findAll(chart).count())
        assertEquals(2, Regex("class=\"best-star\"").findAll(chart).count())
        assertTrue(chart.contains("Bästa tiden · Åk 2 · 33,000 s"))
        assertTrue(chart.contains("Bästa tiden · Åk 3 · 33,000 s"))
        assertFalse(chart.contains("Bästa tiden · Åk 1"))
    }

    @Test fun newRunSignalIsPersonalAndIncludesDnfArrivals() {
        val first = PublishRun("own", 1, 4, RunStatus.COMPLETED, 35000)
        val other = first.copy(id = "other", athleteId = 2, sequence = 8)
        val envelope = training(1, listOf(first, other))
        assertEquals(4, personalView(envelope, envelope.athletes.first()).latestRecordedSequence)
        val dnf = first.copy(id = "dnf", sequence = 9, status = RunStatus.DNF, durationMs = null)
        val updated = envelope.copy(runs = envelope.runs + dnf)
        assertEquals(9, personalView(updated, updated.athletes.first()).latestRecordedSequence)
        assertEquals(4, personalView(envelope.copy(runs = listOf(first.copy(durationMs = 34000), other)), envelope.athletes.first()).latestRecordedSequence)
    }

    @Test fun latestPersonalBestUsesExactTimesAndExcludesNewDnfOrIncompleteRuns() {
        fun best(vararg runs: PublishRun): Boolean {
            val envelope = training(1, runs.toList())
            return personalView(envelope, envelope.athletes.first()).latestIsBest
        }
        val first = PublishRun("one", 1, 1, RunStatus.COMPLETED, 35000)
        assertFalse(best())
        assertTrue(best(first))
        assertTrue(best(first, first.copy(id = "two", sequence = 2, durationMs = 34000)))
        assertTrue(best(first, first.copy(id = "tie", sequence = 2)))
        assertFalse(best(first, first.copy(id = "slower", sequence = 2, durationMs = 35001)))
        assertFalse(best(first, first.copy(id = "dnf", sequence = 2, status = RunStatus.DNF, durationMs = 30000)))
        assertFalse(best(first, first.copy(id = "incomplete", sequence = 2, status = RunStatus.INCOMPLETE, durationMs = null)))
        assertTrue(best(first, first.copy(id = "other-skier", athleteId = 2, sequence = 2, durationMs = 20000)))
    }

    @Test fun bestRunHighlightRendersAndClearsOnLaterSlowerResult() = testApplication {
        application { module(ServerConfig(token)) }
        val run = PublishRun("one", 1, 1, RunStatus.COMPLETED, 35000)
        val initial = training(1, listOf(run))
        publish(initial)
        val html = client.get("/a/tok-alma").bodyAsText()
        assertTrue(html.contains("id=\"latest-card\" class=\"card highlight latest-best\""))
        assertTrue(html.contains("★</span> Bästa åket hittills"))
        assertTrue(client.get("/api/v1/athletes/tok-alma").bodyAsText().contains("\"latestIsBest\":true"))
        publish(initial.copy(revision = 2, runs = listOf(run, run.copy(id = "two", sequence = 2, durationMs = 36000))))
        val slower = client.get("/a/tok-alma").bodyAsText()
        assertTrue(slower.contains("id=\"latest-card\" class=\"card highlight\""))
        assertTrue(slower.contains("role=\"status\" hidden>"))
        assertTrue(slower.contains("latestCard.classList.toggle(\"latest-best\", v.latestIsBest === true)"))
    }

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
        assertEquals(HttpStatusCode.Unauthorized, snapshot.status, "the snapshot carries every token, so it is not public")
        val withToken = client.get("/api/v1/sessions/s-1") { header(HttpHeaders.Authorization, "Bearer $token") }
        assertEquals(HttpStatusCode.OK, withToken.status)
        assertTrue(withToken.bodyAsText().contains("35620"), "the newer revision must remain stored")
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
        assertEquals(
            HttpStatusCode.NotFound,
            client.get("/api/v1/sessions/nope") { header(HttpHeaders.Authorization, "Bearer $token") }.status,
        )
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
        assertEquals(
            HttpStatusCode.Unauthorized,
            client.get("/api/v1/sessions/s-1").status,
            "the snapshot is not public, gone session or not",
        )
        assertEquals(HttpStatusCode.NotFound, client.get("/a/tok-alma").status, "the skier's link stops working")
        assertEquals(
            HttpStatusCode.NotFound,
            client.get("/api/v1/sessions/s-1") { header(HttpHeaders.Authorization, "Bearer $token") }.status,
        )
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
        assertEquals(
            HttpStatusCode.NotFound,
            client.get("/api/v1/sessions/s-1") { header(HttpHeaders.Authorization, "Bearer $token") }.status,
        )
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

    /** Posts the login form the way a browser does; the reply carries the login cookie. */
    private suspend fun io.ktor.server.testing.ApplicationTestBuilder.login(password: String) =
        client.submitForm("/login", Parameters.build { append("password", password) })

    /** The `Set-Cookie` line the server sent for the index, if any. */
    private fun HttpResponse.setCookie(): String? =
        headers.getAll(HttpHeaders.SetCookie)?.firstOrNull { it.startsWith("racelink_index=") }

    /** The index cookie in the form a browser would send back, or null when the server set none. */
    private fun HttpResponse.cookieValue(): String? =
        setCookie()?.substringAfter("racelink_index=")?.substringBefore(';')?.takeIf { it.isNotEmpty() }

    private val loginForm = "action=\"/login\""

    @Test
    fun theIndexAsksForThePasswordInAFormAndTradesItForACookie() = testApplication {
        application { module(ServerConfig(token)) }
        publish(training(1, emptyList()), indexPassword = "kväll1")

        // A protected index is not handed out, and nothing invites the browser's Basic dialog: a
        // challenge is what makes a shared tablet's browser cache the password for the rest of the day.
        val denied = client.get("/")
        assertEquals(HttpStatusCode.OK, denied.status)
        assertNull(denied.headers[HttpHeaders.WWWAuthenticate], "the browser is never challenged for Basic auth")
        assertTrue(denied.bodyAsText().contains(loginForm), "the password is asked for in a form")
        assertFalse(denied.bodyAsText().contains("/a/tok-alma"), "and the links stay behind it")
        // The form CSS goes inside the shared head; a nested second <head> would still "work" in a
        // browser while being a broken document, so the shape is asserted rather than eyeballed.
        assertEquals(1, Regex("<head>").findAll(denied.bodyAsText()).count(), "one head")
        assertEquals(1, Regex("</head>").findAll(denied.bodyAsText()).count(), "closed once")
        assertEquals(1, Regex("<body>").findAll(denied.bodyAsText()).count(), "one body")

        val wrong = login("fel")
        assertEquals(HttpStatusCode.Unauthorized, wrong.status, "a wrong password comes back to the form")
        assertNull(wrong.setCookie(), "with no cookie")

        // The password travels as a form field, so a Swedish one is decoded as UTF-8, and no username
        // is involved: it is one shared secret.
        val right = login("kväll1")
        assertEquals(HttpStatusCode.SeeOther, right.status, "a right password redirects to the index")
        assertEquals("/", right.headers[HttpHeaders.Location])
        val cookie = right.setCookie()!!
        assertTrue(cookie.contains("HttpOnly"), "the cookie is kept away from scripts")
        assertTrue(cookie.contains("SameSite=Strict"), "and off cross-site requests")
        assertTrue(cookie.contains("Path=/"), "and sent wherever the index is")
        assertFalse(cookie.contains("Secure"), "a plain-HTTP dev server would lose a Secure cookie")

        val index = client.get("/") { header(HttpHeaders.Cookie, "racelink_index=${right.cookieValue()}") }
        assertEquals(HttpStatusCode.OK, index.status)
        assertTrue(index.bodyAsText().contains("/a/tok-alma"), "the coach sees the links")
    }

    @Test
    fun loggingOutRevokesTheCookieHereToo() = testApplication {
        application { module(ServerConfig(token)) }
        publish(training(1, emptyList()), indexPassword = "hemlig")
        val cookie = login("hemlig").cookieValue()!!
        assertEquals(HttpStatusCode.OK, client.get("/") { header(HttpHeaders.Cookie, "racelink_index=$cookie") }.status)

        val out = client.get("/logout") { header(HttpHeaders.Cookie, "racelink_index=$cookie") }
        assertEquals(HttpStatusCode.OK, out.status)
        assertTrue(out.bodyAsText().contains("utloggad"))
        assertTrue(out.setCookie()!!.contains("Max-Age=0"), "the browser is told to drop the cookie")

        // Revoked here as well, so a copy the browser kept is worth nothing.
        val replay = client.get("/") { header(HttpHeaders.Cookie, "racelink_index=$cookie") }
        assertTrue(replay.bodyAsText().contains(loginForm), "the old cookie no longer opens the index")
    }

    @Test
    fun theOperatorPasswordStillWorksForAScriptButIsNeverChallengedFor() = testApplication {
        application { module(ServerConfig(token, adminPassword = "hemlig")) }
        publish(training(1, emptyList()))

        val script = client.get("/") { header(HttpHeaders.Authorization, basic("coach", "hemlig")) }
        assertEquals(HttpStatusCode.OK, script.status, "curl can authenticate with Basic")
        assertTrue(script.bodyAsText().contains("/a/tok-alma"))
        assertNull(script.headers[HttpHeaders.WWWAuthenticate], "still no challenge, so no browser ever prompts")

        assertEquals(
            HttpStatusCode.OK,
            client.get("/") { header(HttpHeaders.Authorization, "not-even-authorized") }.status,
            "an unreadable header is just not signed in",
        )
        val wrong = client.get("/") { header(HttpHeaders.Authorization, basic("coach", "fel")) }
        assertEquals(HttpStatusCode.OK, wrong.status)
        assertTrue(wrong.bodyAsText().contains(loginForm), "a wrong Basic password gets the form, never the links")
        assertFalse(wrong.bodyAsText().contains("/a/tok-alma"))

        val unreadable = login("fel")
        assertEquals(HttpStatusCode.Unauthorized, unreadable.status, "a bad password is a 401 with the form")
        assertNull(unreadable.setCookie())
    }

    @Test
    fun repeatedWrongPasswordsAreRefusedForAWhile() = testApplication {
        application { module(ServerConfig(token), throttle = LoginThrottle(limit = 2)) }
        publish(training(1, emptyList()), indexPassword = "hemlig")

        assertEquals(HttpStatusCode.Unauthorized, login("fel").status)
        assertEquals(HttpStatusCode.Unauthorized, login("fel").status)
        assertEquals(HttpStatusCode.TooManyRequests, login("fel").status, "the guessing stops being answered")

        // The coach is never locked out by somebody else's guessing: only attempts that were wrong
        // anyway are refused, and a right password clears the count.
        assertEquals(HttpStatusCode.SeeOther, login("hemlig").status)
        assertEquals(HttpStatusCode.Unauthorized, login("fel").status, "and the count starts over")
    }

    @Test
    fun thePasswordTheCoachSetsOnTheTabletLocksTheIndex() = testApplication {
        application { module(ServerConfig(token)) }
        publish(training(1, emptyList()), indexPassword = "kväll1")

        assertTrue(client.get("/").bodyAsText().contains(loginForm), "a session password locks it for everyone")
        val cookie = login("kväll1").cookieValue()
        assertEquals(
            HttpStatusCode.OK,
            client.get("/") { header(HttpHeaders.Cookie, "racelink_index=$cookie") }.status,
            "and the coach gets in with the password from the tablet",
        )
    }

    @Test
    fun anEmptyPasswordTurnsTheProtectionOffAndAMissingOneLeavesItAlone() = testApplication {
        application { module(ServerConfig(token)) }
        publish(training(1, emptyList()), indexPassword = "hemlig")
        assertTrue(client.get("/").bodyAsText().contains(loginForm), "the index is behind the form")

        publish(training(2, emptyList()), indexPassword = null)
        assertTrue(client.get("/").bodyAsText().contains(loginForm), "a tablet that omits the field changes nothing")

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
            HttpStatusCode.SeeOther,
            login("nästa-gäng").status,
            "any live session's password opens the index, so the coach is not locked out between passes",
        )
        assertEquals(
            HttpStatusCode.SeeOther,
            login("kväll1").status,
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
        val ys = Regex("cy=\"([0-9.]+)\"").findAll(chart).map { it.groupValues[1].toDouble() }.toList()
        assertTrue(ys[0] < ys[1] && ys[1] < ys[2], "lower times must appear lower in the graph")
        assertFalse(chart.contains("Dra i grafen"), "three points fit without any scrolling")
    }

    @Test fun personalBrandingAndOngoingStatusFollowThePublishedSession() = testApplication {
        application { module(ServerConfig(token)) }
        val logo = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jRz8AAAAASUVORK5CYII="
        val open = training(1, emptyList()).copy(logoPngBase64 = logo)
        publish(open)
        val html = client.get("/a/tok-alma").bodyAsText()
        assertTrue(html.contains("src=\"data:image/png;base64,$logo\""))
        assertTrue(html.contains("class=\"badge ongoing\">Pågår"))
        assertTrue(html.contains("prefers-reduced-motion"))
        assertTrue(html.contains("id=\"chart\""), "empty sessions can acquire a chart through live polling")
        assertTrue(client.get("/api/v1/athletes/tok-alma").bodyAsText().contains(logo))
        publish(open.copy(revision = 2, session = open.session.copy(closed = true), logoPngBase64 = null))
        val closed = client.get("/a/tok-alma").bodyAsText()
        assertTrue(closed.contains("class=\"badge\">Avslutad"))
        assertFalse(closed.contains("src=\"data:image/png"))
        assertTrue(closed.contains("status.classList.toggle(\"ongoing\", !v.closed)"))
        publish(open.copy(revision = 3, logoPngBase64 = "\" onerror=\"alert(1)"))
        assertNull(personalView(open.copy(logoPngBase64 = "invalid"), open.athletes.first()).logoPngBase64)
        assertFalse(client.get("/a/tok-alma").bodyAsText().contains("onerror="))
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
