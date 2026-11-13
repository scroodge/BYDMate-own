# On-car checklist — autoservice direct-write candidates

How-to for one session with the car on: walk every unverified write candidate, record
the result, restore the original value. Fid meanings and the already-proven controls are
in [`../AUTOSERVICE_ACTUATION.md`](../AUTOSERVICE_ACTUATION.md). Rows marked **new**
below were found 2026-11-06 in the car's `framework.jar` and are not in that reference
yet. Move each result there after the session.

- **Обновлено:** 2026-11-06 · prepared from `framework.jar` of `way` (Leopard 3, DiLink 3.0,
  CANFD). Nothing below is verified on-car.

## Before you start

1. Car on (ignition, not just accessory), gear **P**, parking brake on, a person in the car
   watching the screen and the body. Nobody near windows, sunroof or tailgate.
2. Connect over network ADB and define helpers:

   ```sh
   CAR=<car-ip>:5555; adb connect $CAR
   r() { adb -s $CAR shell service call autoservice 5 i32 $1 i32 $2; }          # getInt
   w() { adb -s $CAR shell service call autoservice 6 i32 $1 i32 $2 i32 $3; }   # setInt
   ```

   `r` → the value is the 2nd hex word of the Parcel. `w` → `00000001` accepted,
   `ffffd8e5` (-10011) not writable.
3. Per row: `r` the read fid and write the value into the **Before** column. `0000ffff`
   means not fitted: mark ✗ and skip the row. Otherwise run the writes in order, `r`
   after each one, ask the observer what they saw, then write back the **Before** value.
   Run one row at a time.

Result codes: ✅ applied and read back · 👁 applied, seen, no read-back · ⚠ accepted but
nothing happened · ✗ not fitted / rejected.

## 1. Windows — needed for the B-34 presets

dev `1001`. Values (`BYDAutoBodyworkDevice` constants): 1 open full · 2 close · 3 stop ·
4 half · 5 vent. Read fids give %.

| # | Window | SET | Read % | Steps | Expect | Before | Result |
|---|---|---|---|---|---|---|---|
| 1.1 | LF | `1125122104` | `947912728` | `w 1001 1125122104 4` → wait 5 s → `r` | about 50 % | | |
| 1.2 | LF | same | same | `w … 1` → `r` | 100 % | | |
| 1.3 | LF | same | same | `w … 2`, after ~1 s `w … 3` → `r` twice 2 s apart | stops part-way, % stays put | | |
| 1.4 | LF, 25 % preset | same | same | from 0: `w … 1`, poll `r` every 200 ms, `w … 3` at ≥ 25 | stops near 25 %; note the overshoot | | |
| 1.5 | RF / LR / RR | `1125122107` / `1125122112` / `1125122115` | `1267728400` / `947912736` / `1267728408` | repeat 1.1 and 1.3 | same as LF | | |
| 1.6 | close all | all four | all four | `w … 2` on each | 0 % | | |

## 2. Climate

dev `1000` unless noted. Turn AC on first (`w 1000 501219364 1`).

