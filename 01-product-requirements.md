# Product requirements and UI flows

## Purpose

Run repeated ski training from a standalone Android tablet: prepare athletes, capture supported timing data, associate each attempt correctly, compare runs, resolve uncertainty and export results. Timing evidence and confirmed edits live on the tablet, without a PC, cloud account or internet connection.

## Confirmed decisions

| ID | Decision | Consequence |
|---|---|---|
| D01 | Training and repeated runs first | Formal race scoring deferred. |
| D02 | Operator selects next athlete without verified hardware bib | Selection is intent, not evidence of a start; ambiguous association requires review. |
| D03 | One timing operator | Tablet is sole authority; other devices, if supported, are viewers. |
| D04 | Standalone Android tablet | Local edits commit immediately with audit history; no server reconnection required. |
| D05 | Larger tablet screen | Primary console uses available width for roster, current activity and results. |
| D06 | Proprietary BLE unknown | Capability-aware adapter and simulator; no invented protocol. |

The earlier Windows server and operator React PWA requirements are superseded. Offline queued operator edits are unnecessary on the authoritative tablet. Future remote edits would still require review.

## Interview-confirmed product scope

One coach at the finish uses an approximately 10-inch Android tablet beside the timer. Children also view their own feedback. Several skiers may be on course; no split timing is required. Children enter their bib at the start. A saved team roster maps regular bibs to names and supplies today's participant selection. Verified hardware bibs enable automatic association; uncertain matches still require review. Tap a run to reassign it with correction history.

The main screen is a Swedish coaching dashboard with blue accents, light/dark themes and a quick theme switch. Give the athlete name, latest finish, previous-run difference and large trend chart priority; put a compact results table below the chart. Use neutral faster/slower wording, not red/green judgments. Highlight the child's fastest and slowest eligible runs, without ranking children against each other.

Compare against the most recent completed eligible run, identifying that run explicitly. Incomplete attempts remain visible but do not enter time comparisons. Start the chart in actual-time mode with “Lägre tid är snabbare”; allow an immediate switch to improvement relative to the first completed eligible run. Keep the primary previous-run difference unchanged by this chart toggle. Continue comparisons across course/exercise changes, marking those changes on the chart; do not describe the difference as proof of improvement under equivalent conditions.

Opening an active session shows the latest finisher's dashboard. Once reviewing a child, stay on that child as other finishes arrive. Show a prominent name/time banner, waiting-results count and optional short sound. Earlier sessions open separately. Other-device live viewers, Pi gateway, splits and comparisons between skiers' improvement are deferred.

Allow optional run notes and editable coaching tags, initially “Teknikfokus”, “Bra känsla”, “Misstag”, “Ändrade förhållanden” and “Sen”. Renaming a tag updates past and future run displays through stable tag identity. Support team name and optional logo in the app and reports.

Export individual summaries as image and PDF, a whole-session report with a section per child (chart, runs and personal changes, no ranking), and CSV. Tags and notes are excluded from shared summaries by default and selectable at export. Export is a local action; sending requires a separate user action. Previously exported files are not rewritten when data or tags change.

## Remaining validation and proposed design defaults

Exact tablet/Android version, RL mode/firmware, verified BLE capabilities, backup destination and capacity/retention targets remain open. Proposed test envelope remains 200 athletes and 2,000 attempts/day. These do not reopen the completed feature interview.

Proposed edge-state defaults for prototype review: before the first finish, show participants and capture readiness; first completed run shows “Första fullföljda åket” without a delta; incomplete attempts appear as labeled gaps rather than zero times; mark every tied fastest/slowest run and avoid a slowest label on a single-run history. Corrections recalculate the affected children's histories from valid evidence. Ambiguous results appear in review without asserting a child's identity.

## MVP requirements and acceptance

