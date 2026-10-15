# UI cleanup and missing-feature plan

Date: 15 October 2026
Status: Core cleanup, secondary workflows, first-session guidance, roster reuse, reports/image branding, optional result sound and CSV roster import are delivered in the simulator demo. Accessibility and physical-device checks are partial; production reliability, timer integration and field validation remain open.

## Review scope and evidence

Reviewed the current working-tree implementations of the dashboard, chart, run editor, quick tags, course-change editor, settings, simulator, session setup/browser, reports, result review and backup/restore. Existing uncommitted changes are part of this baseline.

This is a source-based UI and workflow audit. No Android emulator window was available during desktop inspection; no live walkthrough, screenshot comparison, accessibility test or physical-tablet test was completed. Crowding and clipping risks below are inferred from layout structure, not claimed as observed rendering defects. Visual validation is an explicit implementation gate.

Primary evidence:

- [MainActivity.kt](app/src/main/java/se/racelink/studio/MainActivity.kt): `Dashboard`, `RunDashboard`, `AthleteList`, `QuickTags`, `EditRun`, `Settings`, `ChangeDialog`, `TrainingViewModel.update`.
- [TrendChart.kt](app/src/main/java/se/racelink/studio/TrendChart.kt): chart controls, gestures, annotations and read-only details.
- [SessionSetup.kt](app/src/main/java/se/racelink/studio/SessionSetup.kt) and [SessionBrowser.kt](app/src/main/java/se/racelink/studio/SessionBrowser.kt): setup, roster and archive navigation.
- [ReportScreen.kt](app/src/main/java/se/racelink/studio/ReportScreen.kt), [ReviewScreen.kt](app/src/main/java/se/racelink/studio/ReviewScreen.kt), [BackupScreen.kt](app/src/main/java/se/racelink/studio/BackupScreen.kt): secondary workflows.
- [Confirmed product direction](05-feature-and-design-interview.md), [implementation inventory](IMPLEMENTATION.md), [existing roadmap](04-backlog-and-roadmap.md).

The older roadmap understates what exists. Session/roster editing, multi-session/date/skier report filters, PDF graphs, backup/restore, timing review, DNF controls, chart zoom and editable course changes already exist. Improve them rather than rebuilding or listing them as missing.

## Design goal

A coach should immediately understand which session is open, which skier is being viewed, what their latest result means, and whether another result needs attention. Keep the chart prominent and routine coaching actions close to the selected run. Reveal administration and timing evidence when needed.

Preserve Swedish UI, offline operation, personal progress without rankings, separate viewed/next skier selection, immutable timing evidence, DNF exclusion from comparisons, course-change context, read-only closed sessions and private-by-default exports.

Existing documents request neutral slower-run feedback, while the current result card uses red/green with arrows. Preserve the explicit comparisons and arrows during cleanup; treat color policy as a product decision to reconcile with the recent implementation, rather than silently reverting it. Do not introduce athlete rankings or cross-session performance claims.

## Proposed navigation and screen structure

Use four consistent destinations: **Aktuellt pass**, **Träningspass**, **Rapporter**, **Inställningar**. On wide tablets use a compact navigation rail; on narrower layouts use an appropriately sized navigation bar. Keep athlete selection within the current-session screen, visually separate from application navigation.

- Current-session header: session name, date, status and a small **Demo** source badge. Expand location/course details on demand.
- Session actions menu: edit, mark course change, finish session and start a new session. Preserve the previous decision to keep course changes out of the dashboard action row.
- Result-review badge: visible whenever events are waiting, with a direct route to review. Explain why session closure/start is blocked and offer **Granska resultat**.
- Settings sections: team, coaching tags, appearance, backup/restore, and demo tools. Put simulator actions together; keep reset separate from ordinary timing actions.
- Standard back behavior: run detail → skier/session detail → session list → current session. Android Back and visible back controls must agree.

Conceptual landscape layout (not a validated pixel specification):

```text
App navigation | Session name · date · Pågår · Demo       Session actions
               | New results / review notice when needed
               | Skier selector | Name · bib · latest time
               |                | Previous-run delta · session-best delta
               |                | Tag latest · DNF
               |                | [Tider | Förändring]  [Filter]
               |                | Chart + course-change boundaries
               |                | Compact run history, newest first
```

Portrait: move skier selection above the result card, stack result/actions as needed, and keep chart and history full width. Do not simply shrink the landscape action rows.

## Prioritized cleanup backlog

P0 = correctness or loss-of-work risk; P1 = core usability cleanup; P2 = useful extension. Effort is relative: S = local change, M = screen/workflow change, L = several screens or new persisted behavior. These are planning sizes, not delivery promises.

