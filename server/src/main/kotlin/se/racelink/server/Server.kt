package se.racelink.server

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.withCharset
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.response.header
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import java.security.MessageDigest
import java.util.Base64
import kotlinx.serialization.Serializable
import se.racelink.publish.PublishAck
import se.racelink.publish.PublishEnvelope
import se.racelink.publish.PublishRequest

/**
 * Read-only publishing service. The tablet POSTs idempotent projections; skiers GET their own page.
 * It never accepts operator edits and never writes anything back to the tablet.
 */
fun Application.module(config: ServerConfig = ServerConfig.fromEnv(), store: ProjectionStore = ProjectionStore()) {
    install(ContentNegotiation) { json(kotlinx.serialization.json.Json { ignoreUnknownKeys = true }) }

    // Expiry is evaluated on every request (cheap at venue scale) and by a periodic sweep in main.
    fun sweep() { store.purge(config.retentionHours) }

    routing {
        get("/healthz") { call.respondText("ok") }

        get("/") {
            sweep()
            val sessions = store.sessions()
            val statusLine = "RaceLink Studio publishing service\nsessions: ${sessions.size}\n"
            // The index lists every skier's link, which is the one page a child must never reach: it
            // would turn the site into exactly the comparison the product avoids. It is gated twice
            // over — by the password the coach sets on the tablet for a session (so the coach can open
            // it from any device), and otherwise by a loopback-only request, which is the pre-existing
            // behaviour for an install that never set one.
            val gated = config.adminPassword != null || store.hasIndexPassword()
            when {
                sessions.isEmpty() -> call.respondText(statusLine, ContentType.Text.Plain)
                gated && !call.hasIndexAccess(config, store) -> {
                    call.response.header(
                        HttpHeaders.WWWAuthenticate,
                        "Basic realm=\"RaceLink Studio\", charset=\"UTF-8\"",
                    )
                    call.respondText("Lösenord krävs.\n", ContentType.Text.Plain, HttpStatusCode.Unauthorized)
                }
                !gated && !isLoopbackHost(call.request.local.remoteHost) ->
                    call.respondText(statusLine, ContentType.Text.Plain)
                else -> call.respondText(renderIndex(sessions), ContentType.Text.Html.withCharset(Charsets.UTF_8))
            }
        }

        // Outbound-only ingest from the authoritative tablet. Bearer token, idempotent by revision.
        post("/api/v1/publish") {
            if (!call.hasPublishToken(config)) {
                call.respond(HttpStatusCode.Unauthorized, "invalid publish token")
                return@post
            }
            val request = call.receive<PublishRequest>()
            val envelope = request.envelope
            val stored = store.put(envelope)
            val accepted = stored == envelope.revision
            // The password rides with the publish instead of living in the projection, and a stale
            // publish must not be able to change it: only an accepted revision updates it.
            if (accepted) request.indexPassword?.let { store.setIndexPassword(envelope.session.id, it) }
            call.respond(HttpStatusCode.Accepted, PublishAck(envelope.session.id, stored, accepted))
        }

        // Hard delete on demand (e.g. a consent withdrawal or an accidental publish).
        delete("/api/v1/sessions/{id}") {
            if (!call.hasPublishToken(config)) {
                call.respond(HttpStatusCode.Unauthorized, "invalid publish token")
                return@delete
            }
            val id = call.parameters["id"]
            if (id != null && store.delete(id)) call.respond(HttpStatusCode.NoContent)
            else call.respond(HttpStatusCode.NotFound)
        }

        get("/api/v1/sessions/{id}") {
            sweep()
            val envelope = call.parameters["id"]?.let(store::session)
            if (envelope == null) call.respond(HttpStatusCode.NotFound) else call.respond(envelope)
        }

        // Small JSON the page polls for live updates; token-scoped, public, noindex.
        get("/api/v1/athletes/{token}") {
            sweep()
            val found = call.parameters["token"]?.let(store::athlete)
            if (found == null) call.respond(HttpStatusCode.NotFound) else {
                val (envelope, athlete) = found
                call.respond(personalView(envelope, athlete, config.namePolicy))
            }
        }

        // The link a skier scans once and bookmarks; opened on a phone in the lift.
        get("/a/{token}") {
            sweep()
            val found = call.parameters["token"]?.let(store::athlete)
            if (found == null) {
                call.respondText("Länken finns inte längre.", ContentType.Text.Html.withCharset(Charsets.UTF_8), HttpStatusCode.NotFound)
            } else {
                val (envelope, athlete) = found
                val token = call.parameters["token"]!!
                call.respondText(renderPersonalPage(personalView(envelope, athlete, config.namePolicy), token), ContentType.Text.Html.withCharset(Charsets.UTF_8))
            }
        }
    }
}

