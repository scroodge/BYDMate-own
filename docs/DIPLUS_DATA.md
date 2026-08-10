# di+ (vandiplus) data reference

How VoltFlow Mate reads vehicle data from **di+** on the BYD DiLink head unit, the full
documented readable-parameter catalog, the known "no data" sentinels, and a captured live
snapshot.

## Source

di+ is **not a database for live data** — it's a **local HTTP API** served on the head unit:

| Endpoint | Purpose |
|---|---|
| `http://127.0.0.1:8988/api/getDiPars?text=<template>` | Batch read. `text` is a `\|`-separated template of `Label:{中文信号名}` placeholders that di+ substitutes. |
| `http://127.0.0.1:8988/api/getVal?name=<中文信号名>&status=true` | Single signal. |
| `/storage/emulated/0/vandiplus/db/van_bm_db` (SQLite, table `TripInfo`) | **Historical trip import only** — not live telemetry. |

Current first-party reference: [di+ Apifox API](https://s.apifox.cn/c3ce5ff5-754f-438c-aef2-055d85aa0391).
The parameter catalog below is cross-checked against its
[`getVal`](https://s.apifox.cn/c3ce5ff5-754f-438c-aef2-055d85aa0391/473237176e0)
and [`getDiPars`](https://s.apifox.cn/c3ce5ff5-754f-438c-aef2-055d85aa0391/473237177e0)
pages and the supplied `research/diplus.1.3.8-beta16.apk`.

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

## Readable parameter catalog

The current official catalog has **141 parameters**. The supplied beta16 APK registers
the first 140; `1105` (`里程值`) is newer and therefore marked **official only**.
`DiParsClient` currently requests 48 entries, marked **Yes** below. Presence in this
catalog does not prove that every BYD model supplies a useful value.

For `getDiPars`, `{name}` returns the numeric/raw form and `[name]` requests the APK's
state description where one exists. Units and scaling below are stated only where the
APK, official documentation, or a live probe establishes them; otherwise they are
`unknown` rather than guessed. `发动机功率` is passed through without scaling,
raw `里程` is in 0.1 km, and the live-probed cell voltages are already volts.

| ID | di+ parameter | Meaning / established units | APK state description | Used |
|---:|---|---|---|:---:|
| 1 | 电源状态 | Vehicle power state | 0 off; 1 powered; 2 driving | Yes |
| 2 | 车速 | Vehicle speed, km/h | — | Yes |
| 3 | 里程 | Raw odometer, 0.1 km | — | Yes |
| 4 | 档位 | Gear | 1 P; 2 R; 3 N; 4 D; 5 M; 6 S | Yes |
| 5 | 发动机转速 | Engine RPM | — | — |
| 6 | 刹车深度 | Brake-pedal depth, unit unknown | — | — |
| 7 | 加速踏板深度 | Accelerator-pedal depth, unit unknown | — | — |
| 8 | 前电机转速 | Front-motor RPM | — | — |
| 9 | 后电机转速 | Rear-motor RPM | — | — |
| 10 | 发动机功率 | Vehicle power, integer kW; negative while charging | — | Yes |
| 11 | 前电机扭矩 | Front-motor torque, unit unknown | — | — |
| 12 | 充电枪插枪状态 | Charge connector state | 1 disconnected; 2 AC; 3 DC; 4 conversion; 5 discharge | Yes |
| 13 | 百公里电耗 | Consumption per 100 km, unit unknown | — | — |
| 14 | 最高电池温度 | Maximum traction-battery temperature, °C | — | Yes |
| 15 | 平均电池温度 | Average traction-battery temperature, °C | — | Yes |
| 16 | 最低电池温度 | Minimum traction-battery temperature, °C | — | Yes |
| 17 | 最高电池电压 | Maximum cell voltage, V live-probed | — | Yes |
| 18 | 最低电池电压 | Minimum cell voltage, V live-probed | — | Yes |
| 19 | 上次雨刮时间 | Last wiper time | — | — |
| 20 | 天气 | Weather inferred by di+ | 0 clear; 1 rain | — |
| 21 | 主驾驶安全带状态 | Driver seatbelt | 0 unbuckled; 1 buckled; 2 invalid | Yes |
| 22 | 远程锁车状态 | Remote-lock state | 0 unlocked; 1 locked | Yes |
| 25 | 车内温度 | Cabin temperature, °C | — | Yes |
| 26 | 车外温度 | Outside temperature, °C | — | Yes |
| 27 | 主驾驶空调温度 | Driver climate setpoint, configured unit | — | Yes |
| 28 | 温度单位 | Climate temperature unit | 0 Fahrenheit; 1 Celsius | — |
| 29 | 电池容量 | Charging-session energy counter, kWh live-probed | — | Yes |
| 30 | 方向盘转角 | Steering-wheel angle, unit unknown | — | — |
| 31 | 方向盘转速 | Steering-wheel rate, unit unknown | — | — |
| 32 | 总电耗 | Lifetime electricity consumption, kWh live-probed | — | Yes |
| 33 | 电量百分比 | Traction-battery SOC, % | — | Yes |
| 34 | 油量百分比 | Fuel level, % | — | — |
| 35 | 总燃油消耗 | Lifetime fuel consumption, unit unknown | — | — |
| 36 | 车道线曲率 | Lane-line curvature, unit unknown | — | — |
| 37 | 右侧线距离 | Right lane-line distance, unit unknown | — | — |
| 38 | 左侧线距离 | Left lane-line distance, unit unknown | — | — |
| 39 | 蓄电池电压 | 12 V auxiliary battery; mV when raw value >100 | — | Yes |
| 40 | 雷达左前 | Front-left radar distance, unit unknown | — | — |
| 41 | 雷达右前 | Front-right radar distance, unit unknown | — | — |
| 42 | 雷达左后 | Rear-left radar distance, unit unknown | — | — |
| 43 | 雷达右后 | Rear-right radar distance, unit unknown | — | — |
| 44 | 雷达左 | Left radar distance, unit unknown | — | — |
| 45 | 雷达前左中 | Front-left-centre radar distance, unit unknown | — | — |
| 46 | 雷达前右中 | Front-right-centre radar distance, unit unknown | — | — |
| 47 | 雷达中后 | Rear-centre radar distance, unit unknown | — | — |
| 48 | 前雨刮速度 | Front-wiper speed, unit unknown | — | — |
| 49 | 雨刮档位 | Wiper setting, values unknown | — | — |
| 50 | 巡航开关 | Cruise-control switch, values unknown | — | — |
| 51 | 前车距离 | Following distance, unit unknown | — | — |
| 52 | 充电状态 | Charger work state | 0 invalid; 1 Ready; 2 started; 3 completed; 4 terminated | Yes |
| 53 | 左前轮气压 | Front-left tyre pressure, kPa live-probed | — | Yes |
| 54 | 右前轮气压 | Front-right tyre pressure, kPa live-probed | — | Yes |
| 55 | 左后轮气压 | Rear-left tyre pressure, kPa live-probed | — | Yes |
| 56 | 右后轮气压 | Rear-right tyre pressure, kPa live-probed | — | Yes |
| 57 | 左转向灯 | Left indicator | 0 off; 1 on | — |
| 58 | 右转向灯 | Right indicator | 0 off; 1 on | — |
| 59 | 主驾车门锁 | Driver-door lock | 1 unlocked; 2 locked | Yes |
| 61 | 主驾车窗打开百分比 | Driver-window opening, % | — | Yes |
| 62 | 副驾车窗打开百分比 | Passenger-window opening, % | — | Yes |
| 63 | 左后车窗打开百分比 | Rear-left window opening, % | — | Yes |
| 64 | 右后车窗打开百分比 | Rear-right window opening, % | — | Yes |
| 65 | 天窗打开百分比 | Sunroof opening, % | — | Yes |
| 66 | 遮阳帘打开百分比 | Sunshade opening, % | — | Yes |
| 67 | 整车工作模式 | Vehicle work mode | 0 stopped; 1 EV; 2 forced EV; 3 HEV | Yes |
| 68 | 整车运行模式 | Vehicle drive mode | 1 ECO; 2 SPORT | Yes |
| 69 | 月 | Device month | — | — |
| 70 | 日 | Device day | — | — |
| 71 | 时 | Device hour | — | — |
| 72 | 分 | Device minute | — | — |
| 73 | 副驾安全带警告 | Passenger-seatbelt warning | 1 alarm; 2 normal | — |
| 74 | 二排左安全带 | Second-row left seatbelt | 0 unbuckled; 1 buckled; 2 invalid | — |
| 75 | 二排右安全带 | Second-row right seatbelt | 0 unbuckled; 1 buckled; 2 invalid | — |
| 76 | 二排中安全带 | Second-row centre seatbelt | 0 unbuckled; 1 buckled; 2 invalid | — |
| 77 | 空调状态 | Climate-control state | 0 off; 1 on | Yes |
| 78 | 风量档位 | Climate fan level | — | Yes |
| 79 | 空调循环方式 | Climate recirculation | 0 fresh air; 1 recirculation | Yes |
| 80 | 空调出风模式 | Climate outlet mode | 1 face; 2 face+feet; 3 feet; 4 feet+defrost; 5 defrost; 6 face+feet+defrost; 7 face+defrost | — |
| 81 | 主驾车门 | Driver door | 0 closed; 1 open | Yes |
| 82 | 副驾车门 | Passenger door | 0 closed; 1 open | Yes |
| 83 | 左后车门 | Rear-left door | 0 closed; 1 open | Yes |
| 84 | 右后车门 | Rear-right door | 0 closed; 1 open | Yes |
| 85 | 引擎盖 | Bonnet/hood | 0 closed; 1 open | Yes |
| 86 | 后备箱门 | Tailgate/trunk | 0 closed; 1 open | Yes |
| 87 | 油箱盖 | Fuel flap | 0 closed; 1 open | — |
| 88 | 自动驻车 | Auto hold | 0 disabled; 1 standby; 2 active; 3 state 3 | Yes |
| 89 | ACC巡航状态 | Adaptive-cruise state | 0 disabled; 1 cancelled/invalid; 2 standby; 3 active; 4 state 4; 5 active start | — |
| 90 | 左后接近告警 | Rear-left approach warning | 0 none; 1 vehicle approaching; 2 alarm | — |
| 91 | 右后接近告警 | Rear-right approach warning | 0 none; 1 vehicle approaching; 2 alarm | — |
| 92 | 车道保持状态 | Lane-keeping state | 0 off; 1 inactive; 2 active 1; 3 active 2; 4 error | — |
| 93 | 左后车门锁 | Rear-left door lock | 0 invalid; 1 unlocked; 2 locked | — |
| 94 | 副驾车门锁 | Passenger-door lock | 0 invalid; 1 unlocked; 2 locked | — |
| 95 | 右后车门锁 | Rear-right door lock | 0 invalid; 1 unlocked; 2 locked | — |
| 96 | 后备箱门锁 | Tailgate lock | 0 invalid; 1 unlocked; 2 locked | — |
| 97 | 左后儿童锁 | Rear-left child lock | 0 invalid; 1 unlocked; 2 locked | — |
| 98 | 右后儿童锁 | Rear-right child lock | 0 invalid; 1 unlocked; 2 locked | — |
| 99 | 小灯 | Position/sidelight | 0 off; 1 on | — |
| 100 | 近光灯 | Low beam | 0 off; 1 on | Yes |
| 101 | 远光灯 | High beam | 0 off; 1 on | — |
| 104 | 前雾灯 | Front fog light | 0 off; 1 on | — |
| 105 | 后雾灯 | Rear fog light | 0 off; 1 on | — |
| 106 | 脚照灯 | Footwell light | 0 off; 1 on | — |
| 107 | 日行灯 | Daytime running lights | 0 invalid; 1 on; 2 off; 3 undefined | Yes |
| 108 | 发动机水温 | Engine coolant temperature, unit unknown | — | — |
| 109 | 双闪 | Hazard lights | 0 invalid; 1 off; 2 on | — |
| 110 | 坡度 | Vehicle slope, unit unknown | — | — |
| 111 | 雨量 | Rain-sensor value, unit unknown | — | Yes |
| 112 | 副驾安全带 | Passenger seatbelt | 0 unbuckled; 1 buckled; 2 invalid | — |
| 113 | 秒 | Device second | — | — |
| 114 | SOC | Duplicate/alternate SOC parameter, unit unknown | — | — |
| 115 | 转向信号 | Combined turn-signal state | 1 off; 2 left; 3 left 2; 4 right; 5 right 2; 6 hazards; 7 emergency; 8 rear flash; 9 flash | — |
| 1001 | 全景状态 | Panoramic-camera display state | 0 hidden; 1 displayed | — |
| 1002 | 配置UI版本 | Configured UI generation | 0 UI3; 1 UI4 | — |
| 1003 | 哨兵状态 | Sentry state | 0 off; 1 on | Yes |
| 1004 | 熄火录像配置开关 | Ignition-off recording configuration | 0 off; 1 recording; 2 sentry; 3 time-lapse sentry | — |
| 1005 | 位置 | Device location; format/units not established here | — | — |
| 1006 | 熄火哨兵报警 | Ignition-off sentry alarm | 0 no alarm; 1 alarming | — |
| 1007 | WIFI状态 | Wi-Fi connection state | 0 disconnected; 1 connected | — |
| 1008 | 蓝牙状态 | Bluetooth connection state | 0 disconnected; 1 connected | — |
| 1009 | 蓝牙信号强度 | Bluetooth signal strength, unit unknown | — | — |
| 1010 | 晃动幅度 | Shake amplitude, unit unknown | — | — |
| 1011 | 振动幅度 | Vibration amplitude, unit unknown | — | — |
| 1012 | 蓝牙MAC | Bluetooth MAC address | — | — |
| 1013 | 蓝牙名称 | Bluetooth device name | — | — |
| 1014 | BSSID | Connected Wi-Fi BSSID | — | — |
| 1015 | SSID | Connected Wi-Fi SSID | — | — |
| 1016 | 屏幕宽度 | Screen width, px | — | — |
| 1017 | 屏幕高度 | Screen height, px | — | — |
| 1018 | 全景记录仪状态 | Panoramic recorder state | 0 stopped; 1 starting; 2 running; 3 storage error | — |
| 1101 | 无线ADB开关 | Wireless ADB switch | 0 off; 1 on | — |
| 1102 | 当前应用包名 | Foreground Android package name | — | — |
| 1103 | 媒体音量 | Media volume, scale unknown | — | — |
| 1104 | 导航音量 | Navigation volume, scale unknown | — | — |
| 1105 | 里程值 | Display odometer, km | Official only; absent from beta16 APK | — |
| 2001 | AI识别人可信度 | Person-detection confidence, scale unknown | — | — |
| 2002 | AI识别车可信度 | Vehicle-detection confidence, scale unknown | — | — |
| 2003 | 上次哨兵触发时间 | Last sentry-trigger time | — | — |
| 2004 | 上次哨兵触发画面 | Last sentry-trigger frame/image reference | — | — |
| 2005 | 上次录像文件开始时间 | Last recording start time | — | — |
| 2006 | 上次录像文件结束时间 | Last recording end time | — | — |
| 2007 | 上次录像路径 | Last recording path | — | — |
| 2008 | 前车起步状态 | Lead-vehicle-start detector | 0 stopped; 1 no target; 2 not moved; 3 alarm | — |

### Other read-only data APIs

These are separate from the 141 `getVal` / `getDiPars` parameters:

| API group | Returned data | First-party reference |
|---|---|---|
| Trips | Start/end time, distance, travel time, average speed and start/end SOC | [`/api/trips`](https://s.apifox.cn/c3ce5ff5-754f-438c-aef2-055d85aa0391/494494633e0) |
| Charging records | Time, energy/power field, start/end SOC, duration, missing-data flag and charging type | [`/api/chargings`](https://s.apifox.cn/c3ce5ff5-754f-438c-aef2-055d85aa0391/494494634e0) |
| Alarm records | Time, alarm type, alarm payload and read state | [`/api/alarms`](https://s.apifox.cn/c3ce5ff5-754f-438c-aef2-055d85aa0391/494494635e0) |
| Video and cameras | Video directories/files/streams and live-camera access | [API index](https://s.apifox.cn/c3ce5ff5-754f-438c-aef2-055d85aa0391) |
| Configuration and automation | di+ configuration and automation-rule reads | [API index](https://s.apifox.cn/c3ce5ff5-754f-438c-aef2-055d85aa0391) |

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
