# autoservice actuation — direct write fids (no di+)

Reference for writing vehicle state straight to the `autoservice` Binder, bypassing
di+ `/api/sendCmd` phrases. Read-side fids live in
[`DIPLUS_DATA.md`](DIPLUS_DATA.md) and `FidRegistry.kt`; the transact-code table is in
[`EV_PRO_APP_ANALYSIS.md`](EV_PRO_APP_ANALYSIS.md) §1. Work plan: `docs/BACKLOG.md` B-34.

- **Обновлено:** 2026-11-06 · car `way` (Leopard 3, DiLink 3.0, CANFD), shell uid over
  network ADB from a Mac, gear P, ignition on. Not yet implemented in the APK.

## Call shape

```sh
service call autoservice 6 i32 <dev> i32 <SET-fid> i32 <value>   # setInt
```

| Reply | Meaning |
|---|---|
| `Parcel(00000001)` | accepted |
| `Parcel(ffffd8e5)` (-10011) | fid not writable — happens when a **read** fid is used for a write |
| `Parcel(ffffd8e2)` (-10014) | returned by tx 10 `setIntArray` with `[X_SET, AC_CTRL_SOURCE_SET]`, which is how the vendor SDK sends AC commands. Plain tx 6 works without the ctrl-source fid. |

- **Write fids are not read fids.** Each control has a separate `*_SET` fid. Writing to the
  read fid fails with -10011, and reading a `*_SET` fid also returns -10011.
- **Where the fids come from:** the car's `/system/framework/framework.jar`, class
  `android.hardware.bydauto.BYDAutoFeatureIds`. `*_SET` fids have only two variants:
  non-CANFD Toyota, and everything else. The table below is the "everything else" branch.
  Read fids have three variants (CANFD / Toyota / other); the ones below are CANFD.
- **Ranges:** taken from the vendor wrappers (`BYDAutoAcDevice`, `BYDAutoSettingDevice`,
  `BYDAutoLightDevice`, `BYDAutoBodyworkDevice`).
- **Accepted ≠ applied.** `00000001` only says the request was accepted. Where a read fid
  exists, verify by reading it back.

## Verified on car `way`, 2026-11-06

"Read-back" means the value was read through the read fid after the write. "Seen" means the
user confirmed it on the car's screen or physically.

| Control | dev | SET fid | Values | Read fid (CANFD) | Evidence |
|---|---|---|---|---|---|
| AC power | 1000 | `501219364` | 0 off · 1 on | `1077936144` | read-back 1→0→1, seen |
| AC control mode | 1000 | `501219352` | 0 manual · 1 AUTO | `1077936146` | read-back 0→1, fan changed by itself, AUTO seen |
| Fan level | 1000 | `501219340` | 0–7 | `1077936156` | read-back 3→4, seen |
| Recirculation | 1000 | `501219355` | 0 fresh · 1 recirculate | `1077936148` | read-back 0→1→0, seen |
| Driver temp | 1000 | `501219368` | °C 17–33 | `1077936168` | read-back 24→22→24, seen |
| Driver seat heat | 1023 | `1125122068` | 1 off · 2 level 1 · 3 level 2 | `1335885835` | read-back 1→2→3→1, both levels seen |
| Passenger seat heat | 1023 | `1125122076` | 1 off · 2 level 1 · 3 level 2 | `1335885843` | read-back 1→2→3→1, level 2 seen |
| DRL | 1004 | `1125122118` | 1 on · 2 off | — (`1231040528` always reads 0) | accepted, off/on seen; **no read-back** |
| Window LF (driver) | 1001 | `1125122104` | 1 open full · 2 close · 4 half · 5 vent | `947912728` (%) | vent → 9 %, close → 0 % |
| Window RF | 1001 | `1125122107` | same | `1267728400` (%) | vent → 9 %, close → 0 % |
| Window LR | 1001 | `1125122112` | same | `947912736` (%) | vent → 11 %, close → 0 % |
| Window RR | 1001 | `1125122115` | same | `1267728408` (%) | vent → 11 %, close → 0 % |

