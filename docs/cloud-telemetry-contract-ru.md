# Cloud Telemetry Contract

Этот документ описывает, что APK VoltFlow Mate отправляет в облако, в каком
формате и с какими заголовками. Контракт соответствует коду:

- `app/src/main/kotlin/com/bydmate/app/data/cloud/CloudTelemetryPayload.kt`
- `app/src/main/kotlin/com/bydmate/app/data/cloud/CloudTelemetryClient.kt`
- `app/src/main/kotlin/com/bydmate/app/data/cloud/VoltflowLinkClient.kt`
- `app/src/main/kotlin/com/bydmate/app/data/cloud/CloudTelemetrySender.kt`
- `app/src/main/kotlin/com/bydmate/app/data/remote/TelemetrySnapshot.kt`
- `app/src/main/kotlin/com/bydmate/app/data/remote/DiParsClient.kt`

## Подключение к VoltFlow (6-значный код)

Предпочтительный способ получить `cloud_sync_api_key` — без копирования длинного
ключа с телефона.

### Шаги для пользователя

1. В веб-приложении VoltFlow: **Настройки → VoltFlow Mate → Подключить BYDMate**.
2. Запомните **6 цифр** (код действует **10 минут**, одноразовый).
3. В VoltFlow Mate: экран **шлюза** или **Настройки → Cloud Sync** → поле **Код из VoltFlow** → **Подключить**.
4. Укажите **имя авто** (`cloud_sync_vehicle_id`), совпадающее с `vehicle_id` в облаке.
5. **Send test** → **Save** → включите Cloud Sync.

Раздел **Дополнительно** — ручной ввод API key (отладка, свой хост VoltFlow).

Уже настроенные установки с вставленным ключом **не требуют** переподключения,
пока в VoltFlow не нажали **Generate key** (старый ключ перестаёт действовать).

### Redeem (APK → сервер)

Клиент: `VoltflowLinkClient.redeem(telemetryEndpointUrl, code)`.

URL redeem выводится из endpoint телеметрии:

```text
https://<host>/api/bydmate/telemetry
  → https://<host>/api/bydmate/link-code/redeem
```

Запрос:

```http
POST /api/bydmate/link-code/redeem
Content-Type: application/json

{ "code": "482913" }
```

Успех (`200`):

```json
{
  "ok": true,
  "api_key": "<64-char hex>",
  "endpoint_url": "https://<host>/api/bydmate/telemetry"
}
```

APK сохраняет `api_key` в `cloud_sync_api_key` и `endpoint_url` в `cloud_sync_url`.

Ошибки: `401` неверный/истёкший код; `429` слишком много попыток (rate limit на сервере).

Полный контракт: `supabase/BYDMATE_APK_API.md` в репозитории VoltFlow (EvAcChargeTimer).

## Transport

APK отправляет `POST` на настроенный HTTPS endpoint. URL должен начинаться с `https://`.

Default endpoint:

```text
https://voltflow.life/api/bydmate/telemetry
```

Headers:

```http
Content-Type: application/json; charset=utf-8
X-API-Key: <cloud_sync_api_key>
X-Vehicle-Id: <cloud_sync_vehicle_id>
X-App: VoltFlow-Mate
```

`vehicle_id` также дублируется в JSON body.

## Response Handling

HTTP `4xx` — non-retryable: записи в очереди помечаются завершёнными с ошибкой.

HTTP `5xx`, другие коды и сетевые исключения — retryable: записи остаются в очереди.

### Подтверждение доставки (application ACK)

После HTTP `2xx` APK разбирает JSON и снимает батч с очереди **только** если:

- `ok == true`
- `skipped_stale_count == 0`
- `inserted_count + duplicate_count >=` число сэмплов в отправленном теле

Иначе батч остаётся в `cloud_sync_queue` и уходит на retry.

Пример полей ответа VoltFlow:

```json
{
  "ok": true,
  "inserted_count": 12,
  "duplicate_count": 3,
  "skipped_stale_count": 0,
  "sample_count": 15
}
```

Диагностика в настройках: `cloud_sync_last_ack` (например `15 sent, 12 ins, 3 dup, 0 skip`).

