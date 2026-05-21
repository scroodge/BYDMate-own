<div align="center">

<img src="docs/assets/voltflow-cloud-release.svg" width="128" alt="CloudEV Gateway">

# BYDMate-own / CloudEV Gateway

### Локальная телеметрия, поездки, зарядки и автоматизация для BYD DiLink

[![Android](https://img.shields.io/badge/Android-10%2B-3DDC84?style=flat-square&logo=android&logoColor=white)](https://developer.android.com)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.1-7F52FF?style=flat-square&logo=kotlin&logoColor=white)](https://kotlinlang.org)
[![Compose](https://img.shields.io/badge/Jetpack_Compose-Material_3-4285F4?style=flat-square&logo=jetpackcompose&logoColor=white)](https://developer.android.com/jetpack/compose)
[![License](https://img.shields.io/badge/License-GPLv3-blue?style=flat-square)](LICENSE)
[![Релиз](https://img.shields.io/github/v/release/scroodge/BYDMate-own?style=flat-square&label=APK)](https://github.com/scroodge/BYDMate-own/releases/latest)

**CloudEV Gateway** — форк BYDMate для головных устройств BYD DiLink. Приложение показывает реальный расход, поездки, зарядки, состояние батареи, плавающий виджет и правила автоматизации. Данные хранятся локально в машине; сеть нужна только для опциональных функций вроде AI-инсайтов, ABRP и проверки обновлений.

[Скачать APK](https://github.com/scroodge/BYDMate-own/releases/latest) · [Страница релиза](docs/release.html) · [Сборка из исходников](#-сборка-из-исходников) · [Поддержать](SUPPORT.md)

</div>

---

## Что умеет

| Иконка | Раздел | Что делает |
|---|---|---|
| <img src="docs/assets/voltflow-cloud-release.svg" width="28" alt=""> | Реальный расход | Берёт данные из BYD `energydata` или DiPlus TripInfo, а не из заниженного штатного БК |
| <img src="docs/assets/voltflow-cloud-release.svg" width="28" alt=""> | Поездки | История поездок, GPS-треки, дистанция, скорость, стоимость |
| <img src="docs/assets/voltflow-cloud-release.svg" width="28" alt=""> | Зарядки | Автоматический журнал AC/DC, статистика за период, ручное добавление и редактирование |
| <img src="docs/assets/voltflow-cloud-release.svg" width="28" alt=""> | Виджет | Плавающий overlay поверх карт и медиа: SOC, запас хода, расход, температуры, 12V |
| <img src="docs/assets/voltflow-cloud-release.svg" width="28" alt=""> | Автоматизация | Правила `КОГДА -> ТОГДА`: параметры машины, геозоны, время, D+ команды |
| <img src="docs/assets/voltflow-cloud-release.svg" width="28" alt=""> | Батарея | Температура, 12V, баланс ячеек, SoH на Leopard 3 при включённых системных данных |
| <img src="docs/assets/voltflow-cloud-release.svg" width="28" alt=""> | Экспорт | CSV для поездок и зарядок |
| <img src="docs/assets/voltflow-cloud-release.svg" width="28" alt=""> | Интеграции | OpenRouter для AI-инсайтов, ABRP live telemetry, облачная отправка телеметрии при ручном включении |

Приложение использует отдельный Android `applicationId`: `dev.scroodge.cloudevmate`. Поэтому оно может стоять рядом с оригинальным BYDMate (`com.bydmate.app`) и не заменяет его.

---

## Быстрое скачивание для обычного пользователя

Самый простой путь — поставить готовый APK из GitHub Releases.

1. Откройте [последний релиз](https://github.com/scroodge/BYDMate-own/releases/latest).
2. В блоке **Assets** скачайте файл `CloudEV-Gateway-v...apk`.
3. Перенесите APK на головное устройство DiLink через USB-флешку, браузер, файловый менеджер или ADB.
4. Откройте APK на DiLink и разрешите установку из неизвестных источников.
5. После запуска выдайте разрешения на геолокацию, хранилище и отображение поверх других приложений.
6. В DiLink выключите ограничение фоновой работы для CloudEV Gateway: **Settings -> General -> Disable background Apps -> CloudEV Gateway = OFF**.

Для пользователей, которым нужна отдельная красивая инструкция с крупной кнопкой скачивания, добавлена локальная страница: [docs/release.html](docs/release.html). Её можно открыть как файл, положить в GitHub Pages или прикрепить к релизу.

### Что ещё нужно установить

CloudEV Gateway читает live-данные машины через **DiPlus (D+)**.

1. Скачайте APK DiPlus: [Google Drive](https://drive.google.com/file/d/1ndKgzh-HWRPrPw2eTbKh9pwhdDwYJ0Ug/view?usp=drive_link).
2. Перенесите файл на DiLink.
3. Установите через файловый менеджер.
4. Запустите DiPlus хотя бы один раз.

Если ADB на DiLink уже активирован, можно установить через компьютер:

```bash
adb connect <IP-адрес-DiLink>:5555
adb install DiPlus.apk
adb install CloudEV-Gateway-v<version>.apk
```

---

## Первый запуск

1. Откройте CloudEV Gateway.
2. Выдайте разрешения на локацию и хранилище.
3. Выберите источник данных поездок:
   - `BYD energydata` — для Leopard 3 / Fangchengbao Bao 3 и машин, где есть база BMS.
   - `DiPlus TripInfo` — для Song, Yuan и других моделей без `energydata`.
4. Укажите ёмкость батареи и тарифы на электричество.
5. Включите плавающий виджет, если хотите видеть данные поверх навигации или медиа.

Если после нескольких поездок история пустая, смените источник данных в настройках.

---

## ADB и системные данные

Без ADB приложение работает в базовом режиме: поездки, расход, виджет, настройки, ручные зарядки и AI-инсайты. ADB нужен для расширенных функций:

- точный SoH и часть BMS-показателей;
- автоматическое определение зарядных сессий;
- вкладка автоматизации и команды через D+;
- устойчивый доступ к системным данным DiLink.

На DiLink 3/4 ADB часто можно включить самостоятельно через инженерные настройки. На DiLink 5.0 ADB обычно заблокирован и открывается удалённо через продавцов на TaoBao. Подробный русский гайд лежит здесь: [docs/guides/dilink5-adb-activation-ru.pdf](docs/guides/dilink5-adb-activation-ru.pdf).

После активации откройте в приложении **Настройки -> Системные данные (экспериментально)**. DiLink покажет системный диалог ADB-отладки: нажмите **Allow** и включите **Always allow from this computer**.

---

## Скриншоты

### Главный экран

<img src="docs/screenshots/dashboard.jpg" alt="Главный экран" width="820">

### Поездки

<img src="docs/screenshots/trips.jpg" alt="Поездки" width="820">

### Зарядки и настройки

<img src="docs/screenshots/settings.jpg" alt="Настройки" width="820">

### Автоматизация

<img src="docs/screenshots/automation.jpg" alt="Автоматизация" width="820">

### Плавающий виджет

<img src="docs/screenshots/widget-infographic.jpg" alt="Плавающий виджет" width="900">

---

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
app/build/outputs/apk/debug/CloudEV-Gateway-v<version>.apk
```

Debug-сборка подписывается стандартным debug-ключом Android и подходит для личной установки через ADB или файловый менеджер DiLink.

### Release-сборка

```bash
./gradlew clean assembleRelease
```

APK будет в:

```text
app/build/outputs/apk/release/CloudEV-Gateway-v<version>.apk
```

Для публичного релиза используйте свой keystore и подпишите APK перед публикацией. Готовый файл прикрепляйте к GitHub Release в блок **Assets**. Автопроверка обновлений в приложении смотрит на `https://api.github.com/repos/scroodge/BYDMate-own/releases/latest` и ищет первый `.apk` в assets последнего релиза.

---

## Как оформить релиз

1. Соберите APK:

```bash
./gradlew clean assembleRelease
```

2. Создайте тег версии, например:

```bash
git tag v0.2.2
git push origin v0.2.2
```

3. На GitHub откройте **Releases -> Draft a new release**.
4. Выберите созданный тег.
5. В название релиза поставьте `CloudEV Gateway v0.2.2`.
6. В описание добавьте краткий список изменений на русском.
7. Прикрепите `CloudEV-Gateway-v0.2.2.apk`.
8. Опубликуйте релиз.

Обычным пользователям после этого достаточно открыть [страницу последнего релиза](https://github.com/scroodge/BYDMate-own/releases/latest) и скачать APK.

---

## Настройка ABRP

CloudEV Gateway может отправлять live-телеметрию в [A Better Route Planner](https://abetterrouteplanner.com/) через Iternio Telemetry API. Функция выключена по умолчанию.

1. В ABRP откройте гараж и настройки автомобиля.
2. В разделе данных подключите провайдера **Generic**.
3. Скопируйте `User Token`.
4. В CloudEV Gateway откройте **Настройки -> ABRP — телеметрия**.
5. Вставьте токен, сохраните и включите отправку.

GPS-координаты через этот канал не отправляются. ABRP на DiLink или телефоне берёт геопозицию из своей ОС.

---

## AI-инсайты

AI-инсайты работают через OpenRouter и включаются вручную.

1. Создайте ключ на [OpenRouter](https://openrouter.ai/).
2. Вставьте ключ в **Настройки -> AI Инсайты**.
3. Выберите модель.
4. Нажмите **Сохранить и получить инсайт**.

В запрос уходит агрегированная статистика за 7 и 30 дней. GPS-маршруты, VIN и история поездок по точкам не отправляются.

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
- [BYD Trip Info](https://www.byd-seal-forum.de/forum/thread/1811-byd-trip-info-app/) — приложение и идея чтения trip-данных на DiLink.
- DiPlus / D+ — локальный мост к данным автомобиля.

---

## Лицензия

GPLv3 с условиями атрибуции. Подробности: [LICENSE](LICENSE) и [NOTICE.md](NOTICE.md).

Copyright (C) 2026 [AndyShaman](https://github.com/AndyShaman)
