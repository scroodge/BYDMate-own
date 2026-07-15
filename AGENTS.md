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

**→ No Android work needed, and no cloud work either.** An earlier version of this note
proposed that the cloud adopt `kwh_charged` for the session total/cost. **That was tried in
EvAcChargeTimer and reverted**, because the BMS `kwh_charged` counter is **cell-only —
roughly 47% low versus grid-metered energy** — so it is wrong for cost and for the power
display, and is kept for diagnostics only. The cloud deliberately computes cost from
`SOC_delta% × capacity ÷ efficiency` (per-tariff efficiency), which is the correct,
grid-side figure. Do **not** re-propose BMS-for-cost. Authoritative rule: EvAcChargeTimer
`AGENTS.md` → "Charging sessions" and `docs/CHARGING_SESSIONS.md`. Mate keeps sending
`kwh_charged` in the payload as a diagnostic; that is fine and unchanged.

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

<!-- BEGIN sqz-agents-guidance (auto-installed by sqz init; remove this block to disable) -->

## sqz — Token-Optimized CLI Output

When running shell commands whose output may be long (directory listings,
git log/diff, test runners, build logs, `docker ps`, `kubectl get`, etc.),
pipe the output through `sqz compress` to reduce token consumption.

`sqz` is a stdin-to-stdout compressor, not a command wrapper. The correct
usage is to pipe the command's output into `sqz compress`:

```bash
# Instead of:     Use:
git status        git status 2>&1 | /usr/local/bin/sqz compress
cargo test        cargo test 2>&1 | /usr/local/bin/sqz compress
git log -10       git log -10 2>&1 | /usr/local/bin/sqz compress
docker ps         docker ps 2>&1 | /usr/local/bin/sqz compress
ls -la            ls -la 2>&1 | /usr/local/bin/sqz compress
```

The `2>&1` captures stderr too, which is useful for commands like `cargo
test` where diagnostics go to stderr. `sqz compress` filters and compresses
the combined output while preserving filenames, paths, and identifiers.
It typically saves 60-90% tokens on verbose commands.

Do NOT pipe output for:
- Interactive commands (`vim`, `ssh`, `python`, REPLs)
- Compound commands with shell operators (`cmd && other`, `cmd > file.txt`,
  `cmd; other`) — run those directly
- Short commands whose output is already a few lines

If `sqz` is not on PATH, run commands normally.

The `sqz-mcp` MCP server is also available — Codex reads it from
`~/.codex/config.toml` under `[mcp_servers.sqz]`. It exposes three
tools: `compress` (the default pipeline), `passthrough` (return text
unchanged — the escape hatch below), and `expand` (resolve a
`§ref:HASH§` token back to the original bytes).

## Escape hatch — when sqz output confuses you

If you see a `§ref:HASH§` token and can't parse it, or compressed
output is leading you to make lots of small retries instead of one
big request, use one of these:

- **`/usr/local/bin/sqz expand <prefix>`** — resolve a dedup ref back to the
  original bytes. Accepts bare hex (`sqz expand a1b2c3d4`) or the full
  token pasted verbatim (`sqz expand §ref:a1b2c3d4§`).
- **`SQZ_NO_DEDUP=1`** — set this env var for one command to disable
  dedup: `SQZ_NO_DEDUP=1 git status 2>&1 | sqz compress`. You'll get
  the full compressed output with no `§ref:…§` tokens.
- **`--no-cache`** — same opt-out as a CLI flag:
  `git status 2>&1 | sqz compress --no-cache`.

If you're using the MCP server, the `passthrough` tool returns raw
text and the `expand` tool resolves refs — call them when you need
data sqz hasn't touched.

<!-- END sqz-agents-guidance -->