| # | Control | SET | Read | Try | Expect | Before | Result |
|---|---|---|---|---|---|---|---|
| 2.1 | Passenger temp | `501219376` | `1077936176` | Before ±2 | passenger °C on screen | | |
| 2.2 | Separate zones | `501219346` | `1077936164` | 1 then 0 | SYNC/DUAL toggles. Read was 3 with ignition off — check what 3 means | | |
| 2.3 | Front defrost | `501219362` | `1077936150` | 1 then 0 | windscreen icon, airflow to glass | | |
| 2.4 | Rear defrost | `501219357` | **new** `1128267825` | 1 then 0 | rear-glass icon | | |
| 2.5 | Airflow mode | `501219336` | `1077936152` | 1, 3, 5, then Before | face → foot → defrost | | |
| 2.6 | Compressor (A/C) | `501219344` | `1077936137` | 1 then 0 | A/C indicator | | |
| 2.7 | Max cooling | `1337983004` | `1314914324` | 1 then 0 | MAX icon, fan 7, low temp | | |
| 2.8 | Ventilation | `501219394` | `1314914314` | 1 then 0 | ventilation mode on screen | | |
| 2.9 | Quick air clean | `1330642960` | `1033895944` | 1, 2, 0 | purifier indicator | | |
| 2.10 | Auto air clean | `1337982992` | `1301291046` | 0, 2, Before | settings toggle | | |
| 2.11 | **new** "I'm cold / hot" | `1337983008` | — | 1, then 0 | temp/fan nudge; write-only; record which value is "hot" | | |
| 2.12 | **new** Remote AC run time | `1307574336` | `1128267826` | Before ±1 (range 1–5) | duration in the remote-AC setting | | |
| 2.13 | **new** AC setting `SET_AC_BT` | `1311768608` (dev 1023) | `1301291030` | flip 0/1 | a toggle in AC settings; name it | | |
| 2.14 | **new** Tunnel recirculation | `1311768586` (dev 1023) | `1301291024` | flip 0/1 | settings toggle | | |
| 2.15 | **new** Pause recirculation | `1311768594` (dev 1023) | `1301291028` | flip 0/1 | settings toggle | | |
| 2.16 | Auto-air / auto-fan preset | `1311768588` / `1311768592` (dev 1023) | — | 1, 3, then 2 | settings preset; write-only | | |
| 2.17 | **new** Rear AC (read only) | — | `1218445324` | `r` only | expect `ffff`; if fitted, add a row | | |

## 3. Seats, steering wheel

| # | Control | dev | SET | Read | Try | Expect | Before | Result |
|---|---|---|---|---|---|---|---|---|
| 3.1 | Steering-wheel heat | 1023 | `944767029` | `1116733454` | 2 then 1 | wheel-heat icon (read was 0 — maybe not fitted) | | |
| 3.2 | **new** Rear-right seat heat (read only) | 1023 | `1125122099` | `1214251027` | `r` only | rear-left was `ffff`; expect the same | | |

## 4. Body — sunroof, sunshade, tailgate

dev `1001` unless noted. Clear the path first.

| # | Control | SET | Read | Try | Expect | Before | Result |
|---|---|---|---|---|---|---|---|
| 4.1 | Sunroof position | `1330642976` | `FidRegistry` sunroof | 30, then 0 | opens to ~30 %, closes | | |
| 4.2 | Sunshade position | `1330642984` | `FidRegistry` sunshade | 50, then 0 | shade moves | | |
| 4.3 | **new** Sunroof command | `1125122056` | as 4.1 | 5, 4, 2 | `VOICE_CMD_*`: 1 open · 2 close · 3 half · 4 stop · 5 vent | | |
| 4.4 | **new** Sunshade command | `1125122060` | as 4.2 | 1, 4, 2 | range 1–4; assume the same names, confirm | | |
| 4.5 | **new** Tailgate command | `1125122080` (dev 1023) | — | 1, then 3 after 1 s, then 2 | range 1–3, no named constants: record what each value does | | |
| 4.6 | Tailgate open height | `1069547584` (dev 1023) | `1074790408` | 60, then Before | height setting changes | | |

## 5. Lights

dev `1023` unless noted.

