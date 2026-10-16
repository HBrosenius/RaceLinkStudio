# Live publishing server

Deployment update: the Debian HTTP service and optional atomic file persistence are implemented; see [server operations](11-server-operations.md). `RACELINK_DATA_FILE` retains projections, receipt times and index-password hashes across restarts on a single instance. The older in-memory/Postgres planning notes below are superseded for this deployment. Public HTTPS is to be supplied by the operator's tunnel; managed hosting automation and scheduled external backups remain open.

Coach-page update: Admin now links to `/coach`, a password-protected start-coach view. Publishing sends a separate `PublishRequest.coaching` projection with skier names/attendance, run observations, next-focus tags, applicable changes and comments. This data is stored with the session but excluded from `PublishEnvelope` and all public skier responses. `/coach` requires the existing index login even for loopback requests and returns `Cache-Control: no-store`. Older clients omit the optional field and show an explicit missing-feedback message. Newer revisions replace feedback; stale revisions cannot replace it. Retention and deletion remove it with the session.

The skier list predicts return-to-start order using each skier's latest global finish sequence, ascending: no runs first (bib order), then the longest since finishing, with non-participants last. DNF/incomplete attempts also count as recent finishes. It is an estimate, not timing of lift travel. Coaches select any skier and see newest-first run history; five-second refreshes retain the session, selected skier and scroll position. On phones the skier list scrolls horizontally without widening the page. Coach access is read-only and does not change the tablet's next-skier assignment.

Status: plan confirmed, Phases 0–4 implemented (Phase 4 except the GDPR/consent write-up) — 16 October 2026. The wire contract (`:shared`), a read-only Kotlin/Ktor service (`:server`), the tablet-side projection, a best-effort publish path (`Publisher.kt`, `PublishConfig.kt`, `PublishTransport.kt`), a settings section, an offline QR pairing sheet (`QrCode.kt`, `PairingSheet.kt`), server-side retention, hard delete and a name policy, and background retry while the app is closed (`PublishRetryRunner.kt`, `PublishJobService.kt`) exist and are tested. Durable server persistence, per-event tokens and deployment are not built yet. See "Phases" and IMPLEMENTATION.md.

## Purpose

The authoritative tablet publishes a minimal, read-only projection of a session to a server. The server renders a fast mobile page so a skier can open **their own** result on a phone in the lift between runs:

- **Training:** latest completed time, the neutral change versus their previous completed run, and their best run. Personal only.
- **Race:** run 1, run 2, the score for the format and the current class place, labelled preliminary until the session closes.

This adds an optional egress path that the product documents previously excluded. It does not change the core promises: the tablet remains the sole authority (D03/D04), all capture, edits, reports and backups still work with no network (FR10), and an install that never configures publishing behaves exactly as before.

## Confirmed decisions

| # | Decision | Consequence |
|---|---|---|
| P1 | **Public HTTPS reachability** | A phone in the lift needs cellular, not venue Wi-Fi. The tablet only makes outbound calls. |
| P2 | **Managed container hosting** | One small container + database + automatic TLS; a venue Pi with a tunnel is the later self-host path. |
| P3 | **Per-skier QR / token links** | The tablet mints an unguessable per-athlete token; the skier scans once and bookmarks it. |
| P4 | **First-name default, coach-controlled** | Display names default to the first name; bib-only is available. No tags, notes or audit leave the tablet. |
| P5 | **Training first, race in Phase 2** | The personal training page ships before race standings. |
| P6 | **Kotlin + Ktor** | The server shares the `:shared` contract (and later the domain) with the app. |

## Topology

```text
  [Tablet: authority, offline-first]
    Training + Attempt + RaceConfig (SQLite v3 + audit journal)
        │  committed state change
        ▼
    PublishQueue (durable, network-constrained, retrying)   ← not built yet
        │  PublishRequest: PublishEnvelope + index password, idempotent by revision
        ▼  HTTPS + Bearer publish token (outbound only)
  [Server: derived projection, read-only]
    Ktor · Netty · in-memory now, Postgres later
        │
        ▼  public HTTPS, noindex
  [Skier phone]
    /a/{athleteToken}  → own result, auto-refreshing
```

