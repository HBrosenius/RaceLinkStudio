# Data protection and consent for the live pages

Implementation update: optional publishing now sends a separate private coach projection containing skier names, attendance, run tags in both categories, applicable change descriptions and comments. Only the password-protected `/coach` page renders it; personal skier pages and public APIs do not receive this feedback. The server can persist this data using `RACELINK_DATA_FILE`; restarting no longer deletes it when persistence is enabled. Retention and hard deletion include private feedback. Earlier statements below that notes/tags never leave the tablet or that a restart erases everything are superseded. The club's participant information and sharing decisions need to cover coach access as well as personal result pages.

What a club has to decide, write down and be able to show before it publishes a child's times to a
phone. It is written for the coach who runs the tablet, not for a lawyer: it says what the software
does, what the club must do around it, and where each claim comes from in the code.

It is **not legal advice**. It summarises the position as of October 2026 for a Swedish club using
GDPR (EU 2016/679), the Swedish Data Protection Act (2018:218) and the recommendations of
Integritetsskyddsmyndigheten (IMY). A club that is unsure — or that publishes to children younger
than 13, or runs events open to the public — should run this past whoever owns data protection in
the club or association before the first event.

Related: [09-live-publishing-server.md](09-live-publishing-server.md) is the technical description of
the service this document talks about.

## The short version

1. Publishing is **off by default** and is switched on by the coach, per event. An install that never
   switches it on stores nothing anywhere but the tablet.
2. Ask the guardian **before** the first publish, in writing, for the group being published. Keep the
   answer. A child old enough to understand should be asked too, and can say no.
3. Publish the least that does the job: **first name or bib**, no notes, no tags, no history, no
   leaderboard.
4. When consent is withdrawn, tap **Ta bort från webben** on the tablet. It deletes the pass and every
   link in it from the server and stops publishing, in one go.
5. Let the retention window do the rest: pages expire by themselves, and a restart of the server
   removes everything anyway, because the server keeps nothing on disk.

## What publishing actually exposes

The tablet sends one *projection* per session ([`PublishEnvelope`](shared/src/main/kotlin/se/racelink/publish/Publish.kt)).
It contains, and contains only:

| Field | Example | Note |
|---|---|---|
| Session name, date, mode, closed | `Torsdagsträning`, `2026-10-15` | The event, not personal data |
| Team, location, course, type | `Nybro SLK` | Club and venue |
| Athlete: id, bib, display name, class | `Alma`, `nr 12`, `H14` | The personal data |
| Athlete: link token | 32 hex characters | The page's own address |
| Runs: sequence, status, time, race round | run 3, completed, 34 320 ms | The skier's own times |
| Standings (race only): class, status, place, score | `H14`, placed 4 | Used to show the skier their own place |
| Course markers: label | `Portarna flyttade` | Carried, not currently shown on a skier page |
| Club logo (optional PNG), revision, published-at | | Branding and versioning |

Never sent: coaching notes, tags, the audit journal, the pending review queue, the session archive,
the roster with anything else in it, date of birth, contact details, photographs, and **any other
skier's times** — a training page shows the skier's own runs and nothing that could be used to rank
them against a friend. Race pages may show the skier's own class place, which is a result of an open
competition rather than a training comparison.

What a skier's phone then holds is a bookmarked URL. The page sets **no cookie and no local storage**,
loads nothing from any third party (no analytics, no external fonts, no CDN), and carries
`noindex, nofollow`. A login cookie is set on the coach's index page only, and only for the coach.

## Who is responsible for what

| Role | Who | Responsibility |
|---|---|---|
| Controller | The club that runs the event | Legal basis, consent, information to guardians, answering rights requests |
| Processor | Whoever operates the publishing server (often the club itself, or a hosting provider) | Runs it on the club's instructions, keeps the pages up, deletes when asked |
| The tablet | The club's own device | Holds the full record; nothing leaves it unless publishing is on |

If the club rents hosting or asks a parent to run the server, put the processor arrangement in
writing (GDPR Art. 28) — a short letter is enough: what is processed, for how long, who may see it,
what happens at the end, and that the host does not use it for anything else. If the server runs on
the venue's own laptop, the club is both roles and the arrangement is a note to itself.

**The server is a copy, never the record.** Deleting everything on the server costs the club nothing;
the tablet remains authoritative. That is the property that makes erasure cheap.

## Legal basis and the children

