# How VoltFlow Mate works

A code-level walkthrough of what this app is, what it does on the head unit, and
exactly what leaves the car. Written against `versionName 0.5.1` / `versionCode 337`.

Companion documents:

- [`TELEMETRY_MAP.md`](TELEMETRY_MAP.md) — one-page map of what is stored locally vs sent to the cloud, and when.
- [`cloud-telemetry-contract-ru.md`](cloud-telemetry-contract-ru.md) — field-by-field wire contract (RU).
- [`REMOTE_COMMAND_DAEMON.md`](REMOTE_COMMAND_DAEMON.md) — the parked/off shell daemon.
- [`DIPLUS_DATA.md`](DIPLUS_DATA.md) — what DiPlus and the BYD `autoservice` binder can actually read.

---

## 1. What the app is

VoltFlow Mate is an Android app for **BYD DiLink head units** (the in-dash tablet).
It is a fork of BYDMate that has been stripped down to one job: **be the gateway
that moves the car's live data into the VoltFlow cloud, and execute commands that
come back from it.**

| | |
|---|---|
| `applicationId` | `dev.scroodge.cloudevmate` (installs alongside original BYDMate `com.bydmate.app`) |
| Kotlin package | `com.bydmate.app` (unchanged from the fork parent) |
| UI | Jetpack Compose, Material 3 |
| `minSdk` / `targetSdk` | 29 / **29 deliberately** — `targetSdk` 30+ breaks `listFiles()` on `/storage/emulated/0/energydata/`, which the trip importer needs |
| Local DB | Room, schema version 16 |
| Distribution | **debug-signed APK only**, published to GitHub Releases |

It is *not* a standalone reader. It depends on **di+ (D+, `vandiplus`)** being
installed and configured — D+ is the process that talks to the vehicle CAN bus and
exposes it over localhost HTTP. VoltFlow Mate is a consumer of that, plus an
optional second reader path through the BYD `autoservice` binder.

**The original BYDMate app is *not* a runtime dependency.** VoltFlow Mate is a fork
of it (hence the `com.bydmate.app` Kotlin package and the GPLv3 attribution in
`NOTICE.md`), but it does not read from it, talk to it, or require it installed.
Provenance only — the data source is di+.

### Dependency reality check — read this before anything else

> **VoltFlow Mate does not read the car on its own — D+ is a hard dependency.** The
> app's own telemetry loop has **no fallback**: when D+ returns null it sends nothing.
> The one exception is the survival **daemon**, which (B-07) now pushes an
> autoservice-only telemetry payload while parked or charging if D+ goes silent — see
> §7. If you believe there is broader independence than that, this is the section to
> check first, and see §11 for why D+ still cannot be removed entirely.

**The whole pipeline lives inside one null check** (`TrackingService.kt:749`):

```kotlin
val data = diParsClient.fetch()
if (data != null) {
    // …build snapshot, enqueue, flush — everything…
} else {
    consecutiveNullCount++          // TrackingService.kt:936
    // …back off, relaunch D+…
}
```