private fun ApplicationCall.hasPublishToken(config: ServerConfig): Boolean {
    val provided = request.headers[HttpHeaders.Authorization]?.removePrefix("Bearer ")?.trim()
    return !provided.isNullOrBlank() && provided == config.publishToken
}

private fun esc(value: String): String = value
    .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

/** True for a request from this machine, which is how the desktop operator browses the service. */
internal fun isLoopbackHost(host: String?): Boolean = when (host) {
    "127.0.0.1", "::1", "0:0:0:0:0:0:0:1", "localhost" -> true
    else -> false
}

/**
 * HTTP Basic check for the link index. Only the password is compared, never the username: the coach
 * types the password they set on the tablet for the session, so there is nothing else to remember. An
 * operator-set password still works for a server nobody holds the tablet for; both checks always run.
 */
private fun ApplicationCall.hasIndexAccess(config: ServerConfig, store: ProjectionStore): Boolean {
    val header = request.headers[HttpHeaders.Authorization] ?: return false
    if (!header.startsWith("Basic ", ignoreCase = true)) return false
    val decoded = try {
        String(Base64.getDecoder().decode(header.substring(6).trim()), Charsets.UTF_8)
    } catch (_: IllegalArgumentException) {
        return false
    }
    val separator = decoded.indexOf(':')
    if (separator < 0) return false
    val provided = decoded.substring(separator + 1)
    val operatorMatches = config.adminPassword?.let { constantTimeEquals(provided, it) } ?: false
    return operatorMatches or store.acceptsIndexPassword(provided)
}

private fun constantTimeEquals(a: String, b: String): Boolean =
    MessageDigest.isEqual(a.toByteArray(Charsets.UTF_8), b.toByteArray(Charsets.UTF_8))

/**
 * The index of the live per-skier links, so the coach does not have to read a token out of the tablet's
 * QR sheet to test a page. It is the one page that must not reach a child, so it is only served to a
 * loopback request or behind the password the coach set for the session: the token is the whole of a
 * page's protection, so a freely readable index of every token would undo the per-skier scoping.
 */
internal fun renderIndex(sessions: List<PublishEnvelope>): String = buildString {
    append("<!doctype html><html lang=\"sv\"><head><meta charset=\"utf-8\">")
    append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">")
    append("<meta name=\"robots\" content=\"noindex, nofollow\">")
    append("<title>RaceLink Studio · lokala länkar</title>")
    append(
        "<style>" +
            ":root{--bg:#f4f7fb;--card:#fff;--ink:#12212f;--muted:#5b6b7a;--accent:#1f6feb;--line:#dbe4ee}" +
            "@media (prefers-color-scheme:dark){:root{--bg:#0e1620;--card:#16202b;--ink:#e8eef5;--muted:#9fb0c0;--accent:#5aa2ff;--line:#243244}}" +
            "*{box-sizing:border-box}body{margin:0;background:var(--bg);color:var(--ink);font:16px/1.4 system-ui,-apple-system,'Segoe UI',Roboto,sans-serif}" +
            "main{padding:16px;max-width:560px;margin:0 auto}h1{font-size:20px;margin:0 0 4px}" +
            "h2{font-size:14px;margin:22px 0 8px;color:var(--muted);font-weight:600}" +
            "a{display:flex;justify-content:space-between;align-items:baseline;gap:12px;background:var(--card);border:1px solid var(--line);" +
            "border-radius:12px;padding:12px 14px;margin-bottom:8px;color:var(--accent);text-decoration:none}" +
            ".bib{color:var(--muted);font-weight:400}code{color:var(--muted);font-size:12px;word-break:break-all}" +
            ".hint{color:var(--muted);font-size:13px}</style></head><body><main>",
    )
    append("<h1>RaceLink Studio</h1>")
    append("<p class=\"hint\">Länkarna är personliga – dela dem bara med respektive åkare.</p>")
    sessions.forEach { envelope ->
        val session = envelope.session
        append("<h2>${esc(session.team)} · ${esc(session.name)} · ${esc(session.date)}</h2>")
        envelope.athletes.sortedBy { it.bib }.forEach { athlete ->
            val label = if (athlete.displayName.isBlank()) "Nr ${athlete.bib}"
            else "${esc(athlete.displayName)} <span class=\"bib\">nr ${athlete.bib}</span>"
            append("<a href=\"/a/${esc(athlete.token)}\"><span>$label</span><code>/a/${esc(athlete.token)}</code></a>")
        }
    }
    append("</main></body></html>")
}

