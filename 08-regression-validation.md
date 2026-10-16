# Refactor regression validation — 16 October 2026

Validated the uncommitted architecture, typed-audit, Swedish-resource and SQLite changes against the previous `517ad8e` version. Existing external changes were retained.

## Findings fixed

1. Unchanged archived payloads skipped position updates when a new session was started. This produced duplicate positions and unstable history ordering. Positions now update separately without rewriting unchanged session payloads.
2. Upgrade/read failures were swallowed and could appear to be an empty installation. Invalid legacy data now fails the upgrade transaction, retaining schema 2 and the original snapshot. A missing current-session row also raises a read error instead of returning an empty state.
3. Queued writes released their mutex before publishing committed state. A waiting `Main.immediate` coroutine could resume inside the unlock and read stale state. State, feedback and commit callbacks now publish within the same lock as persistence. A burst of 30 commands, following a rejected command, retains all 30 results in memory and on disk.

## Automated validation

- 102 JVM tests pass, including four Robolectric SQLite migration/persistence tests.
- Six Android instrumentation scenarios pass on API 35: legacy migration, archive order across repeated saves/reopening, journal/recovery, rapid queued commands, corrupt legacy rollback, and missing-current-row failure.
- Debug app and instrumentation APK builds pass. Android lint reports zero errors and 34 warnings; warnings include plural-resource suggestions, dependency updates and existing manifest/style items.

The instrumentation runner uses dedicated `persistence-regression-*.db` files and isolated ViewModel preferences; it never replaces the running app's database. Run it on an emulator after building/installing both APKs:

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug
.tools/android-sdk/platform-tools/adb.exe -s emulator-5556 install -r app/build/outputs/apk/debug/app-debug.apk
.tools/android-sdk/platform-tools/adb.exe -s emulator-5556 install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
.tools/android-sdk/platform-tools/adb.exe -s emulator-5556 shell am instrument -w se.racelink.studio.demo.test/se.racelink.studio.PersistenceRegressionRunner
```

Check the runner output for six `PASS` lines and no `FAIL` or process-crash message; adb's process exit code alone does not establish success.

## Workflow checks

- An actual schema-2 installation migrated to schema 3 with all 37 runs in three sessions, race classes/assignments and six audit entries preserved.
- Started training, recorded two completed runs and one DNF, selected an earlier run, saved tags in both categories and a note, toggled DNF, and viewed structured before/after history.
- Filtered a report to one session/skier; previewed PDF with and without a graph and an individual PNG. Coaching details remained excluded by default.
- Saved a version-8 external backup and read back its JSON, checking the saved note, typed audit entries, current session and three archived sessions.
- Started a total-time race and simulated two consecutive results each for two skiers without switching rounds. Both slots and totals populated without extra results.
- Updated the normal local tablet emulator and compared persisted session data before/after: six sessions and 106 runs retained, including notes, tags and race settings. Synthetic fixtures stayed on the separate workflow emulator.

These checks cover the main software regressions; they do not establish physical timer compatibility or exhaustive touch/accessibility acceptance.
