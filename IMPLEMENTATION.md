# Demo implementation — 0.1

This is a native Android application built with Kotlin and Jetpack Compose. It is a first functional slice, not the complete MVP. All included athletes and timing observations are fictional. No Bluetooth permissions, hardware connection, cloud service or PC server is used at runtime.

## Try it

Install the debug APK on an Android 8.0+ device or emulator. A roughly 10-inch landscape tablet is the intended layout; narrower windows use horizontal athlete navigation. Android may ask you to allow installation from the app opening the APK.

1. Open **RaceLink Studio Demo**. Alma's latest result is selected initially.
2. Select a child and switch **Visa förbättring / Visa tider**.
3. Press **Simulera mål**. The deterministic simulator rotates through bibs. The currently selected child stays on screen; **Visa nästa** opens queued finishes.
4. Try **Simulera avbrutet**. Incomplete attempts remain visible and are excluded from comparisons.
5. Tap an attempt to reassign the child, mark it incomplete, add tags or write a note. Reassignment updates both children's histories and records an audit entry.
6. Open **Inställningar** to edit the team name and add/rename coaching tags. Tags use stable identities, so renamed labels update older runs.
7. Try **Markera ändring**, the light/dark switch and **Exportera CSV**. CSV excludes coaching tags/notes and labels every row as simulated.
8. Close/reopen the app. Changes persist on this device. **Återställ demo** requires confirmation and clears demo edits.

## Build

Use Android Studio with JDK 17 or a compatible newer JDK, SDK platform 35 and Build Tools 35.0.0. Open the repository root. Android Studio can generate the machine-local `local.properties` file. The Gradle wrapper downloads the pinned Gradle version.

Windows: `gradlew.bat :app:testDebugUnitTest :app:assembleDebug`

macOS/Linux: `sh gradlew :app:testDebugUnitTest :app:assembleDebug`

APK: `app/build/outputs/apk/debug/app-debug.apk`.

## Boundaries

Validation on 14 October 2026: `:app:testDebugUnitTest :app:assembleDebug` succeeded; six tests passed with zero failures. No physical tablet or Android emulator was available for visual/lifecycle validation. The build reported that platform-tools was absent; that did not prevent unit tests or APK creation, but device installation through adb will require it. An additional official Gradle checksum download was not completed because automatic approval review reached a usage limit; the wrapper was retrieved from Gradle's official source repository.

- TimingAdapter isolates the simulator. Real BLE discovery/decoding, overlapping real-device correlation and background capture service are not implemented.
- The demo uses a transactional SQLite snapshot, saved off the UI thread before displaying success. It preserves local state and correction audit but is not yet the planned append-only evidence journal/Room architecture. Read failures do not silently replace saved data with seed data.
- The roster and one session are seeded. Roster editing, attendance selection, creating/closing sessions and earlier-session browsing remain future work.
- CSV is implemented. Image/PDF reports, logo import, export previews, backup/restore and optional notification sound remain future work.
- Timing values are integer milliseconds in this simulator. This does not establish the hardware's precision or protocol.
- First device/emulator visual and lifecycle validation is still required before field use. JVM tests are not a substitute for tablet checks.
- No official timing, certified race results or production data should be stored in this prototype.

## Code structure

- `Training.kt`: pure domain model, deterministic adapter, idempotent observation ingest, comparisons and corrections.
- `TrainingStore.kt`: transactional local snapshot persistence.
- `MainActivity.kt`: lifecycle-retained state, Swedish responsive dashboard, chart, dialogs and user-directed CSV export.
- `TrainingTest.kt`: incomplete-reference handling, duplicate replay, equal legitimate times, reassignment, unknown bib rejection and deterministic simulation.

The planning documents describe the full target product. This file records what is actually available in the first implementation.