private fun stat(id: String, label: String, value: String?, suffix: String = "", big: Boolean = false): String {
    val shown = value?.let { "$it$suffix" } ?: "–"
    val cls = if (big) "value big" else "value"
    return "<div class=\"card\"><div class=\"label\">$label</div><div class=\"$cls\" id=\"$id\">${esc(shown)}</div></div>"
}

/**
 * The skier's own runs as an inline SVG. Lower time is better, so the fastest run sits highest, and
 * every point is labelled with its time just above it. The chart grows wider than the card once a
 * session has many runs, so the times stay legible instead of colliding; the wrapper scrolls, and the
 * note says so.
 *
 * The same geometry is rebuilt by `chart()` in the page script, which repaints on every poll; keep the
 * two in step.
 */
private fun trendCard(points: List<RunTrendPoint>): String = if (points.size < 2) {
    "<div class=\"card\"><div class=\"label\">Tid per åk</div>" +
        "<div class=\"note\">Trenden visas när åkaren har minst två fullföljda åk.</div></div>"
} else {
    "<div class=\"card\"><div class=\"label\">Tid per åk</div>" +
        "<div class=\"chart\" id=\"chart\">${trendSvg(points)}</div>" +
        "<div class=\"note\" id=\"chart-note\">${trendNote(points.size)}</div></div>"
}

/** The chart is wider than the card from nine points on, which the skier has to be told. */
private fun trendNote(points: Int): String =
    TREND_NOTE + if (points > 8) TREND_SCROLL_HINT else ""

private const val TREND_NOTE = "Lägre tid är bättre. Tiden står vid varje punkt, siffran under visar åkets ordning."
private const val TREND_SCROLL_HINT = " Dra i grafen för att se alla tider."

/** A 320-wide chart fits around eight labelled points; beyond that each point gets its own column. */
private fun trendWidth(count: Int): Double = maxOf(320.0, count * 46.0)

private fun trendSvg(points: List<RunTrendPoint>): String {
    val width = trendWidth(points.size)
    val left = 24.0
    val right = width - 14.0
    // The top sits low enough that even the fastest run's time label fits above it.
    val top = 34.0
    val bottom = 112.0
    val axisY = 132.0
    val height = 150.0
    val min = points.minOf { it.ms }
    val max = points.maxOf { it.ms }
    val span = (max - min).toDouble()
    val step = (right - left) / (points.size - 1)
    fun x(index: Int) = left + index * step
    fun y(ms: Long) = if (span == 0.0) (top + bottom) / 2 else top + (ms - min) / span * (bottom - top)
    return buildString {
        append("<svg viewBox=\"0 0 $width $height\" style=\"min-width:${width}px\" role=\"img\" aria-label=\"Tid per åk\">")
        append("<line x1=\"$left\" y1=\"${y(min)}\" x2=\"$right\" y2=\"${y(min)}\" class=\"best\"/>")
        append("<polyline class=\"line\" points=\"")
        points.forEachIndexed { index, point ->
            if (index > 0) append(' ')
            append("${x(index)},${y(point.ms)}")
        }
        append("\"/>")
        points.forEachIndexed { index, point ->
            val cy = y(point.ms)
            val cls = if (index == points.lastIndex) "dot new" else "dot"
            append("<circle class=\"$cls\" cx=\"${x(index)}\" cy=\"$cy\" r=\"3.5\"/>")
            append("<text x=\"${x(index)}\" y=\"${cy - 9}\" class=\"tlabel\">${esc(point.label)}</text>")
        }
        points.forEachIndexed { index, point ->
            append("<text x=\"${x(index)}\" y=\"$axisY\" class=\"axis\">${point.run}</text>")
        }
        append("</svg>")
    }
}

