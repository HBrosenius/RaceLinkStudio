package se.racelink.server

import se.racelink.publish.*
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/** Oldest last finish first approximates the next return from the lift; never a speed ranking. */
internal fun predictedStartOrder(envelope: PublishEnvelope, coaching: CoachProjection?): List<PublishAthlete> {
    val latest = envelope.runs.groupBy { it.athleteId }.mapValues { (_, runs) -> runs.maxOf { it.sequence } }
    val attendance = coaching?.athletes?.associateBy { it.id }.orEmpty()
    return envelope.athletes.sortedWith(
        compareBy<PublishAthlete> { attendance[it.id]?.participating == false }
            .thenBy { latest[it.id] ?: Int.MIN_VALUE }.thenBy { it.bib }.thenBy { it.id },
    )
}

private fun urlPart(value: String) = URLEncoder.encode(value, StandardCharsets.UTF_8)
private fun coachUrl(session: String, athlete: Int? = null) =
    "/coach?session=${urlPart(session)}" + (athlete?.let { "&amp;athlete=$it" } ?: "")

internal fun renderCoach(sessions: List<PublishEnvelope>, envelope: PublishEnvelope?, coaching: CoachProjection?, selectedId: Int?): String = buildString {
    append("""<!doctype html><html lang="sv"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<meta name="robots" content="noindex,nofollow"><title>RaceLink · Starttränare</title><style>
:root{color-scheme:light dark;--bg:#f3f6fb;--card:#fff;--ink:#182a3c;--muted:#526579;--line:#d6e1ed;--accent:#185bb8}
@media(prefers-color-scheme:dark){:root{--bg:#101923;--card:#1b2938;--ink:#eef5fd;--muted:#b5c6d8;--line:#374b60;--accent:#8fbaff}}
*{box-sizing:border-box}body{margin:0;background:var(--bg);color:var(--ink);font:16px/1.5 system-ui,sans-serif}
main{max-width:1100px;margin:auto;padding:20px}h1{margin:0;font-size:26px}h2{font-size:21px}h3{font-size:17px;margin:0 0 10px}
a{color:var(--accent)}nav{display:flex;gap:16px;flex-wrap:wrap;margin-bottom:16px}.hint,small{color:var(--muted)}.hint{font-size:14px}
.layout{display:grid;grid-template-columns:300px minmax(0,1fr);gap:20px}.layout>*{min-width:0}.card,.skier{background:var(--card);border:1px solid var(--line);border-radius:14px;padding:16px;margin-bottom:12px}
.skier{display:block;text-decoration:none;color:var(--ink)}.skier.active{border:2px solid var(--accent);padding:15px}.skier strong,.skier small{display:block}
.time{font-size:28px;font-weight:750}.tags{display:flex;flex-wrap:wrap;gap:6px}.tag{border-radius:20px;padding:4px 10px;background:var(--bg);border:1px solid var(--line);font-size:14px}
.focus .tag{border-color:#b18a35}.comment{white-space:pre-wrap;overflow-wrap:anywhere}.live{color:#178047;font-weight:650}.label{font-size:13px;color:var(--muted);margin:12px 0 4px}
@media(max-width:700px){main{padding:12px}.layout{grid-template-columns:minmax(0,1fr)}.roster{display:flex;overflow-x:auto;gap:8px;padding-bottom:4px}.skier{min-width:190px;margin:0}h1{font-size:23px}}
</style></head><body><main>""")
    append("<nav><a href=\"/\">← Admin</a><a href=\"/logout\">Logga ut</a></nav><h1>Starttränare</h1>")
    append("<p class=\"hint\">Privat återkoppling från mål. Uppdateras automatiskt var femte sekund. <span id=\"connection\" role=\"status\">Ansluten</span></p>")
    append("<nav aria-label=\"Träningspass\">")
    sessions.forEach { append("<a href=\"${coachUrl(it.session.id)}\">${esc(it.session.name)} · ${esc(it.session.date)}</a>") }
    append("</nav>")
    if (envelope == null) {
        append("<p>Inga publicerade pass ännu.</p></main></body></html>")
        return@buildString
    }
    val ordered = predictedStartOrder(envelope, coaching)
    val names = coaching?.athletes?.associateBy { it.id }.orEmpty()
    fun name(athlete: PublishAthlete) = names[athlete.id]?.name?.takeIf { it.isNotBlank() }
        ?: athlete.displayName.takeIf { it.isNotBlank() } ?: "Nr ${athlete.bib}"
    val selected = ordered.firstOrNull { it.id == selectedId } ?: ordered.firstOrNull()
    val feedback = coaching?.runs?.associateBy { it.runId }.orEmpty()
    append("<section id=\"coach-content\" data-session=\"${esc(envelope.session.id)}\" data-revision=\"${envelope.revision}\" data-selected=\"${selected?.id ?: ""}\">")
    append("<h2>${esc(envelope.session.name)} · ${esc(envelope.session.date)} · <span class=\"live\">${if (envelope.session.closed) "Avslutat" else "Pågår"}</span></h2>")
    append("<p class=\"hint\">Förväntad returordning: inga åk först, sedan längst sedan senaste mål. Välj åkare för återkoppling. Ej deltagande visas sist.</p>")
    if (coaching == null) append("<p class=\"card\">Återkoppling saknas. Uppdatera surfplattans app och publicera passet igen för att visa taggar och kommentarer.</p>")
    append("<div class=\"layout\"><aside><h3>Åkare · förväntad ordning</h3><div class=\"roster\">")
    ordered.forEachIndexed { index, athlete ->
        val last = envelope.runs.filter { it.athleteId == athlete.id }.maxByOrNull { it.sequence }
        append("<a class=\"skier${if (athlete.id == selected?.id) " active" else ""}\" ${if (athlete.id == selected?.id) "aria-current=\"true\"" else ""} href=\"${coachUrl(envelope.session.id, athlete.id)}\">")
        append("<strong>${index + 1}. ${esc(name(athlete))}</strong><small>Nr ${athlete.bib}${if (names[athlete.id]?.participating == false) " · Ej deltagande" else ""}</small>")
        append("<small>${if (last == null) "Inga åk ännu" else "Senaste mål #${last.sequence} · ${runTime(last)}"}</small></a>")
    }
    append("</div></aside><section aria-label=\"Åkarens återkoppling\">")
    if (selected != null) {
        append("<h2>${esc(name(selected))} · nr ${selected.bib}</h2>")
        val runs = envelope.runs.filter { it.athleteId == selected.id }.sortedBy { it.sequence }
        if (runs.isEmpty()) append("<p class=\"card\">Inga åk registrerade ännu.</p>")
        runs.withIndex().reversed().forEach { (index, run) ->
            val info = feedback[run.id]
            val duration = run.durationMs
            append("<article class=\"card\"><h3>${if (index == runs.lastIndex) "Senaste åket · " else ""}Åk ${index + 1}</h3><div class=\"time\">${runTime(run)}</div>")
            if (run.status != RunStatus.COMPLETED && duration != null) append("<small>Uppmätt tid: ${formatSeconds(duration)} s · ingår inte i jämförelser</small>")
            val previous = runs.take(index).lastOrNull { it.status == RunStatus.COMPLETED && it.durationMs != null }
            val previousDuration = previous?.durationMs
            if (run.status == RunStatus.COMPLETED && duration != null && previousDuration != null)
                append("<p class=\"hint\">${formatDifference(duration - previousDuration)} jämfört med föregående fullföljda åk</p>")
            fun tags(label: String, values: List<String>, focus: Boolean = false) {
                append("<div class=\"label\">$label</div><div class=\"tags${if (focus) " focus" else ""}\">")
                if (values.isEmpty()) append("<small>Inget registrerat</small>")
                values.forEach { append("<span class=\"tag\">${esc(it)}</span>") }
                append("</div>")
            }
            tags("Beskriv åket", info?.observations.orEmpty())
            tags("Fokus nästa åk", info?.nextFocus.orEmpty(), true)
            tags("Ändringar inför åket", info?.changes.orEmpty())
            append("<div class=\"label\">Kommentar från mål</div><div class=\"comment\">${esc(info?.comment?.takeIf { it.isNotBlank() } ?: "Ingen kommentar")}</div></article>")
        }
    }
    append("</section></div></section></main>")
    append("""<script>
(function(){
  var busy=false;
  async function refresh(){
    if(busy || document.hidden)return;
    busy=true;
    try{
      var url=new URL(location.href), old=document.getElementById('coach-content');
      if(!url.searchParams.has('session'))url.searchParams.set('session',old.dataset.session);
      if(!url.searchParams.has('athlete') && old.dataset.selected)url.searchParams.set('athlete',old.dataset.selected);
      var response=await fetch(url,{cache:'no-store'});
      if(response.status===401){location.href='/';return;}
      if(!response.ok)throw new Error('network');
      var doc=new DOMParser().parseFromString(await response.text(),'text/html'), next=doc.getElementById('coach-content');
      if(!next)throw new Error('session');
      if(next.dataset.revision!==old.dataset.revision){
        var roster=old.querySelector('.roster'), left=roster.scrollLeft, top=window.scrollY;
        old.replaceWith(next);next.querySelector('.roster').scrollLeft=left;window.scrollTo(0,top);
      }
      document.getElementById('connection').textContent='Ansluten';
    }catch(e){document.getElementById('connection').textContent='Anslutningen bröts – försöker igen';}
    finally{busy=false;}
  }
  setInterval(refresh,5000);
})();
</script></body></html>""")
}

private fun runTime(run: PublishRun): String {
    val duration = run.durationMs
    return when {
    run.status == RunStatus.DNF -> "DNF"
    run.status == RunStatus.INCOMPLETE || run.durationMs == null -> "Ofullständigt"
    else -> "${formatSeconds(requireNotNull(duration))} s"
    }
}
