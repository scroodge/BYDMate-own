---
name: release-apk
description: Release a new VoltFlow Mate version end-to-end — version bump, debug build, asset-sync check, CHANGELOG, GitHub release, install, and mandatory post-install telemetry verification against prod. Use when the user says "release", "cut a release", "publish vX.Y.Z", or "ship the APK".
---

# Release VoltFlow Mate APK

Run every step in order. **Do not report success until step 8 passes** — a release
that builds and installs but stops sending telemetry is a failed release (this
exact regression shipped in v0.4.1).

## 1. Preflight

- Working tree must be clean apart from the changes being released (`git status`).
- Run the byte-sync guard — it must exit 0:
  ```bash
  cmp -s tools/start_voltflow_cmd.sh app/src/main/assets/start_voltflow_cmd.sh
  ```
  If it fails, stop and reconcile the two files first (see AGENTS.md → CommandDaemon
  guardrails).

## 2. Version bump

- Edit `app/build.gradle.kts`: increment `versionCode` by 1 and set the new
  `versionName` (semver, e.g. `0.4.8`).
- Do not commit yet — the bump commit comes after a green build (step 3).

## 3. Test + build (debug only)

```bash
./gradlew testDebugUnitTest assembleDebug
```

- **Debug APK only** — never `assembleRelease` unless the user explicitly overrides
  (AGENTS.md rule).
- Output: `app/build/outputs/apk/debug/VoltFlow-Mate-v<version>.apk`.
- If tests fail, stop and report; do not proceed with a red suite.

## 4. CHANGELOG.md

- Add a `## [<version>] - YYYY-MM-DD` section at the top (below the header),
  **in Russian**, Keep a Changelog format (`### Added` / `### Fixed` / `### Changed`).
  Match the tone and detail level of existing entries.

## 5. Commit + tag

```bash
git add app/build.gradle.kts CHANGELOG.md <released files>
git commit -m "chore: bump version to <version> (<versionCode>)"
git push
```

## 6. GitHub release

```bash
gh release create v<version> \
  app/build/outputs/apk/debug/VoltFlow-Mate-v<version>.apk \
  -R scroodge/BYDMate-own \
  --title "VoltFlow Mate v<version>" \
  --notes-file <notes>
```

- Notes in **English**, summarizing the CHANGELOG entry. End with the build line,
  e.g. `Debug build — VoltFlow-Mate-v<version>.apk. Full suite green
  (testDebugUnitTest assembleDebug).`

## 7. Install

- Car head unit (when reachable): `adb connect 192.168.43.71:5555` then
  `adb -s 192.168.43.71:5555 install -r app/build/outputs/apk/debug/VoltFlow-Mate-v<version>.apk`
- Otherwise emulator: `adb install -r ...` on the running emulator.
- If no device is reachable, say so explicitly — the release is **published but
  unverified**, and step 8 still applies as soon as a device installs it.

## 8. Verify telemetry survived the deploy (MANDATORY)

After the app restarts on the device, confirm fresh data is reaching prod.
Query prod (via the `prod-db` MCP in EvAcChargeTimer sessions, or psql per
EvAcChargeTimer AGENTS.md):

```sql
select vehicle_id, max(received_at) as last_sample
from bydmate_telemetry_samples
group by vehicle_id;

select vehicle_id, updated_at from bydmate_live_snapshots;
```

- `last_sample` / `updated_at` must be **newer than the install time** for the
  test vehicle. Poll for up to ~5 minutes.
- Also check the daemon if daemon/launcher code changed:
  `/data/local/tmp/voltflow_cmd_daemon.log` + fresh `bydmate_live_snapshots`
  after car-off (AGENTS.md guardrails).
- **If no fresh samples arrive: the release is broken.** Diagnose before declaring
  done; if the regression can't be fixed quickly, note it in the GitHub release
  and tell the user to keep the previous APK installed.

## 9. Report

State: version, versionCode, release URL, install target, and the timestamp of the
first post-install telemetry sample seen in prod.
