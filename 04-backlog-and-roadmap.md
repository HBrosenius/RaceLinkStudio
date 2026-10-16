# Prioritized MVP backlog and roadmap

Android standalone baseline. P0 is required for trustworthy training; conditional P0 applies only to selected topology or hardware release. A first native simulator demo is implemented; no full production phase is declared complete.

## Implementation progress — 14 October 2026

The debug APK builds and six domain tests pass. Initial slices of B03/B04/B07/B08/B09/B18/B19 are available: domain model, deterministic adapter, Swedish dashboard, personal charts, correction audit, local save, CSV, tags, team name and course markers. Real-device lifecycle validation, robust evidence journaling, roster/session management, full reports, backup/restore, logo import and notification sound remain outstanding. B02 and production reliability gates are not yet complete. See IMPLEMENTATION.md for details.

## Current implementation status — 15 October 2026

The earlier progress paragraph is historical. The native demo now includes session/roster management and archived snapshots, correction history, review, multi-session/date/skier PDF/CSV reports with graphs, individual PNG reports, optional device-local logos, explicit Android sharing, backup/restore with freshness, optional device-local result sound and validated roster CSV import. Sound is off by default; report tags/notes remain opt-in. Import previews conflicts and adds identities without changing current attendance or historical runs. Build and 102 tests pass. A physical tablet coaching rehearsal and separate emulator import/export/accessibility checks are recorded in 06-ui-cleanup-and-feature-plan.md. B02/B05/B10 production reliability and B12/B13 timer/field gates remain open; these delivered simulator features do not establish field readiness.

## Live publishing — 16 October 2026

Optional read-only publishing to skier phones was planned (D07/FR17) and is now largely implemented: a shared wire contract (`:shared`), a Kotlin/Ktor service (`:server`) that accepts idempotent, revision-stamped session projections and renders token-scoped personal pages (training plus race place/score), and a tablet-side publisher wired into the view model (`Publish.kt`, `Publisher.kt`, `PublishConfig.kt`, `PublishTransport.kt`) with a persisted pending marker, a monotonic revision, an off-by-default settings section and an offline per-skier QR pairing sheet, plus server-side retention/TTL, hard delete, a first-name/bib name policy, a background retry that flushes an outstanding publish while the app is closed and again on the next resume, a per-skier trend chart of the skier's own runs with every point labelled by its time, and a link index that the coach locks with a password set per session on the tablet (or that stays loopback-only when none is set). Per-event publish tokens, durable server persistence, the GDPR/consent write-up and deployment remain open. See [09-live-publishing-server.md](09-live-publishing-server.md).

## Backlog

