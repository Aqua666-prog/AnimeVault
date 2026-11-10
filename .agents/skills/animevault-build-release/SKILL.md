---
name: animevault-build-release
description: Use for AnimeVault Gradle/AGP/Kotlin failures, GitHub Actions, APK/AAB inspection, signing, updater metadata and release preparation. Publishing requires task authorization.
---

# Gradle, GitHub Actions и release artifacts

## Build и CI

1. Прочитать AGENTS.md, wrapper, root/app Gradle и затронутый workflow. Версии
   брать из репозитория, а не из примеров навыка. AGP 9 использует built-in Kotlin;
   не добавлять org.jetbrains.kotlin.android по старому шаблону.
2. Проверить JDK 17, SDK 37, Firebase config и environment по именам переменных,
   не печатая values. Не перезаписывать существующий google-services.json/keystore.
3. При ошибке сначала task/stacktrace и dependency graph; distinguish network
   resolution, API compatibility, KSP, compiler и packaging failures.
4. Использовать wrapper; `dependencyInsight --dependency okhttp --configuration
   debugRuntimeClasspath` помогает определить транзитивную версию. Не добавлять
   глобальные excludes и не чистить все caches вместо root-cause diagnosis.
5. CI: учитывать branch/path filters, permissions, forks и secrets. Проверка навыков
   не требует Android signing/Firebase. Не ослаблять gates и не включать production
   deploy для chore-ветки. Внешние Actions/скрипты проверять перед новым использованием.

## APK/release

- Build verification: `testDebugUnitTest lintDebug assembleDebug`; signed release
  требует существующие owner-provided signing credentials. Не генерировать replacement key.
- Проверить `aapt dump badging`, `apksigner verify --verbose --print-certs`, merged
  manifest/permissions/exported components, min/target SDK, versionCode/versionName,
  package, размер и native ABI. Использовать SDK tools по реально установленному пути.
- Сопоставить source commit/branch, APK metadata, updater JSON, digest и tag.
  Прочитать `tools/prepare-update-manifest.py` / `tools/verify-release-version.py`
  перед выполнением; команды параметров брать из их CLI/существующего workflow.
- Для upgrade проверить сохранение Room/preferences/progress/downloads и совпадение
  certificate. Unsigned artifact не готов к обновлению установленного приложения.
- Release notes описывают поведение и известные ограничения. Version bump/tag/upload
  выполняются только в scope задачи release; подготовка навыков их не разрешает.

## Отчёт

Указать версии toolchain, прошедшие checks, artifact identity и ограничения runtime QA.
PR создавать из отдельной ветки с точным base, проверками и списком навыков;
не merge, force push и deploy автоматически. Не заявлять запуск CI без run URL/status.

Зависимости: Gradle wrapper/JDK/SDK; для GitHub — авторизованный gh/connector;
для APK inspection — Android build-tools. Bundletool/device нужны только по сценарию.
