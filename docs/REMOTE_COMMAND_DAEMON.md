# Remote command daemon (survives car power-off)

VoltFlow Mate can execute cloud commands (lock, climate, windows, SOC limit, TTS…) **and push
live telemetry to the cloud** while the head unit is parked / "off". This document explains how
and the exact install / update steps.

## Why a separate daemon

The in-app poller [`VehicleCommandPoller`](../app/src/main/kotlin/com/bydmate/app/data/remote/VehicleCommandPoller.kt)
runs inside the `com.bydmate.app` / `dev.scroodge.cloudevmate` **app process**. When the BYD head
unit parks, its power-off routine (`collectPowerOffEvent` → `quickboot`) **force-stops** that
process, so command polling stops the moment the car sleeps.

[`CommandDaemon`](../app/src/main/kotlin/com/bydmate/app/daemon/CommandDaemon.kt) is the
survival-proof twin. It is launched as a **shell-uid `app_process` daemon** (the same mechanism
DI+ `aps_diplus` uses), which is **not an "app"** and therefore
survives the force-stop. It needs **no Android Context**: both telemetry read and actuation go
over plain localhost HTTP to DiPlus at `http://127.0.0.1:8988`, and it reuses the app's own
[`CommandAllowlist`](../app/src/main/kotlin/com/bydmate/app/data/remote/CommandAllowlist.kt) (movement /
aux-voltage guards + phrase allowlist) and DiPlus clients verbatim — no command logic is duplicated.

```
Supabase /api/bydmate/commands ──poll──> CommandDaemon (app_process, uid shell)
                                              │  guards via DiPlus getDiPars
                                              │  CommandAllowlist.buildPhrase
                                              ▼
                              DiPlus 127.0.0.1:8988 /api/sendCmd  ──> vehicle (CAN)
                                              │
                              POST /api/bydmate/commands/ack  <───┘

DiPlus 127.0.0.1:8988 /api/getDiPars ──read──> CommandDaemon
                                                    │  normal cadence every 60 s, or immediately on gun change
                                                    │  live fast mode: status-only every 3 s
                                                    │  ONLY when the app is NOT sending
                                                    │  (app-alive beacon stale AND not DRIVING)
                                                    ▼
                              POST /api/bydmate/telemetry ──> Supabase bydmate_live_snapshots
```

### Telemetry: the daemon and the app never send at the same time

The daemon's reason to exist is the window when BYD force-stops the app. While the app **is**
alive it already streams telemetry (1 Hz driving / 10 s charging bulk / 1 s charging tail /
30 s parked), so a parallel
60 s daemon heartbeat would only duplicate samples — and because each source stamps its own
`device_time`, the duplicates never dedup, which raised the risk of phantom trips on D→R→P
parking maneuvers. Since **v0.3.9.5** the two are mutually exclusive on telemetry:

- `TrackingService.writeAppAliveHeartbeat()` writes epoch-millis to
  `<externalFilesDir>/voltflow_mate_heartbeat`. This lands at **1 Hz** — the poll loop calls it
  after every successful DiPars read, not once per flush — so the file's age is a fine-grained
  "is the app's loop still turning" signal.
- `CommandDaemon.shouldDeferToApp()` grades that age by what the push would actually write
  (`"telemetry push skipped (app alive …)"`, throttled to one line per minute):

  | Push kind | Threshold | Why |
  | --- | --- | --- |
  | history-writing (cadence, gun edge, forced-full) | `APP_ALIVE_FULL_TTL_MS` = **20 s** | a false "app is dead" stores a duplicate sample, so it keeps margin for a GC pause |
  | status-only (`live_only`) | `APP_ALIVE_LIVE_TTL_MS` = **5 s** | writes no history row at all — worst case is a few seconds of the daemon's reduced snapshot |

