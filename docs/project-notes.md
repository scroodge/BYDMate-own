# Project Notes

## 2026-06-13: фантомные поездки, mate_version, демон ↔ приложение без дублей (v0.3.9.3–v0.3.9.5)

### Что сделано

- **v0.3.9.3** — диалог-напоминание после обновления APK: при первом запуске на новой версии
  показывается напоминание проверить `Disable background Apps → OFF` в DiLink (порт из upstream
  `AndyShaman/BYDMate`). `UpdateChecker.getLastSeenVersion != versionName` → показ один раз.
- **v0.3.9.4** — поле `mate_version` (`BuildConfig.VERSION_NAME`) в каждом payload (и приложения,
  и демона); потребовало `buildConfig = true` в `buildFeatures`. На сервере сохраняется в
  `bydmate_live_snapshots.mate_version` через триггер (миграция `20260613120000`). Видно, какая
  версия APK стоит на каждом авто. Таблица-каталог релизов `mate_app_releases` (миграция
  `20260613140000`) для баннера «доступно обновление».
- **v0.3.9.5** — демон и приложение больше **не шлют телеметрию одновременно**. `TrackingService`
  пишет маяк `<externalFilesDir>/voltflow_mate_heartbeat` (epoch) при каждом enqueue; `CommandDaemon`
  читает его и пропускает свой 60-секундный push, пока маяк свежий (< 120 с, `APP_ALIVE_TTL_MS`).
  Опрос команд остаётся всегда активным. Раньше при живом приложении обе стороны слали данные →
  лишние сэмплы и риск фантомных поездок.

### Фантомные поездки (диагноз и фикс — на стороне VoltFlow/Supabase)

- **Корень:** `bydmate_ingest_telemetry` пишет `bydmate_trips.distance_km` напрямую из
  `current_trip_distance_km` (одометр-счётчик авто, копируется как есть, не дельта). После закрытия
  реальной поездки счётчик не обнуляется сразу; при манёвре D→R→P на 1–3 км/ч ingest открывает новую
  поездку, наследующую старое значение пробега → артефакт `10 с / 2.4 км / 3 км/ч`.
- **НЕ интерференция демона** — авто было на 0.3.9.5 (демон молчал). Чисто поток приложения.
- **Фикс:** `bydmate_discard_trip_if_junk` v2 (миграция `20260613150000`): Rule A (`dist≤0.1` ∧
  `max_speed≤3`), Rule B (`dur<60 с` ∧ `max_speed<10`), Rule C (`implied = dist·3600/dur >
  max(max_speed·1.5, 80)` → физически невозможная средняя скорость = наследованный пробег).
  Удалено 10 фантомов за неделю (`sed`/`way`/`cl`). Подробности в репозитории VoltFlow:
  `docs/TRIPS.md`.
- **Грабли с миграциями:** редактирование уже применённой миграции `20260613130000` не задеплоилось
  (`supabase db push` пропускает применённые) — поэтому ранний Rule B не сработал. Всегда новая
  миграция, не правка старой.

## 2026-06-11: Agentmemory подключён; документация прочитана и зафиксирован текущий статус

### Что прочитано

- `README.md`: VoltFlow Mate — форк BYDMate для BYD DiLink; основной путь установки через APK из GitHub Releases, приложение живёт под `applicationId` `dev.scroodge.cloudevmate`.
- `docs/cloud-telemetry-contract-ru.md`: Cloud Sync использует HTTPS ingest, `X-API-Key`, `X-Vehicle-Id`, 6-значное подключение через `link-code/redeem`, ACK-проверку батчей и GPS privacy через `cloud_sync_omit_gps`.
- `docs/REMOTE_COMMAND_DAEMON.md`: remote command daemon запускается как shell-uid `app_process`, переживает force-stop приложения при parked/off состоянии, читает DiPlus на `127.0.0.1:8988`, пушит live telemetry примерно раз в 60 секунд и poll/ack команд через VoltFlow.
- `docs/release-notes-v0.2.2.md`: первый публичный релиз под брендом VoltFlow Mate; debug/release APK называется `VoltFlow-Mate-v...apk`.

### Текущий статус

- Agentmemory MCP доступен в этой сессии и видит проект `/Users/way/Dev/BYDMate-own` как `BYDMate-own`.
- Документация уже описывает текущую архитектуру Cloud Sync, pairing и parked/off daemon; отдельная память в agentmemory обновлена тем же резюме.
- ~~Открытый риск: смена `cloud_sync_vehicle_id` при непустой очереди → mismatch header/body и потеря mixed batch.~~ **РЕШЕНО (2026-07-14, `7b37366`):** `flushQueue` группирует строки очереди по `vehicle_id` из payload и шлёт по одному батчу на id (заголовок всегда == тело); добавлен регресс-тест, падавший на старом коде. Мёртвый код (~280 строк) удалён тем же коммитом.

### Следующие ориентиры

- Для работы над Cloud Sync сначала проверять `CloudTelemetrySender`, `CloudTelemetryPayload`, `CloudTelemetryCadence`, `TrackingService` и тесты в `app/src/test/kotlin/com/bydmate/app/data/cloud/`.
- Для работы над parked/off remote commands сначала проверять `CommandDaemon`, `VehicleCommandPoller`, `CommandAllowlist`, `DiParsControlClient`, `tools/start_voltflow_cmd.sh` и `docs/REMOTE_COMMAND_DAEMON.md`.

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
