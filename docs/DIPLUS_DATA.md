# di+ (vandiplus) data reference

How VoltFlow Mate reads vehicle data from **di+** on the BYD DiLink head unit, the full
documented readable-parameter catalog, the known "no data" sentinels, and a captured live
snapshot.

- **Installed on car `way`:** di+ **`2.0.0b1`** (`versionCode 158`) as of 2026-08-14.
  The parameter catalog below is unchanged between generations and applies to both.
- **Previous baseline:** `1.3.8b16` (`versionCode 143`), kept at
  `research/diplus.1.3.8-beta16.apk` for rollback.
- **What differs between the two:**
  [di+ 2.0.0-beta1 — delta vs 1.3.8-beta16](#di-200-beta1--delta-vs-138-beta16).
- **Last confirmed:** 2026-08-18 on car `way` (DiLink3.0, `persist.sys.locale=en-US`),
  during **DC fast-charging** — a full audit of the 48-field `getDiPars` template, all 141
  catalog parameters via `getVal`, the 21 read-only 2.0 endpoints, and the dex route
  surface. See [Full endpoint audit — 2026-08-18](#full-endpoint-audit--2026-08-18).
  The 2026-08-14 and 2026-06-30 snapshots further down are retained as history.

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
the first 140; `1105` (`里程值`) is newer and therefore marked **official only** — it is
registered in `2.0.0b1`, closing the gap at 141/141.
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
| 73 | 副驾安全带警告 | Passenger-seatbelt warning | 1 alarm; 2 normal — but **`0` observed live** (2026-08-18), outside the enum and rendered raw | — |
| 74 | 二排左安全带 | Second-row left seatbelt; **emits sentinel `-10011` when absent** (car `way`, 2026-08-18) | 0 unbuckled; 1 buckled; 2 invalid | — |
| 75 | 二排右安全带 | Second-row right seatbelt | 0 unbuckled; 1 buckled; 2 invalid | — |
| 76 | 二排中安全带 | Second-row centre seatbelt; **emits sentinel `-10011` when absent** (car `way`, 2026-08-18) | 0 unbuckled; 1 buckled; 2 invalid | — |
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
| 114 | SOC | **Not a usable SOC duplicate** — returned `0` on car `way` 2026-08-18 while real SOC was 34–36 %. Read `33 电量百分比` instead. | — | — |
| 115 | 转向信号 | Combined turn-signal state | 1 off; 2 left; 3 left 2; 4 right; 5 right 2; 6 hazards; 7 emergency; 8 rear flash; 9 flash | — |
| 1001 | 全景状态 | Panoramic-camera display state | 0 hidden; 1 displayed | — |
| 1002 | 配置UI版本 | Configured UI generation | 0 UI3; 1 UI4 | — |
| 1003 | 哨兵状态 | Sentry state | 0 off; 1 on | Yes |
| 1004 | 熄火录像配置开关 | Ignition-off recording configuration | 0 off; 1 recording; 2 sentry; 3 time-lapse sentry | — |
| 1005 | 位置 | Device location, `lon,lat,…` — e.g. `27.666086,53.95133,0,254` (2026-08-18); 3rd/4th fields unidentified | — | — |
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

The Apifox catalog documents these, but the **beta16 dex contains no route string for any
of them** — they first appear in `2.0.0b1`, which car `way` now runs. Reachability has not
been exercised; see the delta section below for the full route list.

## di+ 2.0.0-beta1 — delta vs 1.3.8-beta16

**Status: installed and verified on car `way`, 2026-08-14.** Static APK analysis first,
then confirmed against the running head unit with the app's own 48-field template. The
captured response is quoted below; anything still unproven says so explicitly.

### The captured response (car `way`, 2026-08-14, di+ 2.0.0b1, PWR=2, AC on)

```
{"success":true,"val":"SOC:76.1|Speed:0|Mileage:454735|Power:0|ChargeGun:1|MaxBatTemp:24
|AvgBatTemp:24|MinBatTemp:24|ChargingStatus:1|BatCapacity:2.4|TotalElecCon:7836
|Voltage12V:13.6|MaxCellV:3.322|MinCellV:3.316|ExtTemp:23|Gear:1|PowerState:2
|InsideTemp:-2000|ACStatus:1|...|Rain:-2147482648|...|Sentry:{哨兵状态}|RemoteLock:0"}
```

Three things this settles, all of which had been open:

1. **The envelope is unchanged.** `{"success":…,"val":"Key:value|…"}`, same `|` and `:`
   framing. No chunking appeared at 48 fields.
2. **SOC is fractional** — `76.1`. See below.
3. **The sentinels are unchanged.** `InsideTemp:-2000` and `Rain:-2147482648` came back
   exactly as documented for beta16, so `sanitizeTempC` / `sanitizeSentinelInt` remain
   correct and necessary. This supersedes the earlier "unverified against 2.0.0b1" note.

### Provenance

Found via di+'s own updater, not a mirror: the beta manifest
`http://jt.x2x.fun:852/Update/dibeta.txt` (URL extracted from the beta16 dex) advertises
`versionCode 158` at `http://61.164.77.66/vdip/diplus.2.0.0-beta1.apk`, changelog at
`http://jt.x2x.fun:852/Update/v200b1.txt`. Downloaded 2026-08-14 to
`research/diplus.2.0.0-beta1.apk`.

| Property | 1.3.8b16 | 2.0.0b1 |
|---|---|---|
| `versionCode` | 143 | 158 |
| Size | 19 138 182 B | 20 068 258 B |
| `sha256` | — | `bfa6d76adc787f43e7edfdf339b17192cd6027280ee7fe6a5b5e25956837cddf` |
| `package` / `minSdk` / `targetSdk` | `com.van.diplus` / 25 / 28 | unchanged |

Distribution is plain HTTP from a bare IP, and this is a **beta**. Identity rests on the
package name and the vendor's own update channel — there is no publisher signature check
beyond that.

### What changed that VoltFlow Mate touches

| Area | Change | Consequence for us |
|---|---|---|
| Parameter catalog | `1105 里程值` now registered (141/141) | Display odometer in km becomes readable without the `里程 / 10` conversion |
| `getDiPars` / `getVal` / `sendCmd` | Present and unchanged | The read path and the daemon's only actuation channel survive the upgrade |
| HTTP API surface | 10 route strings → 38 | 28 new endpoints; **nothing removed** |
| SOC | **Confirmed on car:** `SOC:76.1` | Broke the old `toIntOrNull()` parser — fixed in `versionCode 340`, see below |
| Parameter availability | **New:** gate that leaves placeholders unsubstituted | `Sentry:{哨兵状态}` observed live, see below |
| Large responses | Vendor changelog: chunked JSON transfer | **Not triggered** at 48 fields — the capture came back as one plain JSON body |
| Head-unit support | Vendor changelog: limited Android 6.0 / 7.0 support; 7.0 may lack lock commands | Widens the fleet's installable range |

### The SOC break — confirmed, then fixed

`SOC:76.1` on the wire. `toIntOrNull("76.1")` returns **`null`** — it does not truncate —
so `DiParsClient` dropped SOC entirely on every sample. This was live data loss, not a
future risk: any car already on di+ 2.0 was pushing null SOC to the cloud.

The cause is older than 2.0. SOC has **always** been a double-typed parameter in di+ —
descriptor type 2 in both `1.3.8b16` (`s$c.b`) and `2.0.0b1` (`u$c.c`), rendered through
`NumberFormat.getInstance()` exactly like `电池容量` / `总电耗` / `蓄电池电压`, which this
project already read as `Double`. 1.x merely never had sub-1 % resolution to show. 2.0's
0.1 % SOC flows through unchanged di+ code.

Two consequences worth keeping in mind:

- **`NumberFormat.getInstance()` is locale-sensitive.** A head unit whose locale uses a
  comma decimal separator emits `76,1`. Car `way` is `en-US` so this is latent there, but
  it is unverified across the rest of the fleet — and it would equally affect the cell
  voltages and `总电耗` that 1.x already returns fractionally.
- **Fixed in `versionCode 340`** by `DiParsClient.parseNum` / `parseIntNum`, which accept
  both decimal separators and reject unsubstituted placeholders. `soc` stays a rounded
  `Int?` for existing consumers; `socPrecise: Double?` carries the decimal to the cloud
  in **`telemetry.soc`** (`CloudTelemetryPayload.kt:31`). The **`diplus` object in the
  same payload still carries the rounded `Int`** — `DiParsData.toJson` / `toStatusJson`
  read `DiParsData.soc` — and the cloud flattens `diplus_soc` from that object, which is
  why the column stayed integer on `340`: measured on prod 2026-08-14,
  `telemetry->>'soc' = 66.2` next to `diplus->>'soc' = 66`. Resolved cloud-side rather
  than in the app, so cars already on `340` needed no new release — the ingest RPC now
  takes the `telemetry` value when it is within 0.5 of the di+ one (cloud repo's
  `supabase/migrations/20260814180000_diplus_soc_precise.sql`).

To re-check the wire value on any car:

```bash
adb shell curl -s 'http://127.0.0.1:8988/api/getVal?name=%E7%94%B5%E9%87%8F%E7%99%BE%E5%88%86%E6%AF%94&status=true'
```

### The availability gate — new in 2.0, and it does fire

`2.0.0b1` added a `BooleanSupplier` to each parameter descriptor. When it returns false,
the lookup yields null and the template placeholder is **left unsubstituted** — the wire
carries a literal `Sentry:{哨兵状态}` instead of a number. Confirmed on car `way`:
`getVal` on `哨兵状态` returns `{"success":false}`.

It is attached to the ~22 di+-internal IDs (1001–1018, 1103, 1104, 2001–2008), not to car
CAN signals. Two of them are consumed here: `哨兵状态` (`Sentry` in the template) and
`熄火录像配置开关` (`stallSentryMode` via `getVal` — still working, returned
`开启缩时哨兵`). `parseNum` treats any `{…}` / `[…]` value as absent, so a gated parameter
now reads as null rather than as garbage.

**The gate tracks di+ configuration, not the ID range.** Probing all 28 of those internal
IDs on 2026-08-18 fired the gate on **exactly one** — `1003 哨兵状态` — while the other 27
returned real values. `/api/getConf` on the same car reports `"EnableSentry":"false"`,
which is what closes it: the parameter is unavailable because the sentry feature is
switched off, not because it sits in the internal-ID block. Expect the gate to open and
close as the driver toggles di+ features, and do not assume any other ID is permanently
safe.

### New endpoints (2.0.0b1 only)

Grouped from the dex route strings; no first-party documentation checked against them yet.

| Group | Routes |
|---|---|
| Trips / history | `/api/trips`, `/api/currentTrip`, `/api/tripStatistics`, `/api/tripStatisticsDetail`, `/api/vehicleSegments`, `/api/historyStatus`, `/api/historyRebuild`, `/api/historyRebuildStatus` |
| Charging | `/api/chargings`, `/api/chargingSessions`, `/api/chargingSessionDetail`, `/api/batteryCapacityEstimate` |
| GPS tracks | `/api/gpsTracks`, `/api/gpsPoints`, `/api/gpsCleanup`, `/api/gpsCleanupPreview`, `/api/gpsCleanupStatus` |
| Video / camera | `/api/videoPreview`, `/api/videoDelete`, `/api/liveCameras`, `/api/screenRecord` |
| Config / diagnostics | `/api/getConf`, `/api/setConf`, `/api/getExpiry`, `/api/alarms`, `/api/runStorageTest`, `/api/storageTestResult`, `/api/dumpP2000Thread` |

**All 21 read-only routes above were probed on car `way` 2026-08-18** — every one exists
and answers (the 7 mutating routes — `setConf`, `gpsCleanup`, `videoDelete`,
`historyRebuild`, `screenRecord`, `runStorageTest`, `dumpP2000Thread` — were deliberately
not called on a live car). Routes needing an id (`tripStatisticsDetail`,
`chargingSessionDetail`, `gpsPoints`, `videoPreview`) answer `400 Missing param [...]`,
which still confirms they are registered.

`/api/currentTrip` returns **`null` unless a trip is actually in progress** — it was null
throughout the charging session. The fractional `electricNetKwh` attributed to it is
available from `/api/tripStatistics` at any time (`4.1999…` kWh on the last trip). Prefer
`tripStatistics` / `vehicleSegments` over `currentTrip` for anything that must work while
the car is parked or charging.

Tracked as **B-14** in [`BACKLOG.md`](BACKLOG.md); nothing here is consumed yet, and all
of it is 2.0-only, so any use needs a `versionCode >= 158` check. **Read
[the new-endpoint sentinels](#the-new-endpoints-carry-their-own-sentinel-family) before
consuming any of it** — the existing filters do not cover them.

`/api/trips` and `/api/chargings` appear in the dex here for the **first time** — the
Apifox catalog documented them, but beta16 shipped no such route. If they work,
`/api/chargingSessionDetail` and `/api/batteryCapacityEstimate` are the first di+-native
route to charge-session energy that does not require the on-device ADB `autoservice` path.

### Before installing di+ 2.0 on another car

- The vendor's changelog opens with: **autostart must be re-checked and re-configured after
  every update.** di+ dying silently is indistinguishable from a car that is simply off.
- The car needs VoltFlow Mate **`versionCode 340` or newer**. Older builds parse SOC with
  `toIntOrNull()` and will silently drop it — see above.
- `−2000` and `−2147482648` are confirmed unchanged in 2.0.0b1. `3095` (power) was not
  reproduced in this capture — it only appears with the car OFF while AC charging, so it
  stays **unverified** on 2.0, and `sanitizePowerKw` is kept on that basis.
- Check the head unit's locale (`adb shell getprop persist.sys.locale`). A comma decimal
  separator is handled from 340 onward, but it is worth recording which cars have one.
- Keep `research/diplus.1.3.8-beta16.apk` for rollback.

### Verified on car, 2026-08-14 (VoltFlow Mate `0.5.3` / `versionCode 340`)

> **Check the right package.** Our `applicationId` is **`dev.scroodge.cloudevmate`**, while
> the Kotlin `namespace` is `com.bydmate.app`. Car `way` *also* has upstream
> AndyShaman/BYDMate installed as **`com.bydmate.app`** (versionCode 414 / 3.11.6) plus
> `com.voltflow.dashboard` — and upstream polls di+ too, emitting its own `DiParsClient`
> logcat lines. `dumpsys package com.bydmate.app` therefore returns a plausible, wrong
> version, and its clean log reads as ours. Always target `dev.scroodge.cloudevmate`.

| Check | Result |
|---|---|
| `SOC` on the gateway screen | `76%` — populated (absent under the old parser) |
| `Салон` (cabin temp) | `--` — the `-2000` sentinel still correctly dropped |
| `Polling error` lines | **0** across 2500 log lines |
| Beacon age / data age | 40 s / 0 s; DiPlus `Падключаны` |

**Chain closed on 2026-08-14 17:24 UTC**, but it needed a cloud fix as well as `340`: car
`way` now writes `diplus_soc = 66.2` in `bydmate_telemetry_samples`, matching
`telemetry->>'soc'`. Until the ingest RPC was changed the same row read `66` — the app's
`diplus` object carries the rounded `Int`, see [The SOC break](#the-soc-break--confirmed-then-fixed)
above.

## The two SOC scales — di+ 2.0 is raw, autoservice is the dashboard

- **Last confirmed:** 2026-08-26 on car `way`, cross-checked against 4 cars' cloud history.

di+ and `autoservice getFloat(1014, FID_SOC)` report SOC on **two different scales**. They
are not two readings of one number and must never be differenced against each other.

| | di+ 2.0 `电量百分比` | autoservice `FID_SOC` |
|---|---|---|
| Scale | **raw BMS SOC** | **display SOC** — what the cluster shows |
| Resolution | 0.1 % (`socPermille: 994`) | whole percent, always (0 fractional in 1360 samples) |
| At the top of a charge | `99.4` while cells still balance | `100.0`, clamped |
| At the bottom | keeps ~2 % reserve visible | reaches 0 with reserve remaining |

Observed live on `way`, plugged in on AC, mid balance-tail — di+ `/api/historyStatus`
reported `soc: 99.4`, `socPermille: 994`, `socPrecise: true`, cells `3355`/`3329` mV, while
`service call autoservice 7 i32 1014 i32 1246777400` returned `0x42c80000` = `100.0`, stable
across three minutes of polling. The driver confirmed the cluster reads `100` in exactly
this state, which is what identifies `FID_SOC` as the display value.

Fitted against paired cloud samples (`diplus_soc` vs `autoservice_soc_percent`), the two are
related by an affine map — **per vehicle**:

| car | display = slope × raw + intercept | usable window (raw) | pairs | max residual |
|---|---|---|---|---|
| `way` | `1.0280 × raw − 2.246` | 2.18 – 99.46 % | 414 | 0.86 pp |
| `yuan up` | `1.0178 × raw − 0.519` | 0.51 – 98.77 % | 958 | 1.49 pp |

So the error changes sign with SOC. On `way`: −1.9 pp at 10 %, ≈0 near 80 %, +0.6 pp at the
top.

**The split tracks the di+ version, not the model.** Two cars whose di+ still reports whole
percents (1.x) matched `FID_SOC` **exactly** — `BYE Yuan Up` 1531/1531 samples over 24–95 %,
`BYD` 1172/1172 over 28–85 %. di+ 1.x served the display value itself; 2.0 moved to the raw
signal. A car therefore starts diverging the moment it is upgraded, which makes this a
growing problem rather than a static one.

Consequences already in the code:

- Every telemetry sample now carries **`soc_source`** (`diplus` / `autoservice`) so a
  converted fallback sample is distinguishable from a raw one — see
  [`TELEMETRY_MAP.md` §5](TELEMETRY_MAP.md#5-wire-fields--what-each-payload-actually-contains).
- `battery_capacity_kwh` is defined as kWh per 100 **raw** points. A display-scale delta
  covers less energy per point and must be divided by the slope first.
- `SocScaleCalibration` defaults to identity, which is *correct* for di+ 1.x cars and
  preserves prior behaviour on 2.0 cars. Per-car slopes are **not** hard-coded — fit one
  from `soc_source`-tagged history before setting it.

**Unverified on-car:** the conversion path itself. Under the identity default it is a
no-op, so nothing about today's numbers changes; a non-identity calibration has never run
on a car.

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
| 二排左/中安全带 (74/76) | `-10011` | rear-seatbelt signal absent on this model | **none** — `\|−10011\| < 1_000_000`, so `sanitizeSentinelInt` passes it through. Not consumed today; add a filter before consuming. |

### The new endpoints carry their own sentinel family

The 2.0 JSON endpoints do **not** reuse the three magic numbers above. Observed in
**100/100** `/api/trips` records on car `way`, 2026-08-18:

| Field | Sentinel | Meaning |
|---|---|---|
| `fuelPer_start` / `fuelPer_end` | `255` | `0xFF` — no fuel-level reading (BEV) |
| `fuelCon_*`, `power_start`, `power_end` | `104857.5` | `1048575 / 10` = `0xFFFFF` at 0.1 scale |
| `evMileage_start` / `evMileage_end` | `10485750` | `0xFFFFF × 10` |
| `unplugSoc` (chargingSessions, open session) | `-1.0` | session not yet unplugged |

**`sanitizeSentinelInt` catches none of the first three usefully:** `255` and `104857.5`
are both well under the `1_000_000` threshold and would be forwarded as real data. Only
`10485750` trips it. Any B-14 work that consumes `/api/trips`, `/api/chargings` or
`/api/vehicleSegments` needs its own filter — these are exactly the shape of value that
poisoned 25k+ power rows before.

Units are also mixed **within a single `/api/trips` record**: `mileage` is already km
(`23.7`) while `mileage_start` / `mileage_end` are raw 0.1 km (`458837` / `459074`).

Plausible extremes that must survive: Power `-102` kW (DC fast charge), `~133` kW (drive).

## Power resolution — integer in the parameter catalog, fractional via 2.0 endpoints

Verified by decompiling di+ (`com.van.diplus` base.apk, dex string table, 2026-06-30):

- `发动机功率` is the **only** power signal di+ exposes, and it's emitted as **whole kW**
  (e.g. `-4`). There is no fractional variant — the rounding happens inside di+.
- **In the parameter catalog**, di+ exposes no current signal (`电流`/`充电电流` = 0 hits)
  and no pack voltage (only `蓄电池电压` 12V aux + min/max **cell** voltage). Within
  `getVal` / `getDiPars` this is still true in 2.0.0b1 — but it is **no longer true of di+
  as a whole**; see [what 2.0 changed](#what-20-changed-pack-voltage-current-and-soh-do-exist)
  below.
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

No battery-**current** fid was found in the autoservice catalog reverse-engineered by upstream
[AndyShaman/BYDMate](https://github.com/AndyShaman/BYDMate) (`FidRegistry`, `NativeParsReader`).
The only precise electrical figure at that layer is *energy* (`FID_CHARGING_CAPACITY` float),
which yields accurate **average** charge power over time. Upstream's `nativestack` reads these
fids directly via `service call autoservice` over on-device ADB.

**`发动机功率` remains integer-kW and always will be** — that ceiling is real and applies to
every live read through `getVal` / `getDiPars`.

### What 2.0 changed: pack voltage, current and SoH *do* exist

The earlier conclusion that this was "unfixable" was drawn from the 1.3.8b16 dex on
2026-06-30 and is **superseded**. `/api/vehicleSegments` and `/api/chargingSessions` in
2.0.0b1 expose, per segment/session (car `way`, DC fast-charging, 2026-08-18):

| Field | Value | Note |
|---|---|---|
| `batteryVoltageMin` / `batteryVoltageMax` | `306.0` / `332.0` | **pack** voltage, V |
| `batteryCurrentMin` / `batteryCurrentMax` | `-198.5` / `0.0` | **pack** current, A |
| `batteryPowerMax` | `65.902` | fractional kW |
| `batteryEnergyKwh` | `11.272…` | fractional kWh, monotonically climbing |
| `batterySoh` | `99` | state of health, % |
| `estimatedUsableCapacityKwh` | `49.11` | via `/api/batteryCapacityEstimate` |

`332.0 V × 198.5 A = 65.902 kW`, exactly `batteryPowerMax` — di+ is already computing
`P = V × I` internally.

**These are per-segment aggregates, not an instantaneous reading**, so there is still no
single live float-power parameter. But the open segment (`closed: false`) updates roughly
every 3 s, which makes fractional power derivable at that cadence rather than only as a
session average. Measured directly: `batteryEnergyKwh` went `10.928 → 11.272` over 19.0 s,
i.e. **65.4 kW**, against `发动机功率` reporting `-65` at the same moment.

So the practical ceiling is now **~20 s resolution fractional power**, not "session-average
only". Consuming it is 2.0-only (`versionCode >= 158`) and is part of **B-14**.

## Full endpoint audit — 2026-08-18

Car `way`, di+ `2.0.0b1` (`versionCode 158`), `en-US`, **mid DC fast-charge**
(`ChargeGun:3`, `-65` kW, SOC 34.4 → 44.3 %). VoltFlow Mate `dev.scroodge.cloudevmate`
`0.5.3` / `340`, **0 `Polling error` lines**.

| Claim in this doc | Result |
|---|---|
| Envelope `{"success":…,"val":"K:v\|…"}`, 48 fields, no chunking | confirmed |
| Fractional SOC | confirmed — `SOC:34.4` |
| `InsideTemp:-2000`, `Rain:-2147482648` sentinels | confirmed, both live |
| Availability gate leaves `Sentry:{哨兵状态}` unsubstituted | confirmed; `getVal` → `{"success":false}` |
| Catalog is 141/141 in 2.0 | confirmed — 140 answered, only `1003` gated |
| `1105 里程值` registered, km | confirmed — `45907.4` |
| `里程` raw is 0.1 km | **proven** — `459074 / 10 = 45907.4 = 里程值` |
| Cell voltages already volts | confirmed — `3.533` |
| Route surface 10 → 38, 28 new, nothing removed | confirmed against both dex files; the 28 are **set-equal** to the table above |
| All 59 documented state-description enums | confirmed, every one matched |
| Power negative while charging, integer kW | confirmed — `-65` |

Corrected in this revision as a result: the [integer-power
ceiling](#what-20-changed-pack-voltage-current-and-soh-do-exist), the
[new-endpoint sentinels](#the-new-endpoints-carry-their-own-sentinel-family),
[`/api/currentTrip` being null off-trip](#new-endpoints-200b1-only), the
[gate's real cause](#the-availability-gate--new-in-20-and-it-does-fire), and catalog rows
`73`, `74`, `76`, `114`, `1005`.

Not probed by choice: the 7 mutating routes (`setConf`, `gpsCleanup`, `videoDelete`,
`historyRebuild`, `screenRecord`, `runStorageTest`, `dumpP2000Thread`) — not to be fired at
a car mid-charge.

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
