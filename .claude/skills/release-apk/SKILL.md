---
name: release-apk
description: Release a new VoltFlow Mate version end-to-end — version bump, debug build, asset-sync check, CHANGELOG, ledger update, GitHub release, install, and mandatory post-install telemetry verification against prod. Use when the user says "release", "cut a release", "publish vX.Y.Z", or "ship the APK".
---

# Release VoltFlow Mate APK

Run every step in order. **Do not report success until step 9 passes** — a release
that builds and installs but stops sending telemetry is a failed release. This has
now happened twice: v0.4.1, and v0.5.2's four-day silent outage.

> **A green build proves nothing on this hardware.** Room validates `@Query` SQL
> against its own bundled SQLite grammar; the DiLink head unit carries ~3.22. The
> whole unit suite can pass a statement the car cannot parse. Only step 9 closes
> a release.

## 1. Preflight

- Working tree must be clean apart from the changes being released (`git status`).
- Run the byte-sync guard — it must exit 0:
  ```bash
  cmp -s tools/start_voltflow_cmd.sh app/src/main/assets/start_voltflow_cmd.sh
  ```
  If it fails, stop and reconcile the two files first (see AGENTS.md → CommandDaemon
  guardrails).
- If this release touches raw SQL, the poll loop, or the telemetry path, say so
  explicitly now — it raises the bar at step 9 from "fresh rows" to the full
  smoke check.

## 2. Version bump

- Edit `app/build.gradle.kts`: increment `versionCode` by 1 and set the new
  `versionName` (semver, e.g. `0.5.3`).
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
- For a `### Fixed` entry, state the **observable symptom** and how it was detected,
  not only the code change. A future reader needs to recognise the shape, not just
  the fix.

## 5. Update the ledger (do not skip)

`BACKLOG.md` is a **commitment ledger**, not a derived report. It goes stale by
construction unless the release updates it, which is exactly how it drifted a full
version behind before 2026-08-12.

- `docs/BACKLOG.md` — set the baseline line to the version being tagged:
  ``**Обновлено:** YYYY-MM-DD · база: `main` @ `<version>` (`versionCode <N>`).``
- `docs/ROADMAP.md` — set **Текущая версия кода в `main`** and **Обновлено** to match.
- Move every item shipped in this release from `in-progress`/`todo` to `done`, and
  out of the 🔧 section of ROADMAP.
- If the release settled a question the ledger recorded as open, add or update the
  entry under **Решено не делать** — negative decisions are first-class.
- If any decision in this release was hard to reverse, surprising without context,
  **and** the result of a real trade-off, write an ADR in `docs/adr/` (see
  `.claude/skills/writing-docs/SKILL.md`). Skip it if any of the three is missing.

## 6. Commit + tag

```bash
git add app/build.gradle.kts CHANGELOG.md docs/BACKLOG.md docs/ROADMAP.md <released files>
git commit -m "chore: bump version to <version> (<versionCode>)"
git push
```

## 7. GitHub release

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

## 8. Install

- Car head unit (network ADB, not USB): `adb connect 192.168.43.71:5555` then
  `adb -s 192.168.43.71:5555 install -r app/build/outputs/apk/debug/VoltFlow-Mate-v<version>.apk`
- Otherwise emulator: `adb install -r ...` on the running emulator.
- If no device is reachable, say so explicitly — the release is **published but
  unverified**, and step 9 still applies as soon as a device installs it.

## 9. Verify telemetry survived the deploy (MANDATORY)

Three checks. All three must pass; **fresh rows alone are not sufficient**, because
the daemon keeps producing rows after the app's poll loop dies — that is precisely
why v0.5.2 went unnoticed for four days.

**9a — the app's poll loop is not throwing.** Must print `0`:

```bash
adb -s 192.168.43.71:5555 logcat -d -s TrackingService:* \
  | grep -cE "Polling error|saveLastKnownSoc failed"
```

**9b — the app-alive beacon is fresh.** Beacon age must be **seconds, not minutes**.
A stale beacon means the app died and the daemon silently took over.

**9c — prod is receiving from the app, not only the daemon.**

```sql
select vehicle_id, max(received_at) as last_sample
from bydmate_telemetry_samples
group by vehicle_id;

select vehicle_id, updated_at from bydmate_live_snapshots;
```

- `last_sample` / `updated_at` must be **newer than the install time**. Poll up to ~5 min.
- **Identify the sender**: the app emits **sub-second** `device_time`; the daemon emits
  **whole seconds**. Whole-second timestamps only means the daemon is carrying the
  stream and the app is down — treat that as a failed release, not a pass.
- If daemon/launcher code changed, also check
  `/data/local/tmp/voltflow_cmd_daemon.log` + fresh `bydmate_live_snapshots` after car-off.
- **If any check fails: the release is broken.** Diagnose before declaring done; if it
  can't be fixed quickly, note it in the GitHub release and tell the user to keep the
  previous APK installed.

## 10. Report

State: version, versionCode, release URL, install target, the result of 9a/9b, and
the timestamp **and precision** of the first post-install telemetry sample seen in
prod. Never report a telemetry fix as verified on build output alone.