There is **no alternative producer in the app.** When D+ returns null the app does not
switch sources — it sends *nothing*, stretches the poll interval toward
`MAX_POLL_INTERVAL_MS`, and sets `_diPlusConnected = false`. (The daemon is the sole
exception, and only while parked/charging — see §7's autoservice fallback.)

It goes further than depending on D+: it **actively repairs** it.
`DiPlusWatchdog.shouldRelaunch()` triggers `tryLaunchDiPlus()`
(`TrackingService.kt:1080`), which connects over on-device ADB and runs
`launchDiPlusService()` to restart D+'s MainService. `CommandDaemon` is the same —
it reads D+ over `127.0.0.1:8988` and actuates through `sendCmd`.

The v0.5.1 changelog states it directly:

> Сверка `autoservice` ↔ di+ в логе демона (диагностика, **источник данных пока не
> меняется**) … в облако эти значения пока не отправляются.
> … Основной pipeline телеметрии/команд не изменился.

**Since then (on `main`, post-0.5.1 changelog), the daemon went past logging.**
`CommandDaemon.pushAutoserviceFallback` now POSTs an autoservice-only telemetry payload
to the cloud when D+ is stale **and** the car is parked or charging (§7). The app loop
above is unchanged — the fallback is daemon-only and never runs while driving — so the
quoted "источник данных пока не меняется" still holds for the app, but no longer for the
daemon.

**What *is* the app's own — the part easily conflated with independence:**

| Mechanism | Independent of what | Still needs D+? |
|---|---|---|
| `CommandDaemon` as a shell-uid `app_process` | **BYD's process killer** — survives the power-off force-stop | **Mostly** — reads D+ over HTTP and actuates via `sendCmd`, but falls back to an autoservice-only telemetry push when D+ is stale while parked/charging (§7) |
| `AdbOnDeviceClient` + `AutoserviceClient` + `FidRegistry` (29 fids, hand-rolled binary ADB protocol) | **D+ entirely** — real, wired to the cloud's `autoservice` block, 16/16 fids validated against D+ on 2026-07-22 | **No** — but it is a *supplement*: needs ADB, and covers a fraction of the ~48 signals D+ supplies |

So survival genuinely is the app's own algorithm — but it is independence from
*BYD force-stopping the app*, not from D+.

**Full independence is not merely unfinished — it is partly blocked.** Even if
B-07 moved every read to the `autoservice` binder, D+ would still be required:

- D+'s 熄火哨兵 stall-sentry is what keeps the head unit awake while parked.
- `127.0.0.1:8988/api/sendCmd` is the **only** actuation channel. `AdbOnDeviceClient` is deliberately write-barriered, so there is no autoservice write path for commands.

A full `nativestack` port (direct autoservice reads) was **evaluated and rejected**:
~40 per-vehicle-validated fids, more on-device ADB load, no new data — and D+ still
could not be removed.

If you are looking for where the "no third-party app" architecture is described,
that is [`EV_PRO_APP_ANALYSIS.md`](EV_PRO_APP_ANALYSIS.md) — a comparison table
describing **competitor BYD EV Pro's** design — plus backlog item **B-07**, filed
under *кандидаты, не запланировано*. The parity logging added in 0.5.1 was the first
evidence-gathering step; the daemon's parked/charging autoservice fallback (§7) is the
first slice of B-07 to actually ship. D+ still cannot be removed, though — actuation and
the stall-sentry above, plus the app's own read path still has no fallback and driving
still relies on D+.

### The UI is one screen

`AppNavigation.kt` renders exactly one destination, `GatewayScreen`, with cards for:

- **Status** — is the gateway running, is D+ connected.
- **Live data** — current SOC / power / gear readout for sanity checking.
- **Cloud Sync** — 6-digit link code, vehicle name, Send test, Save, enable toggle.
- **Advanced features** — the on-device ADB unlock wizard (daemon, SoH, remote commands).
- **Updates** — auto-check toggle + "check now".
- **Background-restriction warning** — appears if DiLink's "Disable background Apps" is still on for this app, with a deep link to that DiLink settings screen.
- **Language switcher** (BE / RU / EN).

`DashboardScreen`, `SettingsScreen` and `WelcomeScreen` from the fork parent were
deleted outright (their settings functionality was folded into `GatewayScreen`).
The remaining richer screens (`TripsScreen`, `ChargesScreen`, `MapScreen`,
`PlacesScreen`, `AutomationScreen`) still exist in the source tree but are **not
wired into navigation** in this build.

---

## 2. The two processes

This is the single most important structural fact about the app.

```
┌─────────────────────────────────────────┐   ┌────────────────────────────────┐
│ APP PROCESS  dev.scroodge.cloudevmate   │   │ SHELL-UID DAEMON               │
│                                         │   │ app_process, uid = shell       │
│  MainActivity → GatewayScreen           │   │ nice-name voltflow_cmd_daemon  │
│  TrackingService (foreground service)   │   │                                │
│    ├─ 1 Hz DiPars poll loop             │   │  CommandDaemon.main()          │
│    ├─ Room queue + batch HTTPS flush    │   │   ├─ command poll (always)     │
│    ├─ VehicleCommandPoller (~6 s)       │   │   ├─ telemetry push (only when │
│    └─ writes app-alive beacon @ 1 Hz    │   │   │   the app is NOT alive)    │
│                                         │   │   └─ optional Wi-Fi keep-alive │
│  KILLED by BYD on car power-off ────────┼───┼─> SURVIVES power-off           │
└─────────────────────────────────────────┘   └────────────────────────────────┘
```

When the head unit parks, BYD's power-off routine (`collectPowerOffEvent` →
`quickboot`) **force-stops the app**. A shell-uid `app_process` daemon is not an
"app" and survives. That is why `CommandDaemon` exists — it is the same command and
telemetry logic re-hosted in a context BYD does not kill.

### They never both send telemetry

`TrackingService.writeAppAliveHeartbeat()` writes epoch-millis to
`<externalFilesDir>/voltflow_mate_heartbeat` at **1 Hz**. The daemon reads that
file's age in `shouldDeferToApp()` and stays quiet while the app is clearly alive:

| Push kind | Beacon TTL | Rationale |
|---|---|---|
| history-writing (cadence, gun edge, forced-full) | **20 s** | a false "app is dead" stores a duplicate history row |
| status-only (`live_only`) | **5 s** | writes no history row; worst case is a few stale seconds |