| # | Control | SET | Read | Try | Expect | Before | Result |
|---|---|---|---|---|---|---|---|
| 5.1 | Interior light | `1330643002` | — | 1, then 2 | dome light on/off; name each | | |
| 5.2 | Smart welcome light | `1330643013` | `1011875848` | 0, 2, Before | setting changes | | |
| 5.3 | **new** Adaptive high beam | `1125122110` (dev 1004) | — | 1, then 2 | ADB setting (1 on · 2 off) | | |
| 5.4 | **new** Follow-me-home delay | `1312817170` | `1072693293` | 30, then Before (0–60 s) | value in the lights setting | | |
| 5.5 | **new** Left-home light delay | `1312817173` | `1072693296` | 30, then Before | same | | |
| 5.6 | **new** Courtesy lamp time | `1043333157` | `960495632` | Before ±1 (1–4) | setting changes | | |
| 5.7 | **new** Ambient colour | `1069547528` | `1121976336` | 1, 5, Before (0–31) | front ambient colour (1 ice blue · 5 red) | | |
| 5.8 | **new** Ambient brightness | `1069547536` | `1121976328` | 2, 6, Before | raw = level + 1 (wrapper writes `brightness+1`). CANFD fid; `1069547533` is the non-CANFD one | | |
| 5.9 | **new** Ambient area | `1069547540` | `1121976352` | 1, 3, Before | 1 front · 2 rear · 3 all | | |
| 5.10 | **new** Ambient panel | `1276153872` | `993001480` | 0, 2, Before | ambient panel state; name 0/1/2 | | |

## 6. Charging

dev `1009` unless noted. Car not plugged in.

| # | Control | SET | Read | Try | Expect | Before | Result |
|---|---|---|---|---|---|---|---|
| 6.1 | Charging port flap | `1311768630` (dev 1023) | `1196425228` | 1, 2, Before | flap opens / closes; name 0/1/2 | | |
| 6.2 | SOC target | `1311768632` (dev 1023) | `873463848` | Before ±10 (0–70) | target in charging screen; check whether it is % or an index | | |
| 6.3 | Charging mode | `668530` | `1231032338` | 2, then 1 | immediate / scheduled | | |
| 6.4 | Wireless phone charger | `1312817218` | `1105199120` | 2, then Before | pad on/off; name 0/1/2 | | |
| 6.5 | **new** Charge temperature control | `1311768644` | `1132462112` | 1, then 2 (1 on · 2 off) | battery-thermal-for-charging toggle | | |
| 6.6 | V2L discharge (read only) | `734003214` | `859832367` | `r` only | record; do not write without a load plugged in | | |

## 7. Drive and dynamics — in P only

dev `1006` unless noted. Restore right away.

| # | Control | SET | Read | Try | Expect | Before | Result |
|---|---|---|---|---|---|---|---|
| 7.1 | Operation mode | `734003229` | `555745294` | 3 normal, then Before | mode on the cluster | | |
| 7.2 | Energy mode | `734003254` | `874512420` | `r` only; write only if this car shows the setting | — | | |
| 7.3 | **new** Regen strength | `1311768613` (dev 1023) | `873463842` | 2, then 1 | 1 standard · 2 large | | |
| 7.4 | **new** Steering feel | `1311768610` (dev 1023) | `616562704` | 2, then 1 | 1 comfort · 2 sport | | |
| 7.5 | **new** One-pedal (e-pedal) | `734003240` (dev 1023) | `888143930` | 1, then 2 | 1 on · 2 off | | |
| 7.6 | **new** Road-surface mode | `977545` | `603979827` | 1, then Before (0–4) | 1 common · 2 snow · 3 mud · 4 sand | | |

## 8. Settings, media, cluster

