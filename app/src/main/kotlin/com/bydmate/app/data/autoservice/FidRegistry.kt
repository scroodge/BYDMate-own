package com.bydmate.app.data.autoservice

/**
 * autoservice Binder addresses validated on Leopard 3 2026-04-25.
 *
 * Source: BydDiagnoseToolV2.apk decompile + adb shell service call validation.
 * See .research/leopard3-pulled/AUTOSERVICE-CATALOG-2026-04-25.md for the
 * full catalog (47 device types, sentinel map, decoders).
 *
 * IMPORTANT: read-only constants. NEVER add a setInt fid here — the
 * regex barrier in AutoserviceClientImpl will reject any tx=6 attempt.
 */
object FidRegistry {

    // transact codes — only read-side codes are listed
    const val TX_GET_INT = 5
    const val TX_GET_FLOAT = 7

    // device types — verified against AUTOSERVICE-CATALOG-2026-04-25.md
    const val DEV_STATISTIC = 1014   // BMS lifetime statistics
    const val DEV_CHARGING = 1009    // Charging gun + charger state
    const val DEV_BODYWORK = 1001    // 12V battery voltage on bodywork
    const val DEV_ENGINE = 1012      // Engine controller (live battery power)

    // === Statistic fids (dev=1014) ===
    /** BMS State of Health, percent (transact 5=int, raw 0..100). */
    const val FID_SOH = 1145045032
    /** Lifetime energy throughput, kWh (transact 7=float). */
    const val FID_LIFETIME_KWH = 1032871984
    /** Lifetime average consumption, kWh/100km (transact 7=float). */
    const val FID_LIFETIME_AVG_PHM = 1246761008
    /** Lifetime mileage, km ×10 (transact 5=int, divide by 10). */
    const val FID_LIFETIME_MILEAGE = 1246765072
    /** Current SOC, percent (transact 7=float). */
    const val FID_SOC = 1246777400

    // === Charging fids (dev=1009) ===
    // Validated against AUTOSERVICE-CATALOG-2026-04-25.md and live AC-charging
    // 2026-04-30: the legacy `-1442840xxx` group (gun/type/battery_type) returned
    // sentinel 0xffffd8e5 on every call — phantom values the SentinelDecoder
    // collapsed to null. The catalog fids below return real codes during charging.
    /** Charging gun connect state: 1=NONE, 2=AC, 3=DC, 4=AC_DC, 5=VTOL. */
    const val FID_GUN_CONNECT_STATE = 876609586
    /** Charging type after handshake: 1=DEFAULT, 2=AC, 3=VTOG, 4=GB_DC, 5=GB_NON_DC. */
    const val FID_CHARGING_TYPE = 876609592
    /** Charger HV voltage, V (int). Catalog mapping not yet confirmed — kept stale.
     *  TODO 2026-04-30: locate canonical fid via catalog before relying on it. */
    const val FID_CHARGE_BATTERY_VOLT = -1442840491
    /** Battery type: 0=LEAD_ACID, 1=IRON/LFP, 65535=INVALID. */
    const val FID_BATTERY_TYPE = -1728053169
    /** Per-session charged energy, kWh (transact 7=float). Persists across DiLink
     *  power-cycle; resets on new charging session (gun reconnect or BMS reset). */
    const val FID_CHARGING_CAPACITY = 666894360
    /** BMS charging state: 1=CHARGING, 2=FINISH, 13=PAUSE (other values observed
     *  but not used). transact 5 (int). */
    const val FID_CHARGING_BMS_STATE = 876609560

    // === Bodywork fids (dev=1001) ===
    /** 12V auxiliary battery voltage, V (transact 7=float). */
    const val FID_OTA_BATTERY_POWER_VOLTAGE = 1128267816

    // === Engine fids (dev=1012) ===
    /**
     * Live battery power, signed int kW (transact 5). Positive = consumption,
     * negative = regen/charging. Raw int = kW directly (no scaling).
     * Validated on Leopard 3 2026-05-11: ENG_POW=1→1kW, ENG_POW=3→3kW.
     * Drive probe range -26..+101 matched dashboard observations.
     * See memory `reference_eng_pow_fid.md`.
     */
    const val FID_ENGINE_POWER = 339738656

    // === Bodywork open/close fids (dev=1001) ===
    // Source: android.hardware.bydauto.BYDAutoFeatureIds, decompiled from the vehicle's own
    // /system/framework/framework.jar (2026-07-22) — the real BYD vendor SDK backing `autoservice`,
    // not a magic-number guess. All 16 values below live-validated against di+'s getDiPars on this
    // car (Leopard 3, DiLink 3.0) same day: 100% match, including non-trivial values (tire
    // pressures 245-250 kPa, sunshade=100) — see docs/BACKLOG.md B-07.
    //
    // CAUTION — architecture-conditional: BYDAutoFeatureIds branches these 12 door/window/
    // sunroof/sunshade values on `isCanFD` (sys.car.protocol == "CANFD") vs `isToyota` vs a
    // default/else. The constants below are the CANFD branch, matching `getprop sys.car.protocol`
    // on this car. A non-CANFD or Toyota-platform BYD (older CAN, or a Toyota-derived joint-venture
    // model) needs the OTHER branch's values — do not reuse these on an unconfirmed platform
    // without re-checking `sys.car.protocol` and re-validating against that car's own di+.
    /** Driver-side front door: 0=closed, 1=open (transact 5). CANFD branch. */
    const val FID_DOOR_FL = 692060168
    /** Passenger-side front door. CANFD branch. */
    const val FID_DOOR_FR = 692060170
    /** Left rear door. CANFD branch. */
    const val FID_DOOR_RL = 692060172
    /** Right rear door. CANFD branch. */
    const val FID_DOOR_RR = 692060174
    /** Trunk/tailgate: 0=closed, 1=open (transact 5). CANFD branch. */
    const val FID_TRUNK = 692060186
    /** Hood: 0=closed, 1=open (transact 5). CANFD branch. */
    const val FID_HOOD = 692060188
    /** Driver-side front window, 0-100% open (transact 5). CANFD branch. */
    const val FID_WINDOW_FL = 947912728
    /** Passenger-side front window. CANFD branch. */
    const val FID_WINDOW_FR = 1267728400
    /** Left rear window. CANFD branch. */
    const val FID_WINDOW_RL = 947912736
    /** Right rear window. CANFD branch. */
    const val FID_WINDOW_RR = 1267728408
    /** Sunroof, 0-100% open (transact 5). CANFD branch. */
    const val FID_SUNROOF = 1101004832
    /** Sunshade panel, 0-100% open (transact 5). CANFD branch. */
    const val FID_SUNSHADE = 1101004816