При отставании очереди (>15 unsent) flush дренирует несколько батчей подряд;
`TrackingService` повторяет flush, если предыдущий ещё выполнялся.

## Payload Modes

APK может отправить один telemetry sample:

```json
{
  "schema_version": 1,
  "vehicle_id": "way",
  "device_time": "2026-05-25T12:34:56Z",
  "source": "BYDMate",
  "mate_version": "0.4.10",
  "telemetry": {
    "soc": 73,
    "speed_kmh": 0.0,
    "power_kw": -7.0,
    "battery_temp_c": 26.0,
    "cabin_temp_c": 22.0,
    "outside_temp_c": 18.0,
    "battery_voltage_v": null,
    "aux_voltage_v": 12.6,
    "cell_voltage_min_v": 3.3,
    "cell_voltage_max_v": 3.31,
    "cell_delta_v": 0.01,
    "diplus_min_cell_voltage_v": 3.3,
    "diplus_max_cell_voltage_v": 3.31,
    "diplus_cell_delta_v": 0.01,
    "odometer_km": 12345.0,
    "soh_percent": null,
    "is_charging": true,
    "charge_power_kw": 7.0,
    "charge_type": "AC",
    "kwh_charged": null,
    "range_est_km": 350.0,
    "current_trip_distance_km": 12.4,
    "current_trip_consumption_kwh_100km": null
  },
  "diplus": {
    "soc": 73,
    "speed_kmh": 0,
    "mileage_km": 12345.0,
    "power_kw": -7.0,
    "charge_gun_state": 2,
    "max_battery_temp_c": 28,
    "avg_battery_temp_c": 26,
    "min_battery_temp_c": 24,
    "charging_status": 1,
    "battery_capacity_kwh": 72.9,
    "total_elec_consumption_kwh": 3456.0,
    "voltage_12v": 12.6,
    "max_cell_voltage_v": 3.31,
    "min_cell_voltage_v": 3.3,
    "cell_delta_v": 0.01,
    "exterior_temp_c": 18,
    "gear": 1,
    "power_state": 1,
    "inside_temp_c": 22,
    "ac_status": 0,
    "ac_temp_c": 22,
    "fan_level": 0,
    "ac_circ": 0,
    "door_fl": 0,
    "door_fr": 0,
    "door_rl": 0,
    "door_rr": 0,
    "window_fl_percent": 0,
    "window_fr_percent": 0,
    "window_rl_percent": 0,
    "window_rr_percent": 0,
    "sunroof_percent": 0,
    "trunk": 0,
    "hood": 0,
    "seatbelt_fl": 1,
    "lock_fl": 2,
    "tire_press_fl_kpa": 240,
    "tire_press_fr_kpa": 241,
    "tire_press_rl_kpa": 239,
    "tire_press_rr_kpa": 242,
    "drive_mode": 1,
    "work_mode": 1,
    "auto_park": 0,
    "rain": 0,
    "light_low": 0,
    "drl": 1
  },
  "location": {
    "lat": 53.9023,
    "lon": 27.5619,
    "accuracy_m": 8.0,
    "bearing_deg": 120.0
  }
}
```

Или batch payload:

```json
{
  "samples": [
    {
      "schema_version": 1,
      "vehicle_id": "way",
      "device_time": "2026-05-25T12:34:56Z",
      "source": "BYDMate",
      "telemetry": {},
      "diplus": null,
      "location": {}
    }
  ]
}
```

Важно: каждый элемент `samples[]` имеет ту же схему, что одиночный sample.
Backend должен принимать оба варианта: одиночный объект sample и объект с
массивом `samples`. В APK с client-side rollups batch также может содержать
необязательные sibling-массивы `hourly` (cumulative hourly blocks) и `trips`
(cumulative trip blocks). Они относятся к тому же `X-Vehicle-Id`, что и samples.

## Top-Level Fields

