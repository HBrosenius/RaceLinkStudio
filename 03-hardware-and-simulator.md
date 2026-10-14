# Hardware unknowns and simulator plan

## Evidence boundary

The [Brower Race Link listing](https://apps.apple.com/us/app/brower-timing-race-link/id975758398) describes BLE connectivity but does not document the integration protocol or prove live start/finish delivery. The [2015 RL manual](https://static1.squarespace.com/static/5be35e537c932755af965d6b/t/5bf3117d562fa7ca77b3a545/1542656391402/RL_instructions_v3.pdf) discusses an optional smartphone interface, finite memory and mode-dependent operation. Match it to the user's exact hardware before relying on it. RL Cloud is not assumed compatible.

## Investigation register

| Unknown | Experiment/evidence | Consequence |
|---|---|---|
| Exact RL/interface/firmware | Inventory labels, accessories and matching manual | Confirm BLE path exists. |
| Public/supported protocol | Request manufacturer documentation if user chooses; no contact sent | Prefer supported interface over inference. |
| GATT services, pairing, authentication | Read-only discovery on actual hardware | No invented UUIDs or writes. |
| Single-client limit | Compare connection alone and with official app | Prevent competing tablet/Pi ownership. |
| Starts vs completed records vs bulk pull | Trigger labeled runs and observe supported reads/subscriptions | Honest live capability and latency. |
| Bib, duration, status and precision | Compare frames with timer and official export | Verified field decoding. |
| IDs, history and resets | Repeated reads, equal times, controlled resets after saving evidence | Deduplication and loss boundaries. |
| Clock epoch/rollover and split rules | Controlled fixtures for relevant modes | Correct timing and association. |
| Android lifecycle stability | Screen lock, rotation, background, process kill, power modes, permission revocation | Supported tablet/OS matrix. |
| Tablet-to-timer distance | Actual operating position and field test | Direct BLE versus optional Pi. |
| Gateway network/power | Site survey and interruption/soak tests | No unsupported 50–100 m range guarantee. |

Initial investigation is read-only. Do not guess commands or clear live timing memory. Destructive bench experiments require disposable test data and saved evidence. Hardware-independent development can proceed through the simulator after workflow approval.

## Hardware gates

Inventory → supported connection → labeled raw fixtures → evidence-backed decoder → at least 100 varied reference attempts → reconnect/history tests → supervised outdoor session. Every duration must agree at verified source precision; discrepancies block acceptance.

Include repeated athletes, equal durations, missing/incorrect bibs, DNF/DSQ, splits if used, disconnects and reboot. Document unrecoverable gaps. If only result retrieval is supported, disclose that mode and measure delay; never claim live starts. If protocol access remains blocked, retain simulator/manual-entry mode and do not label it hardware-ready.

## Simulator

Generate deterministic normalized observations behind the same adapter contract. Seed, virtual clock, scenario version and operator action log reproduce each run. Provide play/pause/step/speed and failure injection. Use synthetic domain events, not invented Brower bytes. Add recorded raw-frame replay only after genuine captures exist.

Profiles: full events, completed-result notifications, history-only reads, and no-history recovery. All are marked SIMULATOR. Expected results include explicit unresolved review states where evidence is insufficient.

| Scenario | Required outcome |
|---|---|
| Repeated runs and equal durations | Separate genuine attempts survive; best/delta correct. |
| Missing/conflicting bib | Review when operator intent is insufficient or contradictory. |
| Overlapping attempts/out-of-order events | No silent FIFO inference; late events reconcile without duplicates. |
| Missing start/split or result-only mode | Preserve evidence without fabricating events. |
| DNS/DNF/DSQ/void/correction/reversal | Eligibility and revision history remain correct. |
| Duplicate delivery/lost ACK | One durable effect per observation identity. |
| Historical records without stable IDs | Suspected duplicates reviewed, not discarded by hash alone. |
| Crash before/after local commit | No false success; committed records survive restart. |
| Rotation, lock and background | Capture independent of UI; verified behavior on real target tablet. |
| Force-stop/reboot/power loss | Durable records recover; capture gap disclosed. |
| Permission revoke/Bluetooth off | Clear blocked state and deliberate recovery. |
| Timer reset/wall-clock jump/rollover | No receive-time calculation or invented epoch. |
| Unknown/corrupt packet | Preserve raw evidence, quarantine and diagnose. |
| Storage pressure/full | No silent deletion or false durable ACK. |
| Gateway link loss/restart | Durable spool replays; old session binding retained. |
| Session closes with delayed observations | Original session or review; never silently attach to next session. |
| Backup restore | Evidence/history recover; missing newer observations flagged. |
| Optional viewer disconnect/snapshot race | Correct cursor replay and stale indicator without affecting tablet capture. |

## Completion criteria

Interview-derived fixtures must also cover: multiple skiers with bibs and no splits; previous completed-run comparison after an incomplete attempt; course-change markers without comparison reset; wrong-bib reassignment recalculating both children's histories; noninterrupting finish notifications and queue count; actual/improvement chart toggle without changing stored data; tied fastest/slowest and first-run states; global tag rename; Swedish light/dark layouts; team branding and image/PDF/session-report exports with tags/notes excluded by default. Pi and viewer scenarios are deferred with those features.

Reproducible fixtures cover normal training and every failure boundary. Android lifecycle tests run on the intended tablet, not just an emulator. Gateway fixtures run only if that topology is selected. Simulator success proves application behavior, not BLE compatibility or uninterrupted OS execution.
