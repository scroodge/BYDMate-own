# Cloud offload plan — moving per-sample computation into the APK

Goal: cut cloud DB write work by doing arithmetic on-device and shipping pre-aggregated results,
instead of having the ingest RPC recompute it per sample. The APK stays a **gateway** — no
analytics or UI move; only arithmetic.

Status as of **2026-07-20**: phases 0–3 shipped **and verified end-to-end in prod**. Phase 3's cloud
side (`bydmate_apply_client_hourly`, the `client_hourly` skip guard in `bydmate_ingest_telemetry`,
`ingest-payload.ts`/`route.ts` wiring) was applied to prod on 2026-07-18 — verified via a rolled-back
dry-run transaction (cumulative replace, stale retry guard, skip-vs-normal sample paths all matched
spec) before the real apply, then confirmed live ingest was unaffected across the fleet afterward.

**Phase 3 driving-hour verification closed 2026-07-20.** The previously-open checkpoint ("no driving
hour has gone through the new path yet") is now confirmed on car `way`:

- `bydmate_live_snapshots.raw_payload->>'client_hourly'` is `true` on `way` (`mate_version` 0.4.9),
  so the flag is being emitted and accepted by the redefined ingest function.
- Driving hours land as blocks, not per-sample increments. Completed hours reconcile
  (2026-07-20 06:00 UTC: rollup `sample_count` 2650 vs 2650 stored sample rows), and — the clearer
  tell — the **in-flight** hour lags its stored rows (07:00 UTC: rollup 87 vs 96 stored) because the
  rollup only advances when a flush attaches the block. The per-sample path would stay in lockstep.
- Completed driving hours show small deficits against stored rows (1973/1975, 623/641, 298/302).
  Consistent with the two-writer margin documented under Phase 3 — daemon samples increment
  per-sample while the client's cumulative block replaces on `sample_count >=`. Bounded and benign
  as analysed; worth re-checking if a deficit ever grows to a material share of an hour.
- Overnight parked hours sit at ~3–5 rows/hour, confirming Phase 2's `live_only` fast path is still
  holding (~60 rows/h → ~4/h) alongside Phase 3.

> **Version note.** The APK half of Phases 3 and 4 has been running on the car since **0.4.9**
> (`way` currently reports `mate_version` 0.4.10). Those are internal builds; the public release
> carrying this work is **v0.5.0** (`versionCode` 336). Where this document says "on the car since
> 0.4.9" it means exactly that — do not read it as "not in 0.5.0".

**Phase 4 closed 2026-07-20.** Its APK side shipped in the 0.4.9 internal build (released as
v0.5.0) and sat inert for two days; the cloud
side (`bydmate_apply_client_trip`, the `client_trip` branch in `bydmate_ingest_telemetry`, the
`bydmate_trips.client_trip` marker, and the `ingest-payload.ts`/`route.ts` wiring) was applied to
prod on 2026-07-20 — verified via a rolled-back dry-run transaction covering all eight cases,
then confirmed live ingest was unaffected across the fleet including two cars on old APKs. The
new path has **not yet carried a real drive**; see "Still to confirm" under Phase 4.

## Measured baseline (prod, 2026-07-17, 14 cars, trailing 7 days)

| state | samples | share |
| --- | --- | --- |
| driving | 167,274 | **73.2 %** |
| parked | 36,946 | 16.2 % |
| charging | 24,243 | 10.6 % |

`bydmate_telemetry_samples` ≈ 954 MB · `bydmate_trip_track_points` ≈ 55 MB

> **This corrected the original plan.** The estimate assumed parked would be 40–60 % of ingest work
> ("the car is parked ~22 h/day"). Wrong: when the car is powered off the app isn't running, so
> parked samples only accrue while the car is on but stationary, and driving at 1 Hz produces ~30×
> more samples per minute than the 30 s parked heartbeat. **Prioritise by measured share, not
> wall-clock intuition.** Re-measure with `EvAcChargeTimer/docs/CLOUD_OFFLOAD_BASELINE.sql` (that
> repo's `docs/` is gitignored, so the file is local-only).

## What each per-sample ingest does today

`bydmate_ingest_telemetry` performs five writes per sample: live-snapshot upsert, history insert,
hourly rollup upsert (4 weighted averages), trip create/extend, track-point insert.

## Phases

| # | Phase | Targets | Status |
| --- | --- | --- | --- |
| 0 | Baseline queries | — | ✅ shipped (local-only file) |
| 1 | Float rounding + GPS corridor thinning | wire bytes, 55 MB track points | ✅ shipped |
| 2 | Parked `live_only` fast path | 16 % of samples | ✅ shipped + verified in prod |
| 3 | Client-side hourly rollups | **100 % of samples** | ✅ shipped + verified in prod (driving hour confirmed 2026-07-20) |
| 4 | APK-owned trips | 73 % (driving) | ✅ shipped — APK side on the car since 0.4.9 (released as v0.5.0), cloud side applied to prod 2026-07-20 |
| 5 | Charging hints | ~11 % | ⬜ likely skip |
| 6 | Retire server-side paths | — | ⬜ gated on fleet `mate_version` |

### Phase 1 — rounding + GPS thinning ✅

- `CloudTelemetryPayload.putRounded()`: cell voltages 4 dp, `range_est_km` 1 dp,
  `current_trip_distance_km` / `kwh_charged` 3 dp, consumption 2 dp. Matches the precision
  `telemetry-sanitizer.ts` already applies server-side → **no-op for the backend**, saves wire bytes
  (a raw `0.019999999999999` was ~20 chars on every sample).
- `GpsCorridorFilter` (Reumann–Witkam): keeps a point if it's first after reset, deviates > 12 m
  from the corridor (a turn), or 30 s elapsed. Driving only; resets on any state change so the first
  point of a leg is always kept. Thinned samples send `location: {}`; telemetry still goes at 1 Hz.

### Phase 2 — parked `live_only` ✅

Payload carries optional top-level `"live_only": true` (omitted, never `false`). Server
(`20260716100000_bydmate_live_only_fast_path.sql`) then updates **only** `bydmate_live_snapshots`
plus its `diplus_*` / `autoservice_*` columns, skipping history/hourly/trip writes.

Sent when parked **and** SOC, gun state, gear and 12 V (± 0.3 V) are all unchanged. Any material
change sends a full sample. Implemented in **two** places:

- `CloudTelemetrySender.decide()` — while the app is alive.
- `CommandDaemon` — while the car is off. **The daemon builds its own payload and bypasses
  `CloudTelemetrySender` entirely**, so it needed the logic duplicated. Car-off is most of the day,
  so this is where parked actually lives: ~60 history rows/h → ~4/h. Charging is deliberately
  excluded — its SOC curve and cell-delta tail need every row.

**⚠️ Do not remove the 15-minute forced-full rule** (`LIVE_ONLY_MAX_RUN_MS`, both files).
`bydmate_phantom_drain_daily` sums gaps between consecutive parked samples but **discards any gap
≥ 6 h**. An unbounded `live_only` run across a flat-SOC overnight park would collapse to one such
gap, zero `idle_hours`, and silently break phantom-drain analytics.

**Ack accounting:** the fast path returns **no** `sample_count` key, so `parseIngestStats()` falls
back to the payload count and reports the sample inserted — `CloudTelemetryAck.isFullyAcknowledged()`
passes and the row leaves the queue instead of retrying forever. `route.ts` verifies persistence
against `bydmate_live_snapshots` (not `_samples`), so `live_only` rows still verify.

**Backward compatibility (verified live).** Payloads without the key are `false` and take the
original path unchanged; the Zod field is `nullable().optional()`. Old APKs work **indefinitely** —
no version gating anywhere. Confirmed on the real fleet: `way` (new build) sent `live_only` and wrote
0 history rows in 4 min while its live snapshot refreshed; `Yuan UP` (old APK) had no key and took
the full path. Only Phase 6 would break old clients, and it's gated on
`bydmate_live_snapshots.mate_version` showing the fleet migrated.

### Phase 3 — client-side hourly rollups ✅ (shipped 2026-07-18)

The hourly upsert (4 weighted averages) runs on **every** sample, so this is the largest remaining
lever. Maintain the per-hour aggregate in Room (updated each poll, persisted continuously so
shutdown costs nothing) and ship it once per flush instead of recomputing per sample.

**Two corrections to the original sketch, found while implementing:**

1. **The server's merge is not reusable as-is.** The plan assumed partial aggregates could ride the
   existing merge-on-conflict upsert. That merge is associative only in *shape* — it hardcodes
   weight 1 on the incoming side (`sample_count + 1`, and `+ excluded.power_avg` rather than
   `excluded.power_avg * excluded.power_sample_count`). A migration is needed either way.
2. **Additive deltas are not retry-safe.** A lost ack on a successful POST would count the hour
   twice. The per-sample path is protected today by the samples table's `on conflict … do nothing`
   dedupe; a delta block has no equivalent. **So blocks are cumulative, not deltas**: the client
   sends the full running aggregate for the hour every flush and the server replaces its row only
   when `excluded.sample_count >= existing.sample_count`. Retries are no-ops, out-of-order arrivals
   are rejected, and a block lost to a failed flush heals on the next one.

**Also folded in: `bydmate_update_hourly_energy`.** It runs a `SELECT` against the 954 MB
`bydmate_telemetry_samples` on *every* sample just to find the previous power reading for its
trapezoidal regen/traction integration. The APK already sees consecutive readings, so it now
accumulates `regen_kwh_sum` / `traction_kwh_sum` on-device with the same math (including the 180 s
gap cap and the zero-crossing split) and ships them in the same block.

#### APK side ✅ (built, tests green — on the car since 0.4.9, released as v0.5.0)

- `HourlyRollupEntity` (Room table `cloud_hourly_rollup`, PK `vehicleId`+`hourStart`) — stores
  **sums** rather than means; the wire/server column is `*_avg`, but dividing once at serialization
  avoids the drift of an incrementally updated mean over a driving hour's ~3600 samples.
- `HourlyRollupAccumulator` — pure, mirrors the server case-for-case. Reads the **built payload**,
  not the snapshot: a parked payload omits `battery_temp_c` etc., so folding the snapshot would
  count fields the server never saw. `HourlyRollupAccumulatorTest` pins the parity.
- `CloudTelemetrySender` — folds each non-`live_only` sample at enqueue; attaches dirty blocks at
  flush; `markClean` is guarded on `sampleCount` so a sample landing mid-flight keeps the hour dirty.
- Wire: samples carry `"client_hourly": true` (omitted, never `false` — same convention as
  `live_only`); the flush envelope gains a sibling `"hourly": [...]` array. A single-sample flush now
  uses the envelope too when a block is attached.
- Room 14 → 15 (`MIGRATION_14_15`).

**The daemon deliberately stays on the server-side path.** `CommandDaemon` needs no Android Context
and has **no Room access**, so it cannot accumulate; its samples omit `client_hourly` and the server
rolls them up per sample as today. That is ~4 full parked samples/hour (the rest are `live_only` and
skip the rollup entirely), so the cost is negligible — and it keeps a car-off hour present in
`bydmate_telemetry_hourly`, which `telemetry-history.ts` needs to draw a flat overnight SOC line once
raw samples age out.

**Two writers per hour — read this before trusting the count guard.** The guard is only truly
idempotent for a single writer. Overnight hours are daemon-only and hours with the app running are
usually app-only, so the two collide only in the handover hours (car switching off, and on):

- The daemon adding to a client-written row is **correct**: the existing weight-1 merge reads
  `existing.power_avg * existing.power_sample_count`, and the client populates both honestly.
- If the client then re-sends that hour, the replace drops the daemon's **≤4** flat-SOC, near-zero-power
  samples from the aggregate. `soc_min/max`, `power_avg` and the energy sums barely move.
- In the reverse case (client count below the daemon's — the car was on but parked only briefly, so
  the client has 1–2 hourly-counted samples), the guard **rejects** the client's block instead. Benign:
  the daemon's rows already represent that hour equivalently.

So it is bounded and benign in both directions, but it holds by margin, not by construction. If that
margin ever matters, the clean fix is **additive deltas plus a monotonic `block_seq` per hour**: that
composes with the daemon's additive writes *and* stays retry-safe, which cumulative-replace only
achieves by assuming it is the sole writer.

#### Cloud side ✅ (applied to prod 2026-07-18, `EvAcChargeTimer` @ `8488eb6`)

**The change is purely additive; the existing per-sample path is not modified.** An earlier draft of
this plan said the count-weighted merge had to be fixed first. That was true only for additive
deltas. Cumulative-replace blocks get their **own new RPC with their own merge**, so the merge that
old APKs and the daemon run through stays byte-identical. It was left alone, per plan.

Implemented in `supabase/migrations/20260717120000_bydmate_client_hourly_rollup.sql`:

1. `bydmate_apply_client_hourly(p_user_id, p_vehicle_id, p_hour_start, p_block)` — new function,
   cumulative replace guarded by `excluded.sample_count >= existing.sample_count`. Computes
   `v_hour_start := date_trunc('hour', p_hour_start at time zone 'utc')` — the **same expression** as
   the per-sample path, so both land on the identical `timestamptz` (the column is `timestamptz`; the
   client sends the truncated hour as an ISO instant, e.g. `"2026-07-17T10:00:00Z"`).
2. The per-sample hourly upsert was extracted into its own function,
   `bydmate_apply_hourly_rollup_sample(p_user_id, p_vehicle_id, p_device_time, p_telemetry)`, exactly
   as planned so Phase 4 doesn't have to re-copy the 468-line ingest function again.
   `bydmate_ingest_telemetry` (9-arg) now skips calling it — and skips
   `bydmate_update_hourly_energy` with it — when `p_raw_payload->>'client_hourly'` is true.
3. `ingest-payload.ts`: added `client_hourly: booleanSchema` to `payloadSchema`, a new
   `hourlyBlockSchema` (mirrors `HourlyRollupAccumulator.toJson()` field-for-field), and an optional
   `hourly: hourlyBlockSchema[]` on the batch envelope object variant of `batchPayloadSchema`.
4. `route.ts`: applies each `hourly` block via `bydmate_apply_client_hourly` after the samples land,
   under `headerVehicleId` (blocks carry no `vehicle_id` of their own — `HourlyRollupAccumulator.toJson()`
   omits it, and a batch is always single-vehicle since every sample is already normalized to the
   header). Best-effort: wrapped in its own promise, failure is logged and returns `0`, never fails
   the request. Reported back as `hourly_rollup_applied` in the response, kept separate from
   `sentCount`/`inserted_count` so ack accounting still counts samples only, per plan.

**Verification before the real apply:** ran the migration inside a transaction, exercised
`bydmate_apply_client_hourly` (fresh block, stale-retry rejection, newer-block acceptance) and
`bydmate_ingest_telemetry` with and without `client_hourly` against a real vehicle row, then rolled
back — all five checks matched spec exactly. Applied for real, then confirmed the fleet's live
snapshots kept updating (5 cars, all sub-minute-fresh) — the redefined ingest function didn't break
anything.

**Closed 2026-07-20 — driving hours confirmed on the block path.** See the verification summary at the
top of this document. In short: `client_hourly` is `true` on `way`'s live snapshot, the in-flight hour
lags its stored rows (rollup advances per flush, not per sample), and completed driving hours
reconcile within the expected two-writer margin.

**Why old APKs keep working.** An old payload has no `client_hourly` key, so
`coalesce(nullif(p_raw_payload->>'client_hourly', '')::boolean, false)` is false and step 2's branch
is never taken; it has no `hourly` envelope member, so step 4 never fires; and both Zod fields are
optional under `.passthrough()`. This is the same shape as Phase 2, which was verified live with
`Yuan UP` on an old APK.

**The actual risk is transcription, not the flag.** Step 2 forces a whole redefinition of the
468-line 9-arg `bydmate_ingest_telemetry` (currently in
`20260615120000_bydmate_trip_meter_baseline.sql`), and that is the function old APKs run through — a
slipped line breaks them silently. So:

- Copy it mechanically, then **diff the new body against the current one** and confirm the *only*
  changes are the two guards from step 2. Anything else in that diff is a bug.
- **Extract the hourly upsert into its own function in the same migration.** Phase 4 will otherwise
  hit this same 468-line copy again.
- Verify on the fleet as Phase 2 did: after applying, confirm an old-APK car still gets
  `bydmate_telemetry_hourly` rows with a climbing `sample_count`, and (once the new APK is installed)
  that `way`'s hourly `sample_count` tracks its block rather than its stored sample rows.

**Ordering.** Cloud-first is safe but only buys a verified-inert migration — the new path cannot be
proven until the APK is installed, which has not happened yet. Neither half breaks telemetry alone.

**Known, accepted gaps.** A sample dropped by `pruneToMaxRows` (queue overflow) or by a
non-retryable failure is still counted in the block, so the hour's `sample_count` can exceed the
rows actually stored. An app restart loses one energy interval — the same interval the server would
discard anyway, since a restart gap almost always exceeds the 180 s cap.

### Phase 4 — APK-owned trips 🟡 (APK side done 2026-07-18, cloud side next)

Mint a trip UUID at the confirmed IDLE→DRIVING transition, persist the running summary each
sample, stamp samples with `trip_id`. Close via three redundant markers: the gear→P sample, the
daemon's post-car-off heartbeat, and lazy next-boot finalization. Nothing is computed at
shutdown — close is a marker, not a computation, which is why the < 5 s Drive→P→off window is
safe. Keep the server's 5-min gap detection as a fallback until Phase 6.

**One correction found while implementing: `TripTracker` is not the trip-summary owner.** It's a
pure GPS-point collector for a *different* local concept (`TripEntity`/`TripPointEntity`, tied to
BYD's own `energydata` import via `HistoryImporter`), unrelated to the cloud `bydmate_trips` this
phase targets. `TrackingService`'s "widget session" is a third, also-unrelated boundary. So
`CloudTelemetrySender` — which already independently owns `live_only`/`client_hourly` — owns the
cloud-trip lifecycle too, keyed off its own `decide()` classification (open on the DRIVING
transition, close on gear=P or charging-start, same guards the server's `v_is_gear_p`/`v_is_charging`
use) rather than reusing `TripTracker`'s state machine.

**Also a correction to the distance/consumption source:** rather than integrating GPS or reading
BYD's own internal trip meter (which the server's current logic has to guard against resetting
mid-drive), the client captures the vehicle's real **odometer** and **lifetime consumption**
readings as baselines at trip-open — both monotonic, so `last − baseline` is exact. Average
consumption is derived as energy-over-distance at serialization rather than a running mean of the
per-sample instantaneous rate, which is more accurate than the server's current weighted-mean
approach.

Named `TripRollup*` (mirroring `HourlyRollup*`), not `TripSummary*` — `TripSummaryCloudSync.kt`
already exists for an unrelated feature (energydata-imported trip history for ADB-less cars).

#### APK side ✅ (built, tests green — on the car since 0.4.9, released as v0.5.0)

- `TripRollupEntity` (Room table `cloud_trip_rollup`, PK `tripId`) — cumulative fields mirroring
  `bydmate_trips`, same cumulative-not-delta convention as Phase 3's hourly block (retry-safe: the
  full running trip is resent every flush, guarded by `sample_count` server-side once the RPC
  exists).
- `TripRollupAccumulator` — pure, mirrors `HourlyRollupAccumulator`'s `open`/`fold`/`close`/`toJson`
  shape and reuses its `intervalEnergy()` for the regen/traction split, with its own continuity pair
  scoped to the trip so energy never carries across a trip boundary.
- `CloudTelemetrySender` — plans trip open/extend/close per sample in `decide()`/`enqueue()`
  (mirroring `accumulateHourly`); lazily hydrates the open trip id from Room on first use so a
  process restart resumes the same trip instead of forking a new one; the closing sample itself
  does **not** join the trip (matches the server's early-return close triggers, which insert no
  track point for it either).
- Wire: samples carry `"trip_id"` + `"client_trip": true` (omitted, never `false` — same convention
  as `live_only`/`client_hourly`) while a trip is open; the flush envelope gains a `"trips": [...]`
  sibling array next to `"hourly"`.
- **Single writer, unlike the hourly block**: `CommandDaemon` has no Room access and never sets
  `client_trip`, so there's no two-writer margin-not-construction concern here — only
  `CloudTelemetrySender` ever opens/extends/closes a client-tagged trip. This makes marker #2 (the
  daemon's post-car-off heartbeat) a zero-APK-code fallback: the daemon's un-tagged samples simply
  keep running through the server's existing, unmodified gear-P/charging/gap-close logic regardless
  of who opened the trip row.
- Marker #3 (lazy next-boot finalization) **is** APK-side: `TrackingService.onCreate()` calls
  `CloudTelemetrySender.finalizeStaleOpenTrip()`, which closes a trip orphaned by a process death
  mid-drive using its own last known device time (not "now") once it's gone quiet for 20 minutes. A
  restart within that window instead resumes the same trip via the lazy-hydration path above.
- Room 15 → 16 (`MIGRATION_15_16`).

#### Cloud side ✅ (applied to prod 2026-07-20)

Implemented in `EvAcChargeTimer/supabase/migrations/20260720140000_bydmate_client_trip_rollup.sql`.
Track-point ownership stayed server-side per-sample as flagged (unaffected, cheap, idempotent).

1. `bydmate_trips.client_trip` — the marker that suppresses `bydmate_finalize_trip_energy` on
   close. **This turned out to be the load-bearing part.** That function re-integrates
   regen/traction by scanning `bydmate_telemetry_samples` across the whole trip window, so for a
   client-owned trip it both wasted the scan and overwrote the client's own figures with a
   second estimate that the next cumulative block would flip straight back.
2. `bydmate_ingest_telemetry` (9-arg) gained a `v_client_trip` branch: stub the trip row, write
   the track point, skip the create/extend, weighted means and `trip_meter_baseline_km`
   arithmetic. Placed **after** the charging/gear-P early returns so the server's close triggers
   stay authoritative.
3. `bydmate_apply_client_trip` — cumulative replace guarded by
   `excluded.sample_count >= existing.sample_count`, tenant-scoped on `user_id`/`vehicle_id`.
4. `ingest-payload.ts` (`client_trip`, `trip_id`, `tripBlockSchema`, `trips[]`) and `route.ts`
   (best-effort apply after samples, reported as `trip_rollup_applied`).

**Four corrections found while implementing:**

1. **The RPC must be UPDATE-only, not an upsert.** Row creation belongs to the ingest stub.
   `bydmate_discard_trip_if_junk` *deletes* the row on close, so an upserting block arriving
   after a discard would resurrect the junk trip as a newly-open row and re-collide with
   `bydmate_trips_open_unique`. A block whose row is gone is now silently dropped — the correct
   outcome: no samples, no trip.
2. **Closing stray open trips is mandatory, not defensive.** `bydmate_trips_open_unique` is a
   **partial** index (`where ended_at is null`), so `on conflict (id) do nothing` does not absorb
   a violation from a *different* open trip — the stub insert would raise and fail the whole
   ingest with a 500.
3. **The 5-minute gap close does not apply to client-owned trips.** The client owns that
   lifecycle (gear-P/charging markers plus its 20-minute next-boot finalizer), and a server-side
   gap close would strand a still-open client trip as closed while its blocks kept arriving. The
   gap close remains the fallback for every server-owned trip, including via marker #2 — the
   daemon's untagged post-car-off samples, which still run the unmodified close path.
4. **The 9-arg function is not the entrypoint.** Both `route.ts` and
   `bydmate_ingest_telemetry_batch` call the **10-arg** overload (`p_diplus`, from
   `20260716100000`), which handles `live_only` and otherwise delegates to the 9-arg one with
   `raw_payload || {diplus}`. Only the 9-arg one needed changing — same as Phase 3 — but this is
   worth knowing before hunting for why a flag "isn't firing".

**Verification before the real apply:** ran the migration plus eight assertions inside a
transaction against prod and rolled back — stub creation, stray close, block apply, stale-block
rejection, equal-count idempotency, no junk-trip resurrection, no reopen-after-close, and an
old-APK sample still taking the original server path. All passed. Applied for real, then
confirmed five cars stayed sub-minute fresh **including two on old APKs (0.4.7, 0.4.8)** — the
redefined ingest function didn't break them.

**Still to confirm:** no drive has gone through the new path yet (`client_trip` count was 0
immediately after the apply). Check on `way` after its next drive that the trip row has
`client_trip = true`, that `sample_count` tracks the block rather than the stored samples, and
that `regen_energy_kwh`/`traction_energy_kwh` survive the close instead of being recomputed.

## Deploy notes

- **Order is safe either way.** Cloud-first = no savings until the APK ships. APK-first = the flag is
  ignored (`.passthrough()`) and the full path runs. Neither breaks telemetry.
- Prod is **self-hosted** — apply migrations with `psql -f`, not the Supabase CLI (it forces TLS; the
  Supavisor pooler has none). Connection details live in agent memory.
- **Installing the APK is enough to update the daemon**: the watchdog auto-detects APK changes and
  reloads the new dex. Do **not** manually kill `voltflow_cmd_daemon` — that also kills the watchdog
  and leaves the car with no daemon.
- Install + verify on the car **before** tagging a release.
