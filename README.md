# RaceLink Studio

Planning baseline v3 · 14 October 2026 · Feature interview complete · Implementation not started

A tablet-first ski training application for Brower Race Link RL. The Android tablet runs the application, stores all sessions and processes timing without a Windows PC or internet connection.

## Documents

- [Product requirements and UI flows](01-product-requirements.md)
- [Architecture and data model](02-architecture-and-data-model.md)
- [Hardware unknowns and simulator plan](03-hardware-and-simulator.md)
- [Prioritized backlog and roadmap](04-backlog-and-roadmap.md)
- [Feature and design interview — confirmed answers](05-feature-and-design-interview.md)

## Confirmed direction

Training with repeated runs comes first. One operator manages the session. When a verified hardware bib is unavailable, the operator selects the next athlete; uncertain matches require review. The primary device is a standalone Android tablet, chosen for screen space. No Windows server is required.

This supersedes the original Windows-server/React-PWA architecture. Native Android with Kotlin, Jetpack Compose and Room is the proposed implementation approach, subject to an early device feasibility test. React and PWA delivery are no longer requirements for the operator application. Remote viewers are deferred; the initial release uses only the tablet.

Live timing and offline reliability remain central. The BLE protocol is unknown and stays behind an adapter. Use a simulator until hardware is available. The approximately 10-inch tablet sits beside the timer at the finish; direct BLE is the initial topology. The optional Pi gateway is deferred.

The Swedish dashboard focuses on each child's personal progress, not rankings: latest finish, difference from the previous completed run, a large switchable chart and compact table. Use blue accents, light/dark themes and neutral feedback. Support reusable rosters, editable tags/notes, team branding, individual image/PDF summaries, per-child session reports and CSV. Shared summaries omit tags and notes unless selected at export.

## Next checkpoint

Feature and visual interviews are complete. The next useful step is a reviewable tablet-screen prototype based on the confirmed requirements. Exact tablet/Android version and hardware capabilities remain to validate; backup destination and operating limits remain deployment decisions. No application implementation has started.

The former rule that offline operator edits queue for server review is superseded for the standalone tablet: local operator edits commit immediately with audit history. Uncertain timing associations still require review. If remote editing is introduced later, its offline changes must be reviewed by the authoritative tablet.

## Evidence

Checked 14 October 2026. Manufacturer material establishes BLE connectivity, not a public protocol or live-event guarantees:

- [Brower Race Link app listing](https://apps.apple.com/us/app/brower-timing-race-link/id975758398)
- [Brower BIB ID RL manual, 2015](https://static1.squarespace.com/static/5be35e537c932755af965d6b/t/5bf3117d562fa7ca77b3a545/1542656391402/RL_instructions_v3.pdf)
- [Current Brower ski timing products](https://www.browertiming.com/ski-race-timing): RL Cloud must not be assumed compatible with legacy RL.
- [Android offline-first architecture](https://developer.android.com/topic/architecture/data-layer/offline-first)
- [Android BLE background communication](https://developer.android.com/develop/connectivity/bluetooth/ble/background)
- [Android foreground service types](https://developer.android.com/develop/background-work/services/fgs/service-types)

All performance figures in this plan are proposed acceptance targets, not measured guarantees. The former 50–100 m PC separation is superseded by the confirmed tablet-beside-timer workflow.
