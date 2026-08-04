# Cloud Sync — reduce VoltFlow server load (Vercel CPU + Supabase egress)

Context: the VoltFlow backend (EvAcChargeTimer) hit its **Vercel Fluid Active-CPU**
and **Supabase egress** caps. This doc analyzes the APK's contribution and the one
worthwhile change. Companion plan on the server side:
`EvAcChargeTimer/docs/PHASE_0_EFFICIENCY.md`.

## TL;DR

The APK is **already well-optimized** — it batches and adapts sampling cadence. The
naive "1 POST/sec" assumption was wrong. The only meaningful remaining win is
**flushing less often during the charging-bulk phase**. Most of the backend egress is
the server's *read* path, not APK uploads.

> **Update 2026-08-03 — the telemetry path was never the top cost.** Vercel
> Observability confirmed `/api/bydmate/commands` topped the invocation list, not
> `/api/bydmate/telemetry`. See "Command poll" at the bottom of this document.
> Everything above still stands; it just was not the biggest line item.

## Current behavior (verified)

`CloudTelemetrySender.kt` + `CloudTelemetryCadence.kt`. Local poll runs at 1 Hz
(`TrackingService.POLL_INTERVAL_MS = 1000`), but enqueue + flush are throttled:

**Sampling (rows queued):**
| State | Interval | Constant |
|---|---|---|
| Driving | 1 s | `MOVING_SAMPLE_INTERVAL_MS` |
| Charging < 98% | 10 s | `CHARGING_SAMPLE_INTERVAL_MS` |
| Charging ≥ 98% (tail) | 1 s | `CHARGING_TAIL_SAMPLE_INTERVAL_MS` / `..._SOC_THRESHOLD_PERCENT` |
| Parked | 30 s | `PARKED_CLOUD_HEARTBEAT_MS` |

**Flush (HTTP POST, batched via `buildBatch` → server `bydmate_ingest_telemetry_batch`):**
- Driving and charging tail (≥98%): every `ACTIVE_FLUSH_INTERVAL_MS = 15 s`, batch
  `ACTIVE_BATCH_SIZE = 15`.
- Charging bulk (<98%): every `CHARGING_BULK_FLUSH_INTERVAL_MS = 60 s`.
- Parked / inactive: every `config.flushIntervalSec` (default 60 s), batch up to
  `MAX_BATCH_SIZE = 120`.
- `flushNow` on certain state transitions.

The durable transition sample still uses that queue. Since v0.4.9, a charging/parking
transition also sends one queue-independent `live_only` status ping, so the live snapshot moves
within seconds without adding a history row or resetting the batch timer. Since v0.4.10, an
active VoltFlow live view can request `live_fast_seconds`; while that short-lived grant is valid,
the app sends `live_only` status every 3 seconds.

**Effective durable POST rate per vehicle:** driving and charging tail are about one request per
15 s; charging bulk is about one per 60 s with ~6 samples; parked is about one per 60 s. A live
view can temporarily add one lightweight `live_only` status POST per 3 s; it does not write
history or change the durable batch cadence.

## Original charging-bulk problem (resolved)

A charge runs for hours. Before the 2026-06-24 change, 10 s sampling + 15 s flush made the bulk
phase emit ~4
POSTs/min each carrying only 1–2 samples. **Every POST pays the full server
fixed cost** (key lookup, previous-snapshot read, verify re-read, auto-session,
reconcile-if-changed). Example: a 5 h charge = ~1,200 POSTs; ×100 users = ~120k
backend invocations/day from charging alone.

## Proposed change — charging-bulk flush interval

Decouple the flush cadence by state instead of one `ACTIVE_FLUSH_INTERVAL_MS` for all
active states:

- **Driving** → keep **15 s** (1 s sampling fills a batch of ~15; needed for trip resolution).
- **Charging < 98%** → **60 s** (accumulate ~6 samples per POST).
- **Charging ≥ 98% (tail)** → keep **15 s** (1 s sampling; want prompt completion/stop capture).
- **Parked** → unchanged (60 s).

Expected: ~**4× fewer** charging-phase POSTs → ~4× fewer backend invocations + verify
re-reads during the longest phase of the day.

### ✅ Implemented (2026-06-24)

`CloudTelemetrySender.kt`:
1. Added `const val CHARGING_BULK_FLUSH_INTERVAL_MS = 60_000L`.
2. Added `@Volatile lastChargingBelowTail`, set in `decide()`:
   `charging && (snapshot.soc ?: 0) < CHARGING_TAIL_SOC_THRESHOLD_PERCENT`.
3. In `flushPending`, when `activeBatchMode`, the interval is now
   `CHARGING_BULK_FLUSH_INTERVAL_MS` iff `lastCharging == true && lastChargingBelowTail`,
   else `ACTIVE_FLUSH_INTERVAL_MS` (covers driving and the ≥98% tail).
4. `ACTIVE_BATCH_SIZE` (15) remains the size-based safety flush — a 60 s bulk window
   queues only ~6 samples, so it won't trip early.

### Decision: no prompt charging-start **history** flush