| ID | Priority | Deliverable | Depends on | Acceptance |
|---|---|---|---|---|
| B01 | Complete for product scope | Feature/design interview | User input | Workflow, comparison rules, approximately 10-inch tablet beside timer, and tablet-only viewing confirmed; exact device remains open. |
| B02 | P0 | Android platform/lifecycle spike | B01 | Target tablet runs local storage and a simulated capture service through rotation, lock and background; interruption states visible. |
| B03 | P0 | Domain schema and timing semantics | B01 | Exact durations, repeated attempts, uncertain identity and revisions modeled. |
| B04 | P0 | Adapter contract and simulator | B03 | Deterministic full-event and result-only profiles with capability labels. |
| B05 | P0 | Durable local ingest and journal | B02–B04 | Crash/replay fixtures preserve committed results without duplication. |
| B06 | P0 | Session/roster/source binding | B05 | Import validation, resume and historical-record isolation work. |
| B07 | P0 | Swedish personal coaching dashboard | B06 | Large chart/compact table, actual-time default and chart toggle, previous-completed-run delta, neutral highlights, blue accents, theme switch and noninterrupting finish banner pass rehearsal. |
| B08 | P0 | Comparisons and corrections | B07 | Approved best/delta rules; all edits auditable and immediately saved offline. |
| B09 | P0 | Reports, CSV, backup/restore | B05, B08, B18 | Individual image/PDF and whole-session report with per-child chart/history and no rankings; tags/notes off by default; branding included; backup restores complete history. |
| B10 | P0 | Lifecycle and operational hardening | B02, B05–B09 | Permission, disk, battery, process and recovery tests pass on target device. |
| B11 | Deferred | Optional Pi gateway | Future distance need | Separate future scope; not required beside timer. |
| B12 | Hardware-release P0 | Exact-model BLE decoder | Hardware available, B04 | Verified fields/capabilities and reference fixtures; no invented protocol. |
| B13 | Field-release P0 | Bench and field validation | B10, B12, B11 if selected | Reference attempts, soak, power and recovery pass. |
| B14 | Deferred | Read-only live viewers | Future scope | Initial release is tablet-only. |
| B15 | P0 | Reusable team roster | B03, before B06 | Regular bib/name mapping, today's participant selection and historical session snapshots. |
| B16 | Deferred | Split comparisons | Future need | No split timing in current workflow. |
| B17 | P2 | Formal races, dual lanes, cloud access | Separate specification | Approved rules and reliability requirements. |
| B18 | P0 | Tags, notes and team identity | B03, B07 | Five initial Swedish tags, add/rename with historical label updates, optional notes, team name/logo. |
| B19 | P0 | Course-change markers and prior sessions | B06, B08 | Continue within-session comparisons with visible change markers; earlier sessions open separately. |
| B20 | Done | Publishing wire contract and name policy | B03 | `PublishEnvelope` shared by app and server; no notes/tags/audit published; deterministic per-athlete tokens; first-name default. |
| B21 | Done | Read-only publishing service | B20 | Idempotent publish by revision; personal page renders own data only; noindex; tests for auth, stale revisions and no cross-skier leakage. |
| B22 | Done | Tablet publish queue and settings | B21, B05 | Best-effort publish after each commit and at startup with monotonic revision, persisted pending marker, a "Spara och publicera nu" settings section and an offline QR pairing sheet; default off, never blocking capture. Failures are retried in the background by a network-constrained, expedited JobScheduler job and again immediately in the foreground when the app returns; the settings status refreshes on resume. |
| B23 | Partial | Race publishing | B22, B17 rules | Class place and score plus a Pågår/Avslutad badge render on the personal page with parity tests; explicit preliminära/finalised wording remains. |
| B24 | Partial | Privacy, retention and consent | B22 | TTL (`RACELINK_RETENTION_HOURS`), hard delete (`DELETE /api/v1/sessions/{id}`) and a first-name/bib name policy are done; noindex already in place, and the link index that lists everyone is locked by a per-session password the coach sets on the tablet (any password opens a live session's index; the store keeps only a salted digest). Per-event tenant tokens and the documented consent position for minors remain. |
| B25 | Planned | Managed deployment and operations | B24 | Container deploy, TLS, monitoring, server backup, load test at the stated ceiling. |

## Phases and exit gates

1. **Product definition — interview complete.** Consolidated user choices are recorded. Next demonstrate the tablet screen design; validate the exact model/OS before target-device testing. Exact RL mode is a hardware investigation item.
2. **Android foundation and simulator.** B02–B05. Demonstrate durable synthetic capture and tablet lifecycle behavior. Exit: crash-safe local result and honest capability display; no hardware required.
3. **Tablet training MVP.** B15, B06–B09, B18–B19. Coach rehearses setup, repeated runs, review, corrections, tags and branded exports without internet. Exit: whole simulated session explainable from evidence.
4. **Reliability.** B10. Exercise direct-BLE lifecycle/fault matrix, external backup and eight-hour soak. Gateway development is deferred. Exit: selected topology meets acceptance targets on actual device.
5. **Hardware integration when available.** B12, bench portion of B13. Discovery, labeled captures and decoder. Exit: at least 100 varied reference attempts, verified capabilities and documented recovery limits.
6. **Supervised field pilot.** B13 and selected viewer scope. Parallel reference record, realistic power and distance tests, recovery guide. Exit: coach acceptance and explicit supported tablet/Android/RL firmware list.

Hardware research may start earlier when equipment arrives but cannot bypass reliability gates. Estimates follow the workflow/device spike rather than speculative dates. If hardware permits only pull results, revise the live promise explicitly before release.

## Main risks

- Unknown proprietary protocol: isolate and gate hardware release on evidence.
- Android lifecycle interrupts capture: native service plus target-device tests, durable storage and visible gap reporting; no unconditional background guarantee.
- Tablet is sole authority and can be lost or depleted: external backup, tested power plan and optional independently powered gateway.
- Tablet remains far from timer: nearby gateway and independently validated local backhaul.
- Incorrect athlete matching: separate operator intent from evidence; review ambiguity.
- Scope drifts into race certification: retain training scope until new rules are specified.

## Superseded work

Windows service packaging, LAN HTTPS certificate onboarding for the operator, React PWA operator caching, Windows auto-start and server-authorized operator leases are removed from this roadmap. React/browser technology may be considered separately for optional viewers. The native Android demo is implemented; production lifecycle and hardware validation remain open.