| Field | Type | Required | Description |
| --- | --- | --- | --- |
| `schema_version` | number | yes | Версия схемы payload. Сейчас всегда `1`. |
| `vehicle_id` | string | yes | Имя/ID автомобиля из настроек APK. |
| `device_time` | string | yes | ISO-8601 UTC timestamp, например `2026-05-25T12:34:56Z`. |
| `source` | string | yes | Сейчас всегда `BYDMate`. |
| `live_only` | boolean | no | `true` — парковочный heartbeat, в котором ничего существенного не изменилось. Сервер обновляет **только** `bydmate_live_snapshots` (плюс его `diplus_*` / `autoservice_*` колонки) и пропускает запись в `bydmate_telemetry_samples`, hourly-rollup и логику поездок. Поле **опускается**, когда `false`, — обычный sample не меняет форму на проводе. Старые версии APK его не шлют, и такие payload'ы идут полным путём. |
| `client_hourly` | boolean | no | `true` означает, что sample уже folded в APK-owned cumulative `hourly` block. Сервер пропускает свой per-sample hourly path, чтобы не считать час дважды. Поле опускается, когда `false`. |
| `trip_id` + `client_trip` | string + boolean | no | APK помечает driving sample UUID текущей client-owned поездки и прикладывает её cumulative block в `trips`. Это additive Phase 4: пока cloud-side RPC не введена, ingest должен принимать и игнорировать эти поля, продолжая прежнюю server-side trip-логику. |
| `mate_version` | string | no | Версия APK (`BuildConfig.VERSION_NAME`, сейчас `0.4.10`), которую шлёт и приложение, и демон. Сервер сохраняет её в `bydmate_live_snapshots.mate_version` — видно, какая версия стоит на каждом авто. |
| `telemetry` | object | yes | Нормализованный набор основных метрик. |
| `diplus` | object/null | no | Сырые/расширенные данные DiPlus после парсинга APK. Может быть `null` или отсутствовать, когда Di+ недоступен. |
| `autoservice` | object | no | BMS-direct поля, доступные только при ADB/autoservice Binder; отсутствие блока нормально. |
| `location` | object | yes | GPS-данные. Объект есть всегда, поля внутри могут быть `null` или отсутствовать по смыслу. |

Все неизвестные будущие поля backend должен игнорировать.

## Null Semantics

APK явно пишет `null`, если значение недоступно. Это нормальная ситуация:

- DiPlus мог не вернуть конкретный параметр.
- Нет разрешения или данных геолокации.
- Данные зарядки доступны только во время зарядки.
- Некоторые поля приходят из autoservice/BMS и могут отсутствовать.

Backend не должен отклонять sample только из-за `null` в optional fields.

## `telemetry`

`telemetry` - это нормализованный объект для основного backend-пайплайна.

| Field | Type | Unit/Values | Source/Meaning |
| --- | --- | --- | --- |
| `soc` | number/null | percent | State of charge из DiPlus. |
| `speed_kmh` | number/null | km/h | Скорость автомобиля. |
| `power_kw` | number/null | kW | Мощность. При зарядке обычно отрицательная в исходных данных. |
| `battery_temp_c` | number/null | deg C | Средняя температура батареи. |
| `cabin_temp_c` | number/null | deg C | Температура в салоне. |
| `outside_temp_c` | number/null | deg C | Наружная температура. |
| `battery_voltage_v` | number/null | V | Напряжение батареи зарядки из autoservice, если доступно. |
| `aux_voltage_v` | number/null | V | Напряжение 12V. |
| `cell_voltage_min_v` | number/null | V | Минимальное напряжение ячейки. |
| `cell_voltage_max_v` | number/null | V | Максимальное напряжение ячейки. |
| `cell_delta_v` | number/null | V | `cell_voltage_max_v - cell_voltage_min_v`. |
| `diplus_min_cell_voltage_v` | number/null | V | Дубликат DiPlus min cell для совместимости backend. |
| `diplus_max_cell_voltage_v` | number/null | V | Дубликат DiPlus max cell для совместимости backend. |
| `diplus_cell_delta_v` | number/null | V | Дубликат DiPlus cell delta для совместимости backend. |
| `odometer_km` | number/null | km | Пробег. |
| `soh_percent` | number/null | percent | Battery SOH (BMS `FID_SOH`), если доступен из autoservice и в диапазоне `0..100`. Может приходить и в **idle/parked** payload как кэшированное последнее известное значение. |
| `is_charging` | boolean/null | `true/false` | Определяется по состоянию зарядного порта. |
| `charge_power_kw` | number/null | kW | Положительная мощность зарядки. Если не заряжается, APK ставит `0.0`. |
| `charge_type` | string/null | `AC`/`DC` | `AC` для gun state `2`, `DC` для `3..5`. |
| `kwh_charged` | number/null | kWh | Сколько энергии добавлено за текущую зарядку, если доступно. |
| `range_est_km` | number/null | km | Расчетный запас хода из APK. |
| `current_trip_distance_km` | number/null | km | Дистанция текущей live-поездки. |
| `current_trip_consumption_kwh_100km` | number/null | kWh/100km | Текущий расход поездки, если доступен. |

