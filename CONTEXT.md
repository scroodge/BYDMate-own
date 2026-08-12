# Context — VoltFlow Mate

Glossary for this project. **Terms only** — no implementation detail, no decisions,
no task tracking. How the system works belongs in [`docs/HOW_IT_WORKS.md`](docs/HOW_IT_WORKS.md);
why it is that way belongs in [`docs/adr/`](docs/adr/); what we owe belongs in
[`docs/BACKLOG.md`](docs/BACKLOG.md).

When a term here conflicts with how someone is using a word, the glossary wins or
the glossary changes — not both meanings at once.

---

## The system

**Head unit** — the BYD DiLink infotainment computer in the car. Reports Android 10
but is not a stock Android 10 device; several of its subsystems are older than that
implies. The target hardware, and the only hardware that counts as proof.

**App** — the VoltFlow Mate Android application. Runs while the car is on, polls
vehicle state at 1 Hz, and pushes telemetry to the cloud.

**Daemon** — the separate shell-uid process that survives force-stop and car-off.
Handles remote commands while the car is parked, and takes over telemetry when it
concludes the app is not running. Not a backup copy of the app: it samples far more
coarsely and has no database access.

**Gateway** — the app's single screen and its operating premise: the APK is a
*conduit to the cloud*, not an analytics product. Computation on the device is
legitimate when it reduces cloud load; analytical *presentation* belongs in VoltFlow.

**Cloud / VoltFlow** — the server side, living in the `EvAcChargeTimer` repository.
Owns ingest, pairing, trip filtering, and everything a user actually looks at.

**di+** — the on-device service the app and daemon read vehicle signals from. The
necessary channel for actuation; not every value it exposes is trustworthy for
billing or grid-side reasoning.

**FID** — an identifier for a single vehicle signal in the di+ catalogue. The
mapping from FID to meaning is knowledge about the car, not about the app.

## Telemetry

**Sample** — one observation of vehicle state at one instant. The unit of telemetry.

**Cadence** — the *expected* rate of samples for the current vehicle state. Driving
and charging are dense; parked is sparse. Cadence is a property of the situation, not
of the sender.

**Cadence collapse** — samples arriving far below the cadence the vehicle's own
reported state implies. The observable signature of a fault in the app's poll loop,
and distinct from *silence*: during a collapse data still arrives, which is why one
can run for days unnoticed.

**Sender** — which process emitted a given sample, app or daemon. Currently **not
declared** in the payload; both identify themselves identically. Treat "who sent
this" as an open question about the data, not a field you can read.

**Beacon** — the app's periodic assertion that its poll loop is still turning. The
daemon reads it to decide whether to take over. A stale beacon means the app stopped,
not that the car stopped.

**Takeover** — the daemon assuming the telemetry stream after a stale beacon. By
design when the car is off; a symptom when the car is moving.

**Trip** — a single journey, from drive start to park. May be computed on the device
and shipped whole, or derived server-side from samples.

**Phantom trip** — a trip the system recorded that did not physically happen.
Filtered on the cloud side.

## Power and parking

**Parked wake window** — the bounded period after the daemon observes ignition-off
during which it deliberately keeps the device awake so telemetry and commands stay
responsive. One-shot and not extended: the bound exists to cap 12 V battery drain.

**Wakelock** — the mechanism holding the device out of suspend. The daemon cannot use
the ordinary application API for this, because it is not an application.

**Keep-alive** — periodically re-asserting Wi-Fi so the head unit does not drop the
network shortly after parking. Only meaningful while something is holding the device
awake; outside the parked wake window there is nothing running to re-assert anything.

## Delivery

**Vehicle** — one car, identified by a **vehicle id**. This project targets exactly
one; see [ADR-0001](docs/adr/0001-single-vehicle-deployment.md).

**Pairing** — binding a car to a cloud account via a short-lived code, after which
the APK holds a key and pushes under that identity.

**Release** — a tagged, published APK. A version number present in the build but not
tagged means work in progress, not a release.

**Verified on-car** — observed working on the head unit, with the evidence stated. A
green build is never verification; see [`.claude/skills/writing-docs/SKILL.md`](.claude/skills/writing-docs/SKILL.md) §6.