| ID | Priority / size | Finding and proposed change | Acceptance criteria |
|---|---|---|---|
| U01 | P0 / S | The finish banner reads `pending.last()` but **Visa nästa** consumes `pending.first()`. Use the same queued result for the preview and navigation; show its name, time/DNF and remaining count. | With three different queued finishers, every tap opens the result advertised. Arrivals never switch the current skier automatically. Opening an older queued result identifies/highlights that exact run, even if the skier has newer runs. |
| U02 | P0 / M | Editors close immediately after calling asynchronous `vm.update`; save failure can discard the visible draft. Return a save outcome, retain the editor until commit, and show errors next to the action. Clear stale errors after success. | Forced save failure keeps tags, note, skier and session drafts available for retry. A successful commit closes once; repeated taps cannot duplicate actions. |
| U03 | P1 / M | Dashboard navigation mixes reports, backup, session lifecycle, review and simulation in one menu. Introduce the destination structure above and consistent screen headers. | Coach reaches reports, prior sessions and review directly from navigation/status. Current-session context and route back remain clear on every screen. |
| U04 | P1 / M | Latest-result card puts identity, time, two comparisons and long action labels in one `Row`. Reflow into a clear time/identity area, labeled comparison area and wrapping action area. Avoid repeating the same best time in multiple places. | At the agreed tablet portrait/landscape widths and enlarged fonts, time, identity and both comparisons remain readable; action labels do not clip. DNF still leaves immediate access to tagging. |
| U05 | P1 / M | Every run card renders all available tags and a DNF control. Default to compact rows: run number, time/status, previous delta, selected tags and note indicator. Expand the selected row or open details for all tag choices. | At normal landscape font size, target at least four history rows visible while keeping a useful chart; validate on the target tablet. Latest-run tagging stays one tap away. Existing tags remain visible in collapsed rows. |
| U06 | P1 / M | Chart mode is a button labeled with the alternate action; zoom/swipe behavior is largely gesture-driven. Use a selected **Tider / Förändring** control, visible previous/next skier buttons and accessible zoom/reset controls. Keep current gestures as shortcuts. | Selected chart mode is unambiguous. Every essential chart action works without pinch/swipe. Preserve original run numbers, full-session baselines, tied best results, DNF gaps and change boundaries. |
| U07 | P1 / M | Dense chart labels disappear below the slot-width threshold; each visible change adds another annotation row. Provide a concise selected-run detail area and grouped change labels when necessary. Reserve axis-label space. | Check 0, 1, 12, 50 and 100 runs, long tags, tied best times and several adjacent changes. No unreadable label pileups; details are reachable even at overview scale. Filtered/zoomed positions still refer to the correct original runs. |
| U08 | P1 / M | Run editing opens with every skier as a chip before coaching fields; tag editing sometimes saves instantly and sometimes waits for Save. Put coaching first, corrections in a labeled section, and use searchable skier selection. | Header always identifies the original run/name/time. Quick tagging says **Sparas direkt**; the draft editor uses **Spara / Avbryt** consistently. Reassignment previews the destination and retains evidence/history. |
| U09 | P1 / M | Setup combines metadata and a fully editable roster in one long form. Split **Passuppgifter** and **Deltagare** into clear sections with compact roster rows and dedicated edit controls. Add a date picker, numeric bib input and field-specific validation. | Invalid/duplicate bibs identify the affected rows; zero participants explains the disabled action. Optional fields are clear. Opening the keyboard leaves the focused field and save action reachable. |
| U10 | P1 / M | Reports expose all filters, export buttons and PDF options above the content, with several fixed horizontal rows. Collapse filters to a readable summary and open filter/export panels. | Results show a summary such as **3 pass · 5 åkare · 42 åk**. Filters can be reset, empty selections offer a recovery action, and portrait leaves useful content space. PDF/CSV use exactly the displayed selection. |
| U11 | P1 / M | Export controls mix screen graph visibility with PDF options, and CSV success is rendered on the underlying dashboard. Use one export workflow with format, scope, applicable options and in-place progress/result feedback. | PDF preview reflects graphs/tags/notes choices; tags/notes remain off by default. CSV clearly excludes them. Success, cancellation and failure are visible where export was initiated. |
| U12 | P1 / M | Session browsing duplicates reporting/export UI and uses **Till aktuellt pass** plus local **Tillbaka**, but Dialog dismissal exits the whole browser. Standardize navigation and session summary cards. | Back returns one level at a time. Opening a closed session clearly indicates read-only state. **Öppna i Rapporter** carries that session/skier selection. Preserve a short route to session CSV export. |
| U13 | P1 / M | Review cards expose raw IDs and assignment/duplicate/rejection controls at once; simulator actions occupy the same toolbar. Lead with reason, measured result and recommended next action; tuck evidence into **Visa originaluppgifter**. | Unknown bib and uncertain timing are understandable without reading IDs. Duplicate candidates are searchable by skier/bib/run; confirmations name the exact target. Original evidence and decisions remain inspectable. |
| U14 | P1 / M | Settings is a long dialog with all tag fields expanded; settings is inaccessible when a session is closed. Use a proper settings screen, category sections and deliberate tag editing. Separate global preferences from session mutations. | Appearance and backup remain available after closure. Closed-session tags cannot change through settings. Renaming/moving current tags states its scope; avoid accidental duplicate/blank labels. |
| U15 | P1 / M | Several forms use only `remember`, and Back/dismiss does not protect unsaved changes. Store drafts in appropriate saved state and add a dirty-form leave prompt. | Rotation preserves setup, notes and tag drafts. Back on a dirty draft offers keep editing/discard; clean forms close normally. Do not store large export payloads in navigation/saved-instance state. |
| U16 | P1 / M | Chart points are canvas interactions without explicit point semantics. Add accessible descriptions and an equivalent navigable run list; use consistent labels and touch targets throughout. | TalkBack can identify skier, run, time/status and actions. Target at least 48 dp actionable areas. Verify 100%, 130% and 200% font scales, light/dark themes and keyboard/switch navigation without losing essential actions. |
| U17 | P1 / S | Empty/error/status copy varies; **DNF** is unexplained, and session-start actions can be disabled without a nearby reason. Standardize language and feedback. | Use **DNF · ej fullföljt** on first/contextual explanation, **Passets bästa**, **Föregående fullföljda åk**, **Visar åkare** and **Nästa åkare** distinctly. Empty states offer the relevant action, and busy/error/success states do not permanently consume dashboard space. |