- A second guard skips the push while `classifyFromDiPars == DRIVING`, belt-and-suspenders
  against a reduced-payload `gear=1` heartbeat splitting a live trip. This one is **not** graded
  by `live_only`: the daemon has no GPS and stamps `device_time = now`, so it would beat the
  app's batched samples through the server's stale-guard and blank the live map mid-drive.
- `TrackingService.onDestroy()` deletes the beacon so a graceful stop hands over at once. A
  power-off force-stop never runs `onDestroy`, which is why the thresholds above still matter.

**Operational impact (2026-07-22).** These thresholds replaced a single flat 120 s TTL, and the
push timers now advance only when a push actually went out — previously a *skipped* push still
reset the 60 s history cadence, so after the beacon finally aged out the daemon had just
restarted its own clock. Together those two produced a measured **124-236 s** of stale live
status after every park (14 prod transitions on `way`, 2026-07-21/22). Expected now: **~13 s**
with the live view open, **~31 s** without. Because the cadence timer is no longer reset by
skips, the first push after handover is a **full** sample — a real parked history row at trip end.

### Fast D → P → power-off handoff

The cloud sender retains its 2-minute drive latch after D/R/N or movement so a brief P blip
does not split a trip. It nevertheless recognizes a real shutdown separately: when an already
parked `gear=P` sample is followed by an explicit DiPars `powerState` transition from on to off,
the final sample is queued and flushed immediately. `TrackingService` serializes that enqueue
before the flush; it must not launch them as independent coroutines, because the flush could run
first and leave the final P/off sample in Room until the next boot.

If BYD kills the process before the HTTP acknowledgement, Room retains the row and the shell daemon
remains the fallback. Verify this path after an APK update by driving, parking, then switching off
within a few seconds; confirm a fresh P/off `bydmate_live_snapshots` row within the live flush
window, and then confirm daemon telemetry still arrives after the app is force-stopped.

**Command polling stays always-on** regardless of app liveness — commands are idempotent and
server-acked, so a brief double-poll is harmless and maximizes control reliability.

Every payload (app and daemon) carries a root `mate_version` field (`BuildConfig.VERSION_NAME`);
the server stores it in `bydmate_live_snapshots.mate_version` so the APK version running on each
head unit is visible — see [cloud-telemetry-contract-ru.md](cloud-telemetry-contract-ru.md).

**Proven behavior** (Yuan Up 2024, DiLink 3.0, 2026-06-10):
- Car off (`PWR=0`), app force-stopped by BYD `collectPowerOffEvent`
- Daemon (uid shell) survived; DiPlus still accessible at `127.0.0.1:8988`
- `bydmate_live_snapshots` updated at the normal ~60 s cadence (`SOC=32, PWR=0, GUN=1, V12=13.7`)
- Network stayed alive thanks to head-unit **"Keep network on while parked"** setting (see below)

> **Known limitation**: DiPlus `迪加`-phrases require `电源状态 ≥ 1` (car ON) to actuate physical
> hardware (windows, locks, AC). At `PWR=0` DiPlus ACKs the phrase but nothing moves. The daemon
> still queues and acks commands — they will execute the next time the car is powered on.
> Official BYD remote commands use a separate T-BOX/CAN wake channel.

## Components