| ID | Requirement | Acceptance |
|---|---|---|
| FR01 | Create/resume sessions | Name, venue, date, course/setup version and roster survive application restart. |
| FR02 | Roster import | Preview CSV; flag duplicate session bibs and missing names before committing. |
| FR03 | Repeated attempts | Distinct attempts retained even for identical athlete/time values. |
| FR04 | Receive observations | Preserve source, raw evidence, adapter version, precision and clock semantics. |
| FR05 | Live display | Local committed results appear automatically; source mode and freshness always visible. |
| FR06 | Capability-aware clocks | Show estimated running time only when verified starts exist; otherwise show awaiting result. |
| FR07 | Athlete association | Verified bib matches uniquely; missing/conflicting identity uses review when uncertain. |
| FR08 | Corrections/statuses | DNS, DNF, DSQ, void, reassignment and manual time changes append reasoned revisions. App edits do not control the physical timer. |
| FR09 | Personal comparisons | Difference from most recent completed eligible run, fastest/slowest and chart toggle; incomplete attempts excluded and course changes marked while comparisons continue. |
| FR10 | Full offline use | Setup, direct BLE capture, edits, results and file export work with Wi-Fi/mobile data off and Bluetooth on. |
| FR11 | Lifecycle reliability | Rotation, screen lock, backgrounding, permission loss and process restart have tested recovery behavior. |
| FR12 | Export/restore | Individual image/PDF, per-child session report and CSV; tags/notes opt-in for shared summaries; consistent backup restores evidence, roster, branding, tags and history. |
| FR13 | Saved roster | Regular bibs and names persist; today's participants selected without overwriting earlier session identities. |
| FR14 | Tags and branding | Editable tag library updates historical labels; team name and optional logo appear in app/reports. |
| FR15 | Swedish tablet design | Blue accents, quick light/dark switch, readable chart and table on approximately 10-inch screen. |
| FR16 | Noninterrupting finishes | Opening shows latest finisher; subsequent arrivals notify without replacing selected child's review. |

Excluded initially: formal race scoring/certification, dual lanes, multiple editors, cloud dependence, device-setting writes, video, automated coaching, split comparisons, Pi gateway, remote viewers and cross-child improvement comparisons.

## Tablet UI flows

1. **Home → resume/create:** large active-session card; persistent SIMULATOR/HARDWARE label and BLE/gateway/storage status.
2. **Setup → roster → source check:** preview imports, select source, review capability profile, observe a test result, then activate. Historical hardware records do not silently enter a new session.
3. **Landscape coaching dashboard:** athlete/name/latest-time and previous-run difference above a large chart and compact table; participant navigation and incoming-finish banner remain accessible. Keep review queue and connection state visible. New results do not displace the reviewed child. Adapt to portrait without hiding essential controls.
4. **Select → observe → associate:** planned athlete selection never creates a device start. Verified matching bib can associate; mismatch or uncertain chronology goes to review.
5. **Finish → compare:** commit locally, then refresh results and best/delta. A result-only record completes an attempt without inventing its start.
6. **Review → resolve:** show evidence, uncertainty and candidate matches; assign, leave unresolved or void with reason. Retain original evidence.
7. **Correct → save:** show old/new values and reason; append revision immediately, even without any network connection.
8. **Background → resume:** capture belongs to the native service, not the screen. On return show actual connection/capture state, last observation and any interruption; do not assume capture continued.
9. **Close → reconcile → export:** acknowledge unfinished attempts, review items and capture gaps; create consistent export/backup. Late data stays associated with its original session or enters review.

Use large touch targets, readable outdoor contrast and text/icon status alongside color. Propose landscape as preferred layout, not an orientation lock. Test rotation during capture and real tablet operation. Keep simulated and manually entered results visibly distinguishable from device results.

## Failure behavior

| Failure | Behavior and boundary |
|---|---|
| Internet or Wi-Fi unavailable | Direct BLE and all local functions continue; remote viewers/gateway need their separate link. |
| BLE unavailable | Show gap and reconnect state; recover history only if supported. |
| App screen closes/locks | Native capture service is intended to continue; validate on target OS/device. |
| Process killed, force-stop or tablet reboot | Committed data survives; capture may stop. Require explicit recovery/readiness check, never promise uninterrupted capture. |
| Tablet loses power | Direct capture stops; gateway can buffer if independently powered. Device history recovery is unknown. |
| Pi-tablet link lost | Pi buffers received observations; tablet shows stale capture and later reconciles replay. |
| Storage full/failure | Prominent error; no false durable success or silent eviction. |
| Timer resets/clears memory | New or uncertain source epoch; no assumed recovery of lost records. |

## Proposed release targets

- Local commit-to-display p95 below 250 ms on chosen tablet; measure device-to-capture delay separately.
- No acknowledged observation or confirmed edit lost in crash/restart tests with functioning durable storage.
- Active-session recovery within 30 seconds of app launch on target hardware, excluding hardware reconnect latency.
- Ten replays of 10,000 observations produce identical confirmed results and revision counts.
- Eight-hour training soak on the selected device/power setup, covering lock/background/reconnect and realistic cold-use constraints.
- If gateway chosen: eight-hour buffer at validated peak rate, 80% capacity warning, no silent deletion.
- Simulator rehearsal passes before hardware pilot; hardware release requires exact-model validation and a parallel reference record.