How these map to today's di+ commands:

- `seat_heat_driver level 2` (phrase `主驾座椅加热2档`) maps to value **3**, not 2.
- Window values 1 (open full) and 4 (half) are **unverified on-car**; only 5 and 2 were
  exercised.
- There is **no "open to N %" write**. The vendor wrapper accepts only the 0–5 states above,
  so `window pct` has no direct equivalent.
- `ac_temp_step` / `fan_speed_step` have no direct equivalent either: to step, read the
  current value and write it ±1.

## Candidates — found in firmware, not yet exercised (unverified on-car)

Extracted 2026-11-06 from the car's `framework.jar`: 322 setter methods across 33
`BYDAuto*Device` classes, 689 `*_SET` fids. The table keeps the ones useful for remote
control. "Read now" is a read-only probe on `way` with ignition **off**, 2026-11-06.
`0xFFFF` there means the feature is not fitted. Values come from the vendor wrapper's
range checks and constants. Every row still needs an on-car write test.

| Group | Control | dev | SET fid | Values | Read fid | Read now |
|---|---|---|---|---|---|---|
| AC | Passenger temp | 1000 | `501219376` | °C 17–33 | `1077936176` | 24 |
| AC | Separate temp zones | 1000 | `501219346` | 0 / 1 | `1077936164` | 3 |
| AC | Front defrost | 1000 | `501219362` | 0 off · 1 on | `1077936150` | 0 |
| AC | Rear defrost | 1000 | `501219357` | 0 off · 1 on | — | — |
| AC | Airflow mode | 1000 | `501219336` | 1 face · 2 face+foot · 3 foot · 4 foot+defrost · 5 defrost · 6 face+foot+defrost · 7 face+defrost | `1077936152` | 4 |
| AC | Compressor (A/C) | 1000 | `501219344` | 0 off · 1 on | `1077936137` | 0 |
| AC | Max cooling | 1000 | `1337983004` | 0 off · 1 on | `1314914324` | 0 |
| AC | Ventilation | 1000 | `501219394` | 0 off · 1 on | `1314914314` | 0 |
| AC | Quick air clean | 1000 | `1330642960` | 0–2 | `1033895944` | 0 |
| AC | Auto air clean | 1000 | `1337982992` | 0–2 | `1301291046` | 1 |
| Settings | AC "auto air" preset | 1023 | `1311768588` | 1–3 | — | — |
| Settings | AC auto fan preset | 1023 | `1311768592` | 1–3 | — | — |
| Seats | Steering-wheel heat | 1023 | `944767029` | 1 off · 2 on | `1116733454` | 0 (unclear if fitted) |
| Body | Sunroof | 1001 | `1330642976` | 0 or 21–100 %, 252–255 special | `FidRegistry` sunroof | — |
| Body | Sunshade | 1001 | `1330642984` | 0–100 %, 254/255 special | `FidRegistry` sunshade | — |
| Body | Sunroof + sunshade stop | 1001 | both above | wrapper `setMoonRoofAndSunshadeStop()` | — | — |
| Body | Tailgate open height | 1023 | `1069547584` | 15–100 % | `1074790408` | 0 |
| Settings | Close windows on lock | 1023 | `1311768646` | 0 / 1 | `1222639648` | 0 |
| Settings | Auto lock while driving | 1023 | `1312817160` | 0 / 1 | `1081081876` | 1 |
| Settings | Remote window up / down | 1023 | `1311768602` / `1311768604` | 0 / 1 | — | — |
| Light | Smart welcome light | 1023 | `1330643013` | 0–2 | `1011875848` | 1 |
| Light | Interior light | 1023 | `1330643002` | 1 / 2 | — | — |
| Charging | Charging mode | 1009 | `668530` (literal) | 1 immediate · 2 scheduled | `1231032338` | 0 |
| Charging | Charge timer | 1009 | `1276129320`… (6 fids) | switch, type 3/4, hour, minute, weekdays | — | — |
| Charging | Charging port flap | 1023 | `1311768630` | 0 / 1 / 2 | `1196425228` | 1 |
| Charging | SOC target | 1023 | `1311768632` | 0–70 | `873463848` | 0 |
| Charging | V2V/V2L discharge | 1009 | `734003214` | 0–2 | `859832367` | 2 |
| Charging | V2V discharge limit | 1009 | `734003232` | 0–100 % | — | — |
| Charging | Wireless phone charger | 1009 | `1312817218` | 0–2 | `1105199120` | 1 |
| Drive | Energy mode | 1006 | `734003254` | 1–3 (1 EV · 2 forced EV · 3 HEV) | `874512420` | 3 |
| Drive | Operation mode | 1006 | `734003229` | 1 eco · 2 sport · 3 normal · 4 snow · 5 mud · 6 sand | `555745294` | 1 |
| Media | Volume | 1002 | `1309020176` | 0–100 | `1108344840` | 4 |
| Media | Mute | 1002 | `1309024272` | 0 / 1 | `1108344846` | 0 |
| Cluster | Backlight brightness | 1007 | `1043333144` | 1–22 | `1274019896` | 18 |