A second guard suppresses the daemon push whenever DiPars classifies the state as
`DRIVING` — the daemon has no GPS and stamps `device_time = now`, so it would beat
the app's batched samples and blank the live map mid-drive.

**Command polling is always-on in both.** Commands are idempotent and server-acked,
so a brief double-poll is harmless and maximizes control reliability.

---

## 3. Where the data comes from

### 3.1 DiPlus / D+ over localhost HTTP — the primary source

`DiParsClient` (`data/remote/DiParsClient.kt`) does a `GET` to:

```
http://127.0.0.1:8988/api/getDiPars?text=<template>
http://127.0.0.1:8988/api/getVal?name=<chinese-signal-name>&status=true
```

The template is a pipe-joined list of ~48 Chinese CAN signal names
(`SOC:{电量百分比}|Speed:{车速}|Power:{发动机功率}|…`). D+ answers with a single
`val` string, which is split on `|` and `:` into `DiParsData` — SOC, speed,
odometer, power, charge-gun state, battery temps (max/avg/min), pack capacity,
lifetime consumption, 12 V, min/max cell voltage, exterior/cabin temps, gear,
power state, A/C state, all four doors, all four windows, sunroof, sunshade,
trunk, hood, seatbelt, door lock, all four tire pressures, drive mode, work mode,
auto-park, rain sensor, lights, sentry state, remote-lock state.

Two extra `getVal` calls fetch text-valued signals: `熄火录像配置开关` (stall-sentry
mode) and `电源状态` (power-state label).

**Sanitizing happens here, not in the cloud.** D+ emits magic "no data" sentinels
that would poison analytics, so `DiParsClient` filters them:

- `sanitizePowerKw` — drops anything beyond ±350 kW (D+ returns ~`3095` when the engine-power PID is unreadable, e.g. car OFF while AC charging).
- `sanitizeSentinelInt` — drops `Int.MIN_VALUE`-family magic numbers (`Rain=-2147482648`).
- `sanitizeTempC` — keeps only −90 … +90 °C (D+ uses `-2000` for "no data").
- Cell voltages ≤ 0.5 V and 12 V ≤ 0 are treated as unavailable; 12 V > 100 is interpreted as millivolts and divided by 1000.
- Odometer is divided by 10 (D+ reports decimetres-of-km).

### 3.2 BYD `autoservice` binder — optional, needs on-device ADB

`AutoserviceClient` + `FidRegistry` + `AdbOnDeviceClient` read the BYD vendor
service directly (`service call autoservice …`). This requires shell UID, so the
app hand-rolls the **binary ADB protocol** (`AdbProtocolClient`) against the head
unit's own ADB daemon at `127.0.0.1:5555`, authenticating with a persistent RSA
keypair in `filesDir/adb_keys/`. The user approves one "Allow USB debugging?"
dialog on the tablet itself — **no computer required.**

This path unlocks what D+ cannot give: **`FID_SOH`** (battery state of health),
**`FID_CHARGING_CAPACITY`** (per-session kWh into the cells), `FID_LIFETIME_KWH`,
lifetime mileage, HV pack voltage, battery chemistry, float SOC.

The client is intentionally **write-barriered** — the only non-autoservice write it
permits is granting `PACKAGE_USAGE_STATS` to the app's own package (needed for
camera-overlay detection), plus starting D+'s MainService and launching the daemon.

The same ADB key is what makes the survival daemon, remote commands, and
telemetry-while-parked possible. Without it the app still streams normal live
telemetry; the advanced features simply do not activate.

### 3.3 `energydata` — BYD's own trip history

`EnergyDataReader` reads `/storage/emulated/0/energydata/EC_database.db` (BYD's
own energy-consumption SQLite). It **copies the file locally first** so BYD's
database is never locked, then reads the `EnergyConsumption` table into
`BydTripRecord`s. `HistoryImporter` dedups (±5 min window, min 30 s duration) and
writes them into the app's Room `trips` table. This is how cars **without** ADB
still get trip history in the cloud.

### 3.4 Android GPS

Standard `LocationManager` via `TrackingService` (a `location`-typed foreground
service). Used for track points and trip segmentation.

---

## 4. The runtime pipeline

`TrackingService` is the engine. It is a `location` foreground service, holds a
wake lock, and is auto-started on boot/quickboot via
`BootReceiver` → `ServiceStartWorker` (and `SilentStartActivity` for Android 12+
background-start restrictions).

Its polling loop, once per second:

```
DiParsClient.fetch()            → DiParsData (sanitized)
  ├─ write app-alive beacon (1 Hz)
  ├─ update session state (powerState ≥ 1 OR TripTracker DRIVING)
  ├─ feed local calculators (range, consumption, trip distance, SOC interpolation)
  ├─ optional: autoservice reads (SoH, charging kWh, engine power) when enabled
  ├─ build VehicleTelemetrySnapshot (+ last known GPS)
  └─ CloudTelemetrySender.enqueue(snapshot)      ← decides IF and HOW to queue
        …separately, on its own timers…
      CloudTelemetrySender.flushPending()        ← decides IF to POST
```

