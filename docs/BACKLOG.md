# Backlog — VoltFlow Mate

Actionable, prioritized task list. Milestone-level view lives in
[`ROADMAP.md`](ROADMAP.md); shipped detail in [`CHANGELOG.md`](../CHANGELOG.md);
engineering notes in [`project-notes.md`](project-notes.md).

- **Обновлено:** 2026-07-20 · база: `main` @ `0.5.0` (`versionCode 336`, подготовлен к публикации).
- Приоритет: **P0** блокер · **P1** скоро · **P2** когда дойдут руки.
- Статус: `todo` · `in-progress` · `blocked` · `done`.
- Оценка: S (<½ дня) · M (1–2 дня) · L (>2 дня / кросс-репо).

> Нет формального внешнего бэклога — пункты выведены из кода, git и заметок.
> Владелец расставляет приоритеты; спорные помечены **[verify]**.

---

## P1 — ближайшее

| ID | Задача | Обл. | Оц. | Статус | Критерий готовности |
|----|--------|------|-----|--------|---------------------|
| B-03 | Непрерывность истории телеметрии при переименовании авто | cloud / **VoltFlow** | L | `blocked` | Слить историю до/после rename на стороне Supabase (`vehicle_id` — часть ключа). Блок: логика в VoltFlow/EvAcChargeTimer, нужна координация репо. Потеря данных уже устранена (`7b37366`). |
| B-06 | Cloud-side Phase 4: client-owned trip rollups | cloud / **VoltFlow** | L | `todo` | Добавить `bydmate_apply_client_trip` и route/schema wiring; затем подтвердить, что `client_trip`/`trips` применяются идемпотентно, а старые APK и daemon продолжают работать по прежнему пути. |

---

## P2 — когда дойдут руки

| ID | Задача | Обл. | Оц. | Статус | Критерий готовности |
|----|--------|------|-----|--------|---------------------|
| B-05 | Гард синхронности `start_voltflow_cmd.sh` | tooling / ci | S | `done` | ✅ Тест `LauncherAssetSyncTest` падает, если `tools/…` ≠ `app/src/main/assets/…`. Зелёный при текущих (идентичных) файлах; проверено `./gradlew :app:testDebugUnitTest`. Выпущен в `0.4.8`. |

> **Принцип (2026-07-16):** APK — **шлюз для облака**. Речь о *продуктовой поверхности,
> не о вычислениях*:
> - **Нет** аналитического UI в APK (экраны трендов/агрегатов, regen/traction) — вся
>   презентация аналитики в VoltFlow/облаке. ~~**B-04** экран аналитики~~ **не делаем**.
> - **Можно/полезно:** edge-вычисления и временная локальная БД для пред-агрегации/буфера
>   на устройстве, **если это снижает нагрузку на облако**. Перенос расчётов в APK ради
>   разгрузки cloud compute — ОК (это не аналитический продукт в приложении).

---

## Кандидаты (не запланировано)

Из анализа конкурента [`EV_PRO_APP_ANALYSIS.md`](EV_PRO_APP_ANALYSIS.md) (BYD EV Pro,
2026-07-21) — не приоритизировано, решение за владельцем.

