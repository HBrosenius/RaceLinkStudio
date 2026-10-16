# Server operations

## LAN deployment

The HTTP origin is `http://192.168.1.106:8080` on Debian 12. The server runs as the dedicated `racelink` user under systemd, independently of the existing applications.

- Application distribution: `/opt/racelink`
- Environment and secrets: `/etc/racelink/server.env` (root-only)
- Persistent projection snapshot: `/var/lib/racelink/sessions.json`
- Service: `racelink.service`, enabled at boot and restarted on failure
- Java: OpenJDK 17; maximum application heap 512 MB
- Retention: 24 hours after the last accepted publish

```sh
systemctl status racelink
journalctl -u racelink -n 100 --no-pager
systemctl restart racelink
curl --fail http://127.0.0.1:8080/healthz
```

The publish token and optional index administrator password are generated during installation. Read them locally as root from `/etc/racelink/server.env`; do not commit or share that file. Enter the publish token in the tablet's publishing settings. A coach can set a session-specific index password from the tablet.

Admin links to **Starttränare** at `/coach`. It uses the same login and shows private finish-line tags and comments after the updated tablet app republishes a session. These fields are stored in the same protected snapshot and removed by the same retention/deletion rules. Server restarts invalidate coach login cookies, so sign in again afterwards.

## Public HTTPS address

Point the HTTPS tunnel's origin at `http://192.168.1.106:8080`, or `http://localhost:8080` if its connector runs on this server. Set the tablet's publishing URL to the final public HTTPS address so generated QR links work on phones outside the LAN. Keep that hostname stable. HTTP credentials on the origin connection rely on the LAN being trusted.

## Persistence and backups

`RACELINK_DATA_FILE` enables versioned JSON snapshots, written by atomic replacement. Sessions, receipt times and salted index password hashes survive restarts. Login cookies are intentionally invalidated by a restart. Corrupt or unsupported snapshots fail startup rather than silently discarding data. Run only one service instance against this file.

Back up `/var/lib/racelink/sessions.json` and the protected configuration to a secure external location if server recovery is needed. No scheduled external backup is configured. Retention and hard deletion update the snapshot as well as memory; apply the same privacy policy to backups. The authoritative full training data remains on the tablet and needs its own backup.

## Build and update

Build and test from the repository root:

```powershell
.\gradlew.bat :server:test :server:installDist
```

The distribution is `server/build/install/server/`. Transfer its `bin/` and `lib/` directories into `/opt/racelink`, retaining `/etc/racelink/server.env` and `/var/lib/racelink`. Stop the service while replacing the distribution, ensure `bin/server` is executable, then start it and check health and logs. Keep the previous distribution for rollback.

The initial deployment was checked with a temporary fictional session: authenticated publishing, personal page access, restart recovery, persistent index-password protection, and hard deletion. The test session is removed after validation.