| Piece | Where | Role |
|---|---|---|
| `CommandDaemon` | in the APK (`com.bydmate.app.daemon`) | poll→guard→actuate→ack loop + normal 60 s telemetry cadence (only when the app is not sending); gun-state edges are immediate and an active live-view grant adds 3 s `live_only` status pushes |
| `start_voltflow_cmd.sh` | `/data/local/tmp/` (from [`tools/`](../tools/start_voltflow_cmd.sh)) | watchdog: launches & respawns the daemon, auto-restarts it after an APK update, and (since B-08, 2026-07-22) relaunches the main app itself via `am start` if `pidof dev.scroodge.cloudevmate` comes back empty during the 30 s watcher tick — cooldown-gated (`APP_RELAUNCH_COOLDOWN_SEC=60`, state in `/data/local/tmp/voltflow_app_relaunch_ts`) so a genuinely broken app isn't hammered |
| `assets/start_voltflow_cmd.sh` | APK asset copied to `<externalFilesDir>/start_voltflow_cmd.sh` by `TrackingService.deployDaemonLauncher()` | automatic app-side launcher used when the app revives the daemon after boot/quickboot |
| `voltflow_cmd.conf` | `/data/local/tmp/` | cloud creds (url / api_key / vehicle_id) |
| `exportDaemonConfig()` | `TrackingService` | app writes the conf to external storage so the shell daemon can read it |
| `voltflow_mate_heartbeat` | `<externalFilesDir>/` | app-alive beacon (epoch millis, rewritten at 1 Hz); daemon reads its age to suppress its telemetry push while the app is sending — 20 s for history-writing pushes, 5 s for `live_only` status |

The daemon runs as `--nice-name=voltflow_cmd_daemon`; its log is `/data/local/tmp/voltflow_cmd_daemon.log`.

### Suspend-blocker capability report

The shell daemon has no Android `Context`, so it acquires its partial wakelock through the platform
`IPowerManager` binder rather than `PowerManager.newWakeLock`. On startup it logs the Android
release/API level and the runtime `acquireWakeLock` argument count; the first charger-connected or
live-view iteration then logs either `wakelock acquired via IPowerManager` or a precise binder
failure followed by the legacy sysfs fallback. This lets a DiLink 5 owner return compatibility
evidence without remote ADB access:

```sh
grep -E 'wakelock (IPowerManager|acquired|released|sysfs)' \
  /data/local/tmp/voltflow_cmd_daemon.log
```

The sysfs fallback is expected to report `EACCES` on the verified DiLink 3 Android 10 unit. Do not
infer DiLink 5 compatibility from that unit: retain the corresponding startup and first-use lines
with the car's Android/API values.

### Bounded post-park wake window

On an observed DiPars transition from power-on (`powerState >= 1`) to power-off, the daemon keeps
its partial wakelock for **30 minutes**, even when the charge gun is unplugged. This preserves a
responsive status/command handoff after parking without making the 12 V battery power the head unit
indefinitely. The budget is intentionally non-renewing and process-local: a daemon restart while
the car is already off, periodic platform wakes, or incomplete DiPars data cannot create a new
window. A later power-on clears it immediately. Logs show `parked wake window started` and the
normal wakelock acquire/release evidence; verify a parked 30-minute run on-car before relying on it
for remote control latency.

### Launcher source of truth

There are two copies of the watchdog launcher in the repository:

- [`tools/start_voltflow_cmd.sh`](../tools/start_voltflow_cmd.sh) — the script pushed manually to
  `/data/local/tmp/`.
- [`app/src/main/assets/start_voltflow_cmd.sh`](../app/src/main/assets/start_voltflow_cmd.sh) —
  the script bundled into the APK and copied by `TrackingService.deployDaemonLauncher()` to
  `<externalFilesDir>/start_voltflow_cmd.sh`.

Keep these files byte-for-byte in sync. If only `tools/start_voltflow_cmd.sh` is fixed, manual ADB
installs may use the new watchdog, but app self-revival after boot/quickboot will still deploy the
old asset. This is a real failure mode: on 2026-06-19 the car had stale launchers in both
`/data/local/tmp/start_voltflow_cmd.sh` and
`/storage/emulated/0/Android/data/dev.scroodge.cloudevmate/files/start_voltflow_cmd.sh`, while the
repo's `tools/start_voltflow_cmd.sh` had the v0.4.0 single-instance guard. The app revived the daemon
with the stale asset.

Before every APK release that touches the watchdog, verify:

```sh
cmp -s tools/start_voltflow_cmd.sh app/src/main/assets/start_voltflow_cmd.sh \
  && echo "launcher copies match" \
  || echo "launcher copies differ"
```

## Network keep-alive (required for car-off operation)

