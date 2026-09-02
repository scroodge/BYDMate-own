# BYDMate APK offline telemetry investigation

Date: 2026-08-28  
Scope: investigation only; no source changes. Evidence was reviewed in the requested order:
BYDMate-own/EvAcChargeTimer documentation, project agent memory, then current implementation
and tests.

## Executive answer

**Severity: HIGH for an offline drive longer than about 16 minutes 40 seconds; MEDIUM for
shorter outages; HIGH if the car-off daemon is the only sender.**

The main Android app buffers telemetry durably in the on-device Room/SQLite database table
`cloud_sync_queue`. It preserves each sample's capture-time `device_time`, and the server accepts
historical batches into `bydmate_telemetry_samples` using that timestamp rather than upload time.
Therefore a reconnect does **not** timestamp the retained samples at reconnect time.

However, the queue is capped at 1,000 rows and evicts the **oldest** rows. At the 1 Hz driving
cadence it holds only about 1,000 seconds (16m40s). One hour offline while driving creates about
3,600 samples, so roughly the oldest 2,600 are irrecoverably lost. This creates a real historical
gap that can corrupt or weaken auxiliary-voltage rollups, phantom-drain/resting-window analysis,
trip tracks, and any analysis requiring continuous raw telemetry. The newest samples, including
the reconnect moment, are retained.

The separate shell `CommandDaemon` is materially weaker: it bypasses Room and sends telemetry
best-effort. If its POST fails, that daemon sample is dropped and is not replayed after reconnect
or reboot.

## Answers to the requested questions

### a) Buffered or dropped, and where?

- **Main app (`TrackingService` / `CloudTelemetrySender`): buffered durably in SQLite via Room.**
  `enqueue()` serializes the complete payload and inserts a `CloudSyncQueueEntity` into
  `cloud_sync_queue` before `flushPending()` attempts HTTP. A network exception is classified as
  retryable, leaving `sentAt` null.
- With the Wi-Fi-only option enabled and Wi-Fi absent, `flushPending()` does not attempt HTTP;
  it reports `waiting for Wi-Fi` and leaves rows queued.
- With Wi-Fi-only disabled, absence of internet is discovered through the failed OkHttp request;
  the rows likewise remain queued.
- **Daemon (`CommandDaemon`): silently lost from a durability perspective.** It does not use the
  Room queue. Failed best-effort telemetry POSTs have no persistent replay path.
- Queue-independent `live_only` status pings from the main app are also best-effort, but the
  corresponding normal transition/full sample remains in the durable queue. This does not make
  the daemon durable.

Evidence: `CloudTelemetrySender.enqueue`, `flushPending`, and `sendPendingStatusPing`;
`CloudTelemetryClient.send`; `CloudSyncQueueEntity`; documentation in
`HOW_IT_WORKS.md` sections 4.3-4.4 and 5.7 and `cloud-telemetry-contract-ru.md`.

### b) Capacity and eviction policy

- Nominal cap: **1,000 total queue rows** (`MAX_QUEUE_ROWS = 1000`).
- Eviction: `pruneToMaxRows` keeps the 1,000 newest rows ordered by `createdAt DESC`, therefore it
  deletes the **oldest**, regardless of whether they are unsent, acknowledged, or quarantined.
- FIFO upload order is oldest-unsent-first (`ORDER BY createdAt ASC`).
- Pruning occurs before enqueue and after flush. This permits a transient 1,001st row until the
  next prune, but does not materially extend offline retention.
- There is no separate reserved space for the reconnect sample. Dropping oldest does preserve
  the newest/reconnect edge, at the cost of earlier history.

Capacity by current cadence:

| State | Queue cadence | Approximate full-queue duration | One hour offline |
| --- | ---: | ---: | ---: |
| Driving | 1 s | 16m40s | 3,600 generated; about 2,600 oldest lost |
| Charging below 98% | 10 s | 2h46m40s | 360 generated; retained |
| Charging tail at/above 98% | 1 s | 16m40s | Same risk as driving |
| Parked | 30 s | 8h20m | 120 generated; retained |

These figures assume the queue is empty when the outage begins. Existing unsent/quarantined rows
reduce the available window. Acknowledged rows also occupy the table until normal pruning replaces
them, although they are naturally the first old rows removed as new offline samples arrive.

### c) Reconnect upload, historical batches, and timestamps

- **Yes, retained main-app rows are uploaded on a later flush.** If more than 15 are pending,
  `flushPending()` enters backlog-drain behavior and sends multiple FIFO batches in one call.
- Each queued JSON payload already contains `device_time = snapshot.deviceTimeIso`.
  `VehicleTelemetrySnapshot.from()` creates it from the capture epoch milliseconds. Flush wraps
  the stored JSON; it does not regenerate the timestamp.
- The server parses and normalizes each supplied `device_time`, passes it as `p_device_time`, and
  inserts it into `bydmate_telemetry_samples.device_time`. `received_at` is separately set to the
  reconnect/upload time.
- The batch RPC sorts historical samples by `device_time`, inserts them idempotently, and reports
  `skipped_stale_count = 0`. A database trigger prevents an old backfill sample from moving the
  latest live snapshot backward.
- Server batches accept up to 300 samples, while this APK sends at most 15 per active batch or 120
  per idle/restarted flush, so the client stays within the server contract.

**Conclusion: retained samples land at their true capture timestamps, not reconnect time. There
is no reconnect-time timestamp corruption in the current main-app path.** The daemon creates its
timestamp at send time, but because it has no replay queue there is no delayed daemon backfill to
misdate.

