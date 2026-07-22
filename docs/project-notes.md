# Project Notes

## 2026-07-22: живая сверка на машине — найден вендорский SDK, +16 fid в FidRegistry

Владелец подключил Mac к машине (`adb connect 192.168.43.71:5555`, уже авторизован ранее).
Вместо разбора di+ (тупик — ни в Java, ни в native `.so` нет строки "autoservice"/
"BYDAutoServer"; логика в непрозрачном native-коде) нашли и скачали с самой машины
`/system/framework/framework.jar` (29 МБ, часть BOOTCLASSPATH) → декомпилировали → внутри
полный вендорский SDK **`android.hardware.bydauto.*`** (123 класса, по одному пакету на
подсистему: bodywork, tyre, ac, speed, gearbox, radar, pm2p5, panorama, safetybelt, …) с
именованными константами в `BYDAutoFeatureIds` — то, что `AutoserviceBridge`/`FidRegistry`
до сих пор восстанавливали вручную через магические числа.

- **Важное открытие:** часть fid'ов в `BYDAutoFeatureIds` **архитектурно-зависимая** —
  ветвится по `isCanFD` (`getprop sys.car.protocol == "CANFD"`) vs `isToyota` vs default.
  На этой машине `sys.car.protocol=CANFD` → взяли CANFD-ветку. **Другой BYD (не-CANFD или
  Toyota-платформа, напр. Denza/bZ3 joint-venture) требует ДРУГИЕ значения** — нельзя
  переиспользовать эти константы без проверки `sys.car.protocol` на конкретной машине.
- Найдены и **живьём сверены с di+ (100% match, 16/16)**: двери FL/FR/RL/RR, багажник,
  капот, стёкла FL/FR/RL/RR, люк, шторка, давление в шинах FL/FR/RL/RR — включая
  нетривиальные значения (шторка=100%, шины 245-250 кПа), не только тривиальные нули.
  Метод сверки: `di+ getDiPars` (中文 signal names, HTTP на 127.0.0.1:8988) против
  `service call autoservice 5 i32 1001 i32 <fid>` в одном ADB-сеансе.
- Добавлено в `FidRegistry.kt`: `FID_DOOR_FL/FR/RL/RR`, `FID_TRUNK`, `FID_HOOD`,
  `FID_WINDOW_FL/FR/RL/RR`, `FID_SUNROOF`, `FID_SUNSHADE` (все CANFD-ветка, с
  предупреждением в коде), `FID_TIRE_PRESSURE_FL/FR/RL/RR` (не архитектурно-зависимые).
  `CommandDaemon.pushTelemetry` логирует второй чек (`"autoservice check2: ..."`) для
  дверей/багажника/капота/шин рядом со значениями di+ — только сверка, в облако не идёт,
  то же правило что и для B-07's SOC/power check.