By default, the BYD head unit drops WiFi ~9 minutes after the car is switched off.
Enable **Settings → Connectivity → "Keep network on while parked"** (or equivalent) on the head
unit so the WiFi connection to your phone hotspot (gateway `192.168.43.1`) stays up indefinitely.
Without this, the daemon survives but loses cloud connectivity after ~9 min.

### Automated keep-alive (experimental, opt-in)

Instead of relying on the user finding that DiLink toggle, `CommandDaemon` can re-assert WiFi
itself: **Settings → Cloud Sync → "Keep Wi-Fi awake while parked"**
(`SettingsRepository.KEY_CLOUD_SYNC_KEEP_WIFI_AWAKE`). When on, `TrackingService.exportDaemonConfig()`
writes `keep_wifi_awake=1` to `voltflow_cmd.conf`, and the daemon's main loop runs
`svc wifi enable` every ~60s (`CommandDaemon.shouldRefreshWifiKeepalive`) regardless of DiLink's
own connectivity setting — same trick competitor BYD EV Pro's `ProcessExemptionController` uses
(see [`EV_PRO_APP_ANALYSIS.md`](EV_PRO_APP_ANALYSIS.md) section 4). Idempotent and cheap: a no-op
if WiFi is already on, and no ADB round-trip since the daemon already runs as shell uid. Off by
default — needs real-car testing before it becomes the recommended path over the manual DiLink
toggle. Verify with:

```sh
adb -s $HOST shell "tail -f /data/local/tmp/voltflow_cmd_daemon.log" | grep "wifi keepalive"
```

### Autoservice parity check (experimental, always-on when the daemon runs)

Every telemetry push, the daemon now also reads SOC and engine power directly from the
`autoservice` binder (bypassing DiPlus entirely for these two fields — same fids as
[`FidRegistry`](../app/src/main/kotlin/com/bydmate/app/data/autoservice/FidRegistry.kt)) and logs
them next to the DiPlus-sourced values, purely for comparison — nothing is sent to the cloud from
this path yet:

```sh
adb -s $HOST shell "tail -f /data/local/tmp/voltflow_cmd_daemon.log" | grep "autoservice check"
# expect: autoservice check: soc=79.0 (diplus=79) power_kw=-4 (diplus=-4)
```

**2026-07-22 update:** the daemon also logs a second parity line covering doors, trunk, hood, and
tire pressure — 16 fids total (including 12 window/sunroof/sunshade fids in `FidRegistry` not yet
wired into a log line), sourced from the real BYD vendor SDK
(`android.hardware.bydauto.BYDAutoFeatureIds`, decompiled from this car's own
`/system/framework/framework.jar`) rather than guessed magic numbers, and live-validated 16/16
against di+ the same day (see [`FidRegistry.kt`](../app/src/main/kotlin/com/bydmate/app/data/autoservice/FidRegistry.kt)
for the architecture-conditional caveat — 12 of the 16 values only apply to CANFD-platform BYDs):

```sh
adb -s $HOST shell "tail -f /data/local/tmp/voltflow_cmd_daemon.log" | grep "autoservice check2"
# expect: autoservice check2: doors/trunk/hood(fl/fr/rl/rr/trunk/hood)=0/0/0/0/0/0 (diplus=0/0/0/0/0/0) tires_kpa(fl/fr/rl/rr)=250/247/245/247 (diplus=250/247/245/247)
```

