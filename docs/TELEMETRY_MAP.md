# Telemetry Map — what is saved locally, what is sent to the cloud, and when

Single-page answer to "where does each piece of telemetry go?". Companion docs:
[`HOW_IT_WORKS.md`](HOW_IT_WORKS.md) (full pipeline narrative),
[`cloud-telemetry-contract-ru.md`](cloud-telemetry-contract-ru.md) (wire format),
[`CLOUD_SYNC_EGRESS_PLAN.md`](CLOUD_SYNC_EGRESS_PLAN.md) (why the cadences are what they are),
[`REMOTE_COMMAND_DAEMON.md`](REMOTE_COMMAND_DAEMON.md) (the parked/off daemon).

Verified against `main` @ `4fe21aa`. Field-level wire contents are in [§5](#5-wire-fields--what-each-payload-actually-contains).

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
| **Status ping** | One `live_only` payload, bypasses the queue entirely | On a moving/charging edge that did not already `flushNow`; **and every 3 s** while a `live_fast_seconds` grant is active. The grant now arrives on **two** carriers — the command poll (which idles at 60 s while remote commands are suspended, see §2) and every telemetry ingest response — so fast-mode *entry* is bounded by the flush cadence and *renewal* rides these pings themselves | `sendPendingStatusPing` |
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
| Daemon telemetry push | Own payload, **no GPS**, no Room queue, no `autoservice`/rollup blocks — but a **richer** `diplus` block than the app's (§5.2) | Only while the app is dead: every **60 s**, immediately on gun-state change, `live_only` every **3 s** during a live-fast grant. Skipped while the app heartbeat file is fresh | `CommandDaemon.kt` |
| Daemon command poll | `GET …/api/bydmate/commands`; carries the `live_fast_seconds` grant back | Every **6 s** by default, but the server sets the cadence via `poll_after_seconds` (**60 s** while remote commands are suspended), clamped client-side to 6–300 s. **Not** gated on the app being alive — it runs whenever the head unit is powered | `CommandDaemon.commandLoop` |
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

## 5. Wire fields — what each payload actually contains

There are **three** payload builders, not one. Sections 1–2 cover *when* something is
sent; this covers *what is in it*.

**The rule that governs every table below:** all optional fields go through
`putIfPresent`, so an absent value **omits its key entirely** — no `"key": null` on the
wire. "Sent" therefore means "the builder can emit it"; whether it actually appears
depends on the car reporting the value and on the state gate.

This holds for **both** builders as of 2026-08-06. The daemon previously wrote
`JSONObject.NULL`, putting all 50 `diplus` keys on every push regardless — see §5.4
item 2 for why that changed and why it was safe.

### 5.1 App payload — `CloudTelemetryPayload.build`

**Envelope**

| Field | When |
|---|---|
| `schema_version` (=1), `vehicle_id`, `device_time`, `source` (="BYDMate"), `mate_version` | Always |
| `telemetry {}`, `location {}` | Always present (either may be `{}`) |
| `diplus {}` | Only when di+ answered |
| `autoservice {}` | Only when ≥ 1 autoservice field is present |
| `live_only: true` | Parked, nothing material changed. Omitted, **never** `false` |
| `client_hourly: true` | Sample folded into an on-device hourly block. Omitted, never `false` |
| `trip_id` + `client_trip: true` | Only while a client-owned trip is open |

The flush envelope wraps these as `samples[]` plus optional `hourly[]` / `trips[]`.

**`telemetry {}` — 20 fields, state-gated.** P = parked, D = driving, C = charging.

| Field | Sent when | Rounded |
|---|---|---|
| `soc` | always | — |
| `soh_percent` | always, incl. parked (slow-moving, cached BMS value) | — |
| `is_charging` | not P, or when `soc` is present | — |
| `speed_kmh`, `power_kw` | D/C, or whenever the value exists at all | — |
| `charge_power_kw`, `charge_type` | **C only** | — |
| `kwh_charged` | **C only** | 3 dp |
| `battery_temp_c`, `cabin_temp_c`, `outside_temp_c`, `battery_voltage_v`, `aux_voltage_v` | **not P** | — |
| `cell_voltage_min_v`, `cell_voltage_max_v`, `cell_delta_v` | not P, or when min/max known | 4 dp |
| `odometer_km` | **not P** | — |
| `range_est_km` | **not P** | 1 dp |
| `current_trip_distance_km` | **not P** | 3 dp |
| `current_trip_consumption_kwh_100km` | **not P** | 2 dp |

**`location {}`** — `lat`, `lon`, `accuracy_m`, `bearing_deg`. Emitted as `{}` when
`cloud_sync_omit_gps` is on, the corridor filter thinned this point, accuracy > 30 m, or
lat/lon are missing (see §4).

**`diplus {}` — two different shapes.**

- **Parked** (8): `soc`, `gear`, `charge_gun_state`, `speed_kmh`, `power_state`,
  `voltage_12v`, `sentry_state`, `stall_sentry_mode`
- **Not parked** (23): `soc`, `gear`, `max/avg/min_battery_temp_c`,
  `battery_capacity_kwh`, `total_elec_consumption_kwh`, `voltage_12v`,
  `max_cell_voltage_v`, `min_cell_voltage_v`, `cell_delta_v` (all 4 dp),
  `sunshade_percent`, `sentry_state`, `remote_lock_state`,
  `window_fl/fr/rl/rr_percent`, `sunroof_percent`, `lock_fl`, `ac_status`, `ac_temp_c`,
  `inside_temp_c`
- **Driving or charging adds 5 more**: `speed_kmh`, `mileage_km`, `power_kw`,
  `charge_gun_state`, `charging_status`

**`autoservice {}` — 9 fields:** `soc_percent`, `power_kw`, `gun_state`, `bms_state`,
`charge_capacity_kwh`, `charge_battery_volt`, `battery_type`, `lifetime_mileage_km`,
`lifetime_kwh`.

### 5.2 Daemon payload A — di+ (`CommandDaemon.buildTelemetryPayload`)

Envelope is `schema_version`, `vehicle_id`, `device_time` (from `isoNow()`, **not** a
snapshot time), `source`, `mate_version`, `telemetry {}`, `diplus {}`, `location {}`,
plus `live_only: true` when applicable.

**Never sent by the daemon:** `autoservice {}`, `client_hourly`, `trip_id` /
`client_trip`. `location` is **always** an empty `{}` — the daemon has no GPS, and the
key exists only because the ingest schema requires it.

**`telemetry {}` — 17 fields, with no state gating at all** (unlike §5.1):
`soc`, `speed_kmh`, `power_kw`, `battery_temp_c` (= *avg* battery temp), `cabin_temp_c`,
`outside_temp_c`, `aux_voltage_v`, `cell_voltage_min_v`, `cell_voltage_max_v`,
`cell_delta_v`, `odometer_km`, `is_charging` (always, bool), `is_parked` (always, bool,
from `gear == 1`), `soh_percent`, plus charging-only `charge_power_kw`, `kwh_charged`,
`charge_type`.

**`diplus {}` — 50 fields, all unconditional.** Everything the app sends **plus**:
`exterior_temp_c`, `power_state`, `fan_level`, `ac_circ`, `door_fl/fr/rl/rr`, `trunk`,
`hood`, `seatbelt_fl`, `tire_press_fl/fr/rl/rr_kpa`, `drive_mode`, `work_mode`,
`auto_park`, `rain`, `light_low`, `drl`, `stall_sentry_mode`.

### 5.3 Daemon payload B — autoservice fallback (`buildAutoserviceFallbackPayload`)

Used **only** when di+ is unreachable *and* `shouldUseAutoserviceFallback` has confirmed
the car is parked or charging — never during a drive, because this payload has no
`gear` or `speed` of its own. Has no `live_only` variant.

- **`telemetry {}` (9):** `soc`, `power_kw`, `aux_voltage_v`, `is_charging`, `is_parked`,
  `soh_percent`, plus charging-only `charge_power_kw`, `kwh_charged`, `charge_type`
- **`diplus {}` (14):** `soc`, `power_kw`, `charge_gun_state`, `voltage_12v`,
  `door_fl/fr/rl/rr`, `trunk`, `hood`, `tire_press_fl/fr/rl/rr_kpa`

Note this block is **named** `diplus` but is populated from autoservice reads — the
server keys off that name, so it is deliberate, but it is confusing when reading raw
payloads.

### 5.4 Known discrepancies

1. ~~**Phase 1 float rounding never reached the daemon.**~~ **Fixed 2026-08-06.** Both
   daemon builders now round through `CommandDaemon.roundForWire`: cell voltages
   (`max/min_cell_voltage_v`, `cell_voltage_min/max_v`, `cell_delta_v`) to 4 dp and
   `kwh_charged` to 3 dp, matching `CloudTelemetryPayload` and the precision
   `telemetry-sanitizer.ts` already applies server-side — so it is a no-op for the backend
   and purely saves wire bytes. The motivating case was `cell_delta_v`, computed as
   `maxCellVoltage - minCellVoltage` in both builders, which produced
   `0.019999999999999` (~20 chars) on every push — and the daemon is the writer for most
   of the day. Rounding changed precision only, not which keys are on the wire (that was
   item 2, below).
2. ~~**The daemon sends every key on every push, as `null` when absent.**~~ **Fixed
   2026-08-06.** The daemon's helper was renamed `putN` → `putIfPresent` and now omits the
   key, matching the app. This was the largest remaining payload cost: ~30 of the 50
   `diplus` keys were literal `null` on a parked car, ~800 bytes a push — an order of
   magnitude more than the float rounding in item 1.

   **Why it was safe** (verified before the change, worth re-checking if any of the three
   layers is rewritten):
   - **Zod** (`ingest-payload.ts`): every field is `.nullable().optional()`, so an absent
     key and a null key both validate.
   - **Sanitizer** (`telemetry-sanitizer.ts`): gates on `value != null`, which is true for
     `undefined` too — the carry-forward and plausibility checks cannot tell them apart.
   - **SQL**: jsonb key-existence (`?`) *does* distinguish them, but the only uses against
     `telemetry`/`diplus` are the two `soh_percent` ones in
     `20260710170000_analytics_query_fanout_repairs.sql`. Query results are unchanged
     because that query also requires `between 0 and 100`, which a null fails — and the
     partial index `bydmate_telemetry_samples_soh_analytics_idx` gets **smaller and more
     correct**, since it was indexing daemon rows that had the key but no reading. The
     `location ? 'lat'` checks in the GPS-retention functions are unaffected: the daemon
     has no GPS and already sent `location: {}` with no keys at all.

   Only new rows change shape; historical rows keep their explicit nulls until retention
   purges them.
3. **`is_parked` is daemon-only.** The app never sends it, so its presence effectively
   marks a sample as daemon-authored.
4. **The fallback infers `is_parked` as `!isCharging`**, whereas the di+ path reads
   `gear == 1`. Benign for a parked, unplugged car, but it is a guess rather than a
   reading.
