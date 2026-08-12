---
status: accepted
date: 2026-08-12
supersedes: ADR-0001
---

# 0003 — Support the oldest APK still in the field, measured rather than assumed

## Context

[ADR-0001](0001-single-vehicle-deployment.md) declared this a single-vehicle
deployment and licensed dropping backward compatibility. Its central premise was
false when it was written.

Checked against production on 2026-08-12, `bydmate_live_snapshots` carried **10
distinct vehicles active within the preceding minutes, on three APK versions
simultaneously**:

| `mate_version` | Vehicles |
|---|---|
| `0.5.2` | `way`, `cl`, `BYD Yuan Up 25` |
| `0.5.1.1` | `Bulbazavr`, `BYE Yuan Up`, `BYD`, `Yuan UP`, `yuan up`, `sed` |
| `0.5.0` | `BYD Yuan Up` |

The error came from reading "all changes are aimed at my car" as a statement about
the deployed population. It describes the *test loop*: one car, reached over network
ADB, where changes are validated. Delivery is a different thing — GitHub Releases are
public and `UpdateChecker` points at `releases/latest`, so other people's cars pick up
published builds on their own schedule. One has been on `0.5.0` across two releases.

The distinction that actually matters is **directional**. Telemetry flows one way,
APK → cloud, and the cloud is a single deployment that is always the newest component
in the system. There is no ordering constraint on cross-repo changes and no skew
window to bridge — the only thing that can be stale is the sender.

Forcing an upgrade is not available. These are sideloaded debug APKs on head units
with no store, no update service, and an in-app checker that can only offer. A
minimum-version gate that rejected old payloads would discard real driving data from
real cars to save the cloud a nullable column.

## Decision

**The cloud accepts the oldest APK version still sending data.**

- A field added to the payload is **optional** on the ingest side, following the
  existing `live_only` / `client_hourly` / `client_trip` precedent in
  `EvAcChargeTimer/src/lib/bydmate/ingest-payload.ts`. Absent means "older APK",
  never "invalid".
- No minimum-version gate. Never reject a payload for being old.
- The **support floor is measured, not promised**: the oldest `mate_version`
  observed in `bydmate_live_snapshots` within the last 30 days. Compatibility
  handling for a version may be deleted once that version stops appearing.
- Upgrades are **offered, not enforced**. Making the in-app update prompt more
  visible is in scope; gating function on version is not.

## Consequences

**The tax is small and bounded.** One nullable column per new field. There is no
dual-write, no migration window, no coordinated deploy — those costs come from
bidirectional or multi-node systems, and this is neither.

**"How far back do we support?" becomes a query, not a debate.** The floor is a
number anyone can `select`, it shrinks on its own as people update, and it says
*when* deleting old handling is safe. This is the part ADR-0001 got right in spirit
and wrong in fact: compatibility should be bounded, but by evidence rather than by
assumption.

**Silent-drop remains the trap.** `ingest-payload.ts` closes with `.strip()`, so an
unknown key is discarded without complaint: telemetry keeps flowing, the smoke check
passes, and the new field simply never arrives. Optional-on-ingest is only safe when
the field is actually added to the schema. `source: z.literal("BYDMate")` is the
opposite — it rejects the whole payload loudly, which is why it must not be changed.

**Cross-repo ordering is still one-directional.** Cloud first, APK second. A cloud
that accepts a field before any APK sends it is inert; an APK that sends a field the
cloud strips loses data silently.

**Reversing this needs evidence, not preference.** The floor query is the test: if
prod shows one vehicle on one version for 30 days, single-vehicle assumptions become
true and this ADR can be superseded in turn.

## Alternatives considered

**Force upgrades / minimum-version gate.** Rejected as unavailable and harmful.
There is no enforcement channel, and the only lever — refusing old payloads — throws
away irreplaceable driving history from cars whose owners did nothing wrong.

**Unbounded backward compatibility.** Rejected: an open-ended promise accumulates
shims nobody can ever prove are dead. The measured floor gets the same safety with
an expiry date attached.

**Keep ADR-0001 and treat the fleet as out of scope** — "those are other people's
cars, not my problem." Rejected: they are running published builds through an
updater this project ships, and their data lands in the same tables. Scope follows
the data, not intent.
