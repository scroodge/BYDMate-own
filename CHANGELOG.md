# Changelog

Все заметные изменения BYDMate перечислены здесь.

Формат основан на [Keep a Changelog](https://keepachangelog.com/ru/1.1.0/), версионирование следует [Semantic Versioning](https://semver.org/lang/ru/).

Полные релизы с APK: <https://github.com/scroodge/BYDMate-own/releases>.

> Статус checkout на 2026-07-20: готовится `v0.5.0` (`versionCode 336`). Публикация
> и установка на автомобиль — отдельные шаги; этот выпуск намеренно не устанавливался
> через ADB во время подготовки.

## [0.5.1.1] - 2026-08-04

### Changed
- **Демон больше не отправляет пустые поля как `null`.** Хелпер `putN` писал
  `JSONObject.NULL`, поэтому в каждом пуше присутствовали все ключи — на стоянке около 30
  из 50 ключей `diplus` уходили как литеральный `null`, примерно 800 байт на пуш (на
  порядок больше, чем даёт округление ниже). Теперь хелпер переименован в `putIfPresent`
  и опускает ключ, как в приложении. Побочный эффект: частичный индекс
  `bydmate_telemetry_samples_soh_analytics_idx` с предикатом `telemetry ? 'soh_percent'`
  перестанет включать строки демона без реального замера SoH — раньше они попадали в
  индекс, потому что проверка существования ключа в jsonb истинна и для `null`.
  Безопасность проверена на всех трёх уровнях: поля Zod объявлены
  `.nullable().optional()`; `telemetry-sanitizer.ts` сравнивает через `value != null`, что
  одинаково ловит `undefined`; проверок существования ключа по `telemetry`/`diplus` в
  миграциях всего две (обе по `soh_percent`, и результат запроса не меняется из-за
  условия `between 0 and 100`). Проверки `location ? 'lat'` не затронуты — у демона нет
  GPS, и он и раньше слал `location: {}` вовсе без ключей.
- **Округление чисел Phase 1 добавлено в оба сборщика payload демона.** Приложение
  округляет значения перед отправкой ещё с Phase 1 (`docs/CLOUD_OFFLOAD_PLAN.md`), а
  демон — нет, хотя именно он пишет в облако большую часть суток. Показательный случай:
  `cell_delta_v` в обоих сборщиках считается как `maxCellVoltage - minCellVoltage` и
  давал на проводе `0.019999999999999` (~20 символов) в каждом пуше. Теперь
  `CommandDaemon.roundForWire` округляет напряжения ячеек до 4 знаков, а `kwh_charged`
  до 3 — ровно та точность, которую сервер (`telemetry-sanitizer.ts`) и так применяет,
  так что для бэкенда это no-op и экономит только байты. Набор ключей на проводе не
  изменился: округление сохраняет поведение `putN` с явными `null`.

### Added
- **Строка статуса и кнопка «Установить / запустить демон» в «Дополнительных
  функциях».** Раньше демон-супервизор (`CommandDaemon` + watchdog) поднимался
  только автоматически при старте `TrackingService` — без видимого статуса и без
  возможности перезапустить его вручную, если on-device ADB был настроен позже
  или демон погиб и не поднялся сам. Новая строка показывает live-статус
  (Работает / Работает без watchdog / Не запущен), а кнопка выполняет ту же
  последовательность (подключить ADB → развернуть launcher → проверить →
  перезапустить), что и автоматический супервизор при старте сервиса —
  оба пути теперь используют один и тот же код (`AdbOnDeviceClient.ensureCommandDaemonRunning`),
  вместо двух независимых копий этой логики.

## [0.5.1] - 2026-07-27

### Changed
- **Интервал опроса команд задаётся сервером (`poll_after_seconds`).** Опрос
  `/api/bydmate/commands` каждые 6 с был крупнейшим источником вызовов облачных
  функций: цикл демона не привязан к тому, жив ли app, поэтому машина обращалась к
  серверу ~14 400 раз в сутки — в разы больше, чем весь путь телеметрии, — и, пока
  удалённые команды приостановлены, каждый ответ был пустым. Теперь сервер сообщает
  желаемый интервал (60 с при приостановленных командах, 6 с при активных), а
  `VehicleCommandPoller` и `CommandDaemon` его соблюдают с ограничением 6–300 с.
  Старые сборки поле игнорируют и продолжают опрашивать раз в 6 с — версионирование
  не требуется.
- **Грант быстрого статуса (`live_fast_seconds`) теперь приходит и с ответом на
  телеметрию.** Раньше единственным носителем был опрос команд — именно поэтому его
  и нельзя было замедлить. Сервер вычисляет грант из профиля, уже загруженного для
  аутентификации, так что дополнительных запросов к БД нет. Вход в быстрый режим
  ограничен теперь темпом отправки телеметрии (15 с в движении, 60 с на стоянке и
  зарядке), а продлевают его сами 3-секундные `live_only` пинги.

### Added
- **Экспериментальный автоматический keep-alive Wi-Fi на стоянке.** Новый тумблер
  **Настройки → Cloud Sync → «Keep Wi-Fi awake while parked»** — при включении демон
  каждые ~60 с посылает `svc wifi enable`, чтобы связь с облаком не терялась после
  ~9 минут парковки без ручного тумблера DiLink «Keep network on while parked».
  Выключено по умолчанию.
- **Сверка `autoservice` ↔ di+ в логе демона (диагностика, источник данных пока не
  меняется).** `CommandDaemon` логирует SOC, мощность двигателя, состояние
  дверей/багажника/капота и давление в шинах, прочитанные напрямую через
  `autoservice`, рядом со значениями di+ (`"autoservice check"` /
  `"autoservice check2"` в `voltflow_cmd_daemon.log`) — сбор доказательств перед
  возможным отказом от di+; в облако эти значения пока не отправляются.
- `FidRegistry` пополнен 16 новыми fid (двери, багажник, капот, стёкла, люк,
  шторка, давление в шинах), найденными в вендорском SDK `android.hardware.bydauto`
  (декомпилирован из `/system/framework/framework.jar` реальной машины) и живьём
  сверенными с di+ 16/16. Часть значений архитектурно-зависима (CANFD/Toyota/
  default-ветки) — см. предупреждение в коде `FidRegistry.kt`.
- **Демон перезапускает основное приложение при крэше.** Вотчдог
  (`tools/start_voltflow_cmd.sh`) теперь проверяет каждые ~30 с, жив ли процесс
  `dev.scroodge.cloudevmate`; если нет — поднимает его через
  `am start-foreground-service` + `am start` с cooldown 60 с, чтобы не долбить
  реально сломанное приложение. Закрывает пробел: жёсткий краш/OOM не был
  покрыт ни `onTaskRemoved`, ни boot receiver.
- **Определение «зависших» значений di+ в логе демона (диагностика).**
  `CommandDaemon` отслеживает сигнатуру ключевых полей di+ (soc/power/mileage/
  voltage12v/gun state) и, если она не меняется 3+ минуты при наличии признаков
  движения (зарядка или изменение SOC по `autoservice`), пишет в лог
  `"di+ value-stale=true"`. Обнаружено вживую 2026-07-23: di+ отдавал
  `soc=55, power=0.0` без изменений 11+ минут во время реальной зарядки. Пока
  только лог — на то, что отправляется в облако, не влияет.

### Changed
- **Drive latch сокращён с 10 минут до 2 (`CloudTelemetryCadence.DRIVE_LATCH_MS`).**
  Латч держит Cloud Sync на 1 Гц после D/R/N или движения, чтобы кратковременный
  **P** на светофоре не рвал поездку. 10 минут были заметно длиннее любой остановки
  на перекрёстке: каждая реальная парковка стоила 10 минут трафика на 1 Гц, прежде
  чем возвращался 30-секундный parked heartbeat. 2 минуты по-прежнему поглощают
  stop-and-go, но экономия на стоянке возвращается примерно в 5 раз быстрее.
  Требует проверки на машине: цикл «поездка → парковка», cadence должна упасть до
  30 с примерно через 2 минуты после остановки.
- Остальной pipeline телеметрии/команд не изменился — кроме латча этот релиз
  добавляет только диагностику и опциональный keep-alive, оба выключены/пассивны
  по умолчанию для существующих установок.
- `shouldUseAutoserviceFallback` теперь учитывает не только последнее известное
  состояние передачи (`gear`), но и `gunState` (2–5 = подключена зарядка), плюс
  cold-start кейс: если di+ ни разу не ответил за время работы процесса, демон
  берёт один живой прямой autoservice-отсчёт `FID_GUN_CONNECT_STATE` (fid 1009)
  как доказательство вместо бессрочного молчания.

## [0.5.0] - 2026-07-20

### Added
- **Быстрый живой статус VoltFlowMate.** Пока открыт экран автомобиля, Mate отправляет
  облегчённый снимок каждые **3 секунды**. SOC, мощность зарядки, передача, движение и
  состояние пистолета обычно появляются в VoltFlowMate за 2–5 секунды вместо 15–60 секунд.
- **Быстрый статус при parked/off.** Shell-демон немедленно сообщает подключение или
  отключение зарядного пистолета даже после остановки приложения BYD. В активном live-view
  он также передаёт статус каждые 3 секунды, не создавая лишних строк истории.
- **Локальные агрегаты телеметрии.** Приложение собирает почасовые агрегаты в своей базе и
  отправляет их накопительными, retry-safe блоками. Это уменьшает работу облака при сохранении
  точности SOC, мощности, температуры, рекуперации и тяговой энергии.
- **Основа для клиентских сводок поездок.**

### Changed
- **Меньше лишней телеметрии без потери live-статуса.**

### Fixed
- Переходы **парковка → зарядка** и **зарядка → парковка/отключение** теперь сразу обновляют
  live-статус в VoltFlow, не ожидая очередного batch flush и не сбивая штатный 60-секундный
  flush зарядки.

## [0.4.10] - 2026-07-19 Внутренний релиз
## [0.4.9] - 2026-07-16 Внутренний релиз
## [0.4.8] - 2026-07-16

### Fixed
- Исправлена передача последних телеметрических данных при быстрой последовательности
  **D → P → выключение автомобиля**. Подтверждённый переход в выключенное состояние теперь
  немедленно отправляет накопленный финальный сэмпл до принудительной остановки процесса BYD.
- Сохранена защита от ложного завершения поездки при кратком переходе в P во время манёвров.

### Changed
- Очередь Cloud Sync теперь гарантированно выполняет постановку сэмпла в локальную очередь до
  запуска flush-запроса. Это предотвращает перенос последнего сэмпла до следующего запуска машины.

## [0.4.7] - 2026-07-08

### Added
- **Облачная синхронизация поездок из energydata** (`TripSummaryCloudSync`): после
  локального импорта из `EC_database.db` новые поездки отправляются в VoltFlow
  (`POST /api/bydmate/trip-summaries`, те же ключ API и имя авто, что и у телеметрии).
  Работает **без ADB** — машины, которые ведут energydata (например, Yuan UP 2025 /
  DiLink 5), получают историю поездок и расхода в облаке. Отправка идёт только при
  источнике данных ENERGYDATA (машины с ADB-телеметрией не дублируют поездки),
  батчами до 300 записей, с водяным знаком по `start_ts` — потерянный ответ сервера
  безопасно повторяется (сервер делает upsert по времени старта). Нулевые (0 км)
  записи-стоянки не отправляются. Учитывается режим «только Wi-Fi».

## [0.4.6] - 2026-07-06

### Added
- **Кнопка «Диагностика BYD» на главном экране** (карточка VoltFlow Sync, рядом с
  «Отправить тест»): тот же отчёт о хранилище, что и в Настройках — доступен из
  gateway-режима, где вкладки «Настройки» нет. Локализована (ru/en/be).

## [0.4.5] - 2026-07-05

### Added
- **Кнопка «Диагностика хранилища BYD»** (Настройки → Данные): одним нажатием собирает
  отчёт — разрешения на хранилище, наличие `/storage/emulated/0/energydata` и `.db` файлов,
  структура и количество строк таблицы `EnergyConsumption`, состояние локальной базы.
  Отчёт можно выделить и скопировать в буфер («Копировать»). Работает **без ADB** — нужно
  для удалённой проверки, пишет ли конкретная модель BYD базу energydata (Yuan UP её не
  ведёт, Leopard 3 — ведёт).

## [Unreleased]

## [0.3.9.5] - 2026-06-13

### Fixed
- **Демон и VoltFlow Mate больше не дублируют телеметрию:** `CommandDaemon` теперь
  пушит 60-секундный heartbeat только когда приложение НЕ работает. `TrackingService`
  пишет маяк `voltflow_mate_heartbeat` (epoch) при каждой отправке в облако; демон читает
  его и пропускает свой push, пока маяк свежий (< 120 с). Раньше при живом приложении обе
  стороны слали данные параллельно — лишние сэмплы и риск фантомных поездок при манёврах.
  Демон существует только для окна, когда BYD убивает приложение (парковка/сон). Опрос
  команд остаётся всегда активным (команды идемпотентны и подтверждаются сервером).

## [0.3.9.4] - 2026-06-13

### Added
- **`mate_version` в телеметрии:** каждый payload (TrackingService и CommandDaemon) теперь содержит
  поле `mate_version` с версией APK. На сервере значение сохраняется в
  `bydmate_live_snapshots.mate_version` — можно видеть, какая версия VoltFlow Mate установлена
  на каждом головном устройстве.

### Fixed
- **Фантомные поездки при манёврах у зарядки:** фильтр мусорных поездок (`bydmate_discard_trip_if_junk`)
  теперь отбрасывает трипы с `distance_km ≤ 0.1` и `max_speed_kmh ≤ 3` независимо от числа сэмплов.
  Раньше первым условием была проверка `sample_count < 3`; трипы с 10+ сэмплами, нулевым расстоянием
  и скоростью до 2 км/ч (переключение D→R→P при выезде с зарядки) попадали в историю.

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

[Unreleased]: https://github.com/scroodge/BYDMate-own/compare/v0.4.8...HEAD
[0.3.6]: https://github.com/scroodge/BYDMate-own/compare/v0.3.5...v0.3.6
[0.3.5]: https://github.com/scroodge/BYDMate-own/compare/v0.3.4...v0.3.5
[0.3.4]: https://github.com/scroodge/BYDMate-own/compare/v0.3.3...v0.3.4
[0.3.3]: https://github.com/scroodge/BYDMate-own/compare/v0.3.2...v0.3.3
[0.3.2]: https://github.com/scroodge/BYDMate-own/compare/v0.3.1...v0.3.2
[0.3.1]: https://github.com/scroodge/BYDMate-own/compare/v0.3.0...v0.3.1
[0.3.0]: https://github.com/scroodge/BYDMate-own/compare/v0.2.5...v0.3.0
[0.2.5]: https://github.com/scroodge/BYDMate-own/compare/v0.2.2...v0.2.5
[0.2.2]: https://github.com/scroodge/BYDMate-own/releases/tag/v0.2.2