| ID | Задача | Обл. | Оц. | Статус | Критерий готовности |
|----|--------|------|-----|--------|---------------------|
| B-07 | Прямой доступ к `autoservice` вместо di+ HTTP | app / daemon | L | `in-progress` | ✅ 2026-07-21: `CommandDaemon` логирует SOC + engine power через `autoservice` рядом со значениями di+ (`"autoservice check: ..."`). ✅ 2026-07-22: декомпилирован `/system/framework/framework.jar` с реальной машины → найден полный вендорский SDK `android.hardware.bydauto.*` (`BYDAutoFeatureIds`); 16 новых fid (двери FL/FR/RL/RR, багажник, капот, стёкла FL/FR/RL/RR, люк, шторка, давление в шинах FL/FR/RL/RR) добавлены в `FidRegistry` и **живьём сверены с di+ 16/16 = 100% match** (включая нетривиальные значения: давление 245-250 кПа, шторка=100%) на этой машине (Leopard 3, DiLink 3.0, `sys.car.protocol=CANFD`). Двери и шины уже логируются вторым чеком (`"autoservice check2: ..."`) в `voltflow_cmd_daemon.log`. **Важно:** 12 из 16 значений архитектурно-зависимы (`isCanFD`/`isToyota`/default-ветки в `BYDAutoFeatureIds`) — валидны только для CANFD-платформы, см. предупреждение в `FidRegistry.kt`. Осталось: собрать сэмплы за более долгий период (drive/charge, не только парковка), затем заменить `diplus.*` поля в пейлоаде на autoservice-источник. ✅ 2026-07-22 (позже): **парковка/зарядка без di+ реализована** — `CommandDaemon.buildAutoserviceFallbackPayload` шлёт телеметрию (SOC, мощность, gun state, 12V, двери/багажник/капот, шины, SoH, kWh) целиком через `autoservice`, когда di+ не отвечает (`DIPLUS_STALE_MS` — правильно отличает «di+ завис» от «di+ никогда не запускался», поскольку `latestData` раньше не обнулялся при неудачном fetch) **и** последнее известное состояние — парковка/зарядка (`shouldUseAutoserviceFallback`; тесты зелёные, 516). За рулём поведение не меняется — di+ остаётся источником, пока не собран полный каталог сигналов (скорость/климат/передача и т.д., см. `atomic-humming-hoare.md` «Stage A»). ✅ **2026-07-23: проверено живьём** — di+ умер сам, без `force-stop`: процесс `aps_diplus` жив, но порт 8988 не слушает (`poll error: timeout` 18+ мин). Fallback отработал: `telemetry HTTP 200`, SOC растёт на зарядке. Попутно исправлен холодный старт: `lastKnownGear`/`lastKnownGunState` живут только в памяти и обнуляются при рестарте демона, поэтому если di+ не ответил **ни разу** за запуск, гейт не открывался никогда → добавлено прямое чтение gun state через `autoservice` как доказательство (`cffd234`). **Новое, важное:** корпус из 1236 сэмплов `autoservice check:` показал, что di+ отдаёт **замороженные** значения на зарядке (`soc=55, power=0.0` 11+ мин, пока реально 60→62 при −4 кВт) — при этом `fetch()` успешен, так что `diPlusFresh` такое не ловит. Когда di+ заведомо жив (его `power` совпадает с autoservice), SOC совпадает **точно**, дельта 0 на диапазоне 55…77. Отсюда следующий шаг: сделать autoservice **основным** источником для валидированных полей (план `ok-what-is-next-cryptic-widget.md`). ✅ **2026-07-23 (позже): сделано.** Дашборд машины подтвердил autoservice (75% dash vs 74% autoservice vs 55% замороженный di+) — адюдикация пройдена. `buildTelemetryPayload`/`pushTelemetry` объединены в один билдер с посистемным приоритетом (autoservice → di+ fallback на каждое поле, не всё-или-ничего); заодно исправлены два реальных бага: `is_charging` больше не залипает от замороженного `chargingStatus`, когда autoservice видит актуальный gun state (`isChargingFrom`), и `is_parked` больше не противоречит себе между путями (`isParkedFrom`, единая логика `gear==1`, fallback на `!isCharging`). Добавлен диагностический (лог-онли) сигнал застревания di+ `isDiPlusValueStale`/`diPlusValueSignature` — пока не влияет на решения о пуше. Тесты 533/533 зелёные. Живьём проверено: di+ down → merged payload корректно шлёт autoservice-поля, di+-only поля (`pwr_state` и т.п.) — `null`. **Не проверено живьём:** ветка «оба источника живы одновременно» — di+ (`aps_diplus`, shell-uid нативный сервис) на этой машине завис намертво в этой сессии, порт 8988 не поднялся ни после `am start` реальной launcher-активности (`NaviActivity`), ни после `am force-stop` пакета (сервис не относится к uid приложения, force-stop его не касается); `kill -9`/ребут сознательно не делали. Следующая живая проверка — как только di+ на какой-то машине снова ответит. |
| B-08 | Разделить `CommandDaemon` на I/O-демон + watchdog | daemon | M | `in-progress` | **Уточнение 2026-07-22:** этот разрез уже существовал структурно — `start_voltflow_cmd.sh` (shell-процесс) отдельно от `CommandDaemon` (`app_process`), демон никогда не содержал логику респауна. Реальный разрыв с PEC — watchdog следил только за демоном, не за самим приложением. ✅ Сделано: watchdog теперь проверяет `pidof dev.scroodge.cloudevmate` в своём 30-секундном цикле и перезапускает через `am start`/`am start-foreground-service` при отсутствии (cooldown 60 с, `voltflow_app_relaunch_ts`) — закрывает случай hard crash/OOM, не покрытый `onTaskRemoved`/boot receiver. `tools/` и `assets/` синхронны (`cmp` зелёный), 515 тестов зелёные (изменение только в shell, Kotlin не тронут). Осталось (сознательно отложено): перенос WiFi keep-alive тика из `CommandDaemon` в watchdog (сейчас зависит от живости демона) и binder-канал для мгновенной проверки (аналог `byd_evpro_pec_control`) — оба без явной необходимости сейчас. |
| B-09 | FID-таблица как server-pushed JSON, не хардкод в APK | app / cloud | M | `todo` [verify] | Новый сигнал/модель авто добавляется конфигом на сервере, без релиза APK; старые установки без конфига продолжают работать на текущих хардкод-значениях (fallback). |
| B-10 | Автоматический keep-alive WiFi на стоянке | daemon | S | `in-progress` | ✅ 2026-07-21: тумблер **Настройки → Cloud Sync → «Keep Wi-Fi awake while parked»** → `keep_wifi_awake=1` в `voltflow_cmd.conf` → демон каждые ~60 с шлёт `svc wifi enable` (`CommandDaemon.shouldRefreshWifiKeepalive`, тесты зелёные). Выключено по умолчанию. ✅ **2026-07-23: проверено живьём** — тик `wifi keepalive: svc wifi enable (exit=0)` идёт стабильно каждые ~60 с в `voltflow_cmd_daemon.log`, пережил рестарт и демона, и приложения. Осталось: подтвердить именно длинную стоянку >9 мин без ручного тумблера DiLink «Keep network on while parked». |

