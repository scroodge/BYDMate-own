#!/system/bin/sh
# VoltFlow Mate command-poll daemon — survival watchdog launcher.
#
# Runs CommandDaemon (com.bydmate.app.daemon.CommandDaemon) as a shell-uid app_process
# daemon so it survives BYD's power-off force-stop (collectPowerOffEvent / quickboot),
# exactly like DI+ (aps_diplus) and Overdrive (acc_sentry_daemon) do.
#
# Also supervises the main app process itself (dev.scroodge.cloudevmate): if it's not
# running, the watcher loop below relaunches it via a privileged `am start`, the same
# way competitor BYD EV Pro's ProcessExemptionController does for its own app — see
# docs/EV_PRO_APP_ANALYSIS.md section 4 and docs/BACKLOG.md B-08. This covers a hard
# crash/OOM kill that TrackingService's own onTaskRemoved restart and the boot
# receiver don't reach (those cover "swiped from recents" and "full reboot" only).
#
# Also brings up the VoltFlow Dashboard cluster projection (com.voltflow.dashboard) once
# per power cycle — see dashboard_autostart_tick below for why that app cannot do it
# itself on this head unit. Opt out with /data/local/tmp/voltflow_dash_autostart.disabled.
#
# Deploy once via wireless ADB or Termux (shell uid):
#   1. Put cloud config at /data/local/tmp/voltflow_cmd.conf  (see voltflow_cmd.conf.example)
#   2. adb push tools/start_voltflow_cmd.sh /data/local/tmp/ && chmod 755 /data/local/tmp/start_voltflow_cmd.sh
#   3. setsid sh /data/local/tmp/start_voltflow_cmd.sh >/dev/null 2>&1 &
# To stop: touch /data/local/tmp/voltflow_cmd_daemon.disabled   (watchdog exits on next loop)
# To re-enable: rm that sentinel and relaunch step 3.

PKG="dev.scroodge.cloudevmate"
CLS="com.bydmate.app.daemon.CommandDaemon"
CONF="/data/local/tmp/voltflow_cmd.conf"
# The app (TrackingService.exportDaemonConfig) writes creds here — shell-readable mirror of app settings.
APP_CONF="/storage/emulated/0/Android/data/$PKG/files/voltflow_cmd.conf"
PROCESS_NAME="voltflow_cmd_daemon"
LOG_FILE="/data/local/tmp/voltflow_cmd_daemon.log"
SENTINEL="/data/local/tmp/voltflow_cmd_daemon.disabled"
LOCKFILE="/data/local/tmp/voltflow_cmd_watchdog.pid"
# App-liveness relaunch cooldown state — last successful relaunch attempt (epoch seconds).
APP_RELAUNCH_TS_FILE="/data/local/tmp/voltflow_app_relaunch_ts"
APP_RELAUNCH_COOLDOWN_SEC=60

# --- VoltFlow Dashboard (cluster projection) auto-start ----------------------------
# Separate APK. It projects the gauge cluster onto the 1280x480 virtual display, and it
# sends the Di+ projection command from exactly one place — LauncherActivity
# .startLaunchFlow(). Nothing re-sends it, so if nobody launches that Activity after a
# power cycle the cluster silently stays on the factory dashboard.
#
# Its own BootReceiver cannot cover this. Verified on car way 2026-08-18: the head unit
# never reboots when the car starts (uptime 7.9 days across 17 car starts) — BYD instead
# quickboots, force-stopping ~132 packages and replaying a boot sequence. Across that
# whole log buffer com.voltflow.dashboard/.BootReceiver was invoked 0 times while 16
# other packages were started by the same boot-broadcast wave. Android 10 would also
# block its startActivity from a boot receiver. Shell uid has neither problem.
DASH_PKG="com.voltflow.dashboard"
DASH_ACT="$DASH_PKG/.LauncherActivity"
# Power-cycle marker. The unit does not reboot, so uptime and boot props are useless.
# Track instead the PID of a package quickboot always kills and always restarts:
# launcher3 was force-stopped and re-started on every observed cycle, so a changed PID
# means a new cycle.
DASH_CYCLE_PKG="com.android.launcher3"
DASH_CYCLE_FILE="/data/local/tmp/voltflow_dash_cycle"
DASH_DISABLED="/data/local/tmp/voltflow_dash_autostart.disabled"
DASH_DIPLUS_WAIT_MAX=60
DASH_ATTEMPTS=3
DASH_VERIFY_SEC=15

