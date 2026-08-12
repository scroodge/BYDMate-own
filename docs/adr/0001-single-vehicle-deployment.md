---
status: accepted
date: 2026-08-12
---

# 0001 — Target a single vehicle; carry no backward compatibility for older APKs

## Context

VoltFlow Mate looks like a fleet product. It publishes GitHub Releases, ships an
in-app updater (`UpdateChecker` → `releases/latest`), stamps `mate_version` into
every telemetry payload "so you can see the APK version on each car", and the cloud
ingest schema is full of optional fields whose comments read *"Absent (older APK
versions) means…"* — `live_only`, `client_hourly`, `client_trip` in
`EvAcChargeTimer/src/lib/bydmate/ingest-payload.ts`.

It is not a fleet product. Every change is aimed at exactly one car,
`vehicle_id: way`, owned by the same person who owns both repositories. The
fleet-shaped machinery is a historical accident of how the ingest contract grew,
not a requirement.

This distinction was invisible in the documentation and had been silently inflating
the cost of design decisions: a grilling session on 2026-08-12 spent most of its
effort reasoning about rollout windows, version-skew periods and old-APK fallback
rules that do not exist.

## Decision

Treat this as a **single-vehicle deployment**.

- Do not design staged rollouts, feature gates keyed on APK version, or
  compatibility shims for older installs.
- Do not extend the `live_only` / `client_hourly` / `client_trip` optional-field
  precedent to new fields. That pattern was built for a version-skew window that
  no longer needs to exist.
- Cross-repo changes (BYDMate APK + VoltFlow/Supabase) may deploy in a single
  window; there is no skew period to bridge.
- Iterate on the car at a **fixed** `versionName`/`versionCode`. Build, install,
  observe, fix, reinstall. A version is finalised only when it is tagged — so a
  `versionName` in `app/build.gradle.kts` ahead of the latest GitHub release means
  "in progress", not "released".

## Consequences

**Easier.** Two-repo changes become one change. Schema evolution does not need
optional-field ceremony. Ideas can be tested on real hardware in minutes.

**Harder.** Supporting a second car later means paying the compatibility cost that
was skipped — deliberately, and with this ADR as the record of why.

**The trap for anyone reversing this.** GitHub Releases are public and the in-app
updater points at `releases/latest`. If another vehicle ever installs the APK, the
"single vehicle" premise silently becomes false while every doc still assumes it.
Reversing this decision means auditing every field added after 2026-08-12 for
old-APK behaviour, not just resuming the old pattern.

**Prefer loud failures.** Blast radius is one car that the owner checks immediately,
so a change that fails visibly beats one that fails quietly. This inverts normal
advice. Concretely: `ingest-payload.ts` closes its object with `.strip()`, so an
unknown key is *silently discarded* — telemetry keeps flowing, the smoke check
passes, and the new field simply never arrives. `source: z.literal("BYDMate")`
instead rejects the whole payload, which is loud and therefore safer here. Silent
degradation is what made the v0.5.2 outage cost four days.

## Alternatives considered

**Keep fleet-shaped compatibility discipline anyway.** Rejected: it charges a real,
recurring design tax — an ordering constraint on every cross-repo change — to insure
against a second vehicle that does not exist and is not planned. The insurance is
also cheap to buy later, since this ADR records exactly what was skipped.

**Remove the fleet machinery outright** (drop `mate_version`, stop publishing
releases). Rejected: `mate_version` costs nothing and is genuinely useful for
correlating cloud data with an APK build, and GitHub Releases remain the delivery
and rollback mechanism for the one car. The premise changes; the plumbing stays.