### d) Retry, backoff, and reconnect burst

- Retryable: network exceptions, 5xx/other non-4xx responses, incomplete application ACKs, and
  HTTP 401/403/404/408/429. Rows remain unsent.
- Non-retryable payload/content failures (other 4xx, notably 400/413/415/422) are quarantined by
  setting `sentAt` plus `lastError`; they leave the FIFO stream and are not retried.
- **There is no exponential or capped retry backoff for telemetry.** The `attempts` column is
  diagnostic only; it is not consulted when scheduling the next attempt.
- In active mode, once `unsentCount >= 15`, every poll satisfies the batch threshold. After a
  retryable failure, the next 1 Hz poll can try again. Thus a long outage with Wi-Fi-only disabled
  can produce repeated failed requests roughly once per second (each bounded by OkHttp timeouts).
- On reconnect, the sender intentionally drains all backlog in consecutive batches. At a full
  queue this is up to about 67 requests of 15 samples in an active process, or about 9 requests of
  up to 120 after process restart (because active-mode state is in memory and resets). Requests
  are sequential within one sender, not parallel, but a fleet reconnecting together can still
  create a server-side thundering-herd burst. There is no jitter or rate limiter.

### e) App restart/device reboot while offline

- **Main queue survives** normal process death, app restart, and device reboot because it is an
  ordinary Room database (`bydmate.db`), not an in-memory collection. `BootReceiver`/worker starts
  `TrackingService`, which resumes queue processing.
- It does not survive app-data clearing/uninstall, database corruption, or an incompatible failed
  migration. None of those are ordinary offline/reboot behavior.
- Sender cadence state is memory-only. After restart, the durable rows remain, but the first flush
  may use idle-mode batch sizing until new samples reconstruct state.
- The daemon has no persistent telemetry buffer, so failed daemon samples do not survive either a
  process restart or reboot.

### f) Cadence differences and driving suitability

The offline path uses the same state-dependent enqueue cadence as the online path: driving 1 Hz,
charging bulk 10 s, charging tail 1 Hz, parked 30 s. The fixed row cap does not scale by cadence,
priority, byte size, or outage duration. It is adequate for a one-hour parked outage (120 rows)
and charging below 98% (360 rows), but **not** for a one-hour drive or one-hour balance-tail charge
(3,600 rows each).

The app also keeps cumulative hourly/trip rollups in separate Room tables, so some aggregate trip
or hourly information can survive raw queue eviction. That does **not** restore the evicted raw
samples. In particular, the APK hourly block does not carry auxiliary voltage, while VoltFlow's
12 V daily/resting calculations are server-side over received raw samples. Continuous raw history,
route detail, and resting-window evidence therefore remain damaged by eviction.

## Documentation and memory comparison

### Documentation

The current behavior is substantially documented:

- `BYDMate-own/docs/HOW_IT_WORKS.md` and `docs/cloud-telemetry-contract-ru.md` document Room
  queueing, 1,000 rows, oldest-row trimming, cadence, backlog draining, ACK requirements, and the
  daemon's best-effort exception.
- `EvAcChargeTimer/supabase/VOLTFLOW_MATE_API.md` documents batches, the 300-sample server cap,
  retry ACK semantics, and preservation of `device_time`.
- `EvAcChargeTimer/supabase/TELEMETRY.md` states that offline delivery is supported and records
  the state-specific cadence.

What is **not clearly documented** is the practical conversion of 1,000 rows to only 16m40s while
driving, the lack of telemetry retry backoff/jitter, the possible reconnect request burst, and the
fact that daemon samples are simply unrecoverable when offline.

### Agent memory

The reviewed memory files contain no claim about APK offline buffering. They do establish that
12 V analytics depend on raw telemetry and continuous parked windows, and that server-side daily
rollups were introduced because raw history is the analytical source. Those statements agree with
current code/docs but do not answer queue behavior by themselves.

### Disagreements

1. **Stale documentation:** `BYDMate-own/docs/HOW_IT_WORKS.md` section 5.5 says all 4xx responses
   are non-retryable. Current code, tests, the newer cloud contract, and changelog say
   401/403/404/408/429 are retryable. The implementation follows the newer rule.
2. **Header/body vehicle-id commentary is stale in places:** APK docs/code group queued bodies by
   their baked-in vehicle ID, while the current server route also normalizes body IDs to the
   request header. This does not change offline timestamp behavior, but some comments still
   describe a strict mismatch rejection as the active server behavior.
3. No agent-memory claim disagreed with current code.

## Severity judgement

**HIGH:** the design safely backdates what it retains, but its fixed 1,000-row, oldest-first
eviction cap is far too small for the stated 1 Hz driving requirement. A routine one-hour offline
drive loses most raw samples and creates exactly the continuity gaps that VoltFlow analytics assume
do not exist. This is data loss, not delayed delivery. The daemon path loses every offline sample.

The absence of retry backoff/jitter is a separate **MEDIUM operational risk**: one device drains
sequentially, but many devices reconnecting together can burst requests, and an active offline APK
may repeatedly attempt requests at high frequency before reconnect.

## What cannot be proven from static code

- The actual outage duration before Android/DiLink kills or suspends the app, or whether Di+ keeps
  producing samples throughout a specific head unit's offline period.
- Real network reconnection timing and fleet concurrency.
- The exact production database function definitions if deployed migrations have drifted from this
  repository. The repository's intended/current migration chain preserves historical
  `device_time`; confirming production drift would require read-only access to the live database.

