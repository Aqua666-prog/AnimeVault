---
name: animevault-security
description: Use for AnimeVault Android security reviews and OWASP MASVS evidence mapping across storage, auth, network, platform, APK updates and privacy. Not a certification tool.
---

# Android Security / OWASP MASVS

Прочитать AGENTS.md. Определить активы, атакующего и trust boundaries конкретной
правки. Использовать [MASVS](https://mas.owasp.org/MASVS/) и
[MASTG](https://mas.owasp.org/MASTG/) как внешние стандарты; их текст не поставляется
в пакете. При недоступности актуальной версии записать это; не выдумывать control IDs.

## Области проверки

| Группа MASVS | Применение в AnimeVault | Доказательство |
|---|---|---|
| STORAGE / PRIVACY | SecureSessionStore, preferences, Room, backup exclusions, logs | Пути, payload classification, logout/backup сценарий |
| CRYPTO | Keystore, key lifetime, random/IV, запрет hardcoded secrets | Реальная реализация + API-level fallback |
| AUTH | Firebase/Community, AniList OAuth callback, state/nonce, account isolation | Неверный callback, отмена, истечение session |
| NETWORK | HTTPS, redirect/host validation, cookies и headers | Негативные запросы, очистка логов, transport config |
| PLATFORM | exported activities, FileProvider, intents, grants, permissions | Merged manifest + cold/warm intent checks |
| CODE | untrusted parsers, dependency changes, file bounds, updater | diff, limits и негативный тест |
| RESILIENCE | signature, release packaging, tamper threat assumptions | APK/signing evidence; R8 сам по себе не доказывает защиту |

Для Intent/IPC дополнительно читать [android-intent-security](../android-intent-security/SKILL.md).
Сверять каждый пример с контрактом проекта: marker extra не аутентифицирует sender;
public OAuth/launcher не закрывать signature permission. Проверять merged manifest,
а не добавлять дублирующие permissions автоматически. Логи exception могут содержать secrets.

## Правила

- Не добавлять permissive TrustManager, trust-all hostname verifier или cleartext fallback.
- Валидировать URI scheme/host/path, nested intents, redirects и FileProvider paths;
  минимизировать grants/permissions, PendingIntent делать immutable, где возможно.
- Не хранить backend service-role credentials в APK/BuildConfig. Публичный publishable
  key не заменяет серверную авторизацию/RLS. Не менять backend policy при Android audit.
- У updater проверять package/version/signing/digest и доверенный источник manifest;
  SHA-256 без аутентифицированного источника не подтверждает происхождение APK.
- Не запускать pentest на сторонних endpoints без соответствующего scope.

## Отчёт

Для каждого finding: severity, файл/строка, достижимый сценарий, последствия,
предлагаемая правка и проверка. Матрица MASVS: проверено / не проверено / неприменимо
с объяснением. Отсутствие findings не означает полное соответствие MASVS.
Чтение навыка не устанавливает scanner, MobSF, Frida или сертификат соответствия.