## `diplus`

`diplus` содержит расширенные данные, близкие к тому, что APK читает из локального
DiPlus API. Если DiPlus недоступен, весь объект будет `null`.

| Field | Type | Unit/Values |
| --- | --- | --- |
| `soc` | number/null | percent |
| `speed_kmh` | number/null | km/h |
| `mileage_km` | number/null | km |
| `power_kw` | number/null | kW |
| `charge_gun_state` | number/null | BYD/DiPlus code |
| `max_battery_temp_c` | number/null | deg C |
| `avg_battery_temp_c` | number/null | deg C |
| `min_battery_temp_c` | number/null | deg C |
| `charging_status` | number/null | DiPlus code |
| `battery_capacity_kwh` | number/null | kWh |
| `total_elec_consumption_kwh` | number/null | kWh |
| `voltage_12v` | number/null | V |
| `max_cell_voltage_v` | number/null | V |
| `min_cell_voltage_v` | number/null | V |
| `cell_delta_v` | number/null | V |
| `exterior_temp_c` | number/null | deg C |
| `gear` | number/null | `1=P`, `2=R`, `3=N`, `4=D` |
| `power_state` | number/null | `0=OFF`, `1=ON`, `2=DRIVE` |
| `inside_temp_c` | number/null | deg C |
| `ac_status` | number/null | `0=OFF`, `1=ON` |
| `ac_temp_c` | number/null | deg C |
| `fan_level` | number/null | DiPlus code |
| `ac_circ` | number/null | `0=external`, `1=internal` |
| `door_fl`, `door_fr`, `door_rl`, `door_rr` | number/null | `0=closed`, `1=open` |
| `window_fl_percent`, `window_fr_percent`, `window_rl_percent`, `window_rr_percent` | number/null | `0..100` |
| `sunroof_percent` | number/null | `0..100` |
| `trunk` | number/null | `0=closed`, `1=open` |
| `hood` | number/null | `0=closed`, `1=open` |
| `seatbelt_fl` | number/null | `0=unbuckled`, `1=buckled`, `2=invalid` |
| `lock_fl` | number/null | `1=unlocked`, `2=locked` |
| `tire_press_fl_kpa`, `tire_press_fr_kpa`, `tire_press_rl_kpa`, `tire_press_rr_kpa` | number/null | kPa |
| `drive_mode` | number/null | `1=ECO`, `2=SPORT` |
| `work_mode` | number/null | `0=stop`, `1=EV`, `2=forced EV`, `3=HEV` |
| `auto_park` | number/null | `0=disabled`, `1=standby`, `2=active` |
| `rain` | number/null | DiPlus code |
| `light_low` | number/null | `0=OFF`, `1=ON` |
| `drl` | number/null | `0=invalid`, `1=ON`, `2=OFF` |

## `autoservice` (optional)

Эти BMS-direct поля появляются только когда APK может читать autoservice Binder через
ADB. Их отсутствие не делает telemetry sample неполным:

