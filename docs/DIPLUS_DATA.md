# di+ (vandiplus) data reference

How VoltFlow Mate reads vehicle data from **di+** on the BYD DiLink head unit, the full
signal catalog it requests, the known "no data" sentinels, and a captured live snapshot.

## Source

di+ is **not a database for live data** — it's a **local HTTP API** served on the head unit:

| Endpoint | Purpose |
|---|---|
| `http://127.0.0.1:8988/api/getDiPars?text=<template>` | Batch read. `text` is a `\|`-separated template of `Label:{中文信号名}` placeholders that di+ substitutes. |
| `http://127.0.0.1:8988/api/getVal?name=<中文信号名>&status=true` | Single signal. |
| `/storage/emulated/0/vandiplus/db/van_bm_db` (SQLite, table `TripInfo`) | **Historical trip import only** — not live telemetry. |

Code: [`DiParsClient`](../app/src/main/kotlin/com/bydmate/app/data/remote/DiParsClient.kt)
(template + parse) → `DiParsData` →
[`CloudTelemetryPayload`](../app/src/main/kotlin/com/bydmate/app/data/cloud/CloudTelemetryPayload.kt)
→ cloud `bydmate_telemetry_samples.diplus_*`.
Trip DB: [`DiPlusDbReader`](../app/src/main/kotlin/com/bydmate/app/data/remote/DiPlusDbReader.kt).

### Reading it manually over adb

```bash
adb connect 192.168.43.71:5555        # car IP, port 5555
# Chinese placeholders MUST be percent-encoded for the on-device curl:
python3 - <<'PY'
import urllib.parse, subprocess
url="http://127.0.0.1:8988/api/getDiPars?"+urllib.parse.urlencode(
    {"text":"SOC:{电量百分比}|Power:{发动机功率}|ChargingStatus:{充电状态}"})
print(subprocess.run(["adb","shell","curl","-s",url],capture_output=True,text=True).stdout)
PY
```

## Signal catalog

`Power` (`发动机功率`) is passed through with **no scaling**; `Mileage` is `/10`, cell
voltages already in volts. Negative power = charging.

| Label | di+ signal (中文) | Meaning / units | Enum values |
|---|---|---|---|
| SOC | 电量百分比 | Battery state of charge % | |
| Speed | 车速 | Speed km/h | |
| Mileage | 里程 | Odometer (raw; `/10` = km) | |
| Power | 发动机功率 | Power kW (neg = charging) | |
| ChargeGun | 充电枪插枪状态 | Charge gun | 2 = plugged |
| ChargingStatus | 充电状态 | Charging | 1 = charging |
| PowerState | 电源状态 | Power state | 0 = OFF, 1 = ON, 2 = DRIVE |
| Gear | 档位 | Gear | 1=P, 2=R, 3=N, 4=D |
| BatCapacity | 电池容量 | Live capacity kWh (rises during charge) | |
| TotalElecCon | 总电耗 | Total consumption kWh | |
| MaxBatTemp / AvgBatTemp / MinBatTemp | 最高/平均/最低电池温度 | Battery temp °C | |
| ExtTemp | 车外温度 | Outside temp °C | |
| InsideTemp | 车内温度 | Cabin temp °C | |
| Voltage12V | 蓄电池电压 | 12V aux (mV if >100, else V) | |
| MaxCellV / MinCellV | 最高/最低电池电压 | Cell voltage V (≤0.5 = unavailable) | |
| DriveMode | 整车运行模式 | Drive mode | 1=ECO, 2=SPORT |
| WorkMode | 整车工作模式 | Work mode | 0=stop, 1=EV, 2=forced EV, 3=HEV |
| ACStatus / ACTemp / FanLevel / ACCirc | 空调状态/主驾驶空调温度/风量档位/空调循环方式 | A/C | ACStatus 0=off,1=on; ACCirc 0=ext,1=int |
| Door FL/FR/RL/RR | 主驾/副驾/左后/右后车门 | Doors | 0=closed, 1=open |
| Window FL/FR/RL/RR | …车窗打开百分比 | Windows | 0–100 % |
| Sunroof / Sunshade | 天窗/遮阳帘打开百分比 | Roof | 0–100 % |
| Trunk / Hood | 后备箱门 / 引擎盖 | | 0=closed, 1=open |
| SeatbeltFL | 主驾驶安全带状态 | | 0=unbuckled, 1=buckled, 2=invalid |
| LockFL | 主驾车门锁 | | 1=unlocked, 2=locked |
| TirePress FL/FR/RL/RR | 左前/右前/左后/右后轮气压 | Tire pressure kPa | |
| AutoPark | 自动驻车 | | 0=disabled, 1=standby, 2=active |
| Rain | 雨量 | Rain sensor | |
| LightLow / DRL | 近光灯 / 日行灯 | Lights | LightLow 0=off,1=on; DRL 1=on,2=off |
| Sentry | 哨兵状态 | Sentry state | |
| RemoteLock | 远程锁车状态 | Remote lock state | |

## ⚠️ Sentinel ("no data") magic numbers

