# RaceLink Studio

RaceLink Studio is a Swedish-language Android app for ski training and two-run races. A coach uses a tablet at the finish to follow each skier's times, record feedback, compare runs and create reports. Sessions are stored on the tablet and work without a computer or internet connection. An optional webserver publishes personal result pages that skiers can open on their phones using a QR code.

**Current status:** a working development app with simulated timing. Integration with the Brower Race Link RL timer is planned; real Bluetooth timing capture has not yet been implemented or verified. Use the simulator to try the workflows.

## What the app does

- **Training:** reusable skier roster, bibs, attendance, session details and browsing of previous sessions.
- **Coaching:** multiple tags describing the run and what to focus on next, notes, DNF marking, and editable change markers that apply to subsequent runs.
- **Time card and charts:** latest or selected historical run, comparisons with previous and best runs, best-run highlighting, optional tag labels, pinch zoom and collapsible side panels. Selecting a run in the chart or table brings it into the time card.
- **Races:** two-run total time and differential slalom, ranked by the smallest absolute difference between two runs. Rankings are per class; two completed runs are required, DNF means no final placing, and exact ties share a placing.
- **Reports:** select skiers and all sessions, specific sessions or an inclusive date interval. Export PDF, CSV and individual image reports, with optional graphs and report logo. Tags and notes are optional in PDF reports and omitted from CSV.
- **Storage and recovery:** local SQLite storage, correction history, timing-review workflows, roster CSV import, and manual backup/restore with a recovery point.
- **Phone pages:** optional live publishing, per-skier QR codes on the time card, personal charts, best-run markers, report logo and an ongoing-session indicator. Race pages also show class result and place.
- **Start-coach page:** password-protected feedback from the finish, selectable skier history, run tags, next-focus tags, comments and applicable changes. The list predicts return order from the latest finishes and keeps the selected skier open during live updates.

The tablet is authoritative. Publishing is off by default, and network failures do not prevent local simulation, edits or reports. Coaching tags, notes and audit history are not published to personal pages.

## Run the Android app

### Android Studio setup

Requirements:

- Android Studio and JDK 17 or a compatible newer JDK.
- Android SDK Platform 35, Build Tools 35.0.0 and Platform Tools.
- Android 8.0+ (API 26+) device or emulator. A roughly 10-inch landscape tablet is the intended layout.
- Internet access for initial Gradle and dependency downloads.

1. Open the repository root in Android Studio and let Gradle sync.
2. Configure the Android SDK location when prompted. Android Studio writes the machine-specific, ignored `local.properties` file.
3. Create a tablet emulator in Device Manager, or connect a physical tablet by USB.
4. On a physical tablet, enable **Developer options → USB debugging**, unlock it and accept the computer's debugging authorization prompt.
5. Select the device and run the **app** configuration. The installed app is named **RaceLink Studio Demo**.

### Existing Windows development setup

```powershell
powershell -ExecutionPolicy Bypass -File .\run-local.ps1
```

This convenience launcher builds the debug APK, boots the `RaceLinkTablet` emulator on port 5554, installs the app and opens it. The first boot can take several minutes.

It requires the SDK in `.tools/android-sdk` and the existing AVD under `.tools/android-user/avd`. These ignored, machine-local tools are **not included in a fresh clone**. Use Android Studio setup above if they are missing. The launcher prefers Android Studio's bundled Java, with the locally installed JDK 22 as a fallback.

### Build and update a tablet from the command line

From the repository root, with Java and the Android SDK configured:

```powershell
.\gradlew.bat :app:assembleDebug
```

APK: `app/build/outputs/apk/debug/app-debug.apk`.

With Android Platform Tools on `PATH`, list devices and replace `DEVICE_SERIAL` with the intended device's serial:

```powershell
adb devices
adb -s DEVICE_SERIAL install -r app\build\outputs\apk\debug\app-debug.apk
adb -s DEVICE_SERIAL shell am start -n se.racelink.studio.demo/se.racelink.studio.MainActivity
```

`install -r` retains data when the package and signing key match. Keep using the same debug signing key for updates. For `INSTALL_FAILED_UPDATE_INCOMPATIBLE`, check the signing key before uninstalling: uninstalling removes local app data. Export a backup before any reinstall that removes data.

On macOS/Linux, use `sh gradlew` instead of `.\gradlew.bat` and forward slashes in paths.

### Try a simulated session

1. Explore the seeded demo session, or start a training session or race from **Meny**.
2. Open **Meny → Simulator**, select the next skier and simulate a finish. In a race, the simulator fills that skier's next available run slot.
3. Add feedback or mark DNF in the time card. Select older runs to inspect them without changing the next skier to receive a time.
4. Try change markers, session history and **Rapporter**. Close and reopen the app to check that the session is retained.
5. Use **Meny → Säkerhetskopiera och återställ** to save a transferable backup. Backups include private coaching notes; store them appropriately.