This is the evidence-gathering step for backlog **B-07** (dropping the di+ dependency for reads)
— see [`docs/BACKLOG.md`](BACKLOG.md#кандидаты-не-запланировано). Once enough real-drive/charge/park
log samples confirm parity, the DiPars-sourced fields can be replaced outright instead of just
cross-checked.

## Prerequisites (one time)

- **Wireless ADB enabled** on the head unit, or **Termux** (the daemon must be started by a
  *shell-uid* context — a normal app cannot launch it; there is no Shizuku and
  `AdbOnDeviceClient` is intentionally write-barriered).
- Cloud Sync configured in the app (Settings → Cloud Sync: URL, API key, Vehicle ID) — the app
  exports these to the conf automatically on `TrackingService` start.

## First-time install

```sh
HOST=192.168.43.71:5555          # head unit adb address (adjust)
adb connect $HOST

# 1. Install the APK (see "Building" below) and open the app once so it exports the conf.
adb -s $HOST install -r app/build/outputs/apk/debug/VoltFlow-Mate-v*.apk
adb -s $HOST shell monkey -p dev.scroodge.cloudevmate -c android.intent.category.LAUNCHER 1
#    -> verify: /storage/emulated/0/Android/data/dev.scroodge.cloudevmate/files/voltflow_cmd.conf exists

# 2. Push the watchdog launcher.
adb -s $HOST push tools/start_voltflow_cmd.sh /data/local/tmp/
adb -s $HOST shell chmod 755 /data/local/tmp/start_voltflow_cmd.sh

# 3. Start it detached (survives adb disconnect + power-off).
adb -s $HOST shell "setsid sh /data/local/tmp/start_voltflow_cmd.sh >/dev/null 2>&1 &"

# 4. Verify.
adb -s $HOST shell "ps -A | grep voltflow_cmd_daemon"      # daemon running
adb -s $HOST shell "tail /data/local/tmp/voltflow_cmd_daemon.log"
```

The launcher seeds `voltflow_cmd.conf` from the app's exported copy on every loop, so step 1's
conf is picked up automatically.

## Updating

### Updating the APK (daemon code change) — the common case

```sh
adb -s $HOST install -r app/build/outputs/apk/debug/VoltFlow-Mate-v<new>.apk
```

That's it **if the watchdog is already running**: `start_voltflow_cmd.sh` detects the changed
package path within ~30 s and restarts the daemon on the new code automatically. Reopen the app
once if you changed the cloud creds (so `exportDaemonConfig` rewrites the conf).

> **Must use a DEBUG build.** The installed app is debug-signed; a release-signed APK fails with
> `INSTALL_FAILED_UPDATE_INCOMPATIBLE`. Never uninstall to swap signatures — that wipes the
> in-app cloud config.

### Updating the launcher script (`start_voltflow_cmd.sh`)

The launcher lives in `/data/local/tmp`, **not** in the APK, so an APK update does **not** change
it. Re-deploy manually:

```sh
adb -s $HOST shell "pkill -f start_voltflow_cmd; pkill -f voltflow_cmd_daemon"
adb -s $HOST push tools/start_voltflow_cmd.sh /data/local/tmp/
adb -s $HOST shell "chmod 755 /data/local/tmp/start_voltflow_cmd.sh; setsid sh /data/local/tmp/start_voltflow_cmd.sh >/dev/null 2>&1 &"
```

If the change must also apply to app self-revival after boot/quickboot, also copy the same script to
`app/src/main/assets/start_voltflow_cmd.sh`, rebuild the APK, install it, open the app once, and
confirm the deployed external-files launcher has the same content. The app does **not** read
`/data/local/tmp/start_voltflow_cmd.sh` when it supervises the daemon; it deploys and starts the
bundled asset.

## Boot persistence

The daemon survives **power-off / sleep** (shell-uid) but **not a full reboot** — after a reboot
something must re-run `start_voltflow_cmd.sh`.

**Primary (automatic, no extra setup): the app revives the daemon on start.**
`TrackingService` is auto-started on boot/quickboot (`BootReceiver` → `ServiceStartWorker`).
On every start (when cloud sync is on) it calls `ensureCommandDaemonRunning()`:

1. connect to **on-device ADB** (127.0.0.1:5555) via `AdbOnDeviceClient` (shell uid),
2. verify both `voltflow_cmd_daemon` **and** its watchdog shell,
3. if either is missing, deploy the bundled launcher (`assets/start_voltflow_cmd.sh` →
   `getExternalFilesDir()/start_voltflow_cmd.sh`, shell-readable) and start it detached with
   `setsid`.

So a full reboot self-heals once DiLink finishes booting and the app autostarts — **no manual
`adb`/`setsid` and no `/data/local/tmp` push required** (the launcher ships inside the APK).
Requirement: **on-device wireless ADB enabled and the app's ADB key authorised once** (same
"Allow USB debugging?" prompt the app already uses for D+ launch / usage-stats appop). If ADB is
not authorised, `ensureCommandDaemonRunning()` logs a warning and no-ops — fall back below.

