# APK offline telemetry buffer design

Status: proposed design, no implementation  
Date: 2026-08-28

## Decision summary

The existing offline design is fundamentally sound: capture first, persist locally, preserve
`device_time`, upload idempotently, and accept historical server inserts. The 1,000-row cap and
the daemon bypass are the defects. They should be replaced, not accommodated.

Recommended target:

1. Keep unsent telemetry for **30 days or 4 GiB of queue storage, whichever limit is reached
   first**, subject to a runtime free-space reserve.
2. Make app-owned Room the canonical queue. The shell daemon must submit to that queue through a
   single-owner IPC boundary when the app process is available and write a durable ingress spool
   when it is not. The app imports that spool transactionally before upload. Do **not** open
   `bydmate.db` directly from the shell daemon.
3. Ship persisted exponential backoff with full jitter, `Retry-After` support, 300-sample backlog
   batches, and a token-bucket drain limit **before** increasing retention.
4. When storage pressure forces eviction, compact old data into state-aware representative
   samples rather than blindly deleting the oldest or newest edge.
5. Roll out in stages: drain controls, daemon durability, schema/metrics, then progressively raise
   the byte cap through a remotely controlled cohort ramp.

## 1. Measured storage and the proposed bound

### Payload measurement

The measurement used the current production `CloudTelemetryPayload.build()` serializer and the
project's complete `DiParsData` test fixture, then counted the UTF-8 bytes of the exact JSON string
stored in `CloudSyncQueueEntity.payloadJson`. A temporary diagnostic test was run and removed; no
diagnostic source remains.

| Serialized row type | Payload JSON bytes |
| --- | ---: |
| Driving, full Di+ payload | 1,114 B |
| Charging, full Di+ payload | 1,132 B |
| Parked, full payload | 511 B |
| Parked, `live_only` | 507 B |

These are serialized payload bytes, not an estimate. SQLite adds the integer columns, record/page
headers, B-tree free space, indexes, and WAL. Storage planning below applies a conservative **25%
database overhead allowance**; that allowance is a planning margin, not a measured payload value.
Production code should measure actual DB/WAL bytes and actual `payloadBytes`, not assume 25%.

### Actual head-unit storage

On 2026-08-28, the attached production-like unit reported:

```text
product: DiLink3.0, model: DiLink3_0_For_BYD_AUTO
/data: 105,549,628 KiB total, 65,657,080 KiB available (~62.6 GiB)
bydmate.db: 4,612,096 B; WAL: 524,288 B; SHM: 32,768 B
```

Source: read-only `adb shell df -k /data` and `run-as ... ls -l databases` against the attached
head unit. This proves ample capacity on this unit only; it must not be generalized to every BYD
head unit.

Android stores internal app data on the device filesystem and exposes allocatable capacity through
`StorageManager.getAllocatableBytes(UUID_DEFAULT)`. The runtime policy must consult that API rather
than assuming the observed 62.6 GiB exists everywhere. References:

- [Android app-specific storage](https://developer.android.com/training/data-storage/app-specific)
- [Android `StorageManager`](https://developer.android.com/reference/android/os/storage/StorageManager)

### Worst-case math

Use 1,132 B/sample, the largest measured payload, and add 25% for SQLite/WAL/index overhead.
This intentionally models continuous 1 Hz active telemetry, not an optimistic daily driving mix.

| Offline case | Samples | Raw JSON | With 25% allowance |
| --- | ---: | ---: | ---: |
| 12-hour drive | 43,200 | 46.6 MiB | 58.3 MiB |
| 24-hour active worst case | 86,400 | 93.3 MiB | 116.6 MiB |
| 7 days continuously active | 604,800 | 653 MiB | 816 MiB |
| 30 days continuously active | 2,592,000 | 2.73 GiB | 3.41 GiB |

A realistic week—several driving hours per day plus 30-second parked cadence—is far smaller than
the continuous-active row, but capacity should be safe under the stated worst case.

### Recommended retention rule

The logical policy is:

```text
retain unsent rows while:
  captured_at >= now - 30 days
  AND queue_allocated_bytes <= 4 GiB
  AND device free-space reserve remains healthy
```

The operational byte ceiling is:

```text
effective_cap = min(4 GiB, max(256 MiB, allocatable_bytes - reserve_bytes))
reserve_bytes = max(5 GiB, 10% of data-filesystem capacity)
```

If the reserve is already unavailable, the minimum is not forcibly allocated; compaction begins
immediately. `StorageManager.getAllocatableBytes()` is advisory and can include reclaimable cache,
so also monitor real DB + WAL size and failed writes. Never call `allocateBytes(4 GiB)` up front:
preallocating gigabytes would consume space the queue may never need.

Why 30 days / 4 GiB:

- It covers the measured 30-day continuous 1 Hz worst case with margin on this head unit.
- Thirty days aligns with the shortest server raw-retention tier and is long enough for extended
  travel/network failures.
- The dynamic reserve makes the policy safe on smaller units.
- A smaller 512 MiB or 1 GiB cap would still fail a week of continuous driving, recreating the
  current defect in a less obvious form.

The bound applies to **unsent durable telemetry**. Acknowledged rows should be deleted promptly
after a short diagnostic grace period and must not consume the offline budget. Quarantined rows
should have a separate small byte/age budget (for example 16 MiB / 7 days) so malformed payloads
cannot evict valid telemetry.

### Required accounting

Add and populate `payloadBytes` as the UTF-8 payload length at enqueue time. Maintain queue byte
totals transactionally in a one-row metadata table rather than running `SUM()` over millions of
rows every second. Periodically reconcile the counter against SQL and actual database/WAL file
sizes. Age uses the captured `device_time` parsed to epoch milliseconds, not upload attempt time.

## 2. One durability model for app and daemon

### Why the daemon must not open `bydmate.db`

The app and daemon are not merely two Android processes of one UID:

- The APK runs under its application UID and keeps `bydmate.db` under
  `/data/user/0/dev.scroodge.cloudevmate/databases`.
- `CommandDaemon` is launched through shell/on-device ADB so that it can survive and perform
  privileged work. It runs as the shell UID, which cannot safely access the app-private database.
- Relaxing filesystem permissions on `bydmate.db`, copying it to shared/external storage, or
  running SQLite on `/sdcard` would weaken data isolation and rely on storage layers where POSIX
  locking/WAL behavior is not a safe architectural contract.

Even when processes share a UID, SQLite WAL permits concurrent readers and one writer, not
uncoordinated application-level queue ownership. Room's multi-instance invalidation coordinates
cache invalidation; it does not make two independent dequeue/upload state machines correct.
SQLite's official WAL documentation states that processes must be on the same host and describes
the single-writer/checkpoint constraints: [SQLite WAL](https://sqlite.org/wal.html). Room exposes
multi-instance invalidation, but that is not a substitute for a single queue owner:
[Room database builder](https://developer.android.com/reference/androidx/room/RoomDatabase.Builder#enableMultiInstanceInvalidation()).

**Recommendation: direct shared Room access from `CommandDaemon` is unsafe and, under the current
UID boundary, normally inaccessible. Do not implement it.**

### Proposed ownership and handoff

`TelemetryQueueRepository` in the APK remains the only owner of `bydmate.db`, enqueue/dequeue,
ACK state, byte accounting, compaction, and retry scheduling.

Daemon flow:

1. Build exactly the same versioned telemetry payload with capture-time `device_time` and a stable
   `sample_id`.
2. When the APK queue broker is reachable, send the payload over a narrow IPC endpoint. The broker
   validates size/schema/origin and commits it to Room before acknowledging the daemon.
3. If IPC is unavailable because the APK process is dead, append the payload to a **durable daemon
   ingress spool**, using bounded, checksummed segment files and atomic close/rename. This is a
   write-ahead ingress journal, not a second HTTP queue.
4. The daemon may continue its current best-effort live POST so the dashboard stays current, but
   it does not delete the durable spool record based on that POST. Historical durability is
   acknowledged only after canonical Room import (and ultimately normal server ACK).
5. On app/queue-broker startup, claim closed spool segments atomically, validate each record,
   insert them into Room idempotently by `sample_id`, then delete the imported segment.
6. The canonical sender drains both app and imported daemon rows in `device_time` order.

The spool must live in a location both UIDs can use without opening the Room DB. The exact path and
permission mechanics require an on-car spike because Android scoped-storage behavior is vendor
specific. Preferred options, in order:

1. A shell-owned spool plus a narrowly scoped broker/import command that streams records over IPC.
2. An app external-files inbox containing immutable closed segments, only if on-car tests prove
   atomic rename, ownership, reboot persistence, and no FUSE/permission surprises.

Do not use a shared SQLite database on external storage. Segment files tolerate a process dying
mid-write: only `.ready` files are imported; the current `.open` segment is CRC-validated and
truncated to its last complete frame after restart.

### IPC choice

The IPC surface should be a small Binder-bound service or ContentProvider-style `call`, with:

- explicit/exported configuration limited to the shell caller UID;
- strict maximum record size;
- versioned request/response;
- ACK only after the Room transaction commits;
- no general SQL/file access;
- idempotency via `sample_id`.

A localhost HTTP endpoint is easier but expands the attack and lifecycle surface. Use it only if
Binder invocation from the `app_process` daemon proves impractical. The spike must test app killed,
device reboot, simultaneous app wake/daemon append, corrupt final segment, and full disk.

This is a deliberate qualification of “same queue”: Room is the canonical queue and uploader;
the daemon spool is a crash-safe ingress journal needed because the two UIDs cannot literally share
Room safely. Pretending the UID boundary does not exist would be a bad design.

## 3. Retry and reconnect behavior

Increasing retention removes the current 1,000-row accidental blast-radius limit. Backoff,
jitter, and bounded drain are therefore **release blockers**, not follow-up optimizations.

### Failure backoff

Persist per-endpoint retry state so restart cannot reset a failing client into a request storm:

- Full-jitter exponential delay: random in `[0, min(15 min, 5 s × 2^failureCount)]`.
- Network-unavailable signal: wait for validated connectivity, then add 0–30 seconds of jitter.
- HTTP 429: honor `Retry-After`; otherwise use the exponential delay.
- 401/403/404: retain data but back off much more slowly after initial attempts (cap 6 hours),
  because operator action is required.
- 408/5xx/network exceptions/incomplete ACK: normal transient schedule.
- Reset the failure exponent only after a fully acknowledged batch, not merely TCP/HTTP success.
- Persist `nextAttemptAt`, `failureCount`, and last failure class in Room/settings.

Normal 1 Hz enqueue never waits for retry. Fresh `live_only` display pings may remain a separate
best-effort lane with their own tight cap, but they must not alter backlog ACK/accounting.

### Bounded backlog drain

- Use the server's supported **300 samples per batch** for historical backlog; retain smaller
  active/live batches when backlog is absent.
- Start after reconnect with one 300-sample probe batch.
- On full ACK, drain through a token bucket initially limited to **one request every 2 seconds**
  (150 samples/s), with at most one HTTP request in flight per vehicle/device.
- Apply 0–500 ms per-request jitter so a fleet does not remain phase-aligned.
- Any retryable failure closes the drain gate and returns to exponential backoff.
- Any 429 immediately obeys server pacing.
- Make the rate server-configurable through an authenticated response field, clamped to safe APK
  bounds, so operations can slow the fleet without an APK release.
- Stop backlog work when the car/head unit is resource constrained if testing shows it affects the
  1 Hz capture loop. Persistence always wins over upload throughput.

At 150 samples/s, a seven-day continuous backlog drains in about 67 minutes and a 30-day worst
case in about 4.8 hours. That is intentionally gradual. Thousands of devices leaving a tunnel at
once distribute their first request over 30 seconds and subsequent requests through independent
jitter rather than issuing synchronized batch loops.

The server should publish and monitor backlog-specific metrics before rollout: request rate,
samples/request, historical age, ACK latency, 429/5xx rate, and DB ingest saturation.

## 4. Storage-pressure compaction and eviction

### Recommendation: state-aware temporal compaction

Pure oldest-first keeps the reconnect moment but destroys the beginning of trips and parked
windows. Pure newest-first keeps history but loses transitions/reconnect state. “Every Nth row” is
better than either, but naive decimation can delete a parked→driving transition and make the server
infer a false continuous parked interval.

Use deterministic, state-aware temporal compaction when the byte or free-space limit is reached:

1. Never compact the newest 72 hours.
2. Never discard state transitions, first/last sample of a trip, charging start/stop, P/power-off,
   daemon/app handoff boundaries, or the newest sample.
3. For old driving data, bucket by time and keep first/last plus GPS corridor turns and value
   envelopes. Suggested pressure tiers: 5-second buckets, then 15-second, then 60-second only under
   emergency pressure.
4. For parked/unplugged data, preserve every state boundary and retain at least one representative
   sample every 5 minutes, plus per-bucket min/max auxiliary voltage and SOC edge samples.
5. For charging, retain start/stop/SOC transitions and cell-voltage extrema; use 10- or 30-second
   buckets outside the >=98% balance tail and finer buckets in the tail.
6. Compaction must operate by deleting surplus raw queue rows while retaining selected original
   payloads and their original `device_time`; do not manufacture upload-time summaries as ordinary
   telemetry samples.

Consumer reasoning:

- **Aux voltage daily min/max:** requires retaining voltage extrema, not an arbitrary every-Nth
  row.
- **Resting voltage:** the current server groups consecutive parked samples and qualifies readings
  after two hours. State boundaries are mandatory; otherwise decimation can falsely bridge a trip.
- **Phantom drain:** requires continuous parked intervals of at least four hours and splits on
  state/day boundaries or gaps of six hours. Five-minute parked representatives preserve duration
  well inside that six-hour gap rule, while first/last SOC preserve drain.
- **Trip summaries:** client cumulative trip blocks preserve aggregates, but beginning/end and
  state transitions must remain recoverable.
- **Trip tracks:** current GPS corridor filtering already preserves turns and a 30-second maximum
  gap. Compaction should prefer those location-bearing turn/anchor samples rather than uniform
  row numbers.

Compaction is lossy and must be observable. Mark retained rows with a compaction tier/origin and
report counts/bytes discarded per state. If even maximally compacted data cannot satisfy the free
space reserve, evict the oldest compacted buckets, never the newest/reconnect edge, and surface a
prominent diagnostic that historical data was lost.

### Better long-term option

Representative raw samples preserve compatibility, but true envelope records would preserve
min/max/count/first/last more faithfully. That requires a versioned server contract for compacted
telemetry blocks. Treat it as phase two; do not block the initial queue expansion on a new server
schema.

## 5. Room migration and rollout

### Schema migration

Use an additive Room migration (expected v16→v17; use the actual next free version at
implementation time):

- Add `sampleId TEXT` with a unique index for daemon/import idempotency.
- Add `payloadBytes INTEGER NOT NULL DEFAULT 0`.
- Add `capturedAt INTEGER` populated from existing `createdAt` for legacy rows; new rows derive it
  from validated `device_time`.
- Add `origin TEXT NOT NULL DEFAULT 'app'` (`app` / `daemon`).
- Add `compactionTier INTEGER NOT NULL DEFAULT 0`.
- Add persisted retry/queue-metadata tables for byte totals, failure count, and next attempt time.
- Add indexes needed for `(sentAt, capturedAt)`, compaction scans, and `sampleId`.

Do not rebuild or clear `cloud_sync_queue`. Existing in-flight rows retain `sentAt = NULL`, their
payload JSON and `device_time` unchanged. Backfill `payloadBytes` in bounded chunks after startup,
not one giant migration transaction on an old SQLite 3.22 head unit. Until backfill completes,
byte accounting uses actual DB size plus a conservative unknown-row allowance.

Migration SQL must remain compatible with the head unit's SQLite ~3.22. Avoid modern UPSERT syntax
and validate migration tests against that grammar constraint. After migration, ACK/quarantine
semantics remain unchanged.

### Safe rollout order

1. **Server readiness:** confirm 300-sample backdated batch capacity under load; add telemetry for
   historical age and device version; prepare 429 + `Retry-After` controls.
2. **APK A — pacing first:** ship persisted backoff, jitter, token-bucket drain, and metrics while
   retaining the 1,000-row cap. This changes no maximum backlog but removes the current retry storm.
3. **APK B — daemon durability:** ship broker/spool import behind a feature flag; prove on-car
   behavior across process kill/reboot/offline/reconnect before enabling broadly.
4. **APK C — age/byte schema:** migrate without changing the effective limit; verify DB/WAL/free
   space accounting and that every old in-flight row still uploads.
5. **Progressive retention cohorts:** raise remotely by stable device hash: 64 MiB → 256 MiB →
   1 GiB → 4 GiB, watching disk failures, WAL growth, ingest rate, ACK latency, and battery/CPU.
6. Enable 30-day retention only after several real offline/reconnect cycles and daemon imports are
   observed without data loss.

The first upgraded fleet cannot stampede merely because the schema changed: each device starts
with at most its old 1,000 rows, reconnect attempts are randomly spread, and higher retention is
enabled only after pacing is already deployed. Do not combine “remove cap” and “add backoff” in one
flag flip.

### Validation gates

- Migration preserves a seeded 1,000-row unsent FIFO byte-for-byte.
- Kill/reboot between daemon spool append, segment close, import, Room commit, send, and ACK never
  loses or duplicates a sample server-side.
- Thirty-day synthetic queue stays within the configured byte cap and free-space reserve.
- Compaction parity tests cover aux min/max, two-hour resting qualification, four-hour phantom
  drain, trip boundaries, GPS turns, and reconnect edge.
- Fleet reconnect load test demonstrates the configured request-rate ceiling and `Retry-After`.
- On-car test uses the head unit's SQLite version and measures DB/WAL growth, checkpoint latency,
  enqueue p99, poll-loop jitter, and storage reclaimed after ACK deletion/compaction.

## Documentation correction required during implementation

`BYDMate-own/docs/HOW_IT_WORKS.md` section 5.5 is stale: it says all HTTP 4xx responses are
non-retryable. Current code correctly treats 401, 403, 404, 408, and 429 as retryable, while
payload/content failures such as 400, 413, 415, and 422 are non-retryable/quarantined. Update that
section in the implementation change; it is intentionally not edited as part of this design-only
task.

## Rejected ideas

- **“Just remove the cap.”** Bad idea without byte accounting, free-space reserve, pacing, and
  compaction; it converts bounded data loss into possible disk exhaustion and reconnect overload.
- **A much larger row cap.** Still state-dependent and therefore has no stable retention meaning.
- **Both processes directly open Room.** UID access, database ownership, WAL/checkpoint, and two
  dequeue loops make this unsafe.
- **SQLite on shared/external storage.** Weakens isolation and relies on storage locking semantics
  unsuitable for a correctness-critical WAL database.
- **Naive every-Nth decimation.** Can remove transition/extrema evidence and corrupt resting or
  phantom-drain interpretation.
- **Unlimited maximum-rate reconnect drain.** Removing the old cap removes its accidental safety
  limit; an unlimited drain is operationally unacceptable for a fleet.