## Missing features to add

These extend existing functionality. Production timing work is tracked separately from visual cleanup.

| ID | Priority / size | Feature and reason | Scope, dependency and acceptance |
|---|---|---|---|
| F01 | P1 / M | Searchable skier/session pickers and roster management. Long chip rows and permanent roster growth become difficult to operate. | Search name/bib, filter today's participants, and archive inactive roster members without deleting their historical identity. Active-session attendance and reusable-roster membership remain distinct. Preserve archived names/bibs. Reuse the picker in U08–U10/U13. |
| F02 | P1 / M | View correction history per run. Counts of saved changes and timing-decision history do not explain every coaching/status/reassignment edit. | Read-only chronological record with before/after values where the stored audit supports them. Link run details to source timing evidence. If older entries lack detail, show that limitation rather than reconstructing facts. |
| F03 | P1 / M | Undo quick DNF/tag changes and course-change deletion. Immediate mutations currently lack a convenient recovery path. | Show **Ångra** after a committed change; undo is a new audited operation. Scope reversal to that operation so later finishes/edits are not rolled back. Keep the existing restriction against restoring a complete run without a saved time. Depends on U02. |
| F04 | P1 / M | Backup freshness and an end-of-session handoff. Manual backup exists, but freshness is not visible. | Record/display last successful external backup time and whether newer changes exist. At session end show counts, unresolved-work status, report and backup actions. Picker cancellation must not mark data backed up; local restore recovery is not an external backup. |
| F05 | P1 / M | First-use/empty-session guidance and visible source status. The app currently starts with seeded demo data. | Provide an explicit demo entry and a path to create a real empty training session when supported. Explain first-result waiting state and next-skier selection. Never imply BLE is connected in simulator mode. Defer real connection controls until hardware support exists. |
| F06 | P2 / M | Individual image export and team logo. Both are confirmed product requests still listed as missing. | Build on existing report selection and summaries; preview image/PDF with optional branding, suitable logo scaling and an unbranded fallback. Notes/tags stay opt-in. User explicitly invokes Android sharing; exporting never sends automatically. |
| F07 | P2 / S–M | Optional new-result sound. Already requested, not implemented. | Off by default with a short preview and mute control; respect device sound behavior. Sound accompanies a new accepted finish, not a replay, and never changes the viewed skier. |
| F08 | P2 / M | Reuse previous session setup. Repeated training should not require repetitive entry. | Copy selected roster/course/type into a new draft, choose a new date/name, and start with no attempts, pending events or course-change markers. Existing session start already carries some defaults; extend it rather than introducing a second setup path. |
| F09 | P2 / M | Roster CSV import with validation preview. Valuable for larger teams, not a prerequisite for tidying the dashboard. | Show accepted rows, duplicate bib/name ambiguities and errors before applying. No implicit athlete merges or rewriting historical records. Depends on F01 and a defined import schema. |

Before field release, continue existing B02/B05/B10/B12/B13: verified BLE behavior, durable capture/evidence, interruption/reconnect visibility, storage failure recovery and physical-device testing. These are genuine gaps but are not deliverables of a cosmetic UI pass. Surface source/disconnected/review-needed states only when backed by real system state.

Keep remote viewers, cloud accounts, rankings, formal races, splits, dual lanes and cross-course performance comparisons outside this plan.

## Implementation sequence

### Phase 1 — correctness and consistent feedback

Implement U01, U02 and draft preservation from U15 first. Introduce shared save/error/success handling, then build F03 on committed operation outcomes. Preserve all existing timing and comparison semantics.

Exit: queued result preview/navigation agree; failed saves retain drafts; rotation does not discard a form; undo does not revert unrelated later work.

### Phase 2 — current-session cleanup

Implement U03–U08, U16–U17 and the reusable picker portion of F01. Extract dashboard/header/result/history components from MainActivity as needed; keep styling/layout changes separate from domain mutations. Introduce common spacing, typography, status badges and responsive action layouts.

Exit: coach can select a skier, read the latest result, tag it, mark/restore DNF, inspect a previous run and handle a new finish without losing context. Validate landscape/portrait with long Swedish names and labels.

### Phase 3 — secondary workflows

Implement U09–U14 and remaining U15 work, followed by roster management and F02/F04. Reuse pickers, screen headers, status feedback and export selection state. Keep report filtering logic as the common source for preview and exports.