## Firmware-only discovery queue — do not expose yet

The 34 rows above are the practical first-pass candidates: their availability was at
least probed on `way`. The vendor framework exposes the additional controls below.
They are kept here so the on-car session can be exhaustive, but **none has been
read or written on `way` for this document**. A range is only the wrapper's input
validation, not proof of the meaning of every value. They are not APK commands,
not daemon allowlist entries, and not a reason to write a fid just because it is
listed here.

| Area | Firmware operation | dev | SET fid | Wrapper-accepted values | Intended scope / prerequisite |
|---|---|---:|---:|---|---|
| Windows | Close windows in rain | 1001 | `1125122102` | 0–2 | Preference only; first read `1222639659`. It was absent in the prior off-state probe. |
| Windows | Long-press-unlock opens windows | 1023 | `1311768642` | 0 / 1 | Preference; first read `1222639630`. |
| Windows | Door micro-switch window lock | 1023 | `1311768640` | 0 / 1 | Preference; first read `1222639628`. |
| Mirrors | Reverse mirror tilt mode | 1023 | `1312817180` | 0–3 | First read `1228931096`; the prior off-state probe reported this feature absent. |
| Mirrors | Left / right mirror-tilt angle | 1023 | `1069547536` / `1069547538` | 0–8 | Local settings only; no verified read-back contract yet. |
| Cabin | Door-triggered interior light | 1023 | `1330643000` | 0–2 | First read `1121976363`; semantics of 0/1/2 must be observed. |
| Cabin | Interior ambient-light colour | 1023 | `1069547528` | 0–31 | Cosmetic local setting; first read `283586`. Do not remotely expose colour IDs until their mapping is observed. |
| Cabin | Interior ambient-light brightness | 1023 | `1069547533` | 0–5 | Cosmetic local setting; first read `522414`. |
| Cabin | Interior-light duration | 1023 | `1043333154` | wrapper range not recovered | Local setting; first read `960495637`. |
| Seats | Driver-seat auto-return | 1023 | `1312817209` | 0 / 1 | First read `1228931088`; configuration only. |
| Seats | Steering-position auto-return | 1023 | `1312817211` | 0 / 1 | First read `1228931106`; configuration only. |
| Wipers | Automatic rain wiper | 1023 | `503316540` | 0–2 | First read `321912862`; no remote control, only a persistent preference. |
| Lights | Headlamp height | 1023 | `1125122084` | 0–11 | First read `794524`; local adjustment only — never permit while driving. |
| Air quality | PM2.5 system power | 1023 | `1330642964` | 0 / 1 | First read `1331691528`; the prior probe reported this feature absent. |
| Air quality | PM2.5 mode | 1023 | `1330642962` | 0–3 | The framework reads legacy `42448`; establish a valid read-back path first. |
| Air quality | PM2.5 switch/time checks | 1023 | `1330642966` / `1330642968` | 0 / 1 | First read `1331691532` / `1331691534`; feature availability unknown. |
| Air quality | Negative-ion purifier | 1008 | `1337982994` | 1 / 2 | Feature availability and value semantics unknown. |
| Tailgate | Electric-tailgate mode | 1023 | `1069547546` | 0 / 1 | First read `1264582667`; the prior probe reported this feature absent. |
| Tailgate | Voice tailgate action | 1023 | `1125122080` | 1–3 | Framework-only discovery; no command/value semantics have been observed. Do not test without a person clear of the tailgate. |
| Charging | Charge-stop switch | 1009 | `734003224` | 0–2 | First read the corresponding charge-stop fids; it was absent in the prior probe. |
| Charging | Charge-stop capacity setting | 1009 | `734003226` | 0–6 | Same prerequisite; labels for values 0–6 are unknown. |
| Charging | SOC-save switch | 1009 | `782237704` | 0–2 | First read `1186988086`; the prior probe reported this feature absent. |
| Charging | Predicted temperature range | 1009 | `782237736` / `782237720` | -80–160 / -80–160 | Charging configuration only; no on-car evidence. |
| Audio | Navigation volume / mute | 1002 | `1309081616` / `1309069328` | 0–10 / 2–3 | Head-unit-only setting, not a vehicle remote command. |
| Audio | Sound field / effect / loudness | 1002 | `1309036560` + `1309036576` / `1309044752` / `1309065232` | 0–14 each / 1–11 / 0–1 | Head-unit-only; map named presets before any UI work. |

