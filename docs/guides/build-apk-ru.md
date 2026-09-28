# Как собрать APK

**Обновлено:** 2026-09-28 · база: `main`

## Debug-сборка

```bash
./gradlew assembleDebug
```

APK появится в `app/build/outputs/apk/debug/VoltFlow-Mate-v<versionName>.apk`
(имя зависит от `versionName` в `app/build.gradle.kts`, не от git-ветки).

Перед сборкой, если менялась логика (а не только разметка/ресурсы), стоит
прогнать юнит-тесты той же командой, что использует релизный процесс:

```bash
./gradlew testDebugUnitTest assembleDebug
```

Зелёная сборка ничего не доказывает про поведение на самой магнитоле — см.
`CLAUDE.md` → «Verify on-car before calling anything done». Для установки
собранного APK на машину по Wireless ADB см.
[`docs/REMOTE_COMMAND_DAEMON.md`](../REMOTE_COMMAND_DAEMON.md#updating) —
там уже описан рабочий сценарий (`adb connect`, `adb install -r`), дублировать
его здесь не нужно.