private fun renderPersonalPage(view: PersonalView, token: String): String {
    val isRace = view.mode == "race"
    return buildString {
        append("<!doctype html><html lang=\"sv\"><head><meta charset=\"utf-8\">")
        append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">")
        append("<meta name=\"robots\" content=\"noindex, nofollow\">")
        append("<title>${esc(view.displayName.ifBlank { "Nr ${view.bib}" })} · RaceLink</title>")
        append(
            "<style>" +
                ":root{--bg:#f4f7fb;--card:#fff;--ink:#12212f;--muted:#5b6b7a;--accent:#1f6feb;--line:#dbe4ee}" +
                "@media (prefers-color-scheme:dark){:root{--bg:#0e1620;--card:#16202b;--ink:#e8eef5;--muted:#9fb0c0;--accent:#5aa2ff;--line:#243244}}" +
                "*{box-sizing:border-box}body{margin:0;background:var(--bg);color:var(--ink);font:16px/1.4 system-ui,-apple-system,'Segoe UI',Roboto,sans-serif}" +
                "header{padding:14px 16px;border-bottom:1px solid var(--line)}.team{font-weight:600}.session{color:var(--muted);font-size:14px}" +
                ".badges{display:flex;gap:6px;margin-top:8px;flex-wrap:wrap}.badge{font-size:11px;letter-spacing:.04em;border:1px solid var(--line);border-radius:999px;padding:2px 8px;color:var(--muted)}" +
                "main{padding:16px;display:flex;flex-direction:column;gap:12px;max-width:560px;margin:0 auto}" +
                "h1{font-size:24px;margin:4px 0}.bib{color:var(--muted);font-size:15px;font-weight:400}" +
                ".card{background:var(--card);border:1px solid var(--line);border-radius:14px;padding:14px 16px}" +
                ".row{display:flex;gap:12px}.row .card{flex:1}" +
                ".label{color:var(--muted);font-size:13px;text-transform:uppercase;letter-spacing:.05em}" +
                ".value{font-size:26px;font-weight:600;font-variant-numeric:tabular-nums;margin-top:2px}" +
                ".value.big{font-size:40px;color:var(--accent)}" +
                ".highlight{border-color:var(--accent)}" +
                ".chart{overflow-x:auto}.chart svg{display:block;width:100%;height:auto}" +
                ".chart .line{fill:none;stroke:var(--accent);stroke-width:2;stroke-linejoin:round;stroke-linecap:round}" +
                ".chart .dot{fill:var(--card);stroke:var(--accent);stroke-width:2}" +
                ".chart .dot.new{fill:var(--accent)}" +
                ".chart .best{stroke:var(--line);stroke-dasharray:4 4}" +
                ".chart .tlabel{fill:var(--ink);font-size:9px;text-anchor:middle}" +
                ".chart .axis{fill:var(--muted);font-size:10px;text-anchor:middle}" +
                ".note{color:var(--muted);font-size:12px;margin-top:8px}" +
                "footer{padding:16px;color:var(--muted);font-size:13px;text-align:center}footer a{color:var(--accent)}" +
                "</style></head><body>",
        )
        append("<header><div class=\"team\">${esc(view.team)}</div>")
        append("<div class=\"session\">${esc(view.sessionName)} · ${esc(view.sessionDate)}</div>")
        append("<div class=\"badges\"><span class=\"badge\">SIMULATOR</span>")
        append("<span class=\"badge\">${if (isRace) "Tävling" else "Träning"}</span>")
        append("<span class=\"badge\">${if (view.closed) "Avslutad" else "Pågår"}</span></div></header>")
        append("<main>")
        append("<h1>${if (view.displayName.isBlank()) "" else esc(view.displayName) + " "}<span class=\"bib\">nr ${view.bib}</span></h1>")
        if (isRace) {
            append("<div class=\"row\">")
            append(stat("run1", "Åk 1", view.run1))
            append(stat("run2", "Åk 2", view.run2))
            append("</div>")
            append("<div class=\"card highlight\"><div class=\"label\">Resultat · ${esc(view.scoreLabel ?: "Tid")}</div>")
            append("<div class=\"value big\" id=\"score\">${esc(view.score?.let { "$it s" } ?: "–")}</div>")
            append("<div class=\"label\" id=\"standing\">${esc(view.standing ?: "")}</div>")
            if (view.place != null) append("<div class=\"label\" id=\"place\">Placering ${view.place}</div>")
            append("</div>")
        } else {
            append("<div class=\"card highlight\"><div class=\"label\">Senaste fullföljda åket</div>")
            append("<div class=\"value big\" id=\"latest\">${esc(view.latest?.let { "$it s" } ?: "Väntar på resultat")}</div>")
            append("<div class=\"label\" id=\"delta\">${esc(view.delta ?: "")}</div></div>")
            append("<div class=\"card\"><div class=\"label\">Bästa åket</div>")
            append("<div class=\"value\" id=\"best\">${esc(view.best?.let { "$it s" } ?: "–")}</div></div>")
        }
        append(trendCard(view.trend))
        append("</main>")
        append("<footer>Uppdaterad <span id=\"updated\">${esc(view.updatedAt)}</span> · RaceLink Studio</footer>")
        append(refreshScript(token))
        append("</body></html>")
    }
}

