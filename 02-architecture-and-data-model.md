# Architecture and data model

Standalone Android tablet is the authority. This replaces the Windows service, React operator PWA, LAN certificate onboarding and server-dependent operator workflow. The first native demo is implemented; this document describes the target architecture, while IMPLEMENTATION.md records current scope and the temporary SQLite snapshot approach. Interview baseline: approximately 10-inch tablet beside timer; direct BLE, multiple skiers, no splits, no remote viewers. Pi and publishing modules below are future extension boundaries, not initial deliverables.

## Topology

```text
Brower start / split / finish equipment → existing Brower radio → RL Timer/interface
                                                                     │
                                    nearby direct BLE ────────────────┤
                                             │                       │
                                             ▼                 optional nearby Pi
                                      Android tablet            BLE + durable spool
                                      native capture ◄──────── local IP backhaul
                                             │
                               adapter → evidence/journal → Room/SQLite
                                             │
                                      tablet UI/results

Simulator → same normalized adapter contract
Optional read-only skier pages ← publishing service (see 09-live-publishing-server.md)
```

Place the tablet close enough for tested BLE operation. If it must remain 50–100 m away, place the Pi near the timer and field-test a separate IP backhaul. Do not assume BLE covers that distance. Only one receiver owns the physical timer connection at a time; switching topology requires source reconciliation.

## Proposed stack and boundaries

| Component | Responsibility |
|---|---|
| Kotlin + Jetpack Compose | Tablet layouts and observable local state |
| Android native capture service | BLE connection lifecycle, background capture and source health |
| Room over SQLite | Authoritative local evidence, journal, revisions and projections |
| Domain/repository layer | Exact timing, association, corrections, ranking and review |
| Hardware adapter | Unknown protocol decoding, capability reporting and optional history retrieval |
| Simulator adapter | Deterministic synthetic observations and fault profiles |
| Optional Pi gateway | Native BLE adapter, durable spool, authenticated local transport |
| Export/backup module | Consistent portable backups and user-selected file exports |
| Publishing service (optional) | Read-only, revision-stamped projection of a session to a token-scoped public mobile page; Kotlin/Ktor on the JVM, sharing the wire contract with the app; never an authority. Pairing is offline: the tablet renders each athlete's link as a QR code (ZXing, pure JVM) for the skier's phone |