> **Supervisor guard:** `ensureCommandDaemonRunning()` checks both the daemon PID and the
> watchdog shell. If either is missing it relaunches the hardened launcher. Keep the two
> launcher copies in sync; a stale asset can still recreate an old watchdog after quickboot.

**Fallbacks (if on-device ADB is unavailable):**

- **Termux:Boot**: install Termux:Boot, then create
  `~/.termux/boot/voltflow-cmd.sh` containing:
  ```sh
  #!/data/data/com.termux/files/usr/bin/sh
  sh /data/local/tmp/start_voltflow_cmd.sh >/dev/null 2>&1 &
  ```
  (The launcher already waits for `sys.boot_completed` internally.)
- Or an ADB-helper / autostart tool that runs the same line on boot.

## Verify end-to-end

```sh
# Insert a harmless TTS command (car speaks) and watch the daemon execute it.
# (replace <SB_URL>/<SERVICE_KEY>/<USER_ID> with your values)
curl -s "<SB_URL>/rest/v1/vehicle_commands" \
  -H "apikey: <SERVICE_KEY>" -H "Authorization: Bearer <SERVICE_KEY>" \
  -H "Content-Type: application/json" \
  -d '{"user_id":"<USER_ID>","vehicle_id":"way","type":"tts","params":{"text":"daemon test"},"status":"pending"}'

adb -s $HOST shell "tail -f /data/local/tmp/voltflow_cmd_daemon.log"
# expect: received 1 command(s) / executed 'tts' / ack HTTP 200
```

### Verify daemon survival and telemetry

Use these checks after an APK update, launcher update, or car sleep incident:

```sh
HOST=192.168.43.71:5555
adb connect $HOST

# Process health: need daemon, and ideally a parent watchdog shell too.
adb -s $HOST shell "ps -A | grep -E 'voltflow_cmd_daemon|start_voltflow|app_process| sh$'"
adb -s $HOST shell "pidof voltflow_cmd_daemon"

# Watchdog files and disabled sentinel.
adb -s $HOST shell "ls -l /data/local/tmp/voltflow_cmd* /data/local/tmp/start_voltflow_cmd.sh 2>/dev/null"
adb -s $HOST shell "ls -l /data/local/tmp/voltflow_cmd_daemon.disabled 2>/dev/null || echo no-disabled-sentinel"

# App-deployed launcher and heartbeat used by the app-alive guard.
adb -s $HOST shell "ls -l /storage/emulated/0/Android/data/dev.scroodge.cloudevmate/files/start_voltflow_cmd.sh /storage/emulated/0/Android/data/dev.scroodge.cloudevmate/files/voltflow_mate_heartbeat 2>/dev/null"

# Recent daemon log.
adb -s $HOST shell "tail -n 80 /data/local/tmp/voltflow_cmd_daemon.log"
```

Interpretation:

- `voltflow_cmd_daemon` present + fresh log lines = daemon is alive.
- Fresh app heartbeat + log line `telemetry push skipped (app alive ...)` = daemon is correctly
  staying quiet because VoltFlow Mate is sending live telemetry. That line is throttled to one
  per minute per reason, so a single occurrence covers a whole quiet stretch — check the
  heartbeat's own age, not the log frequency, to judge whether the app is still alive.