# Single-instance guard. Without this, every relaunch (app USER_PRESENT / boot / the
# APK-update kill-respawn gap in TrackingService.ensureCommandDaemonRunning) starts a
# NEW watchdog, each spawning its own voltflow_cmd_daemon. They accumulate and the
# command-poll rate multiplies — root cause of the Vercel Fluid Active-CPU spike: dozens
# of daemons each polling /api/bydmate/commands every BASE_POLL_MS. If a live watchdog
# already holds the lock, exit immediately; otherwise reap orphan daemons and take it.
if [ -f "$LOCKFILE" ]; then
  OLD_PID=$(cat "$LOCKFILE" 2>/dev/null)
  if [ -n "$OLD_PID" ] && [ "$OLD_PID" != "$$" ] && kill -0 "$OLD_PID" 2>/dev/null; then
    echo "[$(date)] watchdog already running (pid=$OLD_PID), exit" >> "$LOG_FILE"
    exit 0
  fi
fi
for stray in $(pidof "$PROCESS_NAME" 2>/dev/null); do
  kill "$stray" 2>/dev/null
done
echo $$ > "$LOCKFILE"
trap 'rm -f "$LOCKFILE" 2>/dev/null' EXIT

# Android 12+ kills "phantom" (app-spawned) processes; lift the cap so app_process daemons persist.
/system/bin/device_config put activity_manager max_phantom_processes 2147483647 >/dev/null 2>&1

echo "=== VoltFlow cmd watchdog started $(date) ===" > "$LOG_FILE"

# Wait for boot so pm/package paths resolve.
BOOT_WAIT=0
while [ "$(getprop sys.boot_completed)" != "1" ] && [ $BOOT_WAIT -lt 120 ]; do
  [ -f "$SENTINEL" ] && { echo "[$(date)] disabled during boot-wait, exit" >> "$LOG_FILE"; exit 0; }
  sleep 2; BOOT_WAIT=$((BOOT_WAIT + 2))
done
echo "[$(date)] boot ready (waited ${BOOT_WAIT}s)" >> "$LOG_FILE"
sleep 3

# Bring the cluster dashboard up once per power cycle.
#
# Deliberately NOT a liveness watchdog like the $PKG relaunch below. The dashboard's
# layout editor has EXIT and FACTORY DASHBOARD buttons, so a driver can choose the stock
# cluster on purpose; relaunching on every absence would fight that choice every 30s.
# One shot per power cycle restores the projection after a car start and then leaves it
# alone until the next one.
#
# Health is judged by the resumed Activity, not by pidof: observed on car way with the
# process alive (pid 20046) but holding no activities at all and Display #1 empty, i.e.
# a process check would have reported "fine" while the cluster showed nothing.
dashboard_autostart_tick() {
  [ -f "$DASH_DISABLED" ] && return 0
  pm path "$DASH_PKG" >/dev/null 2>&1 || return 0

  CUR_CYCLE=$(pidof "$DASH_CYCLE_PKG" 2>/dev/null)
  [ -z "$CUR_CYCLE" ] && return 0
  LAST_CYCLE=$(cat "$DASH_CYCLE_FILE" 2>/dev/null || echo "")
  [ "$CUR_CYCLE" = "$LAST_CYCLE" ] && return 0

  # Claim the cycle up front. A failure below must not re-trigger on the next 30s tick —
  # the retries inside this call are the whole budget for this power cycle.
  echo "$CUR_CYCLE" > "$DASH_CYCLE_FILE"

  if dumpsys activity activities 2>/dev/null | grep -q "$DASH_PKG/.MainActivity"; then
    echo "[$(date)] power cycle ($LAST_CYCLE -> $CUR_CYCLE), cluster already up" >> "$LOG_FILE"
    return 0
  fi

  # LauncherActivity sends the projection command to Di+ at 127.0.0.1:8988 before it
  # waits for the display. Launching before Di+ is up gets the command refused and the
  # cluster stays factory, so wait for it first.
  DIPLUS_WAIT=0
  while [ -z "$(pidof com.van.diplus:remote 2>/dev/null)" ] && \
        [ $DIPLUS_WAIT -lt $DASH_DIPLUS_WAIT_MAX ]; do
    sleep 5
    DIPLUS_WAIT=$((DIPLUS_WAIT + 5))
  done

  echo "[$(date)] power cycle ($LAST_CYCLE -> $CUR_CYCLE), starting $DASH_PKG (di+ wait ${DIPLUS_WAIT}s)" >> "$LOG_FILE"
  DASH_TRY=1
  while [ $DASH_TRY -le $DASH_ATTEMPTS ]; do
    am start -n "$DASH_ACT" >/dev/null 2>&1
    sleep $DASH_VERIFY_SEC
    if dumpsys activity activities 2>/dev/null | grep -q "$DASH_PKG/.MainActivity"; then
      echo "[$(date)] cluster up on attempt $DASH_TRY" >> "$LOG_FILE"
      return 0
    fi
    echo "[$(date)] cluster not up after attempt $DASH_TRY" >> "$LOG_FILE"
    DASH_TRY=$((DASH_TRY + 1))
  done
  echo "[$(date)] gave up starting $DASH_PKG this cycle" >> "$LOG_FILE"
}

