# Direct-engine telemetry field matrix

## Purpose and scope

This is the source-of-truth gap list for the direct-engine branch.  It maps
every field requested by di+'s `getDiPars` template to the **currently known**
BYD `autoservice` read path.  It is deliberately a capability matrix, not a
live-car report: no Binder/ADB request was made to prepare this note.

The di+ side of the table is exactly the template in
[`DiParsClient.kt:77-99`](../app/src/main/kotlin/com/bydmate/app/data/remote/DiParsClient.kt#L77-L99)
and its parsed fields in
[`DiParsClient.kt:206-257`](../app/src/main/kotlin/com/bydmate/app/data/remote/DiParsClient.kt#L206-L257).
Direct availability is limited to the registry and readers in
[`FidRegistry.kt:15-118`](../app/src/main/kotlin/com/bydmate/app/data/autoservice/FidRegistry.kt#L15-L118),
[`AutoserviceClient.kt:14-29`](../app/src/main/kotlin/com/bydmate/app/data/autoservice/AutoserviceClient.kt#L14-L29),
and the daemon's actual snapshot reads in
[`CommandDaemon.kt:879-898`](../app/src/main/kotlin/com/bydmate/app/daemon/CommandDaemon.kt#L879-L898).

### Status terms

| Status | Meaning |
|---|---|
| **Validated on `way`** | The allowed sources contain an explicit direct result or parity statement for car `way`. |
| **Validated on cited vehicle** | The registry documents a catalog/live validation, but the cited source does not establish parity on `way`. |
| **Known FID; not wired** | A registry FID exists but the current daemon snapshot and the public APK reader do not expose it. |
| **Related, not equivalent** | Direct data exists, but its documented meaning is different and must not silently replace the di+ field. |
| **Unsupported / unknown** | No direct FID or reader appears in the permitted primary sources. It must remain `null`, not be inferred from di+. |

## Template field-by-field mapping

| di+ label / field | di+ meaning | Own-engine read path | Current branch use | Validation / decision |
|---|---|---|---|---|
| `SOC` / `soc` | State of charge, % | `getFloat(1014, 1246777400)` (`FID_SOC`); `readBatterySnapshot().socPercent` | APK reader and daemon | **Validated on `way`**: direct `42.0` is documented; daemon also records the direct-vs-stale-di+ field evidence. [`DIPLUS_DATA.md:108-115`](DIPLUS_DATA.md#L108-L115), [`CommandDaemon.kt:775-792`](../app/src/main/kotlin/com/bydmate/app/daemon/CommandDaemon.kt#L775-L792) |
| `Speed` / `speed` | Vehicle speed, km/h | — | Explicit `null` in direct safety carrier | **Unsupported / unknown.** Do not derive it from power or GPS for vehicle telemetry. [`CommandDaemon.kt:900-908`](../app/src/main/kotlin/com/bydmate/app/daemon/CommandDaemon.kt#L900-L908) |
| `Mileage` / `mileage` | Odometer, raw `/10` = km | `getInt(1014, 1246765072) / 10` (`FID_LIFETIME_MILEAGE`); `readBatterySnapshot().lifetimeMileageKm` | APK reader exposes it; daemon currently leaves odometer `null` | **Validated on cited vehicle** as a registry field, but the sources do not establish that it is the same counter as di+ `里程` on `way`. Parity-test before exposing it. [`FidRegistry.kt:31-35`](../app/src/main/kotlin/com/bydmate/app/data/autoservice/FidRegistry.kt#L31-L35), [`AutoserviceClient.kt:82-91`](../app/src/main/kotlin/com/bydmate/app/data/autoservice/AutoserviceClient.kt#L82-L91), [`CommandDaemon.kt:905-910`](../app/src/main/kotlin/com/bydmate/app/daemon/CommandDaemon.kt#L905-L910) |
| `Power` / `power` | Engine/battery power, kW | `getInt(1012, 339738656)` (`FID_ENGINE_POWER`) | APK reader and daemon | **Validated on `way`**: direct `-4` equals di+'s integer value; no float power field exists. [`DIPLUS_DATA.md:108-126`](DIPLUS_DATA.md#L108-L126), [`CommandDaemon.kt:795-810`](../app/src/main/kotlin/com/bydmate/app/daemon/CommandDaemon.kt#L795-L810) |
| `ChargeGun` / `chargeGunState` | Plug state | `getInt(1009, 876609586)` (`FID_GUN_CONNECT_STATE`) | APK `ChargingReading`; daemon | **Validated on cited vehicle** during live AC charging; it is the direct source of charge state. Verify value mapping on `way` during parity capture. [`FidRegistry.kt:37-56`](../app/src/main/kotlin/com/bydmate/app/data/autoservice/FidRegistry.kt#L37-L56), [`AutoserviceClient.kt:99-109`](../app/src/main/kotlin/com/bydmate/app/data/autoservice/AutoserviceClient.kt#L99-L109) |
| `MaxBatTemp` / `maxBatTemp` | Maximum battery temperature, °C | — | `null` | **Unsupported / unknown.** |
| `AvgBatTemp` / `avgBatTemp` | Average battery temperature, °C | — | `null` | **Unsupported / unknown.** |
| `MinBatTemp` / `minBatTemp` | Minimum battery temperature, °C | — | `null` | **Unsupported / unknown.** |
| `ChargingStatus` / `chargingStatus` | di+ charging status | `getInt(1009, 876609560)` (`FID_CHARGING_BMS_STATE`) through `ChargingReading.bmsState` | APK reader; daemon exposes raw `autoservice.charging_bms_state` for diagnostics only | **Not equivalent yet.** On `way` while parked/unplugged, direct BMS state was `15` while di+ reported `ChargingStatus=1`; therefore no mapping is allowed. Charging still uses the direct gun state. [`FidRegistry.kt:51-56`](../app/src/main/kotlin/com/bydmate/app/data/autoservice/FidRegistry.kt#L51-L56), [`AutoserviceClient.kt:99-109`](../app/src/main/kotlin/com/bydmate/app/data/autoservice/AutoserviceClient.kt#L99-L109), [`CommandDaemon.kt`](../app/src/main/kotlin/com/bydmate/app/daemon/CommandDaemon.kt) |
| `BatCapacity` / `batteryCapacityKwh` | di+ live capacity, kWh | `getFloat(1009, 666894360)` (`FID_CHARGING_CAPACITY`) | Kept as direct diagnostic `kwhCharged`; not mapped to `BatCapacity` | **Related, not equivalent.** Direct field is documented as per-session BMS charged energy, whereas di+ calls its field live capacity. Do not substitute it. [`FidRegistry.kt:51-53`](../app/src/main/kotlin/com/bydmate/app/data/autoservice/FidRegistry.kt#L51-L53), [`DIPLUS_DATA.md:37-38`](DIPLUS_DATA.md#L37-L38), [`CommandDaemon.kt:763-773`](../app/src/main/kotlin/com/bydmate/app/daemon/CommandDaemon.kt#L763-L773) |
| `TotalElecCon` / `totalElecConsumption` | Total electric consumption, kWh | `FID_LIFETIME_KWH` exists | APK reader exposes `lifetimeKwh`; daemon does not map it | **Related, not equivalent.** Registry calls it lifetime energy throughput, not consumption. Needs metric-definition/parity decision. [`FidRegistry.kt:25-35`](../app/src/main/kotlin/com/bydmate/app/data/autoservice/FidRegistry.kt#L25-L35), [`AutoserviceClient.kt:82-91`](../app/src/main/kotlin/com/bydmate/app/data/autoservice/AutoserviceClient.kt#L82-L91) |
| `Voltage12V` / `voltage12v` | 12-V auxiliary voltage | `getFloat(1001, 1128267816)` (`FID_OTA_BATTERY_POWER_VOLTAGE`) | APK reader and daemon | **Validated on cited vehicle** in registry. Direct float is the intended replacement; obtain `way` parity value before marking fully validated. [`FidRegistry.kt:58-60`](../app/src/main/kotlin/com/bydmate/app/data/autoservice/FidRegistry.kt#L58-L60), [`CommandDaemon.kt:879-885`](../app/src/main/kotlin/com/bydmate/app/daemon/CommandDaemon.kt#L879-L885) |
| `MaxCellV` / `maxCellVoltage` | Maximum cell voltage, V | — | `null` | **Unsupported / unknown.** |
| `MinCellV` / `minCellVoltage` | Minimum cell voltage, V | — | `null` | **Unsupported / unknown.** |
| `ExtTemp` / `exteriorTemp` | Exterior temperature, °C | — | `null` | **Unsupported / unknown.** |
| `Gear` / `gear` | P/R/N/D | — | Explicit `null`; command guard fails closed | **Unsupported / unknown.** Do not infer park/drive. [`CommandDaemon.kt:900-903`](../app/src/main/kotlin/com/bydmate/app/daemon/CommandDaemon.kt#L900-L903), [`CommandDaemon.kt:920-923`](../app/src/main/kotlin/com/bydmate/app/daemon/CommandDaemon.kt#L920-L923) |
| `PowerState` / `powerState` | Off/on/drive | — | `null` | **Unsupported / unknown.** |
| `InsideTemp` / `insideTemp` | Cabin temperature, °C | — | `null` | **Unsupported / unknown.** |
| `ACStatus` / `acStatus` | A/C on/off | — | `null` | **Unsupported / unknown.** |
| `ACTemp` / `acTemp` | Driver A/C set temperature | — | `null` | **Unsupported / unknown.** |
| `FanLevel` / `fanLevel` | Fan level | — | `null` | **Unsupported / unknown.** |
| `ACCirc` / `acCirc` | Air recirculation mode | — | `null` | **Unsupported / unknown.** |
| `DoorFL` / `doorFL` | Front-left door | `getInt(1001, 692060168)` (`FID_DOOR_FL`) | Daemon | **Validated on cited CANFD Leopard 3**, not yet `way`. The FID is platform-conditional; first check protocol and parity. [`FidRegistry.kt:72-92`](../app/src/main/kotlin/com/bydmate/app/data/autoservice/FidRegistry.kt#L72-L92), [`CommandDaemon.kt:879-889`](../app/src/main/kotlin/com/bydmate/app/daemon/CommandDaemon.kt#L879-L889) |
| `DoorFR` / `doorFR` | Front-right door | `getInt(1001, 692060170)` (`FID_DOOR_FR`) | Daemon | **Validated on cited CANFD Leopard 3**; platform/`way` parity required. [`FidRegistry.kt:79-92`](../app/src/main/kotlin/com/bydmate/app/data/autoservice/FidRegistry.kt#L79-L92) |
| `DoorRL` / `doorRL` | Rear-left door | `getInt(1001, 692060172)` (`FID_DOOR_RL`) | Daemon | **Validated on cited CANFD Leopard 3**; platform/`way` parity required. [`FidRegistry.kt:79-92`](../app/src/main/kotlin/com/bydmate/app/data/autoservice/FidRegistry.kt#L79-L92) |
| `DoorRR` / `doorRR` | Rear-right door | `getInt(1001, 692060174)` (`FID_DOOR_RR`) | Daemon | **Validated on cited CANFD Leopard 3**; platform/`way` parity required. [`FidRegistry.kt:79-92`](../app/src/main/kotlin/com/bydmate/app/data/autoservice/FidRegistry.kt#L79-L92) |
| `WindowFL` / `windowFL` | Front-left window % | `getInt(1001, 947912728)` (`FID_WINDOW_FL`) | Not wired | **Known FID; not wired.** Registry says this group is CANFD-platform conditional and its cited validation is Leopard 3, not `way`. [`FidRegistry.kt:72-84`](../app/src/main/kotlin/com/bydmate/app/data/autoservice/FidRegistry.kt#L72-L84), [`FidRegistry.kt:97-104`](../app/src/main/kotlin/com/bydmate/app/data/autoservice/FidRegistry.kt#L97-L104) |
| `WindowFR` / `windowFR` | Front-right window % | `getInt(1001, 1267728400)` (`FID_WINDOW_FR`) | Not wired | **Known FID; not wired.** CANFD/`way` parity required. [`FidRegistry.kt:97-104`](../app/src/main/kotlin/com/bydmate/app/data/autoservice/FidRegistry.kt#L97-L104) |
| `WindowRL` / `windowRL` | Rear-left window % | `getInt(1001, 947912736)` (`FID_WINDOW_RL`) | Not wired | **Known FID; not wired.** CANFD/`way` parity required. [`FidRegistry.kt:97-104`](../app/src/main/kotlin/com/bydmate/app/data/autoservice/FidRegistry.kt#L97-L104) |
| `WindowRR` / `windowRR` | Rear-right window % | `getInt(1001, 1267728408)` (`FID_WINDOW_RR`) | Not wired | **Known FID; not wired.** CANFD/`way` parity required. [`FidRegistry.kt:97-104`](../app/src/main/kotlin/com/bydmate/app/data/autoservice/FidRegistry.kt#L97-L104) |
| `Sunroof` / `sunroof` | Sunroof opening % | `getInt(1001, 1101004832)` (`FID_SUNROOF`) | Not wired | **Known FID; not wired.** CANFD/`way` parity required. [`FidRegistry.kt:105-108`](../app/src/main/kotlin/com/bydmate/app/data/autoservice/FidRegistry.kt#L105-L108) |
| `Trunk` / `trunk` | Tailgate | `getInt(1001, 692060186)` (`FID_TRUNK`) | Daemon | **Validated on cited CANFD Leopard 3**; platform/`way` parity required. [`FidRegistry.kt:93-96`](../app/src/main/kotlin/com/bydmate/app/data/autoservice/FidRegistry.kt#L93-L96), [`CommandDaemon.kt:889-891`](../app/src/main/kotlin/com/bydmate/app/daemon/CommandDaemon.kt#L889-L891) |
| `Hood` / `hood` | Hood | `getInt(1001, 692060188)` (`FID_HOOD`) | Daemon | **Validated on cited CANFD Leopard 3**; platform/`way` parity required. [`FidRegistry.kt:93-96`](../app/src/main/kotlin/com/bydmate/app/data/autoservice/FidRegistry.kt#L93-L96), [`CommandDaemon.kt:889-891`](../app/src/main/kotlin/com/bydmate/app/daemon/CommandDaemon.kt#L889-L891) |
| `SeatbeltFL` / `seatbeltFL` | Driver seatbelt | — | `null` | **Unsupported / unknown.** |
| `LockFL` / `lockFL` | Driver lock | — | `null` | **Unsupported / unknown.** |
| `TirePressFL` / `tirePressFL` | Front-left tyre pressure, kPa | `getInt(1001, -1728052956)` (`FID_TIRE_PRESSURE_FL`) | Daemon | **Validated on cited vehicle** in registry; direct `way` parity still required. [`FidRegistry.kt:110-118`](../app/src/main/kotlin/com/bydmate/app/data/autoservice/FidRegistry.kt#L110-L118), [`CommandDaemon.kt:891-895`](../app/src/main/kotlin/com/bydmate/app/daemon/CommandDaemon.kt#L891-L895) |
| `TirePressFR` / `tirePressFR` | Front-right tyre pressure, kPa | `getInt(1001, -1728052952)` (`FID_TIRE_PRESSURE_FR`) | Daemon | **Validated on cited vehicle**; direct `way` parity still required. [`FidRegistry.kt:110-118`](../app/src/main/kotlin/com/bydmate/app/data/autoservice/FidRegistry.kt#L110-L118) |
| `TirePressRL` / `tirePressRL` | Rear-left tyre pressure, kPa | `getInt(1001, -1728052948)` (`FID_TIRE_PRESSURE_RL`) | Daemon | **Validated on cited vehicle**; direct `way` parity still required. [`FidRegistry.kt:110-118`](../app/src/main/kotlin/com/bydmate/app/data/autoservice/FidRegistry.kt#L110-L118) |
| `TirePressRR` / `tirePressRR` | Rear-right tyre pressure, kPa | `getInt(1001, -1728052944)` (`FID_TIRE_PRESSURE_RR`) | Daemon | **Validated on cited vehicle**; direct `way` parity still required. [`FidRegistry.kt:110-118`](../app/src/main/kotlin/com/bydmate/app/data/autoservice/FidRegistry.kt#L110-L118) |
| `DriveMode` / `driveMode` | ECO/Sport mode | — | `null` | **Unsupported / unknown.** |
| `WorkMode` / `workMode` | EV/HEV work mode | — | `null` | **Unsupported / unknown.** |
| `AutoPark` / `autoPark` | Auto-hold state | — | `null` | **Unsupported / unknown.** |
| `Rain` / `rain` | Rain sensor | — | `null` | **Unsupported / unknown.** |
| `LightLow` / `lightLow` | Low-beam light | — | `null` | **Unsupported / unknown.** |
| `DRL` / `drl` | Daytime running light | — | `null` | **Unsupported / unknown.** |
| `Sunshade` / `sunshade` | Sunshade opening % | `getInt(1001, 1101004816)` (`FID_SUNSHADE`) | Not wired | **Known FID; not wired.** CANFD/`way` parity required. [`FidRegistry.kt:105-108`](../app/src/main/kotlin/com/bydmate/app/data/autoservice/FidRegistry.kt#L105-L108) |
| `Sentry` / `sentryState` | di+ sentry state | — | `null` | **Unsupported / unknown.** di+ itself remains the sentry/actuation channel; it is not a telemetry source in this branch. |
| `RemoteLock` / `remoteLockState` | di+ remote-lock state | — | `null` | **Unsupported / unknown.** |

## Direct-only fields with no di+ template counterpart

These should be retained as direct-engine diagnostics, but must not be relabelled
as a di+ template field.

| Direct field | FID / reader | Meaning and status |
|---|---|---|
| SoH | `getInt(1014, 1145045032)` / `BatteryReading.sohPercent` | Direct battery health %, available in APK reader and daemon. Registry documents the range; current-`way` parity is not stated in these sources. [`FidRegistry.kt:25-35`](../app/src/main/kotlin/com/bydmate/app/data/autoservice/FidRegistry.kt#L25-L35), [`CommandDaemon.kt:745-761`](../app/src/main/kotlin/com/bydmate/app/daemon/CommandDaemon.kt#L745-L761) |
| Charge type | `getInt(1009, 876609592)` / `ChargingReading.chargingType` | Direct AC/DC handshake code, used by daemon. It is not the di+ `ChargeGun` field. [`FidRegistry.kt:42-45`](../app/src/main/kotlin/com/bydmate/app/data/autoservice/FidRegistry.kt#L42-L45), [`CommandDaemon.kt:879-885`](../app/src/main/kotlin/com/bydmate/app/daemon/CommandDaemon.kt#L879-L885) |
| BMS per-session charged energy | `getFloat(1009, 666894360)` / `ChargingReading.chargingCapacityKwh` | Direct diagnostic energy counter. It is cell-side BMS energy, not grid energy/cost and not automatically di+ `BatCapacity`. [`FidRegistry.kt:51-53`](../app/src/main/kotlin/com/bydmate/app/data/autoservice/FidRegistry.kt#L51-L53), [`DIPLUS_DATA.md:114-128`](DIPLUS_DATA.md#L114-L128) |
| Lifetime average consumption | `getFloat(1014, 1246761008)` | Registry field only; not wired into the current APK/daemon snapshot and not equivalent to di+ `TotalElecCon`. [`FidRegistry.kt:28-33`](../app/src/main/kotlin/com/bydmate/app/data/autoservice/FidRegistry.kt#L28-L33) |
| Charge HV voltage | `getInt(1009, -1442840491)` | **Do not use.** Registry marks the catalog mapping stale and the direct probe documented a sentinel. [`FidRegistry.kt:46-48`](../app/src/main/kotlin/com/bydmate/app/data/autoservice/FidRegistry.kt#L46-L48), [`DIPLUS_DATA.md:110-116`](DIPLUS_DATA.md#L110-L116) |
| Battery type | `getInt(1009, -1728053169)` | Reader exists, but no branch mapping or current-`way` validation is in the permitted sources. [`FidRegistry.kt:49-50`](../app/src/main/kotlin/com/bydmate/app/data/autoservice/FidRegistry.kt#L49-L50), [`AutoserviceClient.kt:99-109`](../app/src/main/kotlin/com/bydmate/app/data/autoservice/AutoserviceClient.kt#L99-L109) |

## Adjustment order before APK installation

1. Capture simultaneous, read-only `way` values for every **validated-on-cited-vehicle** or
   **known-FID** row while di+ is responding. Record `null`/sentinel as a result, not as a
   missing test.
2. Promote only rows that are both semantically equal and stable on `way`; wire them from
   autoservice into both APK and daemon.
3. Keep every unsupported, unproven, or semantically different row as `null` in the direct
   engine. No di+ fallback is permitted for telemetry.
4. Build/install only after the desired promotions are agreed explicitly.

## Drive-recorder evidence (direct-only branch)

The foreground service writes one JSON line after every direct poll (normally
every three seconds) to:

```text
/storage/emulated/0/Android/data/dev.scroodge.cloudevmate/files/telemetry/direct_drive_recorder.jsonl
```

Each line contains the raw validated autoservice snapshot, an
`engine_available` result, and a GPS speed/accuracy/**moving** marker. It does
not store GPS coordinates and does not use GPS to fill an unavailable vehicle
speed or gear field. `unsupported_fields` is written on every line, including
when the direct engine is unavailable. The log keeps the current 2 MiB segment
and one `.prev` segment so a drive cannot consume unbounded storage.

After a drive, retrieve it without modifying the car:

```sh
adb -s 192.168.43.71:5555 pull \
  /storage/emulated/0/Android/data/dev.scroodge.cloudevmate/files/telemetry/direct_drive_recorder.jsonl
```

Compare GPS movement-marker intervals with SOC/power/12-V and the direct
availability result. A direct `speed` or `gear` field remains unsupported until
it has its own safe, on-car validated reader; a zero during parking is not
evidence of a valid mapping.