- No `voltflow_cmd_daemon`, stale watchdog PID, and old log timestamp = sleep/offline coverage is
  broken until the app wakes and relaunches it.
- Fresh server telemetry with `is_charging=true`, `charge_power_kw > 0`, and `charge_gun_state=2`
  proves the app/car data path is healthy while the car is awake; it does not prove daemon survival
  after car-off.

### 2026-06-19 incident: stale launcher and missing watchdog

Observed on the real car (`192.168.43.71:5555`):

- ADB was connected and the head unit was reachable.
- Before the car was turned on, there was no `voltflow_cmd_daemon` process and no matching
  `start_voltflow`/`app_process` watchdog process.
- `/data/local/tmp/voltflow_cmd_watchdog.pid` existed but pointed to a dead PID.
- `/data/local/tmp/voltflow_cmd_daemon.log` showed telemetry HTTP 200 until 08:36, then
  `telemetry push skipped (app alive ...)`, repeated `poll error: timeout`, and stopped at 08:55.
- After the car was turned on, the app relaunched the daemon; DB telemetry became fresh again with
  `SOC=79`, `is_charging=true`, `charge_power_kw=4`, `power_kw=-4`, and `charge_gun_state=2`.

Root cause found in code/package state:

- `tools/start_voltflow_cmd.sh` had the v0.4.0 lock/single-instance watchdog guard.
- `app/src/main/assets/start_voltflow_cmd.sh` did **not** have that guard.
- The app self-revival path deploys the asset, not the `tools/` script.
- Both on-device launchers were stale, so the watchdog supervision was weaker than expected.

Action items:

1. Sync `tools/start_voltflow_cmd.sh` to `app/src/main/assets/start_voltflow_cmd.sh`.
2. Rebuild and install the APK.
3. Force-redeploy/restart the daemon launcher on the car.
4. Improve `ensureCommandDaemonRunning()` to verify watchdog health, not only daemon PID.
5. Run a car-off test at 2, 5, 10, and 20 minutes and confirm both process health and fresh DB
   telemetry.

## Stop / disable

```sh
adb -s $HOST shell "touch /data/local/tmp/voltflow_cmd_daemon.disabled"   # watchdog exits next loop
adb -s $HOST shell "pkill -f voltflow_cmd_daemon"                          # kill the running daemon
# re-enable: rm the .disabled sentinel, then relaunch the launcher (install step 3).
```

## Troubleshooting

| Symptom | Check |
|---|---|
| Daemon not running | `ps -A | grep voltflow_cmd_daemon`; read `voltflow_cmd_daemon.log` |
| `CommandDaemon` config blank | conf missing/incomplete — open the app (Cloud Sync must be enabled) so `exportDaemonConfig` runs; confirm `/data/local/tmp/voltflow_cmd.conf` |
| Commands stay `pending` | daemon can't reach the cloud — check head-unit WiFi/`curl` to the URL; check api_key/vehicle_id |
| Commands `rejected` `vehicle_moving`/`gear_not_park` | safety guard — car must be parked (speed 0, gear P) |
| Old code still running after APK update | watchdog restarts within ~30 s; or `pkill -f voltflow_cmd_daemon` to force immediate respawn |
| Daemon starts after car wakes but disappears during sleep | check for stale launcher asset, stale watchdog PID, missing watchdog shell, and whether `ensureCommandDaemonRunning()` only saw an already-running daemon |
| `/data/local/tmp/start_voltflow_cmd.sh` fixed but app relaunch still old | `app/src/main/assets/start_voltflow_cmd.sh` is stale; sync the asset and rebuild APK |
| Nothing after reboot | boot persistence not set up — see above |

## Safety

Actuation only ever calls DiPlus `127.0.0.1/api/sendCmd` with phrases from `CommandAllowlist`;
`DiParsControlClient` additionally blocks dangerous patterns (`发送CAN`, `执行SHELL`, `下电`…).
Movement and low-12V guards reject commands unless the car is parked.