| Field | Type | Description |
| --- | --- | --- |
| `soc_percent`, `power_kw` | number/null | BMS SOC и signed engine power. |
| `gun_state`, `bms_state` | integer/null | Состояние подключения пистолета и BMS зарядки. |
| `charge_capacity_kwh` | number/null | Счётчик энергии в ячейки за текущую зарядку; диагностика, не источник цены в VoltFlow. |
| `charge_battery_volt`, `battery_type` | number/null | HV напряжение и химия батареи. |
| `lifetime_mileage_km`, `lifetime_kwh` | number/null | Lifetime BMS counters. |

VoltFlow материализует эти значения в live snapshot и history rows; live snapshot
сохраняет последнее известное значение, если следующий slim payload не содержит блока.

## `location`

| Field | Type | Unit | Description |
| --- | --- | --- | --- |
| `lat` | number/null | degrees | Latitude. |
| `lon` | number/null | degrees | Longitude. |
| `accuracy_m` | number/null | meters | Android location accuracy, если есть. |
| `bearing_deg` | number/null | degrees | Android bearing/course, если есть. |

В тестовой отправке геолокация включается только если у APK есть fine location
permission. В live-режиме используется последнее значение `TrackingService`.

## Send Cadence and Queueing

APK не отправляет каждый poll напрямую. Сначала sample кладется в локальную
очередь, затем очередь flush'ится на endpoint.

Правила постановки в очередь:

- Авто движется: sample раз в **1 секунду** (локальный poll 1 s).
- Авто заряжается: sample раз в **10 секунд** до SOC `98%`; от **98%** и до остановки
  зарядки — раз в **1 секунду**, чтобы точно снять cell delta на балансировочном «хвосте».
- Авто стоит (P / parked): heartbeat примерно раз в **30 секунд**; slim `diplus` с `gear`.
  Если SOC, gun state, gear и 12V не изменились (12V — с допуском **0.3 V**), heartbeat уходит
  с `live_only: true`: живое состояние обновляется, history/hourly/trips не пишутся. Любое
  изменение шлёт полный sample. Кроме того, полный sample принудительно шлётся не реже чем
  раз в **15 минут** — `bydmate_phantom_drain_daily` суммирует промежутки между соседними
  parked-samples и отбрасывает промежутки **≥ 6 часов**, поэтому бесконечная серия `live_only`
  на ночной стоянке с ровным SOC обнулила бы `idle_hours`.
- Смена передачи (P/D/R): sample добавляется сразу; переход в parked может flush'иться
  сразу, а driving/charging сохраняют active-batch cadence.
- Изменение состояния движения/зарядки также добавляет sample.

Правила отправки:

- Во время движения и charging tail (SOC ≥98%) samples копятся и отправляются batch'ем каждые
  **15 секунд** (до **15** samples за batch).
- В charging bulk (SOC <98%) batch отправляется раз в **60 секунд**: это уменьшает число
  backend invocations, оставаясь в пределах 90-секундной свежести live состояния.
- В idle режиме HTTP flush использует настройку Cloud Sync; по умолчанию **60 секунд**
  (допустимый диапазон 5–300 s).
- Максимальный batch size в idle: `120` samples.
- Максимум локальной очереди: `1000` rows; старые записи обрезаются.
- Если включен Wi-Fi-only и Wi-Fi недоступен, samples остаются в очереди.

## GPS privacy

В Cloud Sync settings есть переключатель **Don't send GPS to cloud**
(`cloud_sync_omit_gps`). Когда он включен:

- APK передаёт `location: {}` даже при наличии GPS fix.
- Live SOC/зарядка продолжают синхронизироваться.
- Сервер не создаёт `bydmate_trip_track_points` для этих samples.

## Payload tiers

- **Idle-only:** slim JSON — SOC, charging/SoH и compact `diplus` status (`gear`, gun,
  power-state, 12V, давление во всех четырёх шинах) при наличии; без traction
  `power_kw`.
- **Moving/charging:** включает `power_kw`, температуры, odometer/trip fields и расширенный
  `diplus` при наличии Di+.
