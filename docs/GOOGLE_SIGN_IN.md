# Google Sign-In в AnimeVault 3.0.3

Вход необязателен: Настройки → Аккаунт → Войти через Google.
Credential Manager получает Google ID token и передаёт его в Firebase Authentication.
Firebase восстанавливает сессию и обновляет токены; приложение не сохраняет ID token самостоятельно.
Имя, email и аватар берутся из FirebaseUser. Выход завершает сессию Firebase и очищает
состояние Credential Manager. Библиотека, прогресс, коллекции и видео не затрагиваются.
Вход не включает облачную синхронизацию локальных данных.

## Firebase Console

1. Откройте проект **animevault-60b01** → Project settings → Your apps →
   Android-приложение **com.sergey.animevault**.
2. Убедитесь, что SHA-1 относится к постоянному debug-сертификату GitHub Actions.
   Он уже добавлен по исходным данным задачи; менять сертификат не нужно.
   Для локальных APK с другим сертификатом потребуется добавить его SHA-1 отдельно.
3. Authentication → Sign-in method → Google: провайдер должен быть включён,
   адрес поддержки проекта — указан.
4. Скачайте свежий **google-services.json** после настройки Google и SHA-1.
   В нём должен присутствовать Web OAuth client с client_type = 3.
   Плагин Google Services создаст из него ресурс default_web_client_id.
   Не подставляйте Android OAuth client ID.
5. Для проверки установите подписанный APK из Actions на устройство с Google Play,
   войдите, перезапустите приложение и проверьте пользователя в Authentication → Users.

OAuth client secret, service account JSON, приватный ключ и пароль для этого не нужны.

## GitHub Actions

Добавьте единственный новый repository secret **GOOGLE_SERVICES_JSON_B64**:
Settings → Secrets and variables → Actions → New repository secret.

В Linux или Termux получите значение локально (не добавляйте файл в Git):

```bash
base64 -w 0 /path/to/google-services.json > google-services.base64
# Вставьте содержимое в GOOGLE_SERVICES_JSON_B64, затем удалите временный файл.
rm google-services.base64
```

Workflow восстанавливает app/google-services.json с правами 600 перед Gradle.
Скрипт проверяет project ID, package и наличие Web OAuth client, не печатая содержимое.
Файл игнорируется Git и не входит в артефакты. Ресурсы Firebase всё равно входят в APK:
Firebase Android-конфигурация не заменяет серверные правила доступа.

Если secret отсутствует, Actions выдаёт предупреждение и собирает гостевой APK:
кнопка входа отключена с пояснением. Для работающего входа secret обязателен.
Некорректный secret останавливает сборку с понятным сообщением.
Существующие ANIMEVAULT_DEBUG_* и другие секреты подписи менять не нужно.
Workflow запускается для main и feature/firebase-google-signin.

## Локальная сборка

Поместите настоящий файл в app/google-services.json до запуска Gradle.
Без файла сборка поддерживает все прежние функции, но Google-вход недоступен.
JDK, SDK и Wrapper используются прежние: minSdk 24, compileSdk 37.

```bash
./gradlew --no-daemon compileDebugKotlin
./gradlew --no-daemon testDebugUnitTest
./gradlew --no-daemon lintDebug
./gradlew --no-daemon assembleDebug
```

Зависимости: Firebase BoM 34.0.0 (основной модуль firebase-auth, без устаревшего auth-ktx),
Credential Manager 1.5.0, credentials-play-services-auth 1.5.0, googleid 1.1.1.
Они поддерживают API 24. Google Play Services необходимы для Google-входа,
но не для гостевого использования приложения.

## Проверка на устройстве

Проверьте Android 7/API 24 и современную версию Android с сервисами Google Play:

- Гостевой запуск, каталог, поиск, просмотр, загрузки, библиотека и офлайн-просмотр.
- Первый вход и повторный вход, включая аккаунт, который ещё не входил в AnimeVault.
- Имя, email, аватар; перезапуск приложения с сохранённой сессией, в том числе офлайн.
- Отмена окна Google и повторная попытка; отсутствие сети или аккаунта Google.
- Выход: сессия завершена, скачанные видео и библиотека остаются.
- После выхода можно выбрать другой Google-аккаунт.
- Сборка без конфигурации: настройки объясняют недоступность входа, остальные функции работают.

Unit-тесты AccountViewModelTest проверяют восстановленное состояние, обмен токена,
отмену, ошибки сети, отсутствие конфигурации, выход при сбое провайдера,
повторные нажатия и отмену coroutine при уходе с экрана.
Настоящий обмен Google → Firebase требует устройства и реальной конфигурации;
JVM-тесты его не заменяют.