The earlier plan said to add a prompt flush when state enters CHARGING. **Dropped after
tracing it through:** a prompt history flush drains the queue and *resets* the active batch
window, which pushes the *next* bulk flush ~60 s later — net-delaying the batch rather
than helping. Auto-start needs **4 consecutive** `charge_power_kw` samples (~40 s at 10 s
sampling) regardless, and the first 60 s bulk flush already carries them, so auto-start
fires at ~t+60 s — within the documented acceptance and the server's ≤90 s freshness
target. Not worth the added `decide()` complexity or the risk to the batching invariant. This does
not prevent the separate v0.4.9 `live_only` status ping at a charge/park transition: it updates
the live snapshot immediately while the durable transition sample stays in the normal batch.

### Safety / acceptance

- **Live status freshness:** a charge/park transition now pings the live snapshot within seconds;
  the 60 s durable charging-bulk flush remains within the server's ≤90 s target.
- **Auto-start:** fires at ~t+60 s of plug-in (first bulk flush carries the 4 consecutive
  samples). Slightly later than the old ~t+35 s; negligible for a multi-hour charge.
- **Charge-threshold push notifications:** may lag up to ~60 s. Accepted.
- **Tail capture:** unchanged (≥98% → 15 s flush + 1 s sampling).

### Tests — ✅ passing

Added `CloudTelemetrySenderTest`:
`charging below tail soc flushes every sixty seconds not fifteen` (asserts no POST
before 60 s, one batch at 60 s). Existing flush tests use `soc = 98` (tail → 15 s, and
they hit the 15-sample batch flush) so they're unaffected. Full debug suite:
**417 tests, 0 failures** (`./gradlew testDebugUnitTest`).

## Out of scope (server side — see EvAcChargeTimer/docs/PHASE_0_EFFICIENCY.md)

- Trim the `raw_payload` verify re-read in the telemetry route (biggest egress item).
- Tiered web session poll (done).
- Reconcile gated to auto-session start/stop (done).

## Resolved vehicle-ID risk

Changing `cloud_sync_vehicle_id` no longer drops old queued payloads: `flushQueue()` groups rows
by the `vehicle_id` stored in each payload and sends each group with a matching
`X-Vehicle-Id` header (`7b37366`). This preserves delivery, but it does **not** merge the server
history under the old and new IDs. Any batching change must retain this per-vehicle grouping.

---

## Command poll — the actual top consumer ✅ (shipped 2026-08-03)

**Measured, not assumed:** `/api/bydmate/commands` topped Vercel's invocation list.

`CommandDaemon.commandLoop` polls every 6 s on its own thread and — unlike the telemetry
push — is **not** gated on the app-alive beacon (`shouldDeferToApp` guards pushes only).
The daemon runs whenever the head unit is powered, so that is ~14,400 requests/car/day,
plus another ~1,200 from `VehicleCommandPoller` while the app is alive. Against roughly
2,000 telemetry POSTs/car/day, the command poll is **~87 % of all car→cloud requests** —
and since `REMOTE_COMMANDS_DISABLED = true` (server, `fd160ea`, 2026-07-23) every single
one returns `{ commands: [] }` after paying a full key lookup and DB round trip.

**Why it could not simply be slowed down:** the poll was the *only* carrier of the
`live_fast_seconds` grant. Slowing it to 60 s would have meant up to 60 s before a car
noticed someone opened the live view.

**The change (both repos):**

1. **Second grant carrier.** The telemetry ingest response now also returns
   `live_fast_seconds`, computed by the shared `src/lib/bydmate/live-fast.ts` from the
   profile row the route already loaded for auth — **zero extra queries**. Fast-mode
   *entry* is now bounded by the telemetry cadence (15 s driving, 60 s parked/charging)
   instead of the command poll; *renewal* rides the 3 s `live_only` pings, which are the
   most frequent thing a watched car sends.
2. **Server-driven poll interval.** The command response carries `poll_after_seconds` —
   60 s while commands are suspended, 6 s when they are active or a command was just
   delivered. `VehicleCommandPoller.pollIntervalMs` and `CommandDaemon.commandPollIntervalMs`
   honour it, clamped to **6–300 s** so a bad value can never park a car on a cadence only
   an APK release could undo.

**Expected: ~10× fewer command-poll invocations** (~14.4k → ~1.4k/car/day).

**Backward compatibility.** Both fields are additive. An older APK ignores
`poll_after_seconds` and keeps its built-in 6 s; a missing `live_fast_seconds` reads as 0
and simply lets the current window lapse. No version gating anywhere — same convention as
`live_only` / `client_hourly` / `client_trip`.

**Deploy order is safe either way**, but note the win only lands with the APK: cloud-first
means old clients keep polling at 6 s until they update.

**Restoring remote commands** is a one-line server change: flip `REMOTE_COMMANDS_DISABLED`
to `false`, which switches `POLL_AFTER_SECONDS` back to `ACTIVE_POLL_AFTER_SECONDS` (6 s).
No APK release needed.

**Still open (measured but not done):** `updateTelegramLiveWidgets` runs its own `profiles`
lookup on every ingest — including every 3 s live ping — just to early-exit when no Telegram
chat is linked; `resolveBydmateApiKeyProfile` already reads that row and could carry
`telegram_id`. And the PWA's `requestLiveFastStatus` heartbeat (`MobileShell.tsx`, every
8 s per open tab) is a server action writing one timestamp column under
`profiles_update_own` RLS — it could be a direct Supabase write with no function invocation.
