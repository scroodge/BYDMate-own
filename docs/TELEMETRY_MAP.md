# Telemetry Map — what is saved locally, what is sent to the cloud, and when

Single-page answer to "where does each piece of telemetry go?". Companion docs:
[`HOW_IT_WORKS.md`](HOW_IT_WORKS.md) (full pipeline narrative),
[`cloud-telemetry-contract-ru.md`](cloud-telemetry-contract-ru.md) (wire format),
[`CLOUD_SYNC_EGRESS_PLAN.md`](CLOUD_SYNC_EGRESS_PLAN.md) (why the cadences are what they are),
[`REMOTE_COMMAND_DAEMON.md`](REMOTE_COMMAND_DAEMON.md) (the parked/off daemon).

Verified against `main` @ `3a63278`.

## 1. Cloud egress — telemetry (`POST …/api/bydmate/telemetry`)

Source: `CloudTelemetrySender.kt`, `CloudTelemetryCadence.kt`, `CloudTelemetryPayload.kt`.
The local poll runs at 1 Hz (`TrackingService.POLL_INTERVAL_MS`); everything below
throttles off that — the APK never posts once per poll.

| Stage | What | When / cadence | Code |
|---|---|---|---|
| **Queue** (Room `cloud_sync_queue`) | Full sample: `telemetry` + `diplus` + `autoservice` + `location` | Driving **1 s**; Charging < 98 % SOC **10 s**; Charging ≥ 98 % (balance tail) **1 s**; Parked **30 s** | `decide()` |
| Queue (forced) | Same | Immediately on gear change, moving edge, charging edge, or the first-ever sample | `stateChanged` |
| Queue (slim) | `live_only: true` — server refreshes `bydmate_live_snapshots` only, no history/hourly/trip rows | Parked **and** SOC + gun state + gear + 12 V (± 0.3 V) all unchanged | `LIVE_ONLY_12V_EPSILON_V` |
| Queue (anti-starve) | Forced **full** parked sample | At least once per **15 min** even with nothing changed (`LIVE_ONLY_MAX_RUN_MS`) — an unbounded `live_only` run would collapse an overnight park into one gap and zero out `bydmate_phantom_drain_daily.idle_hours` | |
| **Flush** (HTTP POST) | Batch of queued samples + `hourly` + `trips` blocks | Driving & charging tail: every **15 s** or **15** samples; Charging bulk: every **60 s**; Parked/idle: `cloud_sync_interval_sec` (default **60 s**, clamped 5–300) or **120** samples | `flushPending()` |
| Flush (immediate) | Same | `flushNow`: confirmed P → ignition-off, gear change while not active, non-active state change | |
| **Status ping** | One `live_only` payload, bypasses the queue entirely | On a moving/charging edge that did not already `flushNow`; **and every 3 s** while a `live_fast_seconds` grant from the ~6 s command poll is active | `sendPendingStatusPing` |
| **Hourly rollup** | Cumulative per-hour aggregate computed on-device | Folded per non-`live_only` sample; shipped in every flush envelope (≤ 12 blocks); pruned locally after 24 h | `accumulateHourly` |
| **Trip rollup** | Cumulative client-owned trip block + `trip_id` / `client_trip` on the sample | Opens on IDLE → DRIVING, closes on gear P (≤ 5 km/h) or charging start; a stale open trip is closed at service start after 20 min; ≤ 4 blocks per flush, 24 h retention | `planTrip`, `finalizeStaleOpenTrip` |

**Drive latch.** `CloudTelemetryCadence.DRIVE_LATCH_MS` = **2 min**: after D/R/N or
movement the state stays `DRIVING` even if D+ briefly reports P, so a red light does
not split a trip or drop the cadence to the 30 s parked heartbeat mid-drive.

**Gates that block all of the above:** `cloud_sync_enabled != true`, a blank or
non-HTTPS URL, a blank `vehicle_id`, and `wifi_only` while off Wi-Fi (samples stay
queued). Queue is capped at **1000** rows, oldest trimmed.

## 2. Cloud egress — everything else

| Channel | What | When | Code |
|---|---|---|---|
| `POST …/api/bydmate/trip-summaries` | Per-trip aggregates imported from BYD `energydata` | After each `HistoryImporter.runSync()` — TrackingService start / app start, only when the energydata source changed. Batch ≤ 300; sanity caps 2000 km / 500 kWh / 24 h | `TripSummaryCloudSync.kt` |
| Daemon telemetry push | Reduced Di+ sample, **no GPS**, no Room queue | Only while the app is dead: every **60 s**, immediately on gun-state change, `live_only` every **3 s** during a live-fast grant. Skipped while the app heartbeat file is fresh | `CommandDaemon.kt` |
| `POST /api/state`, `/api/poll`, `/api/ack` | Vehicle state for the Alice voice bridge | Poll every **2.5 s**, state report every 10th poll (~25 s); only when `alice_enabled` | `AlicePollingManager.kt` |
| `GET api.github.com/…/releases/latest` | Update check — sends no telemetry | At most once per 10 min per session | `UpdateChecker.kt` |
| `POST …/redeem` | 6-digit VoltFlow link code | User action only | `VoltflowLinkClient.kt` |

## 3. Local persistence (Room / files) — does not leave the device on its own

| Store | What | When written | Retention |
|---|---|---|---|
| `odometer_samples` | Mileage + consumption pairs | Every poll where mileage advanced ≥ 0.05 km | Rolling window, trimmed by km and `MAX_BUFFER_ROWS` |
| `trips` / `idle_drains` | BYD-native trip records | `HistoryImporter.syncFromEnergyData()` at service/app start, only when energydata changed | Permanent |
| `trip_points` / `charge_points` | GPS track | Live during a trip or charge | `DataThinningWorker`: > 7 d thinned to 15 s, > 30 d to 60 s |
| `charges` | Charge sessions | Gun-state edge via `GunStateEdgeDetector`; `AutoserviceChargingDetector.runCatchUp()` at service start for charges that happened while DiLink slept | Permanent |
| `battery_snapshots` | Capacity / SoH estimates | On charges with a sufficient SOC delta | Permanent |
| `cloud_sync_queue` | Pending cloud payloads | See §1 | Trimmed past 1000 rows; marked finished on ACK |
| `hourly_rollups` / `trip_rollups` | Client-side aggregates | See §1 | Pruned 24 h after going clean |
| `settings` | Last SOC, session state, `cloud_sync_last_ok` / `_error` / `_ts` / `_ack` | Every flush and poll | Permanent |
| Session prefs | Widget session id + last-active timestamp | Every poll. **Deliberately not cleared in `onDestroy`** so a sys-kill mid-trip resumes | Cleared only on ignition-off |
| Log capture file | logcat dump | Manual button only; written to MediaStore Downloads (the head unit has no SAF picker) | User-managed |

## 4. Privacy-relevant switches

- `cloud_sync_omit_gps` — sends `location: {}` even with a fix; the server then creates
  no `bydmate_trip_track_points`.
- GPS with accuracy > 30 m is dropped before enqueue.
- While driving, kept points are thinned by a Reumann–Witkam corridor filter: kept if
  first in the leg, > 12 m off the current corridor (a turn), or 30 s since the last
  kept point. The filter resets on any state change.
- `cloud_sync_wifi_only` — holds the queue until Wi-Fi is available.
