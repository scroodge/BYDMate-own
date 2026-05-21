<div align="center">

<img src="docs/assets/voltflow-cloud-release.svg" width="128" alt="VoltFlow Mate">

# VoltFlow Mate

### Локальная телеметрия, поездки, зарядки и автоматизация для BYD DiLink

[![Android](https://img.shields.io/badge/Android-10%2B-3DDC84?style=flat-square&logo=android&logoColor=white)](https://developer.android.com)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.1-7F52FF?style=flat-square&logo=kotlin&logoColor=white)](https://kotlinlang.org)
[![Compose](https://img.shields.io/badge/Jetpack_Compose-Material_3-4285F4?style=flat-square&logo=jetpackcompose&logoColor=white)](https://developer.android.com/jetpack/compose)
[![License](https://img.shields.io/badge/License-GPLv3-blue?style=flat-square)](LICENSE)
[![Релиз](https://img.shields.io/github/v/release/scroodge/BYDMate-own?style=flat-square&label=APK)](https://github.com/scroodge/BYDMate-own/releases/latest)

**VoltFlow Mate** — форк BYDMate для головных устройств BYD DiLink. Приложение является шлюзом для передачи данных с BYDMATE в облако VoltFlow. 

[Скачать APK](https://github.com/scroodge/BYDMate-own/releases) ·[Сборка из исходников](#-сборка-из-исходников) · [Поддержать](SUPPORT.md)

</div>

---

## Что умеет

| Иконка | Раздел | Что делает |
|---|---|---|
| <img src="docs/assets/voltflow-cloud-release.svg" width="28" alt=""> |Передает данные с BYDMATE в облако | Берёт данные из BYDMATE `energydata` или DiPlus TripInfo. 


Приложение использует отдельный Android `applicationId`: `dev.scroodge.cloudevmate`. Поэтому оно может стоять рядом с оригинальным BYDMate (`com.bydmate.app`) и не заменяет его.

---

## Быстрая установка

Самый простой путь — поставить готовый APK из GitHub Releases.

1. Откройте [последний релиз](https://github.com/scroodge/BYDMate-own/releases/latest).
2. Скачайте файл `VoltFlow-Mate-v...apk`.
3. Перенесите APK на головное устройство DiLink через USB-флешку, браузер, файловый менеджер web телеграмм, или ADB.
4. Откройте APK на DiLink и разрешите установку из неизвестных источников.
5. После запуска выдайте разрешения на геолокацию, хранилище и отображение поверх других приложений.
6. В DiLink выключите ограничение фоновой работы для VoltFlow Mate: **Settings -> General -> Disable background Apps -> VoltFlow Mate = OFF**.


### Что ещё нужно установить

VoltFlow Mate читает live-данные машины через **BYDMATE.**

1. Следуйте инструкции установки: (https://github.com/AndyShaman/BYDMate).
2. Перенесите файл на DiLink.
3. Установите через файловый менеджер.
4. Запустите  BYDMATE хотя бы один раз и настройте его под вашу машину.


## Первый запуск

1. Откройте VoltFlow Mate.
2. Выдайте разрешения на локацию и хранилище.
3. Нажмите кнопку "Запустить" и убедитесь что данные запускаются.
4. Зарегестрируйтесь в VoltFlow по этой ссылке (https://github.com/scroodge/VoltFlow/blob/main/INSTALL.md)
5. В приложении VoltFlow зайдите в меню настроек и перейдите в раздел VoltFlow Mate и нажмите кнопку "Generate key"
6. Скопируйте ключ и вставьте его в приложении BYDMATE в поле "API Key"
7. Нажмите кнопку "TEST" и при положительном результате нажмите кнопку "Save" и переключатель Синхронизация VoltFlow Mate в положение "Вкл"
9. Убедитесь что при включеной машине в приложении VoltFlow Mate в разделе "Авто" отображаются данные о машине.

---

## Скриншоты

### VoltFlow Mate на планшетном экране

<img src="docs/screenshots/voltflow-gateway-status.png" alt="VoltFlow Mate: статус шлюза" width="900">

<img src="docs/screenshots/voltflow-sync-settings.png" alt="VoltFlow Mate: настройки синхронизации VoltFlow" width="900">


## Сборка из исходников

Этот путь нужен разработчикам и тем, кто хочет собрать APK самостоятельно.

### Требования

- JDK 17.
- Android SDK Platform 34.
- Android Gradle Plugin из проекта.
- Доступ к интернету при первом запуске Gradle, чтобы скачать зависимости.
- macOS, Linux или Windows с установленным Android SDK.

### Команды

```bash
git clone https://github.com/scroodge/BYDMate-own.git
cd BYDMate-own
./gradlew clean assembleDebug
```

Готовый debug APK появится здесь:

```text
app/build/outputs/apk/debug/VoltFlow-Mate-v<version>.apk
```

Debug-сборка подписывается стандартным debug-ключом Android и подходит для личной установки через ADB или файловый менеджер DiLink.

### Release-сборка

```bash
./gradlew clean assembleRelease
```

APK будет в:

```text
app/build/outputs/apk/release/VoltFlow-Mate-v<version>.apk
```

Для публичного релиза используйте свой keystore и подпишите APK перед публикацией. Готовый файл прикрепляйте к GitHub Release в блок **Assets**. Автопроверка обновлений в приложении смотрит на `https://api.github.com/repos/scroodge/BYDMate-own/releases/latest` и ищет первый `.apk` в assets последнего релиза.

---

## Как оформить релиз

1. Обновите `CHANGELOG.md` из git-коммитов:

```bash
./gradlew releaseChangelog
```

Для предварительного просмотра без изменения файла:

```bash
./gradlew releaseChangelog -PdryRun=true
```

Если нужно указать версию вручную:

```bash
./gradlew releaseChangelog -PreleaseVersion=0.2.3
```

Авто-версия считается по SemVer от последнего тега `v*`: `fix:` и обычные сообщения дают patch, `feat:` даёт minor, `feat!:` или `BREAKING CHANGE:` дают major. Для аккуратного changelog пишите коммиты в формате Conventional Commits:

```text
feat: добавить интеграцию ABRP
fix: исправить расчёт зарядной сессии
refactor: упростить обработку телеметрии
```

2. Проверьте и при необходимости отредактируйте текст изменений в `CHANGELOG.md`.
3. Соберите APK:

```bash
./gradlew clean assembleRelease
```

4. Создайте коммит релиза и тег версии:

```bash
git add CHANGELOG.md
git commit -m "chore(release): v0.2.3"
git tag v0.2.3
git push origin main v0.2.3
```

5. На GitHub откройте **Releases -> Draft a new release**.
6. Выберите созданный тег.
7. В название релиза поставьте `VoltFlow Mate v0.2.3`.
8. В описание добавьте краткий список изменений на русском.
9. Прикрепите `VoltFlow-Mate-v0.2.3.apk`.
10. Опубликуйте релиз.

Обычным пользователям после этого достаточно открыть [страницу последнего релиза](https://github.com/scroodge/BYDMate-own/releases) и скачать APK.

---

## Стек

- Kotlin, Jetpack Compose, Material 3.
- Room, Hilt, OkHttp, Coroutines/Flow.
- osmdroid / OpenStreetMap.
- WorkManager для фоновой работы.
- Min SDK 29, Target SDK 29, Compile SDK 34.

---

## Поддержка и вклад

- Ошибки и предложения: [GitHub Issues](https://github.com/scroodge/BYDMate-own/issues).
- История изменений: [CHANGELOG.md](CHANGELOG.md).
- Поддержать проект: [SUPPORT.md](SUPPORT.md).

При баг-репорте укажите модель BYD, версию DiLink, источник данных поездок и что уже пробовали.

---

## Благодарности

- [BYDMate](https://github.com/AndyShaman/BYDMate) — оригинальное GPLv3-приложение, на котором основан проект.
- DiPlus / D+ — локальный мост к данным автомобиля.

---

## Лицензия

GPLv3 с условиями атрибуции. Подробности: [LICENSE](LICENSE) и [NOTICE.md](NOTICE.md).

Copyright (C) 2026 [Scroodge](https://github.com/scroodge/)