A second, slower loop (`pollGunStateForEdge`) watches the charge-gun state so
plug-in/plug-out edges are caught promptly.

On service start it also: finalizes a stale open trip rollup, runs the energydata
import, runs the autoservice charging catch-up (for charges that happened while
DiLink slept), exports the daemon config, revives the daemon if missing, and
refreshes AI insights if configured.

### 4.1 Enqueue cadence — how often a sample is recorded

Decided in `CloudTelemetrySender.decide()`, with state classified by
`CloudTelemetryCadence`. The cadence has a **2-minute drive latch**
(`DRIVE_LATCH_MS`): after any D/R/N gear or movement, the state stays `DRIVING`
even if D+ briefly reports P — so a red light does not split a trip. It was 10
minutes originally; that was far longer than any junction stop and made every
real park cost 10 minutes of 1 Hz traffic before the 30 s parked heartbeat
resumed. 2 minutes still absorbs stop-and-go while restoring parked economy ~5×
sooner.

| State | Sample interval |
|---|---|
| Driving | **1 s** |
| Charging, SOC < 98 % | **10 s** |
| Charging, SOC ≥ 98 % ("balance tail") | **1 s** — cell delta moves fast here |
| Parked | **30 s** heartbeat |
| Any state change (gear, moving, charging) | immediately |

### 4.2 `live_only` — the parked-heartbeat optimization

While parked, if SOC, gun state, gear and 12 V are all unchanged (12 V with a
**0.3 V** tolerance, because it sags slowly and D+ quantizes it), the heartbeat is
sent with `"live_only": true`. The server then refreshes **only** the live snapshot
and skips the history / hourly-rollup / trip writes.

Two guards keep this honest:

- Any material change falls through to a full sample, so history still gets a row at every real transition.
- A full sample is forced at least every **15 minutes** (`LIVE_ONLY_MAX_RUN_MS`). Without this, an overnight park at flat SOC would collapse into one gap, and the cloud's `bydmate_phantom_drain_daily` discards gaps ≥ 6 h — zeroing out idle hours.

### 4.3 Flush cadence — how often a POST goes out

| Situation | Flush interval | Max batch |
|---|---|---|
| Driving / charging tail | **15 s** | 15 samples |
| Charging bulk (SOC < 98 %) | **60 s** | 15 samples — ~4× fewer backend invocations |
| Idle / parked | **60 s** (setting, 5–300 s) | 120 samples |
| State transition to parked, gear change, D→P→power-off | immediate | — |

Queue caps at **1000 rows**; oldest are trimmed. If "Wi-Fi only" is on and Wi-Fi is
down, samples simply stay queued. If the queue backlogs past 15 unsent, a flush
drains multiple batches in a row.

**Fast D→P→power-off handoff:** when an already-parked `gear=P` sample is followed
by an explicit DiPars `powerState` on→off transition, the final sample is enqueued
and flushed *immediately*, serialized in that order — because BYD is about to
force-stop the process.

**Live-fast mode:** the command poll response carries `live_fast_seconds`. When
somebody has the VoltFlow live view open, the server grants a window and the app
pushes a `live_only` status ping every **3 s** instead of waiting out the batch
flush. It is expiry-based on purpose — a closed tab or dropped network can never
strand the car in fast mode.

### 4.4 Local storage and retention

Room DB, schema v16, ~14 entities (trips, trip points, charges, charge points,
places, rules, rule logs, battery snapshots, idle drains, odometer samples,
settings, and the three cloud-side tables: `cloud_sync_queue`, `hourly_rollups`,
`trip_rollups`).

`DataThinningWorker` thins stored track data on a schedule — points older than 7
days go to 15 s resolution, older than 30 days to 60 s. Settled hourly and trip
rollups are pruned after 24 h.

---

## 5. What the app sends — every outbound destination

### 5.1 Summary table

