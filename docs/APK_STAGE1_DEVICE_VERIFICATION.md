# Stage 1 device verification

Date: 2026-08-31  
Device: BYD DiLink 3.0 head unit, Android 10, wireless ADB at `192.168.43.71:5555`

## Result

Stage 1 installed in place and resumed normal telemetry without losing the existing
Room queue. The offline/backoff, reconnect-drain, server timestamp, and reboot cases
were **not exercised on the vehicle** because the only available ADB path and the
head unit's internet path share the same Wi-Fi interface. Cutting that interface
would also remove the recovery channel. Directly rewriting the live Room database
to force a bad endpoint was rejected as unsafe on the owner's vehicle.

The unit tests remain the only evidence for persisted backoff across process death,
jitter, Retry-After, 300-row batches, and token-bucket pacing. This run found no
device/test discrepancy, but it also did not validate those behaviors under real
network loss.

## Safety inventory and rollback

Two similarly named packages are installed. The upstream BYDMate package
`com.bydmate.app` is not the package built by this repository and was not modified.
The Stage 1 target is:

| Item | Value |
| --- | --- |
| Package | `dev.scroodge.cloudevmate` |
| Installed version | `0.5.4` (`versionCode` 341) |
| Installed APK | `/data/app/dev.scroodge.cloudevmate-mUDchL0du1_uXuM8T0RHQg==/base.apk` |
| Signing certificate SHA-256 | `b4df20e6c53f063e9c0480a6cabab418172741b3c311b98821fdf6ba5c232dd6` |

Before installation, the installed APK was pulled and verified with `apksigner`.
Its SHA-256 is
`13a038af031261f3fe9c1a50f4de2b90da429e616d661fa96227078a39eefa5f`.
The Stage 1 APK has the same package, version, and signing certificate, so the
preserved APK can be restored in place with `adb install -r -d` without uninstalling
the app or clearing its data.

The first inventory pass found `com.bydmate.app` version 3.11.6/code 414. That is a
different application ID and signer; it was not used as the rollback artifact.

## Evidence collected

### Install and durable queue

`run-as dev.scroodge.cloudevmate` succeeds. The app database is
`databases/bydmate.db` with its WAL and SHM files. A pre-install snapshot showed two
unsent rows in `cloud_sync_queue`. The Stage 1 debug APK installed successfully with
`adb install -r -d`, and the app relaunched without a package-manager or Room error.
A post-install snapshot showed four unsent rows: the original queue survived and
new parked samples continued to enqueue.

### Normal synchronization after install

The foreground `TrackingService` was running after installation:

```text
ServiceRecord ... dev.scroodge.cloudevmate/com.bydmate.app.service.TrackingService
isForeground=true foregroundId=1
```

A later read-only database snapshot showed:

```text
cloud_sync_failure_count   0
cloud_sync_next_attempt_at 0
cloud_sync_last_ok         true
cloud_sync_last_ts         1788167392732
cloud_sync_last_ack        6 sent, 6 ins, 0 dup, 0 skip
```

`cloud_sync_last_ts` is 2026-08-31 12:09:52 +0300, after the Stage 1 install. This
confirms that the installed build reached the configured server and processed a
successful application ACK. Four samples captured immediately afterward remained
queued for the normal parked batching interval.

The visible status label initially retained its older 12:03:54 value even though
the database had the newer successful ACK. That is a UI refresh/staleness
observation, not evidence of a telemetry failure.

### Logcat

The sender does not log normal queue flush/backoff decisions. Logcat therefore did
not provide retry-interval or batch-size evidence. Persisted settings and queue
snapshots were used for the evidence above.

## Requested cases not completed

| Requested check | Status | Reason |
| --- | --- | --- |
| Persisted backoff across process death | Not tested | No safe way to keep ADB while isolating this foreground service from the network. |
| No tight retry loop | Not tested on device | Requires a controlled retryable failure. |
| Real jitter | Not tested on device | Requires multiple controlled failures and persisted timestamp sampling. |
| Reconnect token-bucket drain and <=300 batches | Not tested | Requires controlled disconnect/reconnect plus a sufficiently large queue. |
| Server-side `device_time` preservation | Not tested on device | No offline samples were intentionally created; therefore no backdated reconnect batch existed to verify read-only server-side. |
| Queue across head-unit reboot | Not tested | Reboot was not justified after the prerequisite offline queue could not be created safely; the vehicle's operational state was not independently confirmed. |

## Why network loss was not forced

The active network is the validated `ScroodgeCar` Wi-Fi network and carries wireless
ADB. The shell user cannot read or change iptables (`Permission denied`), and Android
data-saver blacklisting does not reliably block an active foreground service.
Airplane mode or disabling Wi-Fi would drop the recovery channel. The remaining
option—force-stopping the app, editing `cloud_sync_url` directly in the live Room
database, copying the database back, then restoring it—could corrupt or lose live
telemetry and was outside the safety threshold for a personal vehicle.

For a future complete run, provide either a second recovery channel (USB ADB), a
test vehicle/head unit, or an owner-approved network fault injection mechanism that
can block only the telemetry endpoint while preserving ADB.