Exit: create/edit/close a session, inspect an archive, resolve uncertain timing, export the chosen scope and back up/restore through clear, consistent flows.

### Phase 4 — product completeness

Add F05–F09 in priority order, with image export/logo and sound following the already confirmed requirements. Update the old roadmap and implementation inventory to distinguish delivered features, usability work and hardware-release gates. Reconcile outdated pagination and validation statements in IMPLEMENTATION.md.

Exit: every delivered feature has an updated try-it flow; remaining hardware and physical-device limits remain explicit.

## Validation plan

Use disposable demo data and a separate test backup. Do not reset existing user data for UI review.

1. Capture before/after screenshots of dashboard, long run history, run editor, session setup, reports, review and backup in landscape/portrait and light/dark themes. Choose final layout breakpoints using actual available width and font scaling.
2. Rehearse the primary workflow: select a skier → receive another skier's result → keep the current view → open the advertised queued run → tag → mark/restore DNF → inspect history. Repeat with several queued results for the same skier.
3. Check empty session, no eligible finishes, DNF-only history, no tagged runs, long names, many tags, tied best times and dense chart markers. Retain original run numbering when filtering.
4. Test draft rotation, Android Back, keyboard visibility, double taps and simulated save failure. Verify that unsuccessful saves do not claim success or lose typed content.
5. Check reports with all/multiple/no sessions, date range boundaries, one/multiple/no skiers, invalid dates and large histories. Confirm displayed scope matches CSV/PDF and privacy options affect only intended output.
6. Exercise unknown bib, uncertain result, duplicate and rejected result paths. Review decisions must not create duplicate runs; closed sessions remain immutable.
7. Verify backup cancellation/failure/success indicators, restore preview and recovery. A backup is fresh only after the output write succeeds.
8. Add focused automated tests for queue identity, operation-aware undo, failed-save draft retention and state/filter propagation. Retain existing domain/report/backup tests. Add Compose interaction coverage where practical for navigation, semantics and form state, rather than tests that merely assert styling constants.
9. Run `gradlew.bat :app:testDebugUnitTest :app:assembleDebug` for implementation changes. This planning-only review did not run builds/tests or claim new test results.
10. Perform final coach rehearsal on the intended roughly 10-inch physical tablet, including outdoor readability and interrupted/background operation. Emulator checks alone do not establish field readiness.

## Decisions to confirm during the first visual iteration

- Validate the proposed rail/bar navigation against available chart width; avoid adding permanent chrome that crowds the main task.
- Reconcile the earlier neutral-feedback requirement with the current red/green comparison treatment, retaining explicit text and arrows either way.
- Confirm the exact target tablet/Android version, normal roster size and typical runs per skier so density and performance targets reflect real use.

These decisions do not block the correctness fixes, compact-history prototype, save feedback or report-flow cleanup.

## Implementation progress — Phase 1, 15 October 2026

- **U01 implemented:** the queue banner and acknowledgement use the same first pending run. A committed acknowledgement opens that exact run editor, even when the skier has a newer finish. Arrivals do not switch skier automatically.
- **U02 implemented for dashboard editors and session setup:** storage returns a commit outcome, publishes only after success, clears stale errors on retry/success and closes the editor only after commit. Save actions are disabled while busy. Review confirmations also stay open on failure. Completion notifications live in the ViewModel so rotation during a save does not strand an editor.
- **U15 started:** run, course-change, team/tag and session/roster drafts use saved state and warn before discarding a dirty form. Large CSV payloads no longer use saved-instance state. Remaining secondary-screen state/navigation work stays in Phase 3.
- **F03 implemented:** committed quick tag/status changes and course-change deletion offer Ångra. Inverses affect only that tag, status or removed marker; later finishes, notes and other changes are retained. Undo checks the session/current field and is audited. Restore/session closure clears the available undo action.
- **Validation:** 52 unit tests passed, including forced persistence failure/retry, queue identity, inverse operations and draft saver round trips. Android debug build passed. In the emulator, an unsaved note and tag survived portrait-to-landscape rotation; temporarily moving its database directory caused SQLITE_READONLY_DBMOVED, left the editor/draft intact, and exposed the save error. The database path was restored immediately. Android Back offered keep editing/discard. No user tablet data was reset or used for failure injection.
- **Still to validate:** rotation and failure rehearsal for every secondary form, 130%/200% fonts and TalkBack, the full coach workflow and outdoor readability. No field-readiness claim is made. Phase 2 progress is recorded below.

## Implementation progress — Phase 2, 15 October 2026

