# Cloud offload plan — moving per-sample computation into the APK

Goal: cut cloud DB write work by doing arithmetic on-device and shipping pre-aggregated results,
instead of having the ingest RPC recompute it per sample. The APK stays a **gateway** — no
analytics or UI move; only arithmetic.

Status as of **2026-07-17**: phases 0–2 shipped and verified in prod. Phase 3 is half-built — the
APK side is written and unit-tested but **not yet installed on the car**, and the cloud side is not
started. Nothing is deployed, and the APK half is inert until the cloud half lands (see Deploy
notes: an unknown `client_hourly` key and an extra `hourly` envelope member are both ignored today).

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
| 3 | Client-side hourly rollups | **100 % of samples** | 🟡 APK side done; cloud side next |
| 4 | APK-owned trips | 73 % (driving) | ⬜ |
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

### Phase 3 — client-side hourly rollups 🟡 (APK side done, cloud side next)

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

#### APK side ✅ (built, tests green — not yet on the car)

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

#### Cloud side ⬜ (next — start here in a fresh session)

**The change is purely additive; the existing per-sample path is not modified.** An earlier draft of
this plan said the count-weighted merge had to be fixed first. That was true only for additive
deltas. Cumulative-replace blocks get their **own new RPC with their own merge**, so the merge that
old APKs and the daemon run through stays byte-identical. Do **not** "fix" it — leave it alone.

1. `bydmate_apply_client_hourly(p_user_id, p_vehicle_id, p_hour_start, p_block)` — new function,
   cumulative replace guarded by `excluded.sample_count >= existing.sample_count`. Compute
   `v_hour_start := date_trunc('hour', p_hour_start at time zone 'utc')` — the **same expression** as
   the per-sample path, so both land on the identical `timestamptz` (the column is `timestamptz`; the
   client sends the truncated hour as an ISO instant, e.g. `"2026-07-17T10:00:00Z"`).
2. Skip the per-sample hourly upsert **and** the `bydmate_update_hourly_energy` call when
   `p_raw_payload->>'client_hourly'` is true.
3. `ingest-payload.ts`: add `client_hourly: booleanSchema` to `payloadSchema` and an optional `hourly`
   array to the batch envelope schema.
4. `route.ts`: call the new RPC once per hour block after the samples land. It must **not** count
   blocks as samples — the ack accounting (`sentCount = items.size`) assumes samples only.

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

### Phase 4 — APK-owned trips ⬜

Mint a trip UUID at the confirmed IDLE→DRIVING transition (`TripTracker` already has 5 s
hysteresis), persist the running summary each sample, stamp samples with `trip_id`. Close via three
redundant markers: the gear→P sample, the daemon's post-car-off heartbeat, and lazy next-boot
finalization. Nothing is computed at shutdown — close is a marker, not a computation, which is why
the < 5 s Drive→P→off window is safe. Keep the server's 5-min gap detection as a fallback until
Phase 6.

## Deploy notes

- **Order is safe either way.** Cloud-first = no savings until the APK ships. APK-first = the flag is
  ignored (`.passthrough()`) and the full path runs. Neither breaks telemetry.
- Prod is **self-hosted** — apply migrations with `psql -f`, not the Supabase CLI (it forces TLS; the
  Supavisor pooler has none). Connection details live in agent memory.
- **Installing the APK is enough to update the daemon**: the watchdog auto-detects APK changes and
  reloads the new dex. Do **not** manually kill `voltflow_cmd_daemon` — that also kills the watchdog
  and leaves the car with no daemon.
- Install + verify on the car **before** tagging a release.
