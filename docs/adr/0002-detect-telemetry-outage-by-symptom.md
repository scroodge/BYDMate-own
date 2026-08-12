---
status: accepted
date: 2026-08-12
---

# 0002 — Detect telemetry outages by symptom, not by cause or by sender identity

## Context

On 2026-08-07 a `SettingsDao.setLastKnownSoc` query written as
`INSERT … ON CONFLICT … DO UPDATE` began throwing on the head unit, which carries
SQLite ~3.22 despite reporting Android 10. Room had validated the statement against
its own bundled grammar, so the build and all 51 unit-test files were green.

The throw landed inside the 1 Hz poll loop, upstream of both the cloud push and the
app-alive beacon, so every tick aborted into the generic `catch`. The daemon saw a
stale beacon, concluded the app was dead, and took over at its own much coarser
parked cadence.

**The cloud therefore never went silent.** It kept receiving rows — roughly 90 a day
instead of ~10,000. Nothing alerted, nothing looked broken, and the outage ran for
four days, losing ~170 km of driving history.

The lesson is not about SQL. Any exception anywhere in that loop produces the same
signature, and the daemon fallback will mask every one of them the same way.

## Decision

Detect the **symptom class**: *the car reports it is moving, and we are not
receiving the sample rate that implies.*

A pg_cron job in VoltFlow runs about every 10 minutes. It reads the latest
`bydmate_live_snapshots` row per vehicle; if `powerState` indicates driving or
charging **and** the sample count over the preceding 10 minutes is below ~10% of
expected (fewer than ~60 where ~600 is normal), it sends a Telegram alert via
`src/lib/telegram/bot-send.ts`. An audit row — following the
`bydmate_trip_finalization_audits` pattern — suppresses repeat alerts until the
cadence recovers.

The threshold sits in a 30× gap: driving samples at 1 Hz (~3600/hour) against a
parked daemon heartbeat every 30 s (~120/hour). It is nowhere near either edge.

This is not shipped until it has been **backtested against the 7–10 August window**,
which is still inside the 90-day premium retention. An alarm that cannot be shown to
fire on the outage that motivated it has not been verified.

## Backtest result (2026-08-12) — the threshold above is wrong; use inter-sample gap

The backtest this ADR made a precondition was run against `way`, 6–12 August. It
**rejected the count-based threshold specified above.** The decision — detect the
symptom, cloud-side — stands unchanged; only the discriminator is corrected.

Counting samples per fixed 10-minute window fires on the outage (8/08, 8/10) but
*also* fires 2–3 times a day on healthy days: 8/06 → 2, 8/07 → 2, 8/11 → 3,
8/12 → 1. Eight false alarms against two true ones. The cause is boundary windows —
a drive beginning at 10:37 leaves a bucket holding 16 samples, and a count rule
cannot distinguish that from a collapse. An alarm firing twice a day gets muted,
which reproduces precisely the failure this ADR exists to prevent.

The **gap between consecutive samples taken while moving** separates cleanly:

| | median gap | p90 gap |
|---|---|---|
| Healthy (8/06, 8/07, 8/11, 8/12) | 1.22–1.25 s | 1.34–1.40 s |
| Outage (8/08, 8/10) | 98,216 s / 192,592 s | — |

Five orders of magnitude, with healthy p90 never above 1.40 s. It is scale-free, so
partial windows cannot trip it. **Alert when a sample arrives with
`diplus_speed_kmh > 0` and a gap from the previous moving sample exceeding ~5 s**
(≈3.5× the healthy p90; anything from 3 s to 90,000 s is defensible).

Measure the gap between *moving* samples only. Gaps spanning a parked period are
routinely 5–14 hours on perfectly healthy days and mean nothing.

**Known blind spot.** The gap rule is triggered by an arriving moving sample, so a
day with no moving samples at all cannot fire it — 8/09 had zero and would have been
missed. Detection would still have landed on 8/08, cutting time-to-detection from
four days to about one. Close the gap with a second, independent floor: **total
samples over 24 h below ~500.** Outage days ran 70 / 178 / 101, while the parked
daemon heartbeat alone should yield ~2880/day — a 3–16× margin, and an absolute
floor rather than the trailing baseline rejected below.

## Consequences

**Catches the next one too.** The rule keys on a contradiction the system cannot fake,
so it fires for any future fault that kills the poll loop — including causes nobody
has thought of yet.

**Entirely cloud-side.** No APK change, no coordinated two-repo deploy, no version
skew. It also works retroactively on data already in the database, which is what
makes the backtest possible.

**It reports "what", not "why".** The alert says the stream collapsed while the car
was moving; diagnosing the cause is still manual. Accepted deliberately — see the
rejected alternative below.

**A tuning risk remains.** If the documented cadence spec ever changes, the absolute
threshold must move with it. `docs/ROADMAP.md` holds the cadence figures; treat them
as coupled.

**What this decision does not buy.** It shortens time-to-detection; it does not
prevent the fault. The v0.5.2 fix (SOC write in its own `try/catch`, beacon written
before `enqueue`) is what reduces recurrence. Detection is the safety net, not the
repair.

## Alternatives considered

**An explicit sender field in the payload.** The app and the daemon are currently
indistinguishable — `CloudTelemetryPayload.kt:91` and `CommandDaemon.kt:1294` both
send `source: "BYDMate"` with the same `mate_version`. A declared sender would name
the fault exactly. Rejected because detection does not need it: the daemon's own
samples carry `powerState`, so the cloud already knows the car is moving. It would
have required a coordinated APK + cloud deploy, could not be backtested, and carried
two live traps — `source: z.literal("BYDMate")` rejects a changed value outright,
and `.strip()` silently discards an added key. Worth revisiting for *diagnosis*; it
was never required for *detection*.

**An automated pre-3.24 SQL gate** — a unit test extracting every Room `@Query` and
rejecting 3.24+ grammar. Rejected as the primary answer: cheap and real, but it
catches exactly one failure class while the daemon-masking blind spot stays open for
every other. The constraint is recorded in `CLAUDE.md` instead. Still a reasonable
belt-and-braces addition later.

**A blast-radius audit of the whole poll loop.** Rejected for now: v0.5.2 already
isolated the three specific steps that tripped, and with this alarm live the next
regression surfaces in minutes rather than days, which makes an exhaustive audit
poor value against its cost.

**Alerting on a trailing 7-day baseline** instead of an absolute threshold.
Rejected: self-calibrating, but a baseline poisoned by a long outage drifts downward
and silently desensitises the alarm — reproducing the exact failure mode being
defended against.

**Timestamp precision as the discriminator** (the app emits sub-second `device_time`,
the daemon whole seconds — `docs/HOW_IT_WORKS.md:621`). Rejected as a load-bearing
signal: it is an accidental artifact of two independent code paths, asserted by no
test, and if it ever broke the detector would fail silently. It remains useful as a
manual diagnostic during release verification.
