package se.racelink.server

import io.ktor.client.request.*
import io.ktor.client.request.forms.submitForm
import io.ktor.client.statement.bodyAsText
import io.ktor.http.*
import io.ktor.server.testing.testApplication
import java.util.Base64
import kotlin.test.*
import se.racelink.publish.*

class CoachViewTest {
    private val envelope = PublishEnvelope(1, "2026-10-16T12:00:00Z",
        PublishSession("session", "Träning", "2026-10-16", PublishMode.TRAINING, false),
        athletes = (1..4).map { PublishAthlete(it, it, "Åkare $it", "token-$it") },
        runs = listOf(
            PublishRun("r1", 1, 1, RunStatus.COMPLETED, 35000),
            PublishRun("r2", 2, 2, RunStatus.DNF, 34000),
            PublishRun("r3", 1, 3, RunStatus.COMPLETED, 33000),
        ))
    private val coaching = CoachProjection(
        athletes = (1..4).map { CoachAthlete(it, "Fullständigt namn $it", it != 4) },
        runs = listOf(CoachRun("r3", listOf("Privat observation"), listOf("Privat fokus"),
            listOf("Privat ändring"), "Privat kommentar <script>alert(1)</script>")),
    )
    private val basic = "Basic " + Base64.getEncoder().encodeToString("coach:coach-password".toByteArray())

    @Test fun orderPredictsReturnRatherThanSpeedAndIncludesDnfAndNoRuns() {
        assertEquals(listOf(3, 2, 1, 4), predictedStartOrder(envelope, coaching).map { it.id })
        val updated = envelope.copy(runs = envelope.runs + PublishRun("r4", 2, 4, RunStatus.INCOMPLETE))
        assertEquals(listOf(3, 1, 2, 4), predictedStartOrder(updated, coaching).map { it.id })
    }

    @Test fun coachPageRequiresLoginAndNeverLeaksFeedbackToPublicEndpoints() = testApplication {
        application { module(ServerConfig("publish-secret", adminPassword = "coach-password")) }
        assertEquals(HttpStatusCode.Accepted, client.post("/api/v1/publish") {
            header(HttpHeaders.Authorization, "Bearer publish-secret")
            contentType(ContentType.Application.Json)
            setBody(PublishJson.encode(PublishRequest(envelope, "session-password", coaching)))
        }.status)
        val anonymous = client.get("/coach?session=session&athlete=1")
        assertEquals(HttpStatusCode.Unauthorized, anonymous.status)
        assertFalse(anonymous.bodyAsText().contains("Privat kommentar"))
        assertEquals("no-store", anonymous.headers[HttpHeaders.CacheControl])
        assertEquals(HttpStatusCode.Unauthorized, client.get("/coach") {
            header(HttpHeaders.Authorization, "Bearer publish-secret")
        }.status)
        val page = client.get("/coach?session=session&athlete=1") { header(HttpHeaders.Authorization, basic) }
        assertEquals(HttpStatusCode.OK, page.status)
        val text = page.bodyAsText()
        listOf("Privat observation", "Privat fokus", "Privat ändring", "Fullständigt namn 1", "33,000 s", "35,000 s").forEach { assertTrue(text.contains(it), it) }
        assertTrue(text.contains("&lt;script&gt;alert(1)&lt;/script&gt;"))
        assertFalse(text.contains("<script>alert(1)</script>"))
        for (path in listOf("/a/token-1", "/api/v1/athletes/token-1", "/api/v1/sessions/session")) {
            val response = client.get(path) { if (path.contains("sessions")) header(HttpHeaders.Authorization, "Bearer publish-secret") }
            assertFalse(response.bodyAsText().contains("Privat"), path)
            assertFalse(response.bodyAsText().contains("Fullständigt namn"), path)
        }
        assertTrue(client.get("/") { header(HttpHeaders.Authorization, basic) }.bodyAsText().contains("href=\"/coach\""))
    }

    @Test fun sessionLoginCookieOpensCoachAndLogoutRevokesIt() = testApplication {
        val store = ProjectionStore().apply { put(envelope, coaching); setIndexPassword("session", "session-password") }
        application { module(ServerConfig("publish-secret"), store) }
        val browser = createClient { followRedirects = false }
        val login = browser.submitForm("/login", Parameters.build { append("password", "session-password") })
        val cookie = login.headers[HttpHeaders.SetCookie]!!.substringBefore(';')
        assertEquals(HttpStatusCode.OK, browser.get("/coach") { header(HttpHeaders.Cookie, cookie) }.status)
        browser.get("/logout") { header(HttpHeaders.Cookie, cookie) }
        assertEquals(HttpStatusCode.Unauthorized, browser.get("/coach") { header(HttpHeaders.Cookie, cookie) }.status)
    }

    @Test fun olderTabletShowsMissingFeedbackAndSelectedSkierStaysSelected() {
        val text = renderCoach(listOf(envelope), envelope, null, 2)
        assertTrue(text.contains("Återkoppling saknas"))
        assertTrue(text.contains("data-selected=\"2\""))
        assertTrue(text.contains("DNF"))
        assertTrue(text.contains("url.searchParams.set('athlete',old.dataset.selected)"))
    }
}
