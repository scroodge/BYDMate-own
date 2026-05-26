# Changelog

Все заметные изменения BYDMate перечислены здесь.

Формат основан на [Keep a Changelog](https://keepachangelog.com/ru/1.1.0/), версионирование следует [Semantic Versioning](https://semver.org/lang/ru/).

Полные релизы с APK: <https://github.com/scroodge/BYDMate-own/releases>.

## [Unreleased]

### Changed
- Cloud Sync batch-запросы стали устойчивее на медленном backend: таймауты чтения и записи увеличены до 45 секунд, charging flush отправляет по одной минутной пачке до 60 samples за тик, а HTTP-ошибки теперь сохраняют короткое тело ответа сервера для диагностики.

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

[Unreleased]: https://github.com/scroodge/BYDMate-own/compare/v0.2.5...HEAD
[0.2.5]: https://github.com/scroodge/BYDMate-own/compare/v0.2.2...v0.2.5
[0.2.2]: https://github.com/scroodge/BYDMate-own/releases/tag/v0.2.2