    // === Tyre pressure fids (dev=1001) — NOT platform-conditional (single literal per fid) ===
    /** Front-left tire pressure, kPa (transact 5). */
    const val FID_TIRE_PRESSURE_FL = -1728052956
    /** Front-right tire pressure, kPa. */
    const val FID_TIRE_PRESSURE_FR = -1728052952
    /** Rear-left tire pressure, kPa. */
    const val FID_TIRE_PRESSURE_RL = -1728052948
    /** Rear-right tire pressure, kPa. */
    const val FID_TIRE_PRESSURE_RR = -1728052944

    // === Telemetry-from-autoservice fids (settings toggle "данные autoservice") ===
    // Source: BYDMate-v4.2.0 (upstream com.bydmate.app) FID table `A2/r.java`, symbol names
    // from BYDAutoFeatureIds. Live-probed on car `way` (Leopard 3, DiLink 3.0, CANFD) on
    // 2026-11-04, ignition OFF, side by side with di+ getDiPars: every value below matched
    // di+ except where noted. Parked-only check — speed/gear D/power in motion and the
    // AC block while running are still unvalidated; the toggle exists to validate them.
    // Not used: DRL (1004/1231040528 read 0 while di+ said 1), 4.2's alternate trunk
    // (1074790416) and window-RR (947912752), which return sentinel on this car.
    const val DEV_AC = 1000
    const val DEV_LIGHT = 1004
    const val DEV_ENERGY = 1006
    const val DEV_INSTRUMENT = 1007
    const val DEV_GEARBOX = 1011
    const val DEV_SPEED = 1013
    const val DEV_SETTING = 1023
    const val DEV_LOCK = 1032

    /** Vehicle speed, km/h (transact 7=float). `Speed.SPEED_AUTO_SPEED`. */
    const val FID_SPEED = -1807745016
    /** Highest / lowest cell voltage, mV (transact 5, ×0.001 → V). di+ 3.335 = raw 3335. */
    const val FID_MAX_CELL_VOLTAGE = 1147142192
    const val FID_MIN_CELL_VOLTAGE = 1147142160
    /** Highest / lowest battery temp, °C + 40 (transact 5). Raw 48 = di+ 8 °C. */
    const val FID_MAX_BATTERY_TEMP = 1148190752
    const val FID_MIN_BATTERY_TEMP = 1148190736
    const val BATTERY_TEMP_OFFSET_C = 40
    /** BMS max allowed charge power, kW ×10 (transact 5). Raw 560 = di+ 56. */
    const val FID_MAX_CHARGE_POWER = 877658136
    /** Gear, same enum as di+ (1=P, 2=R, 3=N, 4=D). `Gearbox.GEARBOX_AUTO_MODE_TYPE`. */
    const val FID_GEAR = 555745336
    /** Power state, same enum as di+ (0=OFF, 1=ON, 2=DRIVE). `Setting.SET_VEHICLE_STATE`. */
    const val FID_POWER_STATE = 315621408
    /** Drive mode / work mode, same enums as di+. `Energy.ENERGY_OPERATION_MODE` / `_MODE_INSTRUMENT`. */
    const val FID_DRIVE_MODE = 555745294
    const val FID_WORK_MODE = 874512420
    /** AC block (dev 1000), same values as di+. Inside temp returns sentinel with ignition off. */
    const val FID_AC_POWER_STATE = 1077936144
    const val FID_AC_CYCLE_MODE = 1077936148
    const val FID_AC_WIND_LEVEL = 1077936156
    const val FID_AC_TEMP_MAIN = 1077936168
    const val FID_AC_TEMP_OUT = 1077936184
    const val FID_AC_TEMP_INSIDE = 1031798832
    /** Driver seatbelt, same enum as di+. `Instrument.INSTRUMENT_DD_MAIN_SAFETYBELT_STATE`. */
    const val FID_SEATBELT_FL = 692060184
    /** Driver door lock, same enum as di+ (1=unlocked, 2=locked). `Ota.OTA_LF_DOOR_LOCK`. */
    const val FID_LOCK_FL = 1081081864
    /** Low beam, 0=OFF 1=ON. `Light.LIGHT_LOW_BEAM_LIGHT`. */
    const val FID_LIGHT_LOW = 950009866
}