- **U03:** persistent Aktuellt pass / Träningspass / Rapporter / Inställningar navigation; rail on wide layouts, bottom bar on narrower/enlarged-font layouts. Existing reports and session browser render within the shell. Session actions stay in Passåtgärder; simulator/backup/appearance move to Settings. Header details expand on demand. Pending review retains a direct button with the closure/start-blocking reason.
- **U04:** extracted responsive result card with identity/time, explicit previous/best comparisons and wrapping tag/DNF actions. Retained the requested arrows and red/green feedback; removed repeated best/worst summary text. Empty and DNF text wraps with appropriate line height.
- **U05:** compact newest-first history rows show time/status, previous delta, selected-tag summary and note/change indicators. Wide layouts place history beside the chart/result; narrower or enlarged-font layouts stack. Tapping opens full details, including read-only archive details. Latest tagging remains one tap away.
- **U06–U07:** selected Tider / Förändring controls, visible previous/next skier actions, and Välj åk / zoom tools with zoom in/out, reset, pan and an explicit original-number run picker. Pinch and swipe remain shortcuts. Nearby change labels group into one timeline annotation and open a change chooser. Axis label spacing uses measured text width; geometry reserves axis space. All runs remain in the overview; filtering never renumbers them, and baseline/best calculations still use the full session. Sparse/zoomed coaching labels remain tappable; full selected-run details are available from tools.
- **U08 / F01 picker portion:** coaching tags/notes precede a collapsed corrections section; reassignment uses the shared name/bib search picker and previews the destination. The current skier selector supports search and participant filtering. Quick tags retain Sparas direkt; draft edits retain Spara/Avbryt.
- **U16–U17 foundations:** equivalent accessible run rows include skier/run/time/status/tag descriptions and 48 dp actions; the canvas provides an overview description. Added contextual DNF explanations and explicit viewed-skier wording. Full TalkBack, switch/keyboard and transient-status feedback acceptance remains open.
- **U12/U14 foundations:** browser Android Back now returns one level at a time; Settings remains available after closure, with appearance/backup independent of read-only session tag editing. Detailed report/export/settings-editor cleanup remains Phase 3.
- **Validation:** 54 unit tests and debug build passed. Inspected disposable histories of 0, 1, 12, 50 and 100 runs, long names, adjacent course changes, tied bests and DNF gaps. Exercised visible zoom/reset, report/session navigation and browser Back. Captured light/dark and enlarged-font iterations. At the actual tablet landscape size/density (3200×2136, 440 dpi), the 100-run overview and six full compact history rows fit simultaneously. The emulator database was restored byte-for-byte and display overrides reset. The tablet's training data was not reset.
- **Remaining validation/scope:** final coach rehearsal on hardware, TalkBack and keyboard/switch testing, all secondary forms at enlarged font scales, transient notices, and broader route/filter retention. Screenshot evidence establishes the inspected cases only. Session setup and report filter/export cleanup are next.

## Implementation progress — Phase 3 session setup and reports, 15 October 2026

- **U09:** session setup separates Passuppgifter and Deltagare. Compact participant cards have deliberate name/bib editing, search, select-all/clear controls and per-row validation. Bib entry uses a numeric keyboard and identifies duplicate/invalid numbers. Date fields offer an explicitly Swedish calendar with UTC date conversion and saved picker state. Optional metadata is labelled. Dirty draft guards and commit-aware saves remain; form fields lock during commit. Shared full-screen dialogs bound content to Android's actual visible frame so footer actions stay above the keyboard and gesture area.
- **U10:** report overview leads with a compact pass/skier/run summary. A dedicated filter panel provides searchable sessions and skiers, all/selected/date scopes, calendars, draft Apply/Cancel, live counts and reset. Empty scopes are explicit and offer recovery actions. The report selection model is shared by displayed rows and export inputs; screen graph visibility is independent of PDF options.
- **U11:** one export dialog serves Reports and session/skier CSV shortcuts. It freezes the selected rows on open, presents format-specific options, previews PDF before saving and reports write success/cancellation/failure in place. Tags/notes default off; CSV excludes them. Export artifacts and pending write state live in an Android ViewModel/cache rather than saved-instance payloads; preparing/writing/picker states prevent duplicate requests. Save success identifies the exported scope. A missing process-restored artifact asks for a fresh export rather than exporting a different selection.
- **U15 / navigation correction:** the screen content now occupies a stable composition position while navigation changes between rail and bottom bar. Per-destination saved-state registries retain report/filter state across navigation and rotation. This fixes the rotation/picker regression discovered during manual testing; completed exports retain the originally selected scope. New-session charts also reset by session identity.
- **Validation:** all 59 unit tests and the Android debug build passed. New tests cover report selection/export scope, empty/reset selection distinctions, saved filter state, positive/duplicate/overflow bib validation and date conversion at DST boundaries. On a separate disposable test device, checked empty-report recovery, PDF preview with graphs and privacy defaults, CSV cancellation and successful writing, one-skier PDF export while rotating in the file picker, session-name draft rotation, duplicate-bib rejection, Swedish calendar labels, portrait and enlarged-text report layouts, and save-action visibility with the keyboard open. Saved CSV was read back: exactly 17 runs and no coaching fields. After the navigation fix, a one-skier PDF saved successfully with exactly the selected five-run scope. Existing tablet/local-preview training data was not reset or replaced for these tests.
- **Next:** U12 session-to-report context, U13 review cleanup, remaining U14 settings editing and U15 draft/navigation work, then F01 roster management and F02/F04 history/backup freshness. TalkBack/switch testing and final hardware coach rehearsal remain outstanding; this is not a field-release sign-off.

## Implementation progress - session report routes and review, 15 October 2026

