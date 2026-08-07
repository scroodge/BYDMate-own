# Roadmap — VoltFlow Mate

Дорожная карта форка BYDMate для BYD DiLink. Источник правды по деталям —
[`CHANGELOG.md`](../CHANGELOG.md) (что уже вышло) и
[`docs/project-notes.md`](project-notes.md) (инженерные заметки и инциденты).
Этот файл даёт единый взгляд: что сделано, что в работе, что рассматривается.

- **Текущая версия кода в `main`:** `0.5.1.1` (`versionCode 338`), tag `v0.5.1.1`
  (2026-08-04).
- **Обновлено:** 2026-08-07.
- Легенда: ✅ сделано · 🔧 в работе / `[Unreleased]` · 🧭 кандидат (не запланирован) · ⚠️ риск / долг.

---

## 🔧 В работе

- 🔧 **B-10: keep-alive Wi-Fi на стоянке.** Функция выпущена выключенной по умолчанию;
  нужна проверка на машине >9 минут с daemon log и свежими cloud snapshots.
- 🔧 **B-11: context-free wakelock shell-демона.** Незакоммиченная работа переводит
  suspend blocker на `IPowerManager` с sysfs fallback; до слияния нужны focused tests и
  живое подтверждение startup/acquire/release.

---

## ✅ Сделано (по вехам)

### Cloud Sync и pairing
- ✅ HTTPS-ingest телеметрии с `X-API-Key` / `X-Vehicle-Id`, ACK-проверка батчей.
- ✅ 6-значный pairing через `link-code` / `redeem` (TTL 10 мин), ключ в «Дополнительно».
- ✅ Каденс: движение/зарядка — сэмпл 1 с, флаш 15 с; charging-bulk (<98% SOC) — сэмпл 10 с,
  флаш 60 с; хвост ≥98% — сэмпл 1 с; стоянка — heartbeat 30 с.
- ✅ GPS privacy (`cloud_sync_omit_gps`), «только Wi-Fi», отбраковка плохого GPS (>30 м).
- ✅ `mate_version` в каждом payload → видно версию APK на каждом авто (v0.3.9.4).
- ✅ Облачная синхронизация поездок из `energydata` без ADB (`TripSummaryCloudSync`, v0.4.7).
- ✅ `live_only` для неизменившейся стоянки, клиентские hourly rollups и GPS corridor thinning;
  server-side hourly path проверен в production.
- ✅ **Client-owned trip rollups (Phase 4).** APK и cloud-side
  `bydmate_apply_client_trip` применены и совместимы со старыми APK; новый путь подтверждён
  на реальной поездке. Детали и доказательства — в [`CLOUD_OFFLOAD_PLAN.md`](CLOUD_OFFLOAD_PLAN.md#phase-4--apk-owned-trips-).
- ✅ Переходы park/charge сразу обновляют live status, а открытый live-экран получает
  `live_only`-статус каждые 3 секунды (v0.4.9–0.4.10).

### Parked/off remote commands
- ✅ CommandDaemon как shell-uid `app_process`: переживает force-stop при parked/off,
  читает DiPlus на `127.0.0.1:8988`, poll/ack команд через VoltFlow.
- ✅ Демон и приложение не дублируют телеметрию (heartbeat-маяк, v0.3.9.5).
- ✅ Single-instance lock и очистка stale-демона в лаунчере (v0.4.0).
- ✅ При car-off daemon сразу обрабатывает смену gun state; в live fast mode отправляет
  `live_only`-статус каждые 3 секунды, не вытесняя 60-секундный history cadence (v0.4.10).

### Диагностика и UX
- ✅ «Диагностика хранилища BYD» (Настройки) и кнопка в Gateway-режиме (v0.4.5–0.4.6).
- ✅ Диалог-напоминание `Disable background Apps → OFF` после обновления APK (v0.3.9.3).
- ✅ Локализация ru/en/be.

### Качество данных (сторона VoltFlow/Supabase)
- ✅ Фильтр фантомных поездок `bydmate_discard_trip_if_junk` v2 (Rules A/B/C, v0.3.9.5).

---

## 🧭 Кандидаты (не запланированы — нужен приоритет владельца)

> У проекта нет формального бэклога; пункты ниже выведены из заметок и кода, а не
> заданы явно. Детальный трекинг — в [`BACKLOG.md`](BACKLOG.md).
>
> **Принцип (2026-07-16):** APK — шлюз для облака. Аналитический **UI** — на стороне
> VoltFlow, не в APK. Но edge-вычисления и временная локальная БД в APK допустимы, если
> снижают нагрузку на облако (расчёт/буфер на устройстве ради разгрузки cloud compute).

- 🧭 **Непрерывность истории при переименовании авто** (B-03) — таблицы телеметрии в
  Supabase хранят `vehicle_id` как часть ключа, поэтому история до/после rename
  разрывается. Батч-фикс `7b37366` устранил *потерю* данных, но *слияние* истории —
  отдельная задача на стороне VoltFlow/Supabase. `blocked` (кросс-репо).

Закрыто после ревизии 2026-07-16: обновление APK уже работает через GitHub Releases
(оставили как есть); экран аналитики в APK — **не делаем** (см. принцип выше); полный
autoservice/nativestack port — **не делаем** без нового доказательства с конкретной машины.

---

## ⚠️ Риски и техдолг

- ⚠️ **Packaging-gotcha:** `tools/start_voltflow_cmd.sh` и
  `app/src/main/assets/start_voltflow_cmd.sh` обязаны быть синхронизированы — self-revival
  запускает asset-копию из APK. Проверять при каждом изменении лаунчера.
- ⚠️ **Правка миграций:** `supabase db push` пропускает уже применённые миграции —
  всегда новая миграция, не редактирование старой.
- ⚠️ **Кросс-репо зависимость:** значимая часть логики (ingest, discard-фильтры,
  pairing-эндпоинты) живёт в VoltFlow/EvAcChargeTimer, а не здесь. Изменения контракта
  требуют координации двух репозиториев.