**B-07a direct-only telemetry cutover (2026-07-23):** `TrackingService`,
`AutoserviceChargingDetector`, and `CommandDaemon` now use the app's own
`autoservice` engine as their only telemetry source. Di+ remains strictly for
stall-sentry and `sendCmd` actuation; no `diplus` block is produced by direct paths.
The explicit unsupported-on-`way` contract is: vehicle speed, gear/park state,
odometer, battery/cell/cabin/outside temperatures, climate, windows, drive mode,
and any unvalidated fid. Those fields remain null/unknown until an on-car validated
direct reader exists. This intentionally makes non-charging parked remote commands
fail closed (`gear_unknown`) rather than consult di+. A vendor-SDK speed probe caused
the `autoservice` Binder to return `Broken pipe`; the service recovered, but that
transaction is excluded pending a safe, live-validated reader design.

---

## Техдолг / риски (перенесены из ROADMAP)

- ⚠️ **Packaging-gotcha** — asset и `tools/` копии лаунчера обязаны совпадать → **B-05**.
- ⚠️ **Миграции Supabase** — `db push` пропускает применённые; всегда новая миграция.
- ⚠️ **Кросс-репо контракт** — ingest/discard/pairing живут в VoltFlow; менять согласованно.

## Done (недавно закрыто)

- ✅ **B-01** Выпуск `0.4.8` — опубликован тег `v0.4.8` (`versionCode 333`) 2026-07-16;
  раздел релиза зафиксирован в CHANGELOG.
- ✅ **B-02** Источник обновлений: решено оставить GitHub Releases — in-app обновление
  (`UpdateChecker` → `.../releases/latest`, `UpdateDialog`, авто-download) уже работает;
  `mate_app_releases` — только для VoltFlow-web. Кода менять не нужно.
- ✅ **B-05** Гард `LauncherAssetSyncTest` — тест падает при рассинхроне двух копий
  `start_voltflow_cmd.sh`. Выпущен в `0.4.8`.
- ✅ **B-00** Потеря батча при смене `cloud_sync_vehicle_id` — `7b37366`, с регресс-тестом.
- ✅ Чистка мёртвого кода (~280 строк) — `7b37366`.
- ✅ Переезд спаренных авто на `voltflow.life` — `e2cd59b`.
