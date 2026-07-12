# Agent Instructions

## Pending plan

### ✅ Android side DONE / ⛔ nativestack rejected — actionable work is server-side (2026-06-30)

User asked whether VoltFlow Mate can beat di+'s integer power and whether to adopt
upstream AndyShaman/BYDMate's `nativestack` (direct BYD autoservice reads) — *only if
it benefits*, noting **di+ must stay** (its 熄火哨兵 stall-sentry keeps the head unit
awake while parked; it is the only actuation channel `127.0.0.1:8988/api/sendCmd`).

**Research findings (verified on car `way`):**
- **Float instantaneous power is impossible.** Engine power is an *integer-kW* field in
  BYD's own data: `service call autoservice 5 i32 1012 i32 339738656` → `-4`; the same
  fid read as float (tx 7) returns the `-1.0` sentinel. No battery **current** fid
  exists, so `P=V×I` is out too. di+ faithfully passes the integer. See
  `docs/DIPLUS_DATA.md`.
- **This repo already does the right thing.** The autoservice stack
  (`AutoserviceClient`, `FidRegistry`, `AdbOnDeviceClient`, `SentinelDecoder`) already
  reads the high-value floats — `FID_CHARGING_CAPACITY` (per-session kWh, live=**2.559**
  on `way`), `FID_SOH`, `FID_LIFETIME_KWH`, float `FID_SOC`, 12V — and **already sends
  the BMS float as `kwh_charged`** in the live charging payload
  (`TelemetrySnapshot.kt:93` = `charging.chargingCapacityKwh`). `soh_percent` too.
  (`TrackingService.kt:~1000` `socDelta×capacity` is only the *offline* local-record
  path, skipped when autoservice is on — NOT the cloud path.)
- **FULL nativestack port REJECTED:** ~40 per-vehicle-validated FIDs (FidMap validated
  on Leopard 3; `way` is a Yuan Up), more on-device ADB load, **no new data** (float
  power impossible; energy/SoH already captured), and di+ still can't be removed. High
  effort, ~zero benefit.

**→ No Android work needed.** The remaining win is in the **cloud (EvAcChargeTimer)**:
it receives the accurate `kwh_charged` but ignores it for the session total/cost (uses
SOC×capacity÷efficiency). See EvAcChargeTimer `AGENTS.md` → Pending plan "BMS-measured
charge energy + derived float charge power".

---

## Startup

- At the start of each new session in this repository, query agentmemory before making changes.
- Use the project path `/Users/way/Dev/BYDMate-own` and the project name `BYDMate-own`.
- First recall current project context with tags such as `bydmate-own-project-status`, `voltflow-mate-cloud-sync`, `command-daemon-parked-off`, `vehicle-id-mismatch-risk`, and `agentmemory-connected`.
- Continue from recalled context instead of rediscovering known project state.

## Project Focus

- VoltFlow Mate is a Kotlin/Compose Android app for BYD DiLink telemetry to VoltFlow.
- Build/install preference is **debug APK only** for this project. Use `./gradlew testDebugUnitTest assembleDebug` and install `app/build/outputs/apk/debug/VoltFlow-Mate-v<version>.apk`; do not build or install release APKs unless the user explicitly overrides this rule.
- **Releases:** follow `.claude/skills/release-apk/SKILL.md` (the `/release-apk` skill). A release is not done until fresh rows appear in `bydmate_telemetry_samples` / `bydmate_live_snapshots` **after** the install — an APK that builds and installs but stops sending telemetry is a failed release (this shipped in v0.4.1).
- For Cloud Sync work, start with `CloudTelemetrySender`, `CloudTelemetryPayload`, `CloudTelemetryCadence`, `TrackingService`, and tests in `app/src/test/kotlin/com/bydmate/app/data/cloud/`.
- For parked/off remote command work, start with `CommandDaemon`, `VehicleCommandPoller`, `CommandAllowlist`, `DiParsControlClient`, `tools/start_voltflow_cmd.sh`, and `docs/REMOTE_COMMAND_DAEMON.md`.
- Preserve the open risk note: changing `cloud_sync_vehicle_id` while old queue payloads exist can create header/body vehicle_id mismatch and drop mixed batches unless fixed.

## CommandDaemon / parked-off telemetry guardrails

- Before touching parked/off remote commands, daemon launch, or sleep telemetry, read `docs/REMOTE_COMMAND_DAEMON.md`.
- `tools/start_voltflow_cmd.sh` and `app/src/main/assets/start_voltflow_cmd.sh` must stay byte-for-byte in sync. The app self-revival path deploys the APK asset, not `/data/local/tmp/start_voltflow_cmd.sh`; always verify with `cmp -s tools/start_voltflow_cmd.sh app/src/main/assets/start_voltflow_cmd.sh` before release/build changes.
- Do not assume `pidof voltflow_cmd_daemon` proves sleep survival. Also verify watchdog health, stale PID/lock files, `/data/local/tmp/voltflow_cmd_daemon.log`, the app-deployed launcher under external files, and fresh `bydmate_live_snapshots` after car-off.
- If daemon code or launcher behavior changes, document the operational impact in `docs/REMOTE_COMMAND_DAEMON.md` and add focused tests or an explicit ADB verification checklist.