When a signal can't be read, di+ returns a magic value instead of null. These were caught
live and **must be filtered** before forwarding to the cloud (they poisoned 25k+ power and
216k+ cabin-temp rows). Filters live in `DiParsClient.parse` (`sanitizePowerKw`,
`sanitizeTempC`, `sanitizeSentinelInt`); see [`DiParsClientSanitizeTest`](../app/src/test/kotlin/com/bydmate/app/data/remote/DiParsClientSanitizeTest.kt).

| Field | Sentinel | When | Filter |
|---|---|---|---|
| Power | `3095` | engine-power PID unreadable (car OFF while AC charging) | drop if `\|kW\| > 350` |
| InsideTemp | `-2000` | cabin sensor not reporting | drop if outside −90…90 °C |
| Rain | `-2147482648` (≈ Int.MIN_VALUE) | rain sensor not reporting | drop if `\|v\| ≥ 1_000_000` |

Plausible extremes that must survive: Power `-102` kW (DC fast charge), `~133` kW (drive).

## Power resolution — why it's integer-only (no float)

Verified by decompiling di+ (`com.van.diplus` base.apk, dex string table, 2026-06-30):

- `发动机功率` is the **only** power signal di+ exposes, and it's emitted as **whole kW**
  (e.g. `-4`). There is no fractional variant — the rounding happens inside di+.
- di+ exposes **no current signal** (`电流`/`充电电流` = 0 hits) and **no pack voltage**
  (only `蓄电池电压` 12V aux + min/max **cell** voltage), so `P = V × I` cannot be computed.
- `充电功率`/`电池功率`/`电机功率` do not exist either.

### Confirmed below di+, at the raw BYD `autoservice` layer (2026-06-30)

di+ itself reads vehicle data from BYD's `autoservice` Binder (native `libdprs.so`,
`getPropertyValue`). You can hit that Binder directly over on-device ADB, bypassing di+:

```
adb shell service call autoservice <tx> i32 <dev> i32 <fid>
#   tx 5 = getInt, 7 = getFloat; result Parcel's 2nd hex word = the 32-bit value/bits
```

Live probes on car `way` proved the integer limit is in the **vehicle data itself**, not di+:

| Probe | Result | Note |
|---|---|---|
| engine power `getInt(1012, 339738656)` | `-4` | integer kW (= di+) |
| engine power `getFloat(1012, 339738656)` | `-1.0` (`0xbf800000`) | **not a float field** — sentinel |
| SOC `getFloat(1014, 1246777400)` | `42.0` | genuinely float-typed |
| **per-session energy `getFloat(1009, 666894360)`** | **`2.559 kWh`** | high-precision float ✅ |
| charge HV volt `getInt(1009, -1442840491)` | `0xffffd8e5` | dead/sentinel fid |

No battery-**current** fid exists in the autoservice catalog reverse-engineered by upstream
[AndyShaman/BYDMate](https://github.com/AndyShaman/BYDMate) (`FidRegistry`, `NativeParsReader`),
so `P = V × I` is also impossible. **Instantaneous power is integer-kW at the hardware layer —
unfixable.** The only precise electrical figure is *energy* (`FID_CHARGING_CAPACITY` float),
which yields accurate **average** charge power over time. Upstream's `nativestack` reads these
fids directly via `service call autoservice` over on-device ADB — an option to get float
energy / SoH / lifetime-kWh and drop the di+ dependency (but still no float live power).

**Therefore instantaneous live power is permanently 1 kW integer resolution.** The only way
to a decimal figure is *average* power over a charging session: `Δ(电池容量 / 总电耗) / Δt`
(both are fractional kWh). That's session-average, not live.

## Live di+ snapshot — car `way`, AC charging (captured 2026-06-30)

State: parked, car OFF (`PowerState 0`), gun plugged (`ChargeGun 2`), `ChargingStatus 1`.

| Field | Value | Field | Value |
|---|---|---|---|
| SOC | 37 % | Power | **−4 kW** (charging) |
| Speed | 0 km/h | BatCapacity | 1.8–2.8 kWh |
| TotalElecCon | 7130.9 kWh | Mileage | 407460 (→ 40746.0 km) |
| MaxBatTemp | 32 °C | AvgBatTemp | 32 °C |
| MinBatTemp | 31 °C | ExtTemp | 27 °C |
| InsideTemp | ⚠ −2000 (sentinel) | Voltage12V | 13.7 V |
| MaxCellV | 3.331 V | MinCellV | 3.325 V |
| Gear | 1 (P) | WorkMode | 3 (HEV) |
| DriveMode | 1 (ECO) | AutoPark | 0 |
| ACStatus | 0 (off) | ACTemp | 24 °C |
| Tire FL/FR | 260 / 262 kPa | Tire RL/RR | 260 / 262 kPa |
| LockFL | 1 (unlocked) | DRL | 1 (on) |
| LightLow | 0 (off) | Sentry | 0 |
| Rain | ⚠ −2147482648 (sentinel) | RemoteLock | 0 |

All 48 requested template fields returned values; the two flagged are di+ sentinels (now
filtered before cloud upload).