| Destination | When | What | Gated on |
|---|---|---|---|
| `POST https://voltflow.life/api/bydmate/telemetry` | continuously (see §4.3) | vehicle telemetry samples, hourly + trip rollups | Cloud Sync enabled + API key + vehicle id |
| `POST …/api/bydmate/trip-summaries` | after energydata import | per-trip aggregates | data source = ENERGYDATA (ADB cars send trips via telemetry instead) |
| `POST …/api/bydmate/link-code/redeem` | once, at pairing | `{"code":"123456"}` | user enters the 6-digit code |
| `GET …/api/bydmate/commands` + `POST …/commands/ack` | every ~6 s (app) and in the daemon | nothing outbound but headers; acks carry command id/status/phrase | Cloud Sync configured |
| `GET https://api.github.com/repos/scroodge/BYDMate-own/releases/latest` | app launch (≥10 min apart) + manual | **nothing** — plain GET | auto-check toggle (default on) |
| GitHub release asset download | user accepts update dialog | **nothing** — download only | user action |
| `POST https://openrouter.ai/api/v1/chat/completions` | once per day | **driving statistics summary** — see §5.6 | OpenRouter API key configured (blank by default → never called) |
| `GET/POST <your-endpoint>/api/poll`, `/api/state`, `/api/ack` | ~polling | window/sunroof/trunk/lock/AC/cabin-temp state | Alice smart-home explicitly enabled + endpoint + key |
| `https://api.iternio.com/1/tlm/send` (ABRP) | **never in this build** | — | `IternioTelemetryClient` exists but is **not wired into any production path** (referenced only by tests and comments) |
| `http://127.0.0.1:8988/*` | continuously | localhost only — never leaves the device | — |

The **default and only** always-on external destinations are therefore
**voltflow.life** and **api.github.com**. Everything else is opt-in and off unless
you configure a key.

### 5.2 Transport and headers

`POST` to an HTTPS endpoint — the URL is rejected outright unless it starts with
`https://`.

```http
POST /api/bydmate/telemetry
Content-Type: application/json; charset=utf-8
X-API-Key:    <cloud_sync_api_key>
X-Vehicle-Id: <cloud_sync_vehicle_id>
X-App:        VoltFlow-Mate          (daemon sends VoltFlow-Mate-Daemon)
```

`vehicle_id` is duplicated inside the JSON body. The header must match the body or
the server rejects the whole batch — so on flush, `CloudTelemetrySender` **groups
queued rows by the id baked into each payload** and sends one batch per id. That
way, renaming the vehicle between enqueue and flush cannot drop old rows.

### 5.3 The telemetry payload

Built by `CloudTelemetryPayload.build()`. Top level:

```json
{
  "schema_version": 1,
  "vehicle_id": "way",
  "device_time": "2026-07-22T12:34:56Z",
  "source": "BYDMate",
  "mate_version": "0.5.1",
  "live_only":    true,
  "client_hourly": true,
  "trip_id": "<uuid>", "client_trip": true,
  "telemetry":   {  },
  "diplus":      {  },
  "autoservice": {  },
  "location":    {  }
}
```

(`live_only`, `client_hourly`, `trip_id`/`client_trip` are **omitted entirely**
unless true — a normal sample keeps its exact shape on the wire. `diplus` may be
`null`; `autoservice` is absent without ADB; `location` may be `{}`.)

`telemetry` (normalized): `soc`, `speed_kmh`, `power_kw`, `battery_temp_c`,
`cabin_temp_c`, `outside_temp_c`, `battery_voltage_v`, `aux_voltage_v`,
`cell_voltage_min_v` / `max_v` / `cell_delta_v`, `odometer_km`, `soh_percent`,
`is_charging`, `charge_power_kw`, `charge_type` (`AC` for gun state 2, `DC` for
3–5), `kwh_charged`, `range_est_km`, `current_trip_distance_km`,
`current_trip_consumption_kwh_100km`.

`diplus`: everything from §3.1 — the full body/comfort/tire/lighting picture.

`autoservice` (only present with ADB): `soc_percent`, `power_kw`, `gun_state`,
`bms_state`, `charge_capacity_kwh`, `charge_battery_volt`, `battery_type`,
`lifetime_mileage_km`, `lifetime_kwh`.

`location`: `lat`, `lon`, `accuracy_m`, `bearing_deg`.

**Payload tiers** keep parked traffic small:

- **Idle/parked** — slim: SOC, charging/SoH, and a compact `diplus` status block (`gear`, gun, power state, 12 V, sentry). No traction `power_kw`, no temps, no odometer.
- **Moving/charging** — full: power, temps, odometer, trip fields, extended `diplus`.
- Nulls are **not serialized** at all (omit-nulls).
- Floats are rounded before serialization — cell voltages/delta to 4 dp, `range_est_km` to 1, trip distance and `kwh_charged` to 3, trip consumption to 2. This mirrors what the server's sanitizer applies anyway, so it is a no-op server-side and purely saves bytes (a raw `0.019999999999999` was ~20 characters).

### 5.4 Batch envelope and client-side rollups

```json
{
  "samples": [ {}, {} ],
  "hourly":  [ {} ],
  "trips":   [ {} ]
}
```