- U12: session and skier browsing now offers Open in Reports with that exact session/person scope. Back returns to the same browser context; direct CSV shortcuts remain.
- U13: review leads with reason/result and recommended next action. Original IDs are expandable; skier selection and duplicate candidates are searchable, including bib/run labels. Duplicate confirmation names the target. Simulator actions remain in Settings.
- Validation: debug build and all 59 unit tests passed. Emulator navigation verified one selected session/person (5 runs), plus return to the same skier view. Updated emulator and tablet without resetting data. Interactive review decision testing and accessibility rehearsal remain outstanding.
- Next: U14 deliberate category-based settings/tag editing, followed by per-run correction history and backup freshness.

## Implementation progress - tag settings, 15 October 2026

- U14: full-screen tag settings now shows one category at a time with compact rows and deliberate add/edit dialogs. Names are trimmed and duplicate/blank names rejected per category. Run/focus tags can move without losing selections; change tags retain their category. Scope copy explains effects on earlier runs in the current session. Closed sessions are protected at commit as well as in the UI; appearance/backup remain available.
- U15: category, list draft and individual editor drafts use saved state and dirty-exit guards. Save errors remain visible and saving is commit-aware.
- Validation: debug build and 62 unit tests passed, including preserved run selections/archive snapshots, name validation and closed/change-category guards. Category layout inspected on emulator; updated emulator/tablet without resetting data. Full enlarged-font/editor-rotation interaction rehearsal remains pending.
- Next: per-run correction history and backup freshness/end-session handoff.

## Implementation progress - run history and backup freshness, 15 October 2026

- F02: run details/editing link to read-only chronological correction history and original timing evidence/decisions where present. New corrections preserve readable before/after values, including names/tag names at edit time. Legacy exact-run audit records show stored text with a limitation notice; unavailable evidence is explicitly labelled. Structured records remain strings in the existing audit list, so existing backup encoding preserves them.
- F04: successful external writes record a device-local receipt containing completion time and the exported snapshot fingerprint. Settings/backup show whether current data matches it. Cancellation, failed writes and local restore recovery do not create successful receipts. Restoring different data is detected by content comparison. Closed sessions open a handoff summary with counts, review/unread-result status, a report scoped to that session, backup and new-session actions.
- Validation: debug build, all 66 unit tests and diff checks passed. Separate test emulator verified actual backup write, picker cancellation without changing the receipt, later tag edit marking data changed, receipt persistence after process restart, before/after history display, close-session summary and scoped report navigation. Updated local preview and tablet without resetting training data. Test emulator stopped.
- Remaining: broad accessibility/font/rotation rehearsal, roster management and first-use guidance; no field-release sign-off.

## Implementation progress - roster and reused setup, 15 October 2026

- F01: Settings provides a searchable team roster with Active/Archived sections and explicit archive/reactivate confirmation, available after session closure. Archiving changes future default selection only; current attendance, historical identity and runs remain. Setup defaults exclude archived members, with explicit visibility/selection when needed. Session browsing and its skier list are searchable; the skier chip strip is replaced with the shared searchable picker.
- F08: new-session drafts offer a searchable previous/current-session setup picker and confirmation before replacing name/location/course/type/participants. The chosen date and current roster identities remain. Archived or unavailable participants are omitted with an explicit count; results, pending observations and change markers are never copied. Dirty cancellation remains protected.
- Persistence: archived roster IDs are encoded in snapshots; backup version 6 requires them and continues reading versions 1-5 with an empty default. External backup fingerprints include archive status.
- Validation: debug build, 70 unit tests and diff checks passed. Separate emulator verified archiving after closure, preserved current attendance, template confirmation/exclusion, three-person draft and dirty-draft cancellation. Local preview updated without resetting data. Tablet was disconnected at installation time, so its update remains pending.
- Remaining: enlarged-font/rotation/accessibility rehearsal and first-use guidance.

## Implementation progress - enlarged text and keyboard rehearsal, 15 October 2026

- U15/U16: separate emulator at tablet density (440 dpi), portrait 2136x3200 and landscape 3200x2136, 200% text. Found roster search results collapsed to zero height with the landscape keyboard open; moved introduction into scrolling content so results/actions remain reachable. Shared searchable skier picker now uses the visible-frame full-screen layout with a scrolling result list.
- Found tag editor action buttons hidden behind the keyboard. Tag, skier, run and course-change editors now use a shared visible-frame editor with scrolling body and separate wrapping footer. Tag keyboard screenshot verifies Cancel/Done above IME. Skier query and tag-name drafts survived rotation; dirty cancel/discard was exercised. Repeated tag/roster actions carry target names in accessibility descriptions; run descriptions include best-run and course-change status.
- Validation: build, 70 unit tests and diff check pass. Local preview updated without changing its data/display settings. Disposable emulator display overrides reset and emulator stopped. Physical tablet still disconnected. Full TalkBack/switch navigation, all screen/theme/font combinations and final hardware coach rehearsal remain pending; this is a targeted usability pass, not full accessibility sign-off.

## Implementation progress - selected run in the time card, 15 October 2026