- Null-поля **не сериализуются** (omit nulls).
- GPS с accuracy > 30 m отбрасывается до enqueue (отправляется `{}`).
- **GPS thinning при движении:** точки прореживаются потоковым corridor-фильтром
  (Reumann–Witkam). Точка сохраняется, если она первая в поездке, отклоняется от текущего
  коридора больше чем на **12 m** (поворот), либо с последней сохранённой прошло **30 s**.
  Прореженный sample шлёт `location: {}` — сама телеметрия (SOC, power) уходит как обычно,
  раз в секунду. Фильтр сбрасывается на любой смене состояния, поэтому первая точка новой
  поездки всегда сохраняется.
- **Округление float:** `cell_delta_v` / `cell_voltage_min_v` / `cell_voltage_max_v` и их
  `diplus`-аналоги — до 4 знаков, `range_est_km` — 1, `current_trip_distance_km` и
  `kwh_charged` — 3, `current_trip_consumption_kwh_100km` — 2. Совпадает с точностью, которую
  сервер и так применяет в `telemetry-sanitizer.ts`, поэтому для backend это no-op — экономятся
  только байты на проводе (raw double вида `0.019999999999999` занимал ~20 символов).

Backend должен быть готов к batch из десятков samples, особенно при зарядке.

`CommandDaemon` не использует Room queue: при car-off он best-effort отправляет сокращённый
Di+ sample без GPS по обычному 60-секундному cadence, сразу при смене gun state, а при активном
`live_fast_seconds` grant — `live_only` status каждые 3 секунды. Его задача — сохранить current
live state и command path до следующего запуска основного приложения, а не заменить надёжную
app-очередь.

## vehicle_id — неизменяемый идентификатор сессии

`vehicle_id` — это не «имя машины для отображения», а **первичный ключ потока телеметрии**
в базе данных VoltFlow. Его изменение имеет необратимые последствия.

### Что происходит при смене vehicle_id в настройках APK

1. **Очередь сохраняет старый ID безопасно.**
   Payload каждого sample получает `vehicle_id` в момент постановки в очередь. При flush
   `CloudTelemetrySender` группирует pending rows по ID, записанному в самом payload, и
   отправляет каждый такой batch с совпадающим `X-Vehicle-Id`. Поэтому переименование
   между enqueue и flush не должно само по себе вызвать `400 Vehicle ID mismatch` или
   потерю старого batch.

2. **Разорванная история телеметрии.**
   Все таблицы `bydmate_telemetry_samples`, `bydmate_telemetry_hourly`,
   `bydmate_trips`, `bydmate_live_snapshots` хранят `vehicle_id` как часть ключа.
   После переименования данные до смены остаются под старым ID, данные после — под новым.
   История в VoltFlow будет разорвана.

3. **Рассинхрон с cars.vehicle_alias.**
   Поле `cars.vehicle_alias` в VoltFlow связывает запись автомобиля (ёмкость, мощность
   зарядки и т.д.) с потоком телеметрии. После смены `vehicle_id` в APK это поле
   нужно вручную обновить в настройках VoltFlow, иначе фронтенд потеряет связь
   между конфигурацией машины и данными телеметрии.

### Рекомендации

- Задавайте `vehicle_id` один раз при первоначальной настройке. Используйте короткий
  латинский slug без пробелов, например `byd_seal_123`.
- Если переименование всё же необходимо — **сначала** обновите `cars.vehicle_alias`
  в VoltFlow, **затем** меняйте имя в APK.
- Старые queued samples безопасно уйдут под старым ID, затем новые — под новым. Это
  сохраняет доставку, но **не объединяет** уже существующую серверную историю: смена
  `vehicle_id` всё равно создаёт новый логический поток данных.

## Backend Validation Recommendations

Минимальная валидация для одиночного sample:

- `schema_version == 1`
- `vehicle_id` непустой и совпадает с `X-Vehicle-Id`, если backend требует это.
- `device_time` парсится как ISO-8601 timestamp.
- `telemetry`, `location` являются объектами.
- `diplus` является объектом или `null`.
- `mate_version` и `autoservice` принимаются как необязательные additive fields.

Для batch:

- body содержит `samples` array;
- каждый элемент валидируется как одиночный sample;
- пустой batch можно отклонять как `400`.

Рекомендуемый ответ:

```json
{
  "ok": true,
  "accepted": 60
}
```

APK не зависит от этих полей, но они удобны для диагностики.