These technologies are proposed, not implemented. The first spike validates the actual tablet and Android version before pinning dependencies. The UI reads the local database as its source of truth, consistent with [Android offline-first guidance](https://developer.android.com/topic/architecture/data-layer/offline-first).

## Android lifecycle and BLE

Start a user-visible capture session from the foreground. Plan an appropriately declared connected-device foreground service for ongoing BLE capture, with required version-specific Bluetooth permissions and a clear persistent notification. Keep capture independent of activity recreation. Validate permission denial/revocation, Bluetooth toggle, screen lock, app background, power-saving modes and manufacturer behavior. [Android BLE background guidance](https://developer.android.com/develop/connectivity/bluetooth/ble/background) and [foreground service requirements](https://developer.android.com/develop/background-work/services/fgs/service-types) inform this design.

A foreground service is not a guarantee against force-stop, device shutdown or process termination. Persist before reporting success; recover durable state on launch and disclose capture gaps. Do not use periodic background jobs as the live timing loop. Schedule nonurgent backup work separately. Do not silently restart an active hardware session after reboot without a verified lifecycle design and readiness check.

## Adapter contract

Expose identity/version, connect/disconnect, health, an observation stream, capabilities and optional history retrieval. Initially read-only. Capabilities independently report unknown/supported/unsupported: starts, splits, completed results, bibs, record IDs, sequences, history, source timestamps, elapsed duration and status changes.

```text
schemaVersion, observationId, adapterId, adapterVersion
sourceDeviceId, sourceEpoch, gatewayId?, gatewaySequence?
deviceRecordId?, deviceSequence?, deviceRunKey?
kind: start | split | finish | result | status | unknown
sourceTimestampRaw?, clockDomain?, clockEpoch?, sourceResolutionUs?
durationUs?, bibRaw?, splitIndex?, lane?, sourceStatusRaw?
receivedAtUtc, receivedMonotonic?, rawFrameRef, parseStatus
capabilityProfileId, simulated, payload
```

Generate observationId once at first durable capture; reuse it for retries. Preserve raw frames and decoder version. Unknown fields remain unknown. An unresolved reset epoch is flagged rather than guessed. Simulated observations cannot silently enter hardware sessions.

## Persistence, replay and timing

Direct capture commits evidence, journal entry and projection/review result transactionally before showing durable success. UI observes committed local state. Configure and test SQLite durability; migrations require a recoverable backup. An application database cannot prevent loss of an event the OS/hardware never delivered.

If using Pi, spool before forwarding. Tablet ACK follows durable commit. Use a contiguous ACK watermark; retain holes and unacknowledged records. Tablet initiates an authenticated connection to the paired Pi where practical, avoiding a requirement to host a network server on Android. Resolve exact transport/security in the gateway spike. Source/session binding survives link outages; late/unbound records enter review.

Transport deduplication uses observation identity. Hardware deduplication needs verified record identity scoped to device and epoch. Hashes, equal times and bibs alone are insufficient: two identical genuine attempts must survive. Command IDs make UI retries idempotent; expected revisions reject stale corrections.

Store source precision and raw values. Normalize duration to integer microseconds without claiming microsecond accuracy. Prefer verified device durations; derive only from compatible device timestamps with known epoch/rollover rules. Never derive race time from network arrival or Android wall-clock time. Running animations are estimated and cannot become results. Completed-result-only adapters show no fabricated starts.

Compare each child's eligible completed runs within the session, including across setup changes with explicit chart markers. No child-to-child rankings. Primary delta uses the most recent completed eligible run; improvement-chart baseline is the first completed eligible run. Reassignments/corrections recalculate affected histories. Preserve original evidence and every correction. Unknown source/session association is reviewable, not silently assigned.

## Interview-driven model additions

- Team: stable id, name and optional locally stored logo asset. Athlete: add team membership and regular bib; SessionEntry retains its own bib/name snapshot.
- CoachingTag: stable id, editable current label and active flag. RunTag links attempt and tag IDs; renaming changes labels for past and future displays without modifying timing evidence. Keep a tag edit audit.
- RunNote: attemptId, text, revision and actor; separate from result status and timing validity.
- SessionMarker: sessionId, event position/time, description and optional setup-version reference. Course changes mark the chart without splitting the child's comparison series.
- AppPreferences: theme, sound enabled and Swedish locale. Results view state: selected athlete, actual/improvement chart mode and waiting finish IDs. Opening uses actual-time mode and latest finisher; incoming finishes do not change selection.
- ExportSnapshot: add selected athlete/session scope, image/PDF/CSV format, includeTags/includeNotes flags (false by default for shared summaries), team identity snapshot and revision cursor. Already generated files remain immutable. CSV field scope is explicitly documented in the export preview.

Backups include roster, notes, tag definitions/links, branding and markers as well as raw timing evidence. Unverified statuses never become completed results merely to populate a chart. Empty/incomplete values are gaps, not zero durations.

## Data model

UUIDs identify domain entities. Foreign keys and unique constraints protect relationships; raw evidence and journal entries are append-only. Projections can be rebuilt.

| Entity | Essential fields / constraints |
|---|---|
| Athlete | id, displayName, optional team/category; no bib as global identity |
| Session | id, name, venue, localDate, timezone, state, courseVersionId, revision, sourceMode |
| CourseVersion | id, courseName, setupNotes, comparisonGroup; immutable once used |
| SessionEntry | id, sessionId, athleteId, bib, order, roster snapshot; unique(sessionId,bib) |
| SourceDevice | id, model, firmware, verified capabilities, adapter profile |
| SourceBinding | id, deviceId, sessionId, sourceEpoch, baseline/cutover evidence, validity period |
| CaptureEpoch | id, sourceId, reset evidence, confidence, startedAt; no inference from timestamp alone |
| RawFrame | id, sourceId, captureSequence, capturedAt, payload bytes, hash, adapterVersion |
| Observation | envelope above; unique(adapterId,observationId); nullable device identity unique only when verified |
| ObservationLink | observationId, attemptId, role, revision, decision evidence; supports reviewed reassignment |
| Attempt | id, sessionEntryId nullable, sessionId, runOrdinal, lane nullable, captureState, resultStatus, reviewState, currentRevision |
| TimingValue | id, attemptId, kind, splitIndex nullable, durationUs/raw ticks, resolution, clock domain, source observation |
| ResultRevision | id, attemptId, revisionNumber, previousRevisionId, duration/status/assignment snapshot, origin, actor, reason, createdAt |
| ReviewItem | id, sessionId, observation/attempt refs, reason, candidate matches, resolution, resolver |
| JournalEvent | journalCursor, eventId, type, entityId, revision, payload, committedAt |
| CommandReceipt | commandId unique, clientId, expectedRevision, fencingToken, outcome, resultingCursor |
| OperatorAuthority | tabletId, sessionId, operator identity; future remote ownership requires fencing |
| ClientSync | clientId, sessionId, lastAppliedCursor; optional viewer synchronization metadata |
| ExportSnapshot | id, sessionId, journalCursor, format, createdAt, warnings, file checksum |

Relationships: Session has many SessionEntries; Athlete has many SessionEntries; SessionEntry has many Attempts; Attempt has many ResultRevisions and linked Observations. Unassigned Attempts still belong to a session; unbound Observations can exist before a session is known.

Use three separate state dimensions: captureState (planned/running/completed/unknown), resultStatus (pending/finished/DNS/DNF/DSQ/void), reviewState (clear/needs_review/resolved). An orphan finish need not pretend it started; a result-only record can complete an attempt without a start event. DSQ, reassignment and reversal append revisions instead of destroying the original result.


## Sharing and operational design

The tablet operator writes directly to local storage without a lease from any other machine. Remote editing is outside initial scope. If live viewers are required, select their delivery method and local transport in a separate feasibility step; lack of viewers must never stop capture. Use replay cursors and consistent snapshots for any viewer stream, with explicit stale status after disconnect. The selected method is a read-only publishing service over public HTTPS ([09-live-publishing-server.md](09-live-publishing-server.md)): the tablet pushes idempotent, revision-stamped session projections outbound, and skiers open token-scoped personal pages. It is a derived projection, never an authority — loss of the server or its link never affects capture.

Keep data in app-private storage. Export via Android's user-selected document destination. Backups must include consistent database state, raw evidence and manifest; test restoration on a fresh installation. A CSV is not a complete backup. Tablet loss/uninstall can remove the authoritative data, so rehearse external backup before a field release.

Show BLE health, gateway health if present, last observation, queue depth, parse failures, storage and battery. Do not equate connected-but-idle with disconnected. Stage upgrades outside active sessions. Record the supported tablet/Android/firmware matrix and recovery instructions.
