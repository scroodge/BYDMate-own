# Project Notes

## 2026-05-29: Смена имени машины в APK ломает очередь и разрывает историю телеметрии

### Диагноз

Баг воспроизводится по следующей цепочке:

1. `CloudTelemetryPayload.build(vehicleId, snapshot)` запекает `vehicle_id` **в JSON payload** в момент постановки в очередь (`CloudSyncQueueDao`).
2. При flush `CloudTelemetrySender.flushQueue()` читает **актуальный** `config.vehicleId` из `SettingsRepository` и ставит его в заголовок `X-Vehicle-Id`.
3. Если между enqueue и flush пользователь изменил `cloud_sync_vehicle_id` в настройках:
   - header: `X-Vehicle-Id: new_name`
   - body payload: `"vehicle_id": "old_name"`
   - Backend: `400 Vehicle ID mismatch` (non-retryable).
4. APK помечает **все items текущего batch** как `finished + error` и удаляет из очереди.
5. В переходный период новые samples тоже оказываются в mixed batch вместе со старыми — они тоже теряются.

Дополнительно: все таблицы телеметрии в Supabase (`bydmate_telemetry_samples`,
`bydmate_live_snapshots`, `bydmate_trips`) хранят `vehicle_id` как часть ключа,
поэтому история до/после переименования оказывается разорвана.

### Затронутые файлы (APK)

- `app/src/main/kotlin/com/bydmate/app/data/cloud/CloudTelemetrySender.kt`:
  `flushQueue()` — именно здесь несоответствие header vs payload
- `app/src/main/kotlin/com/bydmate/app/data/cloud/CloudTelemetryPayload.kt`:
  `build()` — запекает `vehicleId` в JSON

### Статус

Задокументировано. План исправления — см. ниже.
Воркэраунд для пользователей: обновлять `cars.vehicle_alias` в VoltFlow перед сменой
имени в APK, после смены — дождаться очистки очереди (или переустановить APK).



## 2026-05-31: 6-digit VoltFlow pairing

- VoltFlow Settings: **Подключить BYDMate** → `POST /api/bydmate/link-code` → код 6 цифр, TTL 10 мин.
- APK: `VoltflowLinkClient` → `POST …/link-code/redeem` → локально `cloud_sync_api_key` + URL.
- UI: код + **Подключить** по умолчанию; API key в **Дополнительно** (Gateway + Settings).
- Supabase: `bydmate_link_codes`, `bydmate_link_redeem_attempts` (см. VoltFlow migration `20260531120000_bydmate_link_codes.sql`).
- Старые установки с вставленным ключом без изменений.

---

## 2026-05-30: Database architecture + Cloud Sync cadence update

### VoltFlow (cloud)

Pairing (2026-05-31): `bydmate_link_codes`, `link-code` / `redeem` API — see
EvAcChargeTimer `supabase/BYDMATE_APK_API.md` and `supabase/TELEMETRY.md`.

Applied migrations and app features — full reference in EvAcChargeTimer
`supabase/TELEMETRY.md`:

- 90-day raw sample retention, 3-year hourly retention
- Trip `regen_energy_kwh` / `traction_energy_kwh` at trip close
- Hourly `regen_kwh_sum` / `traction_kwh_sum`
- Realtime on `bydmate_live_snapshots`
- Home charger geofence on `cars` + auto home tariff on session start
- Vehicle analytics UI + export APIs
- Charge finish projection on active session screen

### APK Cloud Sync (new behavior)

- **Moving + charging:** enqueue every **1 s**; HTTP flush every **15 s** (batch up to 15 samples).
- **Idle:** heartbeat every **5 min**; skip up to **2** consecutive unchanged samples (SOC/charging/power).
- **Payload:** idle = slim JSON; moving/charging include `power_kw`; null fields omitted; bad GPS (>30 m accuracy) dropped before enqueue.
- **GPS privacy:** Settings → Cloud Sync → **Don't send GPS to cloud** (`cloud_sync_omit_gps`). Sends `location: {}`; no server track points.

Contract: `docs/cloud-telemetry-contract-ru.md`.

---

## 2026-05-22: С какой частотой данные отправляются на сервер?

Короткий ответ: локальная телеметрия читается раз в 1 секунду. На сервер сейчас отправляется только VoltFlow Cloud Sync; ABRP/Iternio отключён из runtime-пути и скрыт из настроек.

### Локальный polling

- `TrackingService` читает данные из DiPars каждые `1000 ms`.
- Если DiPars временно не отвечает, интервал постепенно увеличивается до максимума `60_000 ms`.

Код:
- `app/src/main/kotlin/com/bydmate/app/service/TrackingService.kt`: `POLL_INTERVAL_MS = 1000L`, `MAX_POLL_INTERVAL_MS = 60_000L`.
- Основной цикл: `startPolling()`, где на каждом тике вызывается `diParsClient.fetch()`.

### VoltFlow Cloud Sync

Cloud Sync включается настройкой `cloud_sync_enabled`. `TrackingService` пробует вызвать `maybeSendCloudTelemetry()` на каждом успешном 1-секундном тике, но `CloudTelemetrySender` сам решает, надо ли класть новый снимок в очередь:

- **движение или зарядка:** новый sample раз в **1 секунду**;
- **стоянка (P / parked):** heartbeat раз в **30 секунд**; slim payload с `diplus.gear` и `charge_gun_state`;
- **смена передачи (P/D/R):** sample и flush сразу;
- при смене состояния движение/зарядка sample кладётся сразу.

Flush очереди на сервер:

- **движение/зарядка:** batch до **15** samples каждые **15 секунд**;
- **idle flush:** фиксированно `cloud_sync_interval_sec` = 60 с (без настройки в UI), batch до 120 samples;
- сразу при смене состояния (кроме charging-переходов);
- если Wi-Fi only и Wi-Fi нет — samples копятся локально.

GPS privacy: при включённом `cloud_sync_omit_gps` location не передаётся (`location: {}`).

Код:
- `app/src/main/kotlin/com/bydmate/app/data/cloud/CloudTelemetrySender.kt`
- `app/src/main/kotlin/com/bydmate/app/data/cloud/CloudTelemetryPayload.kt`
- `docs/cloud-telemetry-contract-ru.md`
