# BYD EV Pro (ant0nkr/ev-pro-app) — architecture analysis

Reverse-engineering notes on a competing BYD DiLink app, done to evaluate whether VoltFlow
Mate should drop its DiPlus dependency. Same method already used on di+ itself — see
[`DIPLUS_DATA.md`](DIPLUS_DATA.md).

**Source**: public docs at [ant0nkr/ev-pro-app](https://github.com/ant0nkr/ev-pro-app)
(docs-only repo, no app source) + `jadx`/`apktool` decompilation of the published release
APK `byd-ev-pro-2.0.2-bd0e5a2.apk` (142 MB, `com.kramskyi.byd_ev_pro`), downloaded from
[GitHub Releases](https://github.com/ant0nkr/ev-pro-app/releases/tag/2.0.2), 2026-07-21.

> The app is Flutter (`libapp.so` + `libflutter.so` = AOT-compiled Dart). The Dart business
> logic itself is opaque machine code — not usefully decompilable with jadx/apktool. Everything
> below comes from the **Android-side Kotlin/Java platform bridge** (`com.kramskyi.byd_ev_pro.*`),
> which jadx decompiles cleanly and which contains 100% of the vehicle-I/O and
> process-survival logic — the Dart side is just UI + orchestration calling into it.

## Executive summary

It does **not** talk to DiPlus at all. It declares BYD's own signature-level vehicle
permissions and calls the same `autoservice` binder DiPlus itself wraps — one layer lower,
in-process, no HTTP hop. "Works in background" is not an OS trick (no persistent/system app,
no Doze exemption) — it's a **shell-uid watchdog loop that polls every 30 s and force-relaunches
anything that's been killed**, plus a from-scratch ADB client embedded in the APK so it never
depends on the external `adb` binary or Termux after the one-time pairing.

## 1. No DiPlus — direct `autoservice` access

`AndroidManifest.xml` declares BYD's private vehicle permissions directly:

```
BYDAUTO_STATISTIC_GET/SET, BYDAUTO_CHARGING_GET, BYDAUTO_GB_GET, BYDAUTO_ENGINE_GET,
BYDAUTO_GEARBOX_GET, BYDAUTO_INSTRUMENT_GET, BYDAUTO_AC_GET/SET, BYDAUTO_SETTING_GET/SET,
BYDAUTO_*_COMMON (STATISTIC, CHARGING, ENGINE, GEARBOX, INSTRUMENT, AC, SETTING, ENERGY,
SPEED, BODYWORK, LIGHT, SAFETY_BELT, AUDIO, TYRE, RADAR, PM2P5, PANORAMA, TIME, DOOR_LOCK,
MULTIMEDIA), plus WRITE_SECURE_SETTINGS.
```

`AutoserviceBridge.java` resolves the raw binder and talks to it directly:

```java
IBinder svc = (IBinder) Class.forName("android.os.ServiceManager")
    .getMethod("getService", String.class).invoke(null, "autoservice");
// AIDL interface token used for every transact():
parcel.writeInterfaceToken("android.gui.BYDAutoServer");
```

Observed transaction codes (raw `Binder.transact(code, ...)`, args are `(dev, fid[, value])` —
the same `(dev, fid)` addressing scheme VoltFlow's own `DIPLUS_DATA.md` already documented at
the autoservice layer via upstream `AndyShaman/BYDMate`'s `FidRegistry`/`NativeParsReader`):

| Code | Op | Signature |
|---|---|---|
| 5 | `getInt` | `(dev, fid) -> Int` |
| 6 | `setInt` | `(dev, fid, value) -> Int` |
| 7 | `getFloat`/`getDouble` | `(dev, fid) -> Double` (int bits observed too) |
| 9 | `getIntArray` | `(fids[]) -> Int[]` |
| 10 | `setIntArray` | `(dev, fids[], values[]) -> count` |
| 11 | `getFloatArray` | `(fids[]) -> Float[]` |
| 13 | `getBuffer` | `(dev, fid) -> byte[]` |

This confirms `android.gui.BYDAutoServer` is a real, stable AIDL interface name and that reads
*and* writes (climate, locks, windows — everything DiPlus's `迪加`-phrase actuation covers) are
reachable through the exact same binder, with no phrase-matching / NLU layer in between.

## 2. Embedded, from-scratch ADB client (no `adb` binary, no Termux)

`AdbClient.java` re-implements the ADB **host** wire protocol from scratch inside the APK:
`CNXN`/`AUTH`/`OPEN`/`OKAY`/`WRTE`/`CLSE` framing over a raw `Socket("127.0.0.1", 5555)`,
including the RSA-2048 keypair auth handshake (`adbd`'s `ADB RSA public key` signature
challenge). The keypair is generated once and persisted to app-private storage
(`adb_key.priv`/`adb_key.pub`); after the one-time "Allow USB debugging? → Always allow from
this computer" tap, it reconnects and re-authenticates silently on every launch — matching this
project's own `AdbOnDeviceClient` (`127.0.0.1:5555`, same trust-once model), except VoltFlow
Mate presumably leans on a library/simpler subset rather than a full hand-rolled protocol stack.
Once connected, it opens `shell:` streams to run arbitrary shell commands — this is the only
privilege escalation path used anywhere in the app; there is no root requirement beyond the
one-time DiLink ADB unlock the user already has to do.

## 3. A separate, persistent "vehicled" daemon — not the Flutter app itself

The Flutter app (`com.kramskyi.byd_ev_pro`, normal app uid) is **not** what stays alive. A
second process, launched over the `AdbClient` shell as a raw Dalvik executable:

```
CLASSPATH=<path> setsid app_process / --nice-name=byd_ev_pro_veh \
    com.kramskyi.byd_ev_pro.vehicled.Main
```

registers itself as an Android system service named `byd_evpro_vehicled` via
`ServiceManager.addService` (called reflectively through the bundled
`org.lsposed.hiddenapibypass` library, since `addService` is a hidden API on app-uid callers —
shell uid can call it directly). It exposes an AIDL interface, `IVehicleState`:

```
getEntry(key) -> Bundle       getStateSnapshot() -> Bundle     getSnapshot() -> Map<String,Bundle>
getVin() -> String            ping() -> String                subscribe/unsubscribe(listener)
setAction(actionId, i1,i2,i3,i4) -> Int     setActionsBatch(Bundle[]) -> Int
updateFidConfig(json: String)               shutdown()
```

`VehicledBridge.java` (the Flutter app's client for this) resolves the binder via
`ServiceManager.getService("byd_evpro_vehicled")`, caches it, and re-resolves on
`DeadObjectException`. So the actual "does this survive force-stop" answer is: **the vehicle-I/O
daemon is architecturally separate from the UI app** and exposes a clean high-level binder API,
rather than VoltFlow Mate's model of one daemon (`CommandDaemon`) doing both HTTP-to-DiPlus
telemetry/actuation *and* cloud polling in a single shell-uid process.

### Hot-swappable vehicle-compat layer

`byd_evpro_vehicled_classpath` (an `Settings.Global` key) points at a **versioned jar file**
(path pattern `.../v-<version>.jar`) that *is* the `vehicled` daemon's code — the FID tables and
per-model decode logic ship as a downloadable, separately-versioned payload, not baked into the
APK. `vehicled_loader/Loader.java` + `LoaderCrypto`/`LoaderStore` decrypt/verify it via a native
lib (`libvehicled_crypto.so`, JNI: `decryptFidConfig`/`encryptFidConfig`/`decryptBlob`) before
`ProcessExemptionController` launches it. `updateFidConfig(json)` on the AIDL interface is a
second, lighter-weight update path (push a JSON FID map without redeploying the whole jar). This
is the mechanism behind the FAQ claim *"if BYD changes a sensor ID... the app picks that up via
remote config... without you having to update the APK"* — it is a real dynamic-code-loading
pipeline, not just a values file.

## 4. The real "runs in background" mechanism: a 30 s watchdog, not an OS trick

`ProcessExemptionController.main()` is a **third** shell-uid process, also spawned over
`AdbClient`, running an infinite loop (`Thread.sleep(30_000)`) that on every tick:

- re-enables WiFi (`svc wifi enable`) if the user opted into `byd_evpro_keep_wifi` — same
  problem VoltFlow Mate currently solves by asking the user to toggle a DiLink system setting
  ("Keep network on while parked"), done here automatically from the shell loop instead;
- re-enables Bluetooth while the car is asleep (checked via `dumpsys power | grep
  mWakefulness`), same idea for `byd_evpro_keep_bluetooth`;
- **silently registers its own AccessibilityService** (`SteeringWheelKeyService`, used to
  capture physical steering-wheel button presses) by writing
  `settings put secure enabled_accessibility_services` directly — no user trip through
  Settings → Accessibility required, because the shell uid can write secure settings the app
  itself cannot;
- checks `pidof com.kramskyi.byd_ev_pro`; if empty, **force-relaunches the Flutter app**
  (`am start-foreground-service -n .../.DiLinkForegroundService`, then
  `am start -n .../.MainActivity --activity-single-top`);
- checks `pidof byd_ev_pro_veh`; if empty and a valid classpath jar exists on disk, respawns the
  `vehicled` daemon the same way it was originally launched;
- registers itself as yet another binder service, `byd_evpro_pec_control` (`IPecControl`), so
  the app can command an immediate exemption cycle instead of waiting up to 30 s.

`DiLinkForegroundService` (the actual Android `Service` in the Flutter app) is completely
ordinary by contrast: `startForeground()` with a 12 h `WakeLock`, GNSS listeners, a
connectivity-change `NetworkCallback`, a `SCREEN_ON` receiver. Nothing about it explains
background survival — that's 100% delegated to the PEC watchdog. Per the public FAQ, the app is
explicitly *not* trying to survive full car-off/park at all beyond a short grace window; that
capability is pushed to a separate hardware add-on (T-Box: OBD-port dongle, own 4G/GPS/BLE
key-fob relay) rather than solved in software.

## 5. Comparison with VoltFlow Mate today

| | VoltFlow Mate (current) | BYD EV Pro |
|---|---|---|
| Data source | DiPlus HTTP, `127.0.0.1:8988` (third-party app) | `autoservice` binder directly, in-process/relay, no third-party app |
| Actuation | DiPlus `/api/sendCmd` phrases (`CommandAllowlist`) | Same `autoservice` binder, `setInt`/`setIntArray` by `(dev, fid, value)` |
| On-device ADB | `AdbOnDeviceClient` → `127.0.0.1:5555` | Same target, but a full hand-rolled ADB protocol stack embedded in the APK |
| Background daemon | One process (`CommandDaemon`): HTTP poll + DiPlus calls + telemetry | Three cooperating processes: `vehicled` (I/O daemon, AIDL service), PEC (watchdog/respawner), app itself |
| Survival strategy | Shell-uid `app_process`, relies on user disabling DiLink self-start restriction + app-side `ensureCommandDaemonRunning()` on relaunch | Same self-start toggle *plus* an active 30 s watchdog that force-relaunches app and daemon regardless |
| Car-off / parked reachability | Same daemon keeps polling/pushing over WiFi (user must enable "keep network on while parked") | App explicitly pauses when car is off; 24/7 reachability is a separate OBD hardware dongle (T-Box), not attempted in software |
| Vehicle-model compatibility | Hardcoded `DiParsClient` template + sanitizer filters in the APK; needs an app update for new signals | Downloadable, encrypted, versioned jar (`vehicled` daemon code) + separate JSON `updateFidConfig` push — no APK update for new models/signals |
| Cloud payload security | Plain HTTPS + `X-API-Key`/`X-Vehicle-Id` headers | Claimed E2E: AES-256-GCM + HMAC-SHA256, keys device-side only, cloud sees ciphertext |

## 6. What's applicable to VoltFlow Mate

Roughly in order of payoff vs. effort:

1. **Drop DiPlus for reads (and eventually writes) by calling `autoservice` directly.** This
   project's own `DIPLUS_DATA.md` already proved this binder is reachable over on-device ADB
   (`service call autoservice`, upstream `FidRegistry`/`NativeParsReader`) and named the
   blocker (`P = V × I` impossible either way — no current/pack-voltage FIDs exist regardless of
   path). Reading `autoservice` directly removes the DiPlus HTTP hop and the risk of DiPlus's own
   sentinel/magic-value poisoning (the 25k+ bad `Power` rows, 216k+ bad `InsideTemp` rows this
   repo already had to filter) — those sentinels are almost certainly introduced by DiPlus's own
   wrapper, not by `autoservice` itself. Net effect: same permission model VoltFlow already needs
   (on-device ADB), one fewer moving part, likely cleaner numeric data.
2. **Split `CommandDaemon` into a vehicle-I/O daemon + a separate watchdog**, mirroring
   `vehicled` + PEC. Today one shell-uid process does HTTP polling, DiPlus calls, *and* its own
   respawn logic; ev-pro-app's split means the I/O daemon can crash/restart independent of the
   watchdog, and the watchdog's only job (respawn app + daemon, keep WiFi/BT alive) is trivial to
   get right and test in isolation.
3. **Active respawn loop, not just "ensure on relaunch."** `ensureCommandDaemonRunning()` today
   only runs when `TrackingService` itself starts. A standalone 30 s-tick watchdog (same shell
   process, cheap) would catch daemon deaths that happen *between* app launches — closing the gap
   this repo's own `project-notes.md`/`REMOTE_COMMAND_DAEMON.md` troubleshooting log already
   flagged (stale watchdog PID, dead process undetected until car wakes).
4. **Automate the WiFi/network keep-alive from inside the daemon**, instead of asking users to
   find "Keep network on while parked" in DiLink settings — same `svc wifi enable` /
   `Settings.Global` trick, gated by an in-app toggle.
5. **Move FID/signal tables to a server-pushed config**, so a BYD firmware change or new model
   variant doesn't need an APK release + user reinstall (this project's asymmetric-update-pain
   point is worse than ev-pro-app's, since VoltFlow Mate's users are debug-signed installs that
   can't take Play Store auto-updates either). A JSON-only version of `updateFidConfig` (skip the
   downloadable-jar part — that's real complexity for marginal gain here) gets most of the value.
6. **E2E-encrypt cloud payloads** if/when GPS or command payloads are judged sensitive enough to
   warrant it — lower priority than the above since VoltFlow's threat model (own private
   Supabase project, not a shared multi-tenant relay) is different from a commercial product
   whose relay serves unrelated customers.

None of this requires decompiling further — the binder interface name
(`android.gui.BYDAutoServer`), the `(dev, fid)` transaction codes, and the permission list above
are enough to prototype direct `autoservice` reads the same way `DIPLUS_DATA.md`'s
"raw BYD `autoservice` layer" probe section already did.