The framework also offers rear-A/C, rear seats, massage, sunroof, sunshade, camera/DVR,
format-card, language/theme, ADAS, motor, power and test APIs. They are intentionally
outside this queue: either they need a multi-fid protocol, are absent on `way`, have no
safe user-facing semantics yet, or could affect driving/safety. They must not be promoted
to a remote-control candidate by bulk extraction.

### Deferred validation order

When the car is on and a person can observe the result, validate only one row at a time:

1. Read the availability/read fid first; `0xFFFF` means stop and mark the feature absent.
2. Record its current value, make one reversible change with the car stationary in P,
   read it back, and observe the UI or physical result.
3. Restore the recorded value before advancing. For tailgate, windows, sunroof and
   sunshade, first make sure nobody or nothing is in their path.
4. Add a command to an allowlist only after its values and a read-back/visible proof are
   recorded here. Settings, lights and audio remain local until there is a separately
   approved remote-control use case.

Window positions for the planned presets (vent, 25 %, 50 %, full open, close): only 5 and
2 are proven. Value 4 (half) and 1 (full) were sent on 2026-11-06 with ignition **off**:
accepted, but no window moved (vent did not move either), so they still need a retest
with ignition on. 25 % has no direct value. The idea is to open, poll the % read fid, then
write `WINDOW_STOP` (3) — untested.

Central door lock is **not** reachable this way. `BYDAutoSettingDevice.setDoorLock` writes
legacy fid `515647`, and `BYDAutoDoorLockDevice` has no setters.

Deliberately not listed, never to be written from a remote command: ADAS (AEB, ESP, LKS,
HDC, AVH…), `Power`, `Ota`, `Qcfs`, `Test`, `Time`, `CpuTemprature`, `Motor`, `Rescue`,
`VehicleData`, `Dtc`, `RSE`, `Panorama`, instrument navigation feeds.

## Absent on this car

These read fids return `0x0000FFFF` (feature link error) on `way`, so the control is not
fitted. Do not offer them for this model.

- Seat ventilation: driver `1335885832`, passenger `1335885840`.
- Rear seat heating: left `1214251019`.
- Warm source `1314914364`, close-windows-on-rain `1222639659`, mirror flip `1228931096`,
  PM2.5 power `1331691528`, tailgate electric mode `1264582667`, charge-stop capacity
  `1132462120` and switch `1132462118`, SOC save `1186988086`.
- Legacy literal read fids answer -10011, so these are unknown: PM2.5 switch `42448`,
  mirror auto-fold `244159`.