private fun refreshScript(token: String): String = """
<script>
(function(){
  var token = "$token";
  function set(id, text){ var el = document.getElementById(id); if (el && text) el.textContent = text; }
  // Mirrors trendNote() and trendSvg() on the server; all three must stay in step.
  function note(count){
    return "${TREND_NOTE}" + (count > 8 ? "${TREND_SCROLL_HINT}" : "");
  }
  function chart(points){
    var el = document.getElementById("chart");
    var noteEl = document.getElementById("chart-note");
    if (!el) return;
    if (!points || points.length < 2) {
      if (noteEl) noteEl.textContent = "Trenden visas när åkaren har minst två fullföljda åk.";
      return;
    }
    if (noteEl) noteEl.textContent = note(points.length);
    var w = Math.max(320, points.length * 46), h = 150;
    var left = 24, right = w - 14, top = 34, bottom = 112, axisY = 132;
    var times = points.map(function(p){ return p.ms; });
    var min = Math.min.apply(null, times), max = Math.max.apply(null, times);
    var span = max - min, step = (right - left) / (points.length - 1);
    function x(i){ return left + i * step; }
    function y(ms){ return span === 0 ? (top + bottom) / 2 : top + (ms - min) / span * (bottom - top); }
    var s = '<svg viewBox="0 0 ' + w + ' ' + h + '" style="min-width:' + w + 'px" role="img" aria-label="Tid per åk">';
    s += '<line x1="' + left + '" y1="' + y(min) + '" x2="' + right + '" y2="' + y(min) + '" class="best"/>';
    s += '<polyline class="line" points="' + points.map(function(p, i){ return x(i) + "," + y(p.ms); }).join(" ") + '"/>';
    points.forEach(function(p, i){
      var cy = y(p.ms);
      s += '<circle class="dot' + (i === points.length - 1 ? " new" : "") + '" cx="' + x(i) + '" cy="' + cy + '" r="3.5"/>';
      s += '<text x="' + x(i) + '" y="' + (cy - 9) + '" class="tlabel">' + p.label + '</text>';
    });
    points.forEach(function(p, i){ s += '<text x="' + x(i) + '" y="' + axisY + '" class="axis">' + p.run + '</text>'; });
    el.innerHTML = s + '</svg>';
  }
  async function refresh(){
    try {
      var r = await fetch("/api/v1/athletes/" + encodeURIComponent(token), { cache: "no-store" });
      if (!r.ok) return;
      var v = await r.json();
      set("latest", v.latest ? v.latest + " s" : null);
      set("delta", v.delta || "");
      set("best", v.best ? v.best + " s" : null);
      set("run1", v.run1 || "");
      set("run2", v.run2 || "");
      set("score", v.score ? v.score + " s" : null);
      set("standing", v.standing || "");
      var p = document.getElementById("place");
      if (p && v.place != null) p.textContent = "Placering " + v.place;
      var u = document.getElementById("updated");
      if (u && v.updatedAt) u.textContent = v.updatedAt;
      chart(v.trend);
    } catch (e) {}
  }
  setInterval(refresh, 5000);
})();
</script>
""".trimIndent()