The app now owns hourly and trip aggregation locally (`HourlyRollupAccumulator`,
`TripRollupAccumulator`) and ships cumulative blocks once per flush, marking the
constituent samples `client_hourly` / `client_trip` so the server does not
double-count. Blocks are **re-sent in full every flush** until acknowledged — the
server replaces its row only when the incoming `sample_count` is at least what it
holds, so a retry is a no-op rather than a double-count, and a block lost to a
failed flush heals on the next one.

Trips open on the confirmed idle→driving transition and close on gear→P (with the
same `≤ 5 km/h` guard the server uses) or on charging start. A trip left open by a
process death is closed on next boot if it has seen no sample for 20 minutes; a
faster restart resumes the same trip instead of forking a new one.

### 5.5 Delivery acknowledgement

HTTP status alone is not enough. After a `2xx`, the batch is removed from the queue
**only** if the parsed JSON satisfies all three:

- `ok == true`
- `skipped_stale_count == 0`
- `inserted_count + duplicate_count >= <samples sent>`

Otherwise the batch stays queued and retries. `4xx` is non-retryable (rows marked
finished with the error); `5xx`, unknown codes and network exceptions are
retryable. Diagnostics land in the settings as `cloud_sync_last_ack`, e.g.
`15 sent, 12 ins, 3 dup, 0 skip`.

### 5.6 What goes to OpenRouter (if you enable it)

Off by default — `KEY_OPENROUTER_API_KEY` is blank and `refreshIfNeeded()` returns
the cache immediately when it is. If you do set a key, once per day
`InsightsManager` sends **aggregated driving statistics** (consumption trends,
trip counts, temperature correlations, a 7-day 12 V history) to
`https://openrouter.ai/api/v1` with a system prompt asking for a small JSON of
insights in Russian. No GPS coordinates, no VIN, no API keys. The `HTTP-Referer`
header identifies the app as `https://github.com/scroodge/BYDMate-own`.

### 5.7 GPS privacy controls

Three independent mechanisms:

1. **`Don't send GPS to cloud`** (`cloud_sync_omit_gps`) — the payload carries `location: {}` even with a valid fix. Live SOC/charging still sync; the server creates no track points.
2. **Accuracy gate** — a fix with `accuracy_m > 30` is dropped before enqueue (sent as `{}`).
3. **Corridor thinning while driving** — `GpsCorridorFilter` runs a streaming Reumann–Witkam filter. A point is kept if it is the first of the trip, deviates from the current corridor by more than **12 m** (a real turn), or 30 s have passed since the last kept point. Thinned samples send `location: {}` — the telemetry itself (SOC, power) still goes out at 1 Hz. The filter resets on any state change, so the first point of a new leg is always kept.

The daemon has no GPS at all and always sends `location: {}`.

---

## 6. What the app receives — remote commands

### 6.1 The loop

`VehicleCommandPoller` (in-app, every ~6 s, exponential backoff to 30 s on error)
and `CommandDaemon` (in the shell daemon) both `GET` the commands endpoint, derived
from the telemetry URL by swapping `/telemetry` → `/commands`.

```
VoltFlow /api/bydmate/commands ──poll──> poller / daemon
                                             │ guards
                                             │ CommandAllowlist.buildPhrase()
                                             ▼
                            DiPlus 127.0.0.1:8988/api/sendCmd ──> vehicle (CAN)
                                             │
                            POST /api/bydmate/commands/ack <────┘
```

Actuation is **only ever** a D+ voice-assistant phrase (`迪加`-style) pushed to
`sendCmd`. There is no direct CAN write path in this app.

### 6.2 Safety layers

Four of them, in order:

1. **Movement guard** — `movementBlockReason()` rejects unless speed is 0 and gear is P (stationary-charging is accepted as a P-equivalent). Rejections: `vehicle_moving`, `gear_not_park`, `gear_unknown`.
2. **12 V guard** — rejects with `aux_voltage_low` below **11.8 V**, so a weak aux battery is not drained by actuation.
3. **Allowlist** — `CommandAllowlist.buildPhrase()` maps a small typed command set to fixed phrases and range-checks every parameter (`set_soc_limit` 50–100, `ac_temp` 16–32, `fan_level` 0–7, window/sunroof/sunshade percentages 0–100, `tts` text ≤ 80 chars and no brackets). An unknown `type` is `unknown_type`.
4. **Deny list** — any produced phrase containing `发送CAN`, `执行SHELL`, `执行TSHELL`, `点击`, `滑动`, `按钮`, `按键`, `浮窗`, `下电` is rejected outright. `DiParsControlClient` blocks the same patterns again at the send site.

Supported types include: lock/unlock, windows (per-window % and presets), sunroof,
sunshade, A/C on/off/vent/temp/fan, defrost and rear defrost, seat and steering
heating, mirror fold, trunk, charge port, SOC limit, scheduled charging, sentry
mode, screen off, HUD, auto high-beam, child lock, find-car, honk, flash lights,
and TTS.