## Run the optional webserver

The Android app works without this service. Start it to test personal phone pages and QR pairing.

In a separate PowerShell terminal at the repository root:

```powershell
$env:RACELINK_PUBLISH_TOKEN = 'local-demo-token'
$env:PORT = '8080'
.\gradlew.bat :server:run
```

Leave the terminal running; Ctrl+C stops the server. Check [the health endpoint](http://localhost:8080/healthz), or open [the local link index](http://localhost:8080/) after publishing a session.

Environment variables are read at server startup:

| Variable | Purpose | Default |
| --- | --- | --- |
| `PORT` | HTTP listening port | `8080` |
| `RACELINK_PUBLISH_TOKEN` | Bearer token used by the tablet to publish | `dev-token`; set your own beyond local development |
| `RACELINK_RETENTION_HOURS` | Expiry after the last accepted publish; `0` disables expiry | `24` |
| `RACELINK_NAME_POLICY` | `first` for first names or `bib` for bib-only display | `first` |
| `RACELINK_ADMIN_PASSWORD` | Optional operator password for the link index | Unset; remote index access uses the session password configured on the tablet |
| `RACELINK_DATA_FILE` | Atomic persistent snapshot path for a single server instance | Unset (memory only) |

For a built distribution instead of running through Gradle:

```powershell
.\gradlew.bat :server:installDist
.\server\build\install\server\bin\server.bat
```

### Connect the tablet and a phone

1. Open **Inställningar → Publicering** in the app.
2. Enter the server base URL and the same publish token used above (`local-demo-token` in this example). Configure the session's link-index password and name-display preference.
3. Enable publishing, save and publish the session. Check the publishing status for errors or retries.
4. View a skier's time card and tap the QR code to enlarge it. Scan it with a phone to open that skier's personal page.

Choose a URL reachable from the devices scanning the QR code:

- **Tablet and phone on the same LAN:** use the computer's LAN address, for example `http://192.168.1.100:8080`. Allow the server port through the computer's firewall if needed.
- **Android emulator only:** `http://10.0.2.2:8080` reaches the host computer. This emulator-specific address does not work in QR links scanned by a physical phone; use the LAN address when testing both together.
- **Remote phones:** use a publicly reachable HTTPS server. These local commands do not set up public deployment. HTTP is for local debug testing; use HTTPS for deployment.

`localhost` on a tablet or phone refers to that device, not the development computer. QR codes are available after a server URL is configured, including while publishing is off; results require a successful publish.

Set `RACELINK_DATA_FILE` to a writable file path to retain published sessions, revisions, retention timestamps and hashed index passwords across restarts. Without it, storage is in memory and restarting requires republishing. Use one server process per data file. This does not affect the tablet's saved sessions. See [server operations](11-server-operations.md), [live publishing documentation](09-live-publishing-server.md), and [data protection and consent](10-gdpr-and-consent.md).

## Validation

Run JVM tests and Android lint from the repository root:

```powershell
.\gradlew.bat :app:testDebugUnitTest :shared:test :server:test :app:lintDebug
```

Build the APK with `:app:assembleDebug`. Device checks, persistence regression checks and the manual workflow checklist are in [08-regression-validation.md](08-regression-validation.md). Hardware timing and field readiness still require validation with the actual timer.

## Project structure and documentation

| Path | Contents |
| --- | --- |
| `app/` | Kotlin/Jetpack Compose tablet app, domain logic, SQLite storage, reports and publishing client |
| `shared/` | Kotlin publishing contract shared by app and server |
| `server/` | Kotlin/Ktor service and mobile personal result pages |
| `run-local.ps1` | Launcher for the existing Windows tablet emulator setup |

The numbered documents include planning history; later implemented behavior supersedes earlier proposals.

- [Implementation history and technical details](IMPLEMENTATION.md)
- [Product requirements and UI flows](01-product-requirements.md)
- [Architecture and data model](02-architecture-and-data-model.md)
- [Hardware unknowns and simulator plan](03-hardware-and-simulator.md)
- [Backlog and roadmap](04-backlog-and-roadmap.md)
- [Feature and design decisions](05-feature-and-design-interview.md)
- [UI cleanup and feature plan](06-ui-cleanup-and-feature-plan.md)
- [Race mode](07-race-mode.md)
- [Regression validation](08-regression-validation.md)
- [Live publishing server](09-live-publishing-server.md)
- [Server deployment and operations](11-server-operations.md)
- [Data protection and consent](10-gdpr-and-consent.md)
- [Personal webpage updates](10-personal-webpage-updates.md)