while true; do
  [ -f "$SENTINEL" ] && { echo "[$(date)] disabled (sentinel), exit" >> "$LOG_FILE"; exit 0; }

  # Pull latest creds the app exported (if present) into our shell-readable conf.
  if [ -f "$APP_CONF" ]; then
    cp -f "$APP_CONF" "$CONF" 2>/dev/null && chmod 600 "$CONF" 2>/dev/null
  fi

  # Resolve current APK path each iteration (survives app updates).
  APK_PATH=$(pm path "$PKG" 2>/dev/null | sed -n 's/^package://p' | head -1)
  if [ -z "$APK_PATH" ]; then
    echo "[$(date)] $PKG not installed yet, retry in 10s" >> "$LOG_FILE"
    sleep 10; continue
  fi

  # Rotate log if large (>5MB).
  LOG_SZ=$(stat -c%s "$LOG_FILE" 2>/dev/null || echo 0)
  [ "$LOG_SZ" -gt 5242880 ] && : > "$LOG_FILE"

  echo "[$(date)] launching $PROCESS_NAME (apk=$APK_PATH)" >> "$LOG_FILE"
  CLASSPATH="$APK_PATH" app_process /system/bin --nice-name="$PROCESS_NAME" "$CLS" "$CONF" >> "$LOG_FILE" 2>&1 &
  DAEMON_PID=$!

  # Watcher: while the daemon runs, refresh exported creds and detect APK updates.
  # On `adb install -r` the package path changes — kill the daemon so the loop respawns
  # it from the NEW code automatically (no manual restart needed after an APK update).
  (
    while kill -0 "$DAEMON_PID" 2>/dev/null; do
      sleep 30
      [ -f "$APP_CONF" ] && cp -f "$APP_CONF" "$CONF" 2>/dev/null && chmod 600 "$CONF" 2>/dev/null
      NOW_APK=$(pm path "$PKG" 2>/dev/null | sed -n 's/^package://p' | head -1)
      if [ -n "$NOW_APK" ] && [ "$NOW_APK" != "$APK_PATH" ]; then
        echo "[$(date)] APK changed, restarting daemon for new code" >> "$LOG_FILE"
        kill "$DAEMON_PID" 2>/dev/null
        break
      fi

      # App-liveness check: relaunch dev.scroodge.cloudevmate if it's not running.
      # Covers a hard crash/OOM kill — onTaskRemoved and the boot receiver don't reach
      # those. Cooldown-gated so a genuinely broken app isn't hammered with `am start`
      # every 30s.
      if [ -z "$(pidof "$PKG" 2>/dev/null)" ]; then
        NOW_TS=$(date +%s)
        LAST_TS=$(cat "$APP_RELAUNCH_TS_FILE" 2>/dev/null || echo 0)
        if [ $((NOW_TS - LAST_TS)) -ge $APP_RELAUNCH_COOLDOWN_SEC ]; then
          echo "[$(date)] $PKG not running, relaunching" >> "$LOG_FILE"
          am start-foreground-service -n "$PKG/com.bydmate.app.service.TrackingService" >/dev/null 2>&1
          am start -n "$PKG/com.bydmate.app.MainActivity" >/dev/null 2>&1
          echo "$NOW_TS" > "$APP_RELAUNCH_TS_FILE"
        fi
      fi

      # Cluster projection: one-shot per power cycle, see dashboard_autostart_tick.
      dashboard_autostart_tick
    done
  ) &
  WATCHER_PID=$!
  wait "$DAEMON_PID"
  EXIT_CODE=$?
  kill "$WATCHER_PID" 2>/dev/null

  [ -f "$SENTINEL" ] && { echo "[$(date)] disabled after daemon stop, exit" >> "$LOG_FILE"; exit 0; }
  echo "[$(date)] daemon died (code=$EXIT_CODE), respawn in 3s" >> "$LOG_FILE"
  sleep 3
done