- Updated interaction: chart point/label and history-row selection now bring that exact run into the time card, scroll the card into view and highlight the corresponding chart point/list row. Default selection follows the latest run; explicitly selected older runs remain selected when new results arrive. Returning to latest clears the pinned selection. Session/skier changes reset it appropriately, and rotation preserves the selection. Archived session browsing uses the same read-only card interaction.
- The card identifies run number/latest/selected state, compares the shown run with its preceding completed run and session best, and offers separate inline expandable Run/Next-focus tag categories with multi-selection and immediate commit/undo. DNF applies to the shown run. Inherited course changes remain read-only on the run; explicit annotation/correction and history actions remain available. Selecting a run no longer opens a separate editor/detail dialog.
- Validation: debug build and all 72 tests pass. Disposable emulator verified table selection, multiple tags in each category on the older run, direct canvas point selection, rotation, new finish while an old run is pinned, and return to latest. User-data devices were not reset. Updated local preview and reconnected physical tablet with install-r; test emulator stopped.

## Implementation progress - run browsing and inline notes, 15 October 2026

- Time card adds previous/next run navigation (including DNF, disabled at boundaries). Following latest is explicit and separate from a pinned run that currently happens to be the latest. Older selections show the number of newer runs; Follow latest clears the pin.
- Notes edit inline with explicit Save/Cancel. Opening a note pins its exact run. Saved-state drafts survive rotation; run/skier/navigation/session actions prompt before discarding dirty notes. Save merges only the note into the current run fields and uses commit-aware completion, preserving intervening tag/DNF updates and audit history. Focus/IME changes bring both the note field and save/cancel controls into view.
- Validation: debug build, 74 unit tests and diff checks pass. Disposable emulator with 100 synthetic runs at tablet landscape size/density showed the collapsed card plus full graph without scrolling. Verified run 99 navigation, inline draft/keyboard controls, dirty navigation guard, rotation, note save after tag/DNF updates, and pinned-latest versus Follow latest. Database inspection confirmed only run 99 received the note and all 100 runs remained. Synthetic fixtures were confined to emulator-5556. Updated local preview and physical tablet without resetting their data. Test emulator stopped.

## Implementation progress - diagram tag switch, 15 October 2026

- Added a saved Visa taggar switch in the shared chart, default off, available in current sessions, archived browsing and reports. Per-run Run/Next-focus labels are shown when enabled and wrap rather than truncate. For dense plots, labelled tagged-run cards below the plot preserve readable run attribution while all plot points remain. Course-change timeline labels remain independent.
- Validation: debug build and diff checks pass; preview on/off changes annotation visibility. Updated tablet and preview without resetting data. Dense-label layout has build verification; broader visual/accessibility rehearsal remains pending.

## Implementation progress - collapsible sides and quieter notices, 15 October 2026

- Tag changes no longer display an undo notification banner. Operation-scoped undo remains accessible in Pass actions; failed-save feedback remains. DNF/change-deletion notices retain their existing behaviour.
- New-results banner adds an accessible close button. Dismissal hides the currently observed batch without acknowledging/deleting queued results; a new last pending ID makes the banner reappear. Pass actions can reopen the current queue banner. Dismissal is saved per session.
- Wide layout exposes directional hide buttons for the left skier and right previous-run lists, with 48dp edge handles to restore them. The centre takes the released width. Panel state uses saved state, independent of selected skier; right history has an inline collapse control on narrow layouts. Existing run selection, graph gestures and note drafts remain.
- Validation: build, 74 unit tests and diff checks pass. Preview verified hide/restore for both panels, expanded centre, notification dismissal and reopening with all 21 queued results retained. Preview/tablet updated without resetting data.

## Implementation progress - diagram toolbar cleanup, 15 October 2026

- Replaced separate chips/links with a single shared toolbar surface: joined Times/Change selector in session views, Show tags switch and a trailing options menu. Moved tagged-only filter, explicit run picker, zoom/reset/pan and skier navigation into the menu. Time-card run navigation and chart swipe/pinch/tap remain. Active tagged-only filtering is included in the count caption. Instructions are one muted caption below the plot.
- Shared charts in archived sessions/reports use the same toolbar. Explicit run selection remains available without gestures. The removed expanded control rows no longer consume graph height.
- Validation: debug build and diff checks passed. Disposable 100-run emulator inspected default layout and exercised mode selection, zoom/reset, menu run picker selecting run 1 into the time card, and tagged-only filtering. Preview/tablet updated without resetting data; test emulator stopped.

## Implementation progress - current-result glow, 15 October 2026

- Added a pale fuzzy blue border around the time card when it shows the latest run of the active session, including an explicitly selected latest run. It disappears when that selection becomes older, and is absent for empty/read-only historical views. Layered strokes preserve text sharpness; padding is constant across selection states.
- Validation: debug build passed; latest and previous-run screenshots inspected, return-to-latest exercised. Preview/tablet updated without resetting data.

## Implementation progress - empty-session guidance, 15 October 2026

