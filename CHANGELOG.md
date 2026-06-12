# Changelog

Все заметные изменения BYDMate перечислены здесь.

Формат основан на [Keep a Changelog](https://keepachangelog.com/ru/1.1.0/), версионирование следует [Semantic Versioning](https://semver.org/lang/ru/).

Полные релизы с APK: <https://github.com/scroodge/BYDMate-own/releases>.

## [Unreleased]

## [0.3.9.3] - 2026-06-12

### Added
- **Напоминание о фоновом доступе после обновления:** при первом открытии после обновления APK
  появляется диалог «VoltFlow Mate обновлён до vX.X.X» с напоминанием проверить настройку
  `Disable background Apps → OFF` в DiLink — после обновления DiLink может сбросить этот
  переключатель. Диалог открывает `AppStartManagement` напрямую; повторно не показывается.

## [0.3.9.2] - 2026-06-12

### Fixed
- **`CommandDaemon` не разбивает поездки:** heartbeat-push каждые 60 с пропускается, пока
  машина едет (`IternioIntervalPolicy.DRIVING`). DiPars в reduced-payload режиме возвращает
  `gear=1` (P) даже при движении, что закрывало активный trip в VoltFlow каждые ~60 с и
  порождало десятки 1-минутных фрагментов вместо одной поездки. Демон теперь молчит в
  движении — VoltFlow Mate отправляет данные сам на частоте 1 Гц.

## [0.3.8] - 2026-06-10

### Added
- **`CommandDaemon` — shell-uid daemon, survives car power-off:** headless `app_process` daemon
  launched by `start_voltflow_cmd.sh` as uid shell (identical mechanism to DI+ `aps_diplus`).
  Polls cloud for commands every 2.5 s and executes them via DiPlus `127.0.0.1:8988/sendCmd`.
  Survives BYD `collectPowerOffEvent` force-stop that kills all regular app processes.
- **Telemetry push while car is off:** `CommandDaemon` reads DiPlus `getDiPars` and POSTs to
  `/api/bydmate/telemetry` every 60 s — `bydmate_live_snapshots` updated continuously even when
  app is dead and car is off (`PWR=0`). Proven on Yuan Up 2024 (DiLink 3.0).
- **Auto-update via APK path change:** `start_voltflow_cmd.sh` watchdog detects when `pm path`
  changes (adb install -r), kills the running daemon, and respawns on the new code automatically
  within ~30 s. No manual restart needed after APK updates.
- **Extended `CommandAllowlist`:** added `sentry`, `sentry_autostart`, `screen_off`,
  `windows_close`, `ac_temp_up`, `ac_temp_down` (confidence: apk) and comfort-set guesses
  `ac_temp`, `fan_level`, `trunk`, `defrost`, `rear_defrost`, `seat_heat_driver`,
  `seat_heat_pass`, `steering_heat`, `mirror_fold`, `find_car`, `honk`, `flash_lights`,
  `charge_port` — fully synced with `command-allowlist.ts` and `BYD_MA/COMMAND_ALLOWLIST.md`.

### Changed
- `exportDaemonConfig()` in `TrackingService` writes cloud credentials to
  `getExternalFilesDir(null)/voltflow_cmd.conf`; the shell daemon reads them from
  `/data/local/tmp/voltflow_cmd.conf` (copied by the launcher on each restart).

### Notes
- DiPlus `迪加`-phrases require car `PWR ≥ 1` to actuate hardware. At `PWR=0` the daemon
  correctly acks commands (they are queued), but physical actuation happens only when the car
  is powered on. This is a DiLink/T-BOX architectural constraint, not a daemon bug.
- Network keep-alive: enable **"Keep network on while parked"** in the head-unit settings;
  otherwise WiFi drops ~9 min after power-off and the daemon loses cloud connectivity.

## [0.3.6] - 2026-06-05

### Fixed
- **Графики поездки без дыр в пробке:** drive latch держит Cloud Sync на **1 Гц** ещё 10 мин после D/движения, даже если DiPars кратко отдаёт **P**; `speed_kmh`/`power_kw` остаются в payload (включая 0 км/ч).

## [0.3.5] - 2026-06-05

### Added
- **`SohResolver`:** cloud/UI SoH from live BMS `FID_SOH`, last charge `battery_health` snapshot, or persisted `last_known_soh_percent` cache.

### Changed
- Cloud Sync sends **`soh_percent` while parked** when a cached BMS value exists (VoltFlow live SOH no longer stuck on `—`).
- Autoservice **auto-enables** when ADB/autoservice responds (aligns with upstream BYDMate ADB-on behavior).
- Throttled battery snapshot read every **15 min** when not charging, so SoH can refresh outside charge sessions.

### Fixed
- VoltFlow never received numeric `soh_percent` because cloud sync only read BMS SoH during charging and idle payloads omitted the field.

## [0.3.4] - 2026-06-04

### Added
- **Подтверждение доставки Cloud Sync:** APK разбирает ACK от VoltFlow (`inserted_count`, `duplicate_count`, `skipped_stale_count`, `ok`) и снимает батч с очереди только при полном подтверждении; иначе retry.
- Диагностика `cloud_sync_last_ack` в статусе синхронизации (например `15 sent, 12 ins, 3 dup, 0 skip`).

### Changed
- При отставании очереди (>15 samples) flush отправляет несколько батчей подряд; повторный flush не пропускается, если предыдущий ещё выполняется.

### Fixed
- Пропуски на графиках SOC в VoltFlow при догоне очереди после сетевых задержек (в паре с серверным batch backfill).

## [0.3.3] - 2026-06-03

### Changed
- Убрана настройка «Интервал отправки» из UI: cadence задаётся автоматически (30 с на парковке, 1 с / 15 s batch в движении и на зарядке).
- VoltFlow Cloud Sync on parked (gear P): heartbeat **30 s** instead of 5 min so live status (Online) updates promptly in the web app.
- Parked payloads include minimal `diplus` (`gear`, `charge_gun_state`, `speed_kmh`) for VoltFlow driving/parked detection.
- Cloud Sync state classification uses `IternioIntervalPolicy` (gear + charge gun); gear changes enqueue and flush immediately while parked.

## [0.3.2] - 2026-06-01

### Added
- **Автообновление APK:** при запуске (если включено) проверка GitHub Releases `scroodge/BYDMate-own`, диалог с release notes, скачивание и установка по согласию пользователя. Реализация портирована из upstream [AndyShaman/BYDMate](https://github.com/AndyShaman/BYDMate); блок «Обновления» на экране шлюза (переключатель + ручная проверка).
- Подключение к VoltFlow по **6-значному коду** из веб-настроек (`VoltflowLinkClient`, redeem URL от telemetry endpoint). Ручной API key — в **Дополнительно** на экране шлюза и в Cloud Sync.

## [0.3.1] - 2026-05-31

### Fixed
- Cloud Sync больше не теряет 1 Hz samples во время HTTP flush: enqueue и flush разделены, каждый секундный poll ставит sample в очередь даже если предыдущий batch ещё отправляется. Раньше `cloudInFlight` пропускал тики и в VoltFlow попадали ~15 точек на burst вместо ~15 точек в секунду.

### Changed
- Cloud Sync batch-запросы стали устойчивее на медленном backend: таймауты чтения и записи увеличены до 45 секунд, charging flush отправляет по одной минутной пачке до 60 samples за тик, а HTTP-ошибки теперь сохраняют короткое тело ответа сервера для диагностики.

## [0.3.0] - 2026-05-30

### Changed
- Cloud Sync при движении/зарядке: enqueue 1 s, HTTP flush каждые 15 s (batch до 15 samples); idle heartbeat 5 min; slim idle payload; optional GPS privacy (`cloud_sync_omit_gps`).

## [0.2.5] - 2026-05-26

### Added
- Автоматическая генерация changelog из git-коммитов: `release/generate-changelog.js` собирает изменения после последнего тега `v*`, группирует их по секциям Keep a Changelog и умеет считать следующую SemVer-версию.
- Gradle-задача `releaseChangelog` для релизного процесса:
  `./gradlew releaseChangelog`, `./gradlew releaseChangelog -PdryRun=true`, `./gradlew releaseChangelog -PreleaseVersion=0.2.3`.

### Changed
- Cloud Sync во время зарядки теперь собирает телеметрию локально примерно 1 раз в секунду и отправляет накопленные samples одним batch payload раз в минуту; при отсутствии сети или Wi-Fi-only без Wi-Fi точки остаются в локальной очереди до следующего успешного flush.
- Cloud Sync теперь работает щадяще для сервера и базы: локальный polling остаётся 1 раз в секунду, но при движении в локальную очередь Cloud Sync попадает примерно 1 sample в минуту, после чего очередь отправляется в облако по обычному интервалу синхронизации.
- Отключена неиспользуемая ABRP/Iternio-телеметрия из runtime-пути и скрыта из настроек, чтобы VoltFlow Mate работал только как шлюз VoltFlow Cloud Sync.
- Обновлена инструкция релиза в README: добавлен шаг генерации `CHANGELOG.md`, dry-run режим, ручной выбор версии и памятка по Conventional Commits.
- Уточнены README и release-страница: VoltFlow Mate описан как шлюз передачи данных из BYDMATE в облако VoltFlow, обновлены шаги установки и требования к BYDMATE.
- Обновлены ссылки на релизы форка `scroodge/BYDMate-own`.
- Обновлены SUPPORT/FUNDING и служебные настройки репозитория.
- NOTICE переведён с CloudEV Gateway на актуальное имя VoltFlow Mate Gateway.

## [0.2.2] - 2026-05-21

### Changed
- Первый публичный релиз под единым брендом VoltFlow Mate.
- Приложение переименовано из CloudEV Gateway в VoltFlow Mate.
- APK переименован в формат `VoltFlow-Mate-v<version>.apk`.
- Обновлены пользовательские тексты, уведомления, настройки и экран шлюза под бренд VoltFlow Mate.
- Обновлена проверка обновлений: приложение смотрит на GitHub Releases репозитория `scroodge/BYDMate-own`.
- Обновлены README, release-страница и release notes для установки на BYD DiLink.

### Notes
- Приложение остаётся форком BYDMate и использует отдельный Android `applicationId`: `dev.scroodge.cloudevmate`, поэтому может стоять рядом с оригинальным BYDMate.
- Для работы шлюза нужен установленный и настроенный BYDMATE, из которого VoltFlow Mate берёт live-данные машины.

[Unreleased]: https://github.com/scroodge/BYDMate-own/compare/v0.3.6...HEAD
[0.3.6]: https://github.com/scroodge/BYDMate-own/compare/v0.3.5...v0.3.6
[0.3.5]: https://github.com/scroodge/BYDMate-own/compare/v0.3.4...v0.3.5
[0.3.4]: https://github.com/scroodge/BYDMate-own/compare/v0.3.3...v0.3.4
[0.3.3]: https://github.com/scroodge/BYDMate-own/compare/v0.3.2...v0.3.3
[0.3.2]: https://github.com/scroodge/BYDMate-own/compare/v0.3.1...v0.3.2
[0.3.1]: https://github.com/scroodge/BYDMate-own/compare/v0.3.0...v0.3.1
[0.3.0]: https://github.com/scroodge/BYDMate-own/compare/v0.2.5...v0.3.0
[0.2.5]: https://github.com/scroodge/BYDMate-own/compare/v0.2.2...v0.2.5
[0.2.2]: https://github.com/scroodge/BYDMate-own/releases/tag/v0.2.2