- `./gradlew :app:testDebugUnitTest` зелёный, 515 тестов (было 509 в начале сессии).
- Осталось для B-07: собрать сэмплы за drive/charge (не только парковку), окна/люк/шторку
  ещё не завели в лог (fid есть, чек не написан) — при желании добавить аналогично.
  Подробности и статус — [`BACKLOG.md`](BACKLOG.md#кандидаты-не-запланировано) (B-07).

## 2026-07-21: первый шаг по EV_PRO_APP_ANALYSIS.md — WiFi keep-alive + autoservice-сверка

Владелец подтвердил приоритет: (1) в перспективе отказаться от di+, начав со сверки данных,
(2) **важнее** — не дать стоянке «засыпать» без ручного тумблера DiLink. Реализовано и
покрыто тестами (`./gradlew :app:testDebugUnitTest` зелёный, 509 тестов):

- **B-10 (WiFi keep-alive, выключено по умолчанию)**: новый тумблер **Настройки → Cloud Sync →
  «Keep Wi-Fi awake while parked»** (`SettingsRepository.KEY_CLOUD_SYNC_KEEP_WIFI_AWAKE`) →
  `TrackingService.exportDaemonConfig()` пишет `keep_wifi_awake=1` в `voltflow_cmd.conf` →
  `CommandDaemon` каждые ~60 с шлёт `svc wifi enable` (`shouldRefreshWifiKeepalive`, чистая
  функция, покрыта тестами). Ничего не меняли в `start_voltflow_cmd.sh` — конфиг читает сам
  демон, риск рассинхрона asset/tools не тронут.
- **B-07 (autoservice вместо di+, только сверка пока)**: `CommandDaemon.pushTelemetry` теперь
  читает SOC и engine power напрямую через `autoservice` (те же fid, что в `FidRegistry`) и
  логирует их рядом со значениями di+ (`"autoservice check: soc=... (diplus=...) power_kw=...
  (diplus=...)"` в `voltflow_cmd_daemon.log`) — в облако эти значения пока не идут, это только
  сбор доказательств перед переключением по умолчанию.
- Оба тумблера **выключены по умолчанию** — включаются вручную в Настройках перед тестом на
  реальной машине (парковка >9 мин без DiLink-тумблера «Keep network on while parked»; сверка
  autoservice/di+ на реальном drive/charge/park цикле).
- Полный технический разбор конкурента и что из него применимо — в
  [`EV_PRO_APP_ANALYSIS.md`](EV_PRO_APP_ANALYSIS.md); статус задач — в
  [`BACKLOG.md`](BACKLOG.md#кандидаты-не-запланировано) (B-07, B-10 теперь `in-progress`).

## 2026-07-21: реверс-инжиниринг BYD EV Pro (ant0nkr/ev-pro-app)

- Запрос: конкурентное приложение заявляет работу без di+ и «в фоне». Скачан и
  декомпилирован (`jadx`/`apktool`) публичный релиз `byd-ev-pro-2.0.2-bd0e5a2.apk`
  (`com.kramskyi.byd_ev_pro`, Flutter — бизнес-логика в AOT Dart непрозрачна, но
  Android-мост на Kotlin/Java декомпилируется полностью и содержит всю логику
  доступа к авто и выживания процесса). Полный разбор:
  [`docs/EV_PRO_APP_ANALYSIS.md`](EV_PRO_APP_ANALYSIS.md).
- **Без di+ в принципе**: манифест объявляет собственные signature-permissions
  `BYDAUTO_*` и напрямую дергает `ServiceManager.getService("autoservice")`
  (`android.gui.BYDAutoServer`, transact-коды 5/6/7/9/10/11/13 = get/set
  Int/Float/Array/Buffer по `(dev, fid)`) — тот же слой, что уже описан в
  [`DIPLUS_DATA.md`](DIPLUS_DATA.md) как «raw BYD autoservice layer», только без
  HTTP-обёртки di+.
- **«Работает в фоне» — не системный трюк, а watchdog**: отдельный shell-uid
  процесс (`ProcessExemptionController`, тик 30 с) перезапускает и сам Flutter-app,
  и отдельный демон-«vehicled» через `am start`/`app_process`, если `pidof` пуст;
  плюс сам держит WiFi/BT включёнными и молча регистрирует свой
  AccessibilityService через `settings put secure`. Обычный `Service` в приложении
  — стандартный foreground-service с wakelock, никакой магии.
  Демон-с-данными (`byd_ev_pro_veh`, AIDL `IVehicleState`) — отдельный процесс,
  а не то же самое, что даемон команд.
- Own from-scratch ADB client (`AdbClient.java`) в APK — полностью свой ADB
  wire-protocol (CNXN/AUTH/OPEN/OKAY/WRTE/CLSE, RSA-2048 auth) поверх
  `127.0.0.1:5555`, без внешнего `adb`/Termux после разового «Allow USB
  debugging?» — тот же принцип, что `AdbOnDeviceClient` в этом репо.
- FID-таблица / логика распознавания модели авто у конкурента — не хардкод в
  APK, а скачиваемый зашифрованный versioned jar (`byd_evpro_vehicled_classpath`
  → `.../v-<version>.jar`, `libvehicled_crypto.so`) + JSON-пуш
  (`updateFidConfig`) поверх него — поэтому не нужен релиз APK на новую модель/
  сигнал.
- Кандидаты для VoltFlow Mate из разбора — B-07/B-08/B-09 в
  [`BACKLOG.md`](BACKLOG.md#кандидаты-не-запланировано): прямой `autoservice`
  вместо di+ HTTP, разделение `CommandDaemon` на I/O-демон + watchdog,
  server-pushed FID-конфиг вместо хардкода. Не приоритизировано.

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
  **Заменено 2026-07-22:** плоские 120 с давали 124-236 с устаревшего live-статуса после каждой
  парковки. Теперь два порога по типу push'а — `APP_ALIVE_FULL_TTL_MS` (20 с) для записи истории
  и `APP_ALIVE_LIVE_TTL_MS` (5 с) для status-only `live_only`, — плюс таймеры каденса сдвигаются
  только после реально отправленного push'а. См. `docs/REMOTE_COMMAND_DAEMON.md`.

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

## 2026-05-29: Исторический инцидент — смена имени машины ломала очередь

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

**Исправлено 2026-07-14 (`7b37366`).** `flushQueue()` теперь группирует pending rows по
`vehicle_id`, записанному в payload, и отправляет каждую группу с совпадающим
`X-Vehicle-Id`. Старые queued samples доставляются под старым ID, новые — под новым;
переустановка APK и ожидание очистки очереди больше не нужны.

Остаётся только продуктовый эффект: серверная история до и после переименования живёт под
разными `vehicle_id`. Перед сменой по-прежнему обновите `cars.vehicle_alias` в VoltFlow.



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