## The publish contract

`:shared` (`se.racelink.publish`) is the single definition used by both sides. One `PublishEnvelope` describes one session at one `revision`; a publish wraps it in a `PublishRequest`, which is the envelope plus the session's link-index password — the one piece of operator state that has to travel with it, kept outside the projection because the projection is what any public read returns.

Invariants:

- **Projection, not backup.** No audit journal, coaching notes, tags, pending review events or nested session archive — and no credentials: the index password rides beside the envelope, never inside it.
- **Idempotent by revision.** The server keeps the highest revision per session id and rejects older ones, so a retry after a flaky link cannot move the page backwards. A stale publish cannot change the index password either, and `indexPassword: null` (an older tablet) leaves the stored one alone.
- **Stable ids.** `runs[].id` is the existing `Attempt.id`, so a correction updates one row rather than duplicating it.
- **Derived status.** `COMPLETED` (has a time), `DNF` (retained time, not finished) or `INCOMPLETE` (no usable time) — never a fabricated duration.
- **Server never derives from a payload it cannot check**, and never writes back to the tablet.

Endpoints:

| Method | Path | Auth | Purpose |
|---|---|---|---|
| `POST` | `/api/v1/publish` | publish token | Idempotent upsert of one session projection |
| `DELETE` | `/api/v1/sessions/{id}` | publish token | Hard delete one session and its tokens (consent withdrawal / accidental publish) |
| `GET` | `/api/v1/sessions/{id}` | publish token | Current stored snapshot, for whoever holds the token (it carries every skier's link at once, so it is not public) |
| `GET` | `/api/v1/athletes/{token}` | public | Small JSON the page polls for live updates |
| `GET` | `/a/{token}` | public | The personal page a skier bookmarks |
| `GET` | `/` | login cookie, session password, operator password or loopback | Plain status line for anything on the network; the live per-skier link index only for a loopback request or a signed-in coach |
| `POST` | `/login` | session or operator password | The coach's way in: trades the password for an HttpOnly cookie |
| `GET` | `/logout` | login cookie | Revokes that login here and clears the cookie |
| `GET` | `/healthz` | none | Liveness |

Configuration is environment-only: `RACELINK_PUBLISH_TOKEN` (fallback `dev-token` prints a startup warning), `RACELINK_RETENTION_HOURS` (default 24; **0 disables expiry**), `RACELINK_NAME_POLICY` (`first`, the default, or `bib`), and the optional `RACELINK_ADMIN_PASSWORD`, which is an operator override for the link index rather than the normal path. Retention is measured from the last accepted publish — so effectively from session close — and is enforced on every request plus a 15-minute sweep; expiry drops the session and its token index together, so a stale link 404s rather than serving a page.

Because the token is the only thing protecting a page, `GET /` never hands out the index over the network. With no password anywhere it is served to a loopback request only, which keeps an install that never sets one exactly as it was. Otherwise it serves a **login page** first and needs a password **from any host**, so the coach can open it from a laptop or phone while a child cannot:

- **The password is set per session on the tablet** (Inställningar → Publicering → *Lösenord för länklistan*), stored against the session id, and travels with that session's publish. A new event therefore starts from no password instead of inheriting the previous group's, which is the whole point of the gate. Clearing the field publishes an empty value, which removes the protection again — the tablet sends the field on every publish precisely so that "off" is expressible.
- **The form, not the browser's Basic dialog.** The coach types the password into a page that posts to `/login`, and the reply is an opaque 128-bit random cookie (`racelink_index`, `HttpOnly`, `SameSite=Strict`, 12 hours, `Secure` when the request arrived over HTTPS). A shared tablet therefore never caches the password itself, and **Logga ut** on the index clears the cookie *and* revokes the token server-side, so a copy the browser kept is worth nothing. Passing `X-Forwarded-Proto: https` (what a TLS terminator sends) is what earns the `Secure` attribute; a plain-HTTP development server would otherwise drop the cookie and lock the coach out.
- **Nothing is ever challenged with `WWW-Authenticate`.** HTTP Basic is still accepted *silently* for a script (`curl -u :password http://host/`), but `GET /` never invites the browser's native dialog — that prompt is exactly what makes a browser store the password for the rest of the day.
- **Wrong passwords are throttled** per client address (8 inside 5 minutes → `429`), so the short shared secret cannot be guessed through the form. A correct password is always honoured and clears the count: the lockout only ever refuses attempts that were wrong anyway, so a flood cannot shut the coach out.
- Any live session's password opens the index, so the coach is not locked out between passes. It is a single shared secret per event; the operator password below shows everything regardless.
- An operator can also set `RACELINK_ADMIN_PASSWORD`, for a server run by someone who is not holding the tablet.
- Only the password is checked, never the username: it is one shared secret, so there is nothing else for the coach to remember or mistype. Both the stored digest and the operator password are compared with `MessageDigest.isEqual`, and both checks always run.
- The store keeps only a **per-session salted SHA-256** of the password, so the server never holds the password itself and two sessions that happen to pick the same one do not share a digest. Deleting or expiring a session takes its password with it, and logins live only in memory — a restart signs everyone out.
- The password is still only as private as the transport: over plain HTTP the form body carries it in the clear, so a real deployment must add HTTPS termination.

## Tablet publisher

- `PublishTransport` posts one Bearer-authenticated `POST` over a plain `HttpURLConnection` (no extra networking dependency) with a bounded connect/read timeout.
- `Publisher` sends the shared projection and keeps a **monotonic per-session revision** plus a persisted **pending** marker (`PublishStateStore`), so a failed publish is retried on the next commit or app start and a late reply can never move the page backwards.
- Wired into `TrainingViewModel`: publishing runs after each committed write, on restore and at startup, always **outside** the store's write lock and serialised by its own `Mutex`, so it can never delay capture, edits or export.
- Settings section (**Inställningar → Publicering**): server URL, token, on/off (**off by default**), link-index password, **Spara och publicera nu**, **Koppla åkare (QR-koder)**, last-published time and status.
- **Ta bort från webben** takes the pass off the web in one tap: it deletes the session and every skier link on the server (also on a `404` — a session that is already gone is the state being asked for) **and switches publishing off in the same breath**, because the app republishes on every committed edit and a delete alone would come straight back with the next timing change. A failure leaves publishing on and says the page may still be there, so the coach can retry; a pass that is removed while a publish is outstanding clears the waiting marker, since the page it was waiting for no longer exists. This is what makes consent withdrawal possible without the person who runs the server.
- Pairing is offline: the **Koppla åkare** sheet renders each skier's `{base}/a/{token}` link as a QR code (`QrCode.kt` uses ZXing; `PairingSheet.kt` paints one-pixel modules nearest-neighbour), with copy-link and share-all. The token is the same deterministic one the envelope carries, so the code points at the right personal page.
- The published projection is the privacy-filtered one: personal times only for training, no notes, tags, audit, pending queue or archive.
- **What a skier sees is only ever their own:** latest completed run and the change against their previous one, their personal best, and a trend chart of their own runs in order (SVG, repainted from the polled JSON so it stays live; every point is labelled with its time, the fastest run sits highest under a dashed best line, and the x axis is the skier's own run order, not the session-wide sequence). The chart only appears from two runs on, and once a session has more than eight runs it grows wider than the card and scrolls, so no time is dropped or overlapped; the note says so. Race pages add their own run 1/run 2, score and class place. No page shows another skier's name, time or link, so nothing on the site can be used to compare children — the one page that does list everyone is the index, which is password-gated and is never linked from a skier page.
- **Name policy, coach-controlled:** the tablet sends the first name by default and can send **bib only**; the server can additionally force `bib` for the whole venue via `RACELINK_NAME_POLICY`. Under `bib` the page shows only `nr <bib>`.
- **Background retry and resume.** An outstanding publish is handed to the platform `JobScheduler` so it can still flush while the app is closed, and it is also attempted immediately whenever the app returns to the foreground. The publish-now-versus-retry-later decision lives in `PublishRetryRunner`, unit-tested off-device; WorkManager was rejected because it pulls in Room. The job is network-constrained with exponential backoff under one job id, so repeated failures cannot reset the backoff, and it is **expedited** on API 31+ (`setExpedited`), because a plain no-deadline job is deferrable: measured on the emulator, the job sat `Ready` with every constraint satisfied and its earliest run time already past, yet did not execute for minutes. Expedited and persisted are mutually exclusive, so the job no longer survives a reboot; that is acceptable because the publisher also sends the current snapshot on every app start. The connectivity constraint requires `ACCESS_NETWORK_STATE`, and a scheduling failure is logged rather than swallowed. Because the platform still has final say over when the job runs, a publish that is still outstanding when the tablet next comes back is also retried directly: `TrainingViewModel.refreshPublishStatus()` runs from `MainActivity.onResume()`, re-reads the journal so the settings screen cannot keep showing a stale "waiting" or failure line after a background retry has already succeeded, and re-attempts the publish in the foreground.
- **Remaining:** a printable QR variant through the PNG/PDF pipeline.

## Privacy and security

This is data about children on the open internet. Minimum bar before any real event:

- Opt-in per event, by the coach, with explicit copy about what is exposed.
- Data minimisation: no notes, no tags, no audit, no date of birth; first name or bib only.
- Unguessable tokens (≥128-bit) in URLs; no sequential ids; a `noindex, nofollow` meta tag on every page. The only read that returns a whole session — and so every skier's token at once — is `GET /api/v1/sessions/{id}`, which needs the publish token; without it, the most anyone can learn about a token is whether that one page exists.
- **No shared view for children.** A skier page is scoped to its own token, never links anywhere else on the site, and there is no leaderboard in training. The only page that lists everyone is the operator index, which needs the password (or a loopback request) and is documented as the one page that must not reach a child's phone.
- Retention TTL (auto-delete after session close) plus a hard delete path. **Implemented:** `RACELINK_RETENTION_HOURS` expiry (per-request and swept), `DELETE /api/v1/sessions/{id}`, and a one-tap **Ta bort från webben** on the tablet that also stops republishing.
- Tenant-scoped publish tokens, rotated per event; HTTPS only. **Partially:** one publish token plus retention/name policy, and the link-index password is now per session (so it rotates with the event); per-event publish tokens and TLS termination remain.
- A documented GDPR / consent position for minors. **Written down:** see [10-gdpr-and-consent.md](10-gdpr-and-consent.md) — legal basis, data minimisation, a consent template the club can copy, the rights and how each is served, and the withdrawal procedure the tablet now implements.

## Phases

| Phase | Deliverable | Status |
|---|---|---|
| 0 | Contract, server, tablet projection, tests | **Done** |
| 1 | Tablet publish path + settings + QR pairing + training page | **Done** |
| 2 | Race standings, class view, preliminary/final labelling | Class place/score + closed badge **done**; explicit prelim/final wording remains |
| 3 | Background retry while closed, replay/idempotency across outages | **Done** — revision idempotency, retry on commit/start, JobScheduler background retry and a pending/status UI |
| 4 | Retention/TTL, hard delete, name policy, consent | **Done** — TTL, hard delete, name policy, one-tap withdrawal from the tablet, and the consent write-up in [10-gdpr-and-consent.md](10-gdpr-and-consent.md) |
| 5 | Managed deploy automation, monitoring, server backups, load test | Planned |

## Testing

- `:shared` — contract round-trip, forward-compatible decoding, neutral delta wording, and that a `PublishRequest` keeps "no password", "empty password" (clear it) and a real password apart.
- `:server` — token enforcement, newer-revision-wins / stale-rejection, personal-page rendering, race place and score, 404s, noindex, no cross-skier leakage, plus retention expiry and stale-revision-does-not-extend-retention (with an injected clock), hard delete, and the bib name policy. The link index is covered for every path: the login form (no `WWW-Authenticate` ever, the links never served before signing in), the cookie it sets (`HttpOnly`, `SameSite=Strict`, `Max-Age`, no `Secure` on plain HTTP), a wrong password answering `401` with the form and no cookie, the per-session password from the tablet (UTF-8 included), logging out clearing the cookie *and* revoking the token, the operator password working for a script, clearing the password, a publish that omits the field leaving it alone, an unknown session, deletion taking the password with it, the per-session salted digest, and that a skier page never links to `/`. `IndexAuthTest` covers the login TTL, revocation, 128-bit uniqueness and the throttle's per-address window and clearing in isolation. The chart is covered for one labelled point per run, its widening, and its "drag to see all times" note.
- `:app` — projection content, DNF/incomplete status derivation, deterministic tokens, race standings parity with the tablet; the publisher's off/on/stale/failure paths, revision monotonicity and that the session's password is sent beside the projection (and nobody else's) with in-memory fakes; the withdrawal path (one `DELETE` to the session's own URL, a refusal before any traffic when there is no server, a failure that keeps the pending marker and says the page may remain); the per-session password store against real `SharedPreferences` under Robolectric; the retry runner's skip/publish/retry decisions; the real `HttpURLConnection` transport against a loopback server (method, headers, JSON body, ack parsing, auth hint, unreachable host, and `DELETE` with `404` treated as already gone); the QR pairing URL shape and an encode→decode round trip proving the code carries the personal link.
- Verified end to end: the packaged server boots, accepts a publish and renders the personal page with race place and score (manual smoke run).
- Verified on an emulator (API 35) against a host-run server: publish over `http://10.0.2.2:8080`, the personal page showing that skier's own times and no other skier, the QR sheet's per-skier links resolving to the same pages, a publish into a dead link leaving a pending marker and a scheduled job, and that job flushing automatically once the link returned while the app was backgrounded — the last two exercised on-device because they depend on platform permission and scheduling behaviour that JVM tests cannot see.
- Verified on the emulator against the packaged server: the coach's own password typed into the tablet's *Lösenord för länklistan* field protects `GET /` (401 without it with a `WWW-Authenticate` challenge, 200 with it and no username required, 401 with a wrong one), a second session's password also opens it, deleting a session removes its page and its password, and the chart's 13 labelled times are identical in the server-rendered SVG and after the JavaScript repaint (checked in a desktop browser: `min-width:598px`, 13 labels, one per point, each sitting 9 units above its dot).
- Local HTTP works only in debug builds: `app/src/debug/res/xml/network_security_config.xml` permits cleartext to `10.0.2.2`, `127.0.0.1` and `localhost` (all three are the developer machine as seen from the emulator). Release builds keep the platform default, so publishing over the wire stays HTTPS-only.

## Risks

| Risk | Mitigation |
|---|---|
| Mountain connectivity | Outbound queue + retries; 3G-sized page; on-site Pi + tunnel path |
| Server becomes a dependency | Async publisher only; capture and UI never wait; default off |
| Tablet/server divergence | Revision-monotonic; tablet wins; rebuild from next snapshot |
| Children's data exposure | Opt-in, minimisation, tokens, TTL, tenant isolation, consent review |
| Scope creep into "official results" | Keep the *preliminärt* label; D01 stands |