- **Publishing to a phone rests on consent**, GDPR Art. 6(1)(a), plus Art. 9 where a health detail
  could ever be inferred (it should not be — nothing here says anything about a child's health).
- Timing and coaching on the tablet itself is the club's own training activity; this document is
  about the part that leaves the tablet.
- **Age.** GDPR Art. 8 sets 16 as the age at which a child may consent to an information-society
  service, and lets a member state lower it to 13; Sweden has used that option. Treat 13 as the
  floor, not a target: even where the child may consent alone, a result page identifies the club and
  says where and when the child was training. **Ask the guardian for every child**, and ask the child
  too when they are old enough to understand. A "no" from either is a no.
- Consent must be **specific and informed**: which child, which event, what the page shows, how long
  it lasts, who will see the link, and that it can be withdrawn. A blanket "we may publish photos and
  results" in a season form is not specific enough for this.
- Consent is **freely given**: a child who does not consent must still be timed and coached exactly as
  before. Since the tablet works fully offline, that is a promise the club can actually keep — and the
  reason publishing can never be made a condition of taking part.

## Before the first event: a checklist

- [ ] The event's purpose is clear, and the club has decided that a phone page is worth it.
- [ ] Guardians of the group have been asked, in writing, with the text below.
- [ ] Answers are recorded (who, which child, when, what was agreed, how to reach them).
- [ ] The coach knows the **name policy** choice (first name, or bib only) and that bib-only is the
      safer default for a group the club does not know well.
- [ ] A password for the index has been set on the tablet (**Lösenord för länklistan**), so the list
      of every child's link cannot be opened from a phone in the stand.
- [ ] The server address starts with `https://`.
- [ ] Retention is understood: the default is 24 hours after the last publish, and `0` turns expiry
      off — a deliberate choice to be written down, because it makes the club the only deletion path.
- [ ] Someone knows how to withdraw (below), and how to reach whoever runs the server.

## Consent text the club can copy

Swedish, because it is the guardians who will read it. Fill in the brackets.

```text
Samtycke till att publicera resultat på webben – [förening], [evenemang], [datum]

Vi vill kunna visa ditt barn sina egna tider i mobilen under och efter träningen.
Sidan ligger på en egen adress och innehåller:
  • barnets förnamn (eller bara startnummer, om du hellre vill)
  • barnets egna tider och utveckling över passet
  • träningspassets namn, datum och förening
Sidan innehåller inga anteckningar, inga bilder, inga personnummer och inga andra barns tider.
Det finns ingen resultatlista och inget sätt att jämföra barnen på sidan.

Länken fungerar som ett lösenord. Den som har länken kan se sidan, så vi ber dig att
inte dela den vidare. Sidorna tas bort automatiskt [X timmar efter passet] och kan tas
bort direkt när du vill.

Barnet behöver inte vara med på sidan för att vara med på träningen. Det påverkar ingenting.
Du eller barnet kan när som helst säga till, så tar vi bort sidan direkt.

Barnets namn: ______________________________  Startnummer: __________

[ ] Ja, publicera barnets tider på webben, med förnamn.
[ ] Ja, men bara med startnummer.
[ ] Nej, publicera inte.

Vårdnadshavare: ______________________________  Datum: __________

Kontakt för frågor och för att ta bort sidan: ______________________________
```

A club that wants to publish only the adults' or the older group's results should say so in the same
form rather than treating silence as consent.

## Keeping the record

Write down, per event and per child: the answer, the date, who asked, how the guardian can be
reached, and the point at which any change was made. A spreadsheet in the club's own drive is
enough; the tablet is a timing device, not a consent register. Keep the record no longer than the
club needs to be able to show that consent was given.

## The rights, and how each one is served here

| Right | How it is served |
|---|---|
| Information (Art. 13/14) | The consent text above, and this document |
| Access (Art. 15) | The skier's own page is their access; a copy can be printed from the tablet's per-child report |
| Rectification (Art. 16) | Correct the run on the tablet; the next publish replaces the page within seconds |
| Erasure (Art. 17) | **Ta bort från webben** on the tablet deletes the session and every link; or `DELETE /api/v1/sessions/{id}` with the publish token; or the retention window; or simply stopping the server, which keeps nothing on disk |
| Restriction (Art. 18) | Switch publishing off: the tablet keeps the timing, nothing further is sent |
| Objection and withdrawal (Art. 7(3)) | As easy as consenting: one tap, no reason required, and the timing is unaffected |
| Portability (Art. 20) | CSV / PDF / image export from the tablet is the club's own copy |

Answer a request without undue delay and within one month. If a request arrives about one child in a
group that was published, delete the whole session and republish without that child — publishing is
per session, so a club that needs finer control should run one session per group.

## Withdrawing consent, step by step

1. Open the session on the tablet, **Inställningar → Publicering**.
2. Tap **Ta bort från webben** and confirm. The server drops the session and every link in it, and
   publishing is switched off at the same time, so the next timing change cannot put the pages back.
3. Say what happened to the guardian.
4. If publishing should continue for the rest of the group, switch it on again deliberately and press
   **Spara och publicera nu** — but only for children who have agreed.

If the server is unreachable, tell the guardian that, fix the link and repeat. Do not leave a page up
because the tablet could not reach the server: it can also be deleted from the server side with the
publish token, and in the end the retention window deletes it.

## Retention, deletion and the server

- The default retention is **24 hours from the last accepted publish** — effectively from the end of
  the pass — enforced on every request and by a sweep every 15 minutes. An expired session and its
  tokens are dropped together, so a stale link answers 404 rather than showing an old page.
- `RACELINK_RETENTION_HOURS=0` switches expiry off. That is a legitimate choice for a club that wants
  to delete by hand; it must be a written decision, because it removes the automatic backstop.
- The packaged server holds sessions **in memory only**. There is no database, no dump and no backup
  to forget about: restarting it deletes every page. A club that adds durable storage later inherits
  a backup-retention problem and should extend this document at the same time.
- Deletion is complete: the session, its projection, its per-skier tokens and its index password go
  together (`ProjectionStore.delete`). Nothing is kept "for statistics".

## Security, as data protection

The protection is the address, so the addresses have to stay secret and the service has to stay
boring:

- **Tokens, not ids.** A page lives at `/a/{token}` where the token is 128 bits derived from the
  session and athlete ids. Without the token a page does not exist; there is no directory to browse.
  The one call that returns a whole session — and therefore every token at once — needs the publish
  token, so a leaked session id alone exposes nothing.
- **The index is gated.** The page listing every child's link is not served over the network at all
  unless a coach has set a password, and it is reached through a login form whose reply is an
  `HttpOnly` cookie rather than the password itself. A shared tablet therefore cannot leave the
  password behind, and **Logga ut** revokes the login on the server as well.
- **Transport.** Serve the whole thing over HTTPS (a TLS terminator in front of the packaged server is
  enough); both the publish token and the index password travel in the clear over plain HTTP.
- **No tracking.** No analytics, no cookies on skier pages, no third-party requests, `noindex`.
- **Logs.** The application installs no request logging, so it does not write a visitor's address
  anywhere. The packaged server's logging is set to `INFO` in
  [`server/src/main/resources/logback.xml`](server/src/main/resources/logback.xml); anyone raising it
  to `DEBUG` for troubleshooting should know that Netty then prints connection internals to the
  console, and should not leave that on, nor keep console output longer than the troubleshooting.
- **Not a target.** The service holds no credentials, no payment data and no identifiers beyond the
  bib and first name, so a compromise of a page exposes one child's training times.

## What this design deliberately does not do

- No leaderboard, no class list, no "compare with friends" view — and no route that could grow into
  one. Children never receive a password, so they cannot reach the index. See the privacy bar in
  [09-live-publishing-server.md](09-live-publishing-server.md).
- No accounts for children, no logins, no app install, no push, no location tracking.
- No photographs, videos or free-text notes; a club that wants those is outside this document.
- No publishing to social media, and no "share" button on a skier's page.

## Risk assessment, and when to do a DPIA

The processing is small: a first name, a bib and one session's times, for the length of a training
pass, visible only to whoever holds a link. That makes a full data protection impact assessment
(Art. 35) hard to justify for a normal training group. But do write down a short assessment — the
purpose, the data, the risk of a leaked link, and the measures above — and do a full DPIA before:

- publishing to an event open to the public, or to a class where the club cannot name every guardian;
- publishing anything beyond times (photographs, health-related notes);
- any change that makes pages enumerable, searchable or lasting.

## If something goes wrong

A link posted where it should not have been, a mail to the wrong guardian, a stolen tablet with the
publish token: if it risks the rights of the children, the club must tell IMY within 72 hours of
becoming aware (Art. 33) and, if the risk is high, the guardians and children concerned (Art. 34).
Short of that, delete the pages (**Ta bort från webben**), note what happened and how it was fixed.

## Where the code backs this up

| Claim | Where |
|---|---|
| Publishing is off by default | `PublishConfig.enabled = false`; `PublishSettingsSection` starts unselected |
| The projection carries no notes, tags, audit or archive | `PublishEnvelope` / `PublishProjection`; `:shared` and `:app` projection tests |
| Only the skier's own runs are rendered | `PersonalView`, `personalView()`; server tests that a page never contains another skier |
| Retention, 0 disables it, tokens die with the session | `ProjectionStore.purge`, `ServerConfig.retentionHours`; retention tests |
| Hard delete and one-tap withdrawal | `DELETE /api/v1/sessions/{id}`, `TrainingViewModel.withdrawFromWeb`, `Publisher.withdraw`; server and `:app` tests |
| The index needs a password; the cookie is not the password | `IndexPassword`, `IndexSessions`, `POST /login`, `GET /logout`; `ServerTest`, `IndexAuthTest` |
| A whole session needs the publish token | `GET /api/v1/sessions/{id}` in `Server.kt` |
| Nothing on a skier page is a cookie or a third-party request | `Server.kt` sets cookies only on `/login` and `/logout`; the page is one self-contained document |
