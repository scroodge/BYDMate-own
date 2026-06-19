# Agent Instructions

## Startup

- At the start of each new session in this repository, query agentmemory before making changes.
- Use the project path `/Users/way/Dev/BYDMate-own` and the project name `BYDMate-own`.
- First recall current project context with tags such as `bydmate-own-project-status`, `voltflow-mate-cloud-sync`, `command-daemon-parked-off`, `vehicle-id-mismatch-risk`, and `agentmemory-connected`.
- Continue from recalled context instead of rediscovering known project state.

## Project Focus

- VoltFlow Mate is a Kotlin/Compose Android app for BYD DiLink telemetry to VoltFlow.
- Build/install preference is **debug APK only** for this project. Use `./gradlew testDebugUnitTest assembleDebug` and install `app/build/outputs/apk/debug/VoltFlow-Mate-v<version>.apk`; do not build or install release APKs unless the user explicitly overrides this rule.
- For Cloud Sync work, start with `CloudTelemetrySender`, `CloudTelemetryPayload`, `CloudTelemetryCadence`, `TrackingService`, and tests in `app/src/test/kotlin/com/bydmate/app/data/cloud/`.
- For parked/off remote command work, start with `CommandDaemon`, `VehicleCommandPoller`, `CommandAllowlist`, `DiParsControlClient`, `tools/start_voltflow_cmd.sh`, and `docs/REMOTE_COMMAND_DAEMON.md`.
- Preserve the open risk note: changing `cloud_sync_vehicle_id` while old queue payloads exist can create header/body vehicle_id mismatch and drop mixed batches unless fixed.

## CommandDaemon / parked-off telemetry guardrails

- Before touching parked/off remote commands, daemon launch, or sleep telemetry, read `docs/REMOTE_COMMAND_DAEMON.md`.
- `tools/start_voltflow_cmd.sh` and `app/src/main/assets/start_voltflow_cmd.sh` must stay byte-for-byte in sync. The app self-revival path deploys the APK asset, not `/data/local/tmp/start_voltflow_cmd.sh`; always verify with `cmp -s tools/start_voltflow_cmd.sh app/src/main/assets/start_voltflow_cmd.sh` before release/build changes.
- Do not assume `pidof voltflow_cmd_daemon` proves sleep survival. Also verify watchdog health, stale PID/lock files, `/data/local/tmp/voltflow_cmd_daemon.log`, the app-deployed launcher under external files, and fresh `bydmate_live_snapshots` after car-off.
- If daemon code or launcher behavior changes, document the operational impact in `docs/REMOTE_COMMAND_DAEMON.md` and add focused tests or an explicit ADB verification checklist.