| # | Control | dev | SET | Read | Try | Expect | Before | Result |
|---|---|---|---|---|---|---|---|---|
| 8.1 | Close windows on lock | 1023 | `1311768646` | `1222639648` | flip, restore | settings toggle | | |
| 8.2 | Auto lock while driving | 1023 | `1312817160` | `1081081876` | flip, restore | settings toggle | | |
| 8.3 | Remote window up / down | 1023 | `1311768602` / `1311768604` | — | flip, restore | settings toggles | | |
| 8.4 | **new** Remote-unlock setting (`SET_CAR_LOCK_OFF`) | 1023 | `1312817176` | `1081081878` | flip, restore | name the toggle | | |
| 8.5 | **new** Mirror follow-up | 1023 | `1312817178` | `1081081884` | flip, restore | settings toggle | | |
| 8.6 | **new** Speed reminder | 1023 | `1043333160` | `634388528` | 90, then Before (35–241) | km/h in settings | | |
| 8.7 | **new** Pedestrian sound simulator | 1012 | `1043333152` | `1223688202` | flip, restore | setting toggle | | |
| 8.8 | **new** DVR recording | 1023 | `1069547566` | `1000341536` | flip, restore | recorder icon | | |
| 8.9 | Volume | 1002 | `1309020176` | `1108344840` | Before +3 | volume bar | | |
| 8.10 | Mute | 1002 | `1309024272` | `1108344846` | 1 then 0 | mute icon | | |
| 8.11 | Cluster backlight | 1007 | `1043333144` | `1274019896` | 10, then Before (1–22) | cluster dims | | |

## 9. Camera snapshot — direct capture (0.6.5 / 348 and later)

How the path works: [`../REMOTE_COMMAND_DAEMON.md`](../REMOTE_COMMAND_DAEMON.md#camera-snapshot-camera_snapshot).
Trigger each case from the web («снимок с камер»). Then read the daemon log and the app log:

```sh
L() { adb -s $CAR shell 'grep -E "camera (direct|snapshot)" /data/local/tmp/voltflow_cmd_daemon.log | tail -4'; }
A() { adb -s $CAR shell 'logcat -d -s CameraSnapshot:* | tail -4'; }
```

The command is taken by whichever poller reaches it first. A line in `L` means the daemon
took it; a line in `A` with no `L` line means the app took it.

| # | Case | Do | Expect | Result |
|---|---|---|---|---|
| 9.1 | Car on, app alive | press snapshot | web shows a 2×2 photo 2560×1920; `source: direct`; `L` or `A` shows `camera 1 5120x960 → 2560x1920` | |
| 9.2 | Which poller took it | look at `L` and `A` from 9.1 | record daemon or app. If app: `A` shows `source=direct`, which proves the capture also works in the real `untrusted_app` context | |
| 9.3 | App path | if 9.2 was the daemon, repeat 9.1 until `A` handles one | `A`: `source=direct … → done`. If it shows `UnsatisfiedLinkError` or `SecurityException` instead, the same command must still end `source=diplus` | |
| 9.4 | Daemon only | stop the app (`am force-stop dev.scroodge.cloudevmate`), press snapshot | `L`: `camera snapshot direct: <n> B upload → done`, photo in web | |
| 9.5 | di+ untouched | right after 9.1–9.4: `ps -A -o NAME \| grep -E "vankpl\|aps_diplus"`, open di+ live view | both processes alive, di+ live view plays | |
| 9.6 | Repeat | press snapshot 3× about 10 s apart | 3 × `done`, no `open(1) failed` | |
| 9.7 | Parked, car off | lock the car, wait 5 min, press snapshot | record: `direct` photo, or `camera direct: no frame` → `via app` → `diplus` / `failed`. Any of these is a valid answer — write down which | |
| 9.8 | Fallback works | only if 9.7 or 9.3 gave no direct photo | ack `source: diplus` and a 1920×1080 photo — the old path still works | |
| 9.9 | Photo check | open the photo from 9.1 | four views, natural colours (a yellow car stays yellow); no green or purple tint | |

Restart the app after 9.4 (open it once) and confirm the heartbeat is fresh again (seconds).

## After the session

1. Check every **Before** value was restored. Re-read the whole table if unsure.
2. Move each ✅/👁 row into "Verified on car" in
   [`../AUTOSERVICE_ACTUATION.md`](../AUTOSERVICE_ACTUATION.md), and each ✗ into "Absent on
   this car".
3. Update B-34 in [`../BACKLOG.md`](../BACKLOG.md) with the window values that worked.
