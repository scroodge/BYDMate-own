# Agent Instructions

## Startup

- At the start of each new session in this repository, query agentmemory before making changes.
- Use the project path `/Users/way/Dev/BYDMate-own` and the project name `BYDMate-own`.
- First recall current project context with tags such as `bydmate-own-project-status`, `voltflow-mate-cloud-sync`, `command-daemon-parked-off`, `vehicle-id-mismatch-risk`, and `agentmemory-connected`.
- Continue from recalled context instead of rediscovering known project state.

## Project Focus

- VoltFlow Mate is a Kotlin/Compose Android app for BYD DiLink telemetry to VoltFlow.
- For Cloud Sync work, start with `CloudTelemetrySender`, `CloudTelemetryPayload`, `CloudTelemetryCadence`, `TrackingService`, and tests in `app/src/test/kotlin/com/bydmate/app/data/cloud/`.
- For parked/off remote command work, start with `CommandDaemon`, `VehicleCommandPoller`, `CommandAllowlist`, `DiParsControlClient`, `tools/start_voltflow_cmd.sh`, and `docs/REMOTE_COMMAND_DAEMON.md`.
- Preserve the open risk note: changing `cloud_sync_vehicle_id` while old queue payloads exist can create header/body vehicle_id mismatch and drop mixed batches unless fixed.
