# Remote command daemon (survives car power-off)

VoltFlow Mate can execute cloud commands (lock, climate, windows, SOC limit, TTS…) **while
the head unit is parked / "off"**. This document explains how and the exact install / update steps.

## Why a separate daemon

The in-app poller [`VehicleCommandPoller`](../app/src/main/kotlin/com/bydmate/app/data/remote/VehicleCommandPoller.kt)
runs inside the `com.bydmate.app` / `dev.scroodge.cloudevmate` **app process**. When the BYD head
unit parks, its power-off routine (`collectPowerOffEvent` → `quickboot`) **force-stops** that
process, so command polling stops the moment the car sleeps.

[`CommandDaemon`](../app/src/main/kotlin/com/bydmate/app/daemon/CommandDaemon.kt) is the
survival-proof twin. It is launched as a **shell-uid `app_process` daemon** (the same mechanism
DI+ `aps_diplus` and Overdrive `acc_sentry_daemon` use), which is **not an "app"** and therefore
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
```

## Components

| Piece | Where | Role |
|---|---|---|
| `CommandDaemon` | in the APK (`com.bydmate.app.daemon`) | the poll→guard→actuate→ack loop |
| `start_voltflow_cmd.sh` | `/data/local/tmp/` (from [`tools/`](../tools/start_voltflow_cmd.sh)) | watchdog: launches & respawns the daemon, auto-restarts it after an APK update |
| `voltflow_cmd.conf` | `/data/local/tmp/` | cloud creds (url / api_key / vehicle_id) |
| `exportDaemonConfig()` | `TrackingService` | app writes the conf to external storage so the shell daemon can read it |

The daemon runs as `--nice-name=voltflow_cmd_daemon`; its log is `/data/local/tmp/voltflow_cmd_daemon.log`.

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

## Boot persistence

The daemon survives **power-off / sleep** (shell-uid) but **not a full reboot** — after a reboot
something must re-run `start_voltflow_cmd.sh`. The app cannot do this itself. Use one of:

- **Termux:Boot** (recommended): install Termux:Boot, then create
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
| Nothing after reboot | boot persistence not set up — see above |

## Safety

Actuation only ever calls DiPlus `127.0.0.1/api/sendCmd` with phrases from `CommandAllowlist`;
`DiParsControlClient` additionally blocks dangerous patterns (`发送CAN`, `执行SHELL`, `下电`…).
Movement and low-12V guards reject commands unless the car is parked.