- Empty active sessions replace empty cards/lists with three steps: check participants, choose the next skier, and open the simulator. Participant editing opens directly on its participant section. The next-skier picker saves before closing and does not change the viewed skier. Simulator entry is disabled until a next skier is selected. Steps use columns on wide screens and stack on narrow or enlarged-text layouts.
- Header and pass information identify Simulator mode. The guide disappears after a result is stored; skiers without results receive contextual guidance instead. Closed historical views remain read-only.
- Validation: debug build, 74 unit tests and diff checks pass. Separate test emulator rehearsed session creation, participant entry point, next-skier selection, two simulated results, exact queued-run selection, multiple tags, saved note, DNF, session closure, scoped PDF preview with graph and successful external backup with current freshness. Preview and physical tablet updated without data resets. Test emulator stopped. Physical coaching/accessibility rehearsal remains pending.

## Implementation progress - physical tablet rehearsal, 15 October 2026

- Rehearsed the coaching workflow through ADB on the connected Xiaomi tablet in landscape, using a clearly named Tablet-check session. The previous 34-run session was preserved in the archive. Checked participant selection, next skier, three simulated arrivals, exact queued-run selection, two tags per run/focus category, note entry with the physical tablet keyboard, DNF, older/latest navigation, collapsed panels and full graph, session closure, scoped report preview and PDF saving.
- Found and fixed export option labels that did not respond to taps. Each complete row now toggles the option with a minimum 48dp target and merged checkbox semantics. Verified label taps and a PDF containing the graph, selected run/focus tags and note. Existing privacy defaults remain opt-in. Updated tablet and local preview.
- Build and 74 unit tests pass. This is an automated physical-device rehearsal; hands-on coaching, pinch gestures, outdoor visibility and TalkBack assessment remain to be done.
- Saved RaceLink-Tablet-check.pdf and RaceLink-Tablet-check-backup.json in the tablet Downloads folder. Backup freshness reports all current data included. Read-back verified the archived 34-run session plus the three-run test session, with run 1 retaining its time, DNF status, four selected tags and saved note.

## Implementation progress - enlarged text and status semantics, 15 October 2026

- Reproduced a header failure at narrow width and 200% text: action buttons consumed the width and forced a long session name into a nearly vertical column. The session heading now occupies its own row below 600dp or at 150% text and above; action buttons wrap beneath it. Normal wide-screen layout retains the compact heading/action arrangement.
- Chart mode selector caps its preferred width to available space. New-result announcements and shared save/error feedback use polite live-region semantics, without moving selection or adding tag notifications. Queued DNF text includes ej fullföljt.
- Validation: debug build and diff checks pass. Separate emulator checked narrow heading at 200% text and tablet portrait at 200%, including chart mode change, options menu and explicit run picker. Display overrides reset and test emulator stopped. Tablet/preview updated with existing data retained. Actual TalkBack speech, switch/keyboard navigation and outdoor readability remain unverified.

## Implementation progress - reports and convenience, 15 October 2026

- F06: export offers PDF, CSV and individual PNG (one skier in one session). PNG uses the same frozen selection, graph, coaching and note options as PDF, and combines at most six rendered pages into one image with a readable error for longer reports. Tags/notes remain off by default. Full-screen export keeps footer actions reachable. Preview, document saving and explicit Android sharing are available; sharing only opens the system chooser and never selects a recipient. FileProvider exposes only the report cache directory with temporary read grants.
- F06 branding: Settings imports an optional PNG/JPEG/WebP logo with bounded input and sampled decoding, stored atomically in device-local files. Export can include it in PDF/PNG with preserved aspect ratio; unbranded reports remain available. Logo and sound preferences are deliberately device-local and outside training backups.
- F07: result sound defaults off, with preview and immediate mute in Settings. It runs only after committing a newly added completed run in the same session, so duplicate replay, tagging/correction, DNF restoration and new-session transitions do not beep. Silent/vibrate, zero notification volume and Do Not Disturb suppress it. It never selects a skier.
- F09: Settings > roster > Import CSV reads bounded UTF-8 files (1 MB, 5000 rows) with Nummer/Namn headers, semicolon/comma separators, BOM and quoted fields. Preview lists new/existing/error rows. Conflicting bibs, duplicate/ambiguous names, invalid numbers and malformed input block the whole import. Existing exact identities remain unchanged, including archived status. Commit revalidates against the current roster, allocates IDs above current/historical identities, adds new roster members and audits the operation without changing attendance, runs or archived sessions. Preview data lives outside saved-instance bundles and survives rotation.
- Validation: build, 81 unit tests and diff checks pass. Separate emulator exercised invalid and valid CSV previews, rotation, successful import, sound preview and DND suppression, logo import, branded PDF with opt-in tags/notes, PNG with privacy defaults, Android share chooser/cancellation, and PNG saving across file-picker rotation. Read-back confirmed a valid 1190x1684 PNG. Database inspection retained the same four participants, two current runs and 100 archived runs, with only two roster additions. Inspected export at 200% portrait and import at 130% dark landscape. Tab focused the import picker and Enter opened it. TalkBack service bound successfully with the export open; this is a smoke check, not spoken-feedback acceptance. Spoken TalkBack, switch-device navigation, hands-on pinch gestures and outdoor readability remain pending. Test display/accessibility overrides reset and emulator stopped. Tablet and preview updated preserving training data; synthetic CSV/logo fixtures stayed on the test emulator.