Every command is acked back with `{"id", "status", "result"}` where status is
`done` / `rejected` / `failed`.

> **Known platform limit:** D+ phrases require `电源状态 ≥ 1` (car ON) to move
> physical hardware. At `PWR=0` D+ acks the phrase but nothing actuates. The daemon
> still queues and acks — the command executes next time the car powers on. Official
> BYD remote control uses a separate T-BOX/CAN wake channel this app has no access to.

---

## 7. The parked/off daemon in detail

- **Launched by** `start_voltflow_cmd.sh`, a watchdog shell script. Two copies must stay byte-identical: `tools/start_voltflow_cmd.sh` (pushed manually to `/data/local/tmp/`) and `app/src/main/assets/start_voltflow_cmd.sh` (bundled in the APK, deployed by `TrackingService.deployDaemonLauncher()` to `<externalFilesDir>/`). **The app's self-revival path uses the asset, not `/data/local/tmp`** — a stale asset is a real, previously-observed failure mode (2026-06-19 incident, documented in `REMOTE_COMMAND_DAEMON.md`).
- **Watchdog behavior:** respawns the daemon, auto-restarts it after an APK update (detects the changed package path within ~30 s), and since B-08 also relaunches the main app via `am start` if `pidof dev.scroodge.cloudevmate` comes back empty — cooldown-gated at 60 s so a genuinely broken app is not hammered.
- **Config:** `TrackingService.exportDaemonConfig()` writes `voltflow_cmd.conf` (url / api_key / vehicle_id / `keep_wifi_awake`) to shell-readable external storage, because the daemon runs as uid shell and cannot read app-private settings.
- **Telemetry cadence:** normal 60 s, immediate on gun-state change, and 3 s `live_only` status while a `live_fast_seconds` grant is active. It does **not** use the Room queue — it is best-effort, its job is to keep live state and the command path alive until the app runs again, not to replace the durable app queue.
- **Wi-Fi keep-alive (opt-in):** DiLink drops Wi-Fi ~9 min after power-off. The documented fix is the head-unit's own "Keep network on while parked" toggle. Alternatively, enabling **Settings → Cloud Sync → "Keep Wi-Fi awake while parked"** makes the daemon run `svc wifi enable` every ~60 s itself — idempotent, cheap, no ADB round-trip (it already is shell uid). Off by default pending more real-car testing.
- **Autoservice reads (always-on when the daemon runs):** every push, the daemon also reads SOC, engine power, doors/trunk/hood and tire pressures straight from the `autoservice` binder and logs them next to the D+ values (`autoservice check` / `check2`). This began as parity logging for backlog B-07 (dropping the D+ dependency for reads). **It has since grown a real send path:** when D+ is stale (`DIPLUS_STALE_MS`) **and** the last-known state is parked or charging (`shouldUseAutoserviceFallback`), `pushAutoserviceFallback` POSTs an **autoservice-only** telemetry payload — SOC, power, gun state, 12 V, doors/trunk/hood, tyres, SoH, kWh — to the cloud, so data keeps flowing without D+. It never runs while driving (the fallback has no `gear`/`speed` of its own), and is **not yet field-validated**. The fids come from the real BYD vendor SDK (`android.hardware.bydauto.BYDAutoFeatureIds`, decompiled from the car's own `framework.jar`), and 16/16 validated live against D+ on 2026-07-22.
- **Survives:** power-off / sleep. **Does not survive:** a full reboot — but the app auto-starts on boot and `ensureCommandDaemonRunning()` redeploys and relaunches it, so a reboot self-heals with no manual ADB.
- **Log:** `/data/local/tmp/voltflow_cmd_daemon.log`. **Kill switch:** `touch /data/local/tmp/voltflow_cmd_daemon.disabled`.

---

## 8. Pairing and configuration

### 8.1 The 6-digit link code

The preferred path — no copying a 64-char key from a phone.

1. VoltFlow web app → **Settings → VoltFlow Mate → Connect BYDMate** → a 6-digit code, valid 10 minutes, single use.
2. In VoltFlow Mate → **Cloud Sync → code field → Connect**.
3. `VoltflowLinkClient.redeem()` derives the redeem URL from the telemetry endpoint (`…/api/bydmate/telemetry` → `…/api/bydmate/link-code/redeem`) and POSTs `{"code":"482913"}`.
4. On `200` it stores `api_key` → `cloud_sync_api_key` and `endpoint_url` → `cloud_sync_url`. `401` = bad/expired code, `429` = rate-limited.

An **Advanced** section still allows pasting a key manually (for debugging or a
self-hosted VoltFlow).

### 8.2 `vehicle_id` is not a display name

It is the **primary key of the telemetry stream** in VoltFlow's database
(`bydmate_telemetry_samples`, `_hourly`, `bydmate_trips`, `bydmate_live_snapshots`
all key on it). Changing it:

- does **not** lose queued data — each queued payload keeps the id it was recorded with, and flushes are grouped by that id;
- **does** fork the history — everything before stays under the old id, everything after under the new one;
- **desyncs `cars.vehicle_alias`** in VoltFlow, which links the car's configuration (capacity, charge power) to the telemetry stream.

Set it once, at first setup, as a short latin slug. If you must rename: update
`cars.vehicle_alias` in VoltFlow **first**, then change it in the app.

### 8.3 Notable settings keys

`cloud_sync_enabled` (default **true**), `cloud_sync_url`, `cloud_sync_api_key`,
`cloud_sync_vehicle_id`, `cloud_sync_interval_sec` (default 60, clamped 5–300),
`cloud_sync_wifi_only`, `cloud_sync_omit_gps`, `cloud_sync_keep_wifi_awake`,
`autoservice_enabled`, `data_source`, `app_language`, plus read-only diagnostics
`cloud_sync_last_ok` / `_last_ts` / `_last_error` / `_last_ack`.

Nothing leaves the car until Cloud Sync is enabled **and** both an API key and a
vehicle id are present — the same hard gate applies to telemetry and trip summaries.

---

## 9. Permissions and why each is needed

| Permission | Why |
|---|---|
| `ACCESS_FINE/COARSE/BACKGROUND_LOCATION` | GPS track points and trip segmentation |
| `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_LOCATION` | `TrackingService` must survive as a location foreground service |
| `READ/WRITE_EXTERNAL_STORAGE` | read BYD's `energydata` DB; write the shell-readable daemon config and launcher |
| `INTERNET`, `ACCESS_NETWORK_STATE` | cloud sync; Wi-Fi-only detection |
| `RECEIVE_BOOT_COMPLETED` | auto-start after boot/quickboot |
| `START_ACTIVITIES_FROM_BACKGROUND` | `SilentStartActivity` boot path on Android 12+ |
| `REQUEST_INSTALL_PACKAGES` | install a downloaded APK update |
| `WAKE_LOCK`, `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` | keep the poll loop turning |
| `SYSTEM_ALERT_WINDOW` | the optional floating widget |
| `POST_NOTIFICATIONS` | foreground-service notification |
| `PACKAGE_USAGE_STATS` | camera-overlay detection (granted via the ADB appop path, not a normal runtime grant) |

`allowBackup="false"` — the cloud API key is never included in an Android backup.

---

## 10. Release rules worth knowing

- **Debug APK only.** The installed app is debug-signed; a release-signed APK fails with `INSTALL_FAILED_UPDATE_INCOMPATIBLE`, and uninstalling to swap signatures wipes the in-app cloud config. Build with `./gradlew testDebugUnitTest assembleDebug`.
- **A release is not done when the build passes.** It is done when fresh rows appear in `bydmate_telemetry_samples` / `bydmate_live_snapshots` **after** the install. An APK that builds and installs but stops sending telemetry is a failed release — that shipped once, in v0.4.1.
- **Verify the launcher copies match** before any release touching the watchdog:
  `cmp -s tools/start_voltflow_cmd.sh app/src/main/assets/start_voltflow_cmd.sh`.
- Auto-update reads `api.github.com/repos/scroodge/BYDMate-own/releases/latest` and picks the first `.apk` asset of the newest release.

---

## 11. Known limits and deliberate non-goals

- **Float instantaneous power is impossible.** Engine power is an *integer-kW* field in BYD's own data; reading the same fid as float returns a `-1.0` sentinel, and no battery-current fid exists so `P = V × I` is out either. D+ faithfully passes the integer, and so does this app.
- **The BMS `kwh_charged` counter is cell-only** — roughly 47 % below grid-metered energy. It is sent as a **diagnostic** and must not be used for cost. VoltFlow deliberately computes cost from `SOC_delta% × capacity ÷ efficiency`. This was tried the other way and reverted; do not re-propose it.
- **A full `nativestack` port was evaluated and rejected** — ~40 per-vehicle-validated fids, more on-device ADB load, and no new data, while D+ still cannot be removed (its stall-sentry keeps the head unit awake while parked and it is the only actuation channel).
- **D+ still cannot be dropped.** B-07 (replacing D+ reads with direct autoservice reads) has *partly* shipped: the daemon now sends an autoservice-only payload while parked/charging when D+ is stale (§7). But driving still relies on D+ for reads, the app's own loop has no fallback, and D+ remains the only actuation channel and stall-sentry — so the dependency stands.
- **Termux and Shizuku do not bypass the platform lock.** They need the same one-time ADB/root bootstrap. The unlock is done on the tablet, without a computer, but it cannot be skipped.
