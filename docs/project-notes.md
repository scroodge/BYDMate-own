# Project Notes

## 2026-05-22: С какой частотой данные отправляются на сервер?

Короткий ответ: локальная телеметрия читается раз в 1 секунду. На сервер сейчас отправляется только VoltFlow Cloud Sync; ABRP/Iternio отключён из runtime-пути и скрыт из настроек.

### Локальный polling

- `TrackingService` читает данные из DiPars каждые `1000 ms`.
- Если DiPars временно не отвечает, интервал постепенно увеличивается до максимума `60_000 ms`.

Код:
- `app/src/main/kotlin/com/bydmate/app/service/TrackingService.kt`: `POLL_INTERVAL_MS = 1000L`, `MAX_POLL_INTERVAL_MS = 60_000L`.
- Основной цикл: `startPolling()`, где на каждом тике вызывается `diParsClient.fetch()`.

### VoltFlow Cloud Sync

Cloud Sync включается настройкой `cloud_sync_enabled`. `TrackingService` пробует вызвать `maybeSendCloudTelemetry()` на каждом успешном 1-секундном тике, но `CloudTelemetrySender` сам решает, надо ли класть новый снимок в очередь. Это сделано, чтобы внутри авто данные обновлялись часто, а облако и база не получали лишние записи:

- машина движется (`speed > 0.5 km/h`): новый sample раз в 60 секунд;
- зарядка (`isCharging == true` или мощность зарядки больше `0.1 kW`): раз в 30 секунд;
- стоим/парковка: heartbeat раз в 5 минут;
- при смене состояния движение/зарядка sample кладется сразу.

Отдельно от частоты samples есть flush очереди на сервер:

- сразу, если накопилось 60 samples;
- сразу при смене состояния;
- иначе по настройке `cloud_sync_interval_sec`, по умолчанию 60 секунд, допустимый диапазон 5-300 секунд.

В нормальном режиме движения это означает примерно 1 HTTP-запрос в минуту с 1 свежим sample. Если связи нет или включен Wi-Fi only без Wi-Fi, samples копятся локально и потом уходят батчем.

Если включен режим Wi-Fi only и Wi-Fi нет, samples остаются в локальной очереди и не отправляются до появления Wi-Fi.

Код:
- `app/src/main/kotlin/com/bydmate/app/data/cloud/CloudTelemetrySender.kt`: `decide()`, `MOVING_SAMPLE_INTERVAL_MS`, `CHARGING_SAMPLE_INTERVAL_MS`, `STOPPED_HEARTBEAT_INTERVAL_MS`.
- `app/src/main/kotlin/com/bydmate/app/data/cloud/CloudTelemetrySender.kt`: `flushIntervalSec`, `MAX_BATCH_SIZE`.
- `app/src/main/kotlin/com/bydmate/app/service/TrackingService.kt`: `maybeSendCloudTelemetry()`.
