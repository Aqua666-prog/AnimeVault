# Подпись локальных сборок

Android принимает обновление только с тем же `applicationId`, тем же или более
высоким `versionCode` и совместимой подписью. Начиная с готового debug APK 0.3.1
используется сохранённый ключ со следующим SHA-256 отпечатком сертификата:

`4f6fce17b139abaeba41581603bc75ebcbe912f89d4c0384a63f7b75383003c8`

Сам закрытый ключ не входит в исходники. Для официальной локальной сборки
укажите его абсолютный путь одним из способов:

```bash
export ANIMEVAULT_DEBUG_KEYSTORE=/absolute/path/AnimeVault-debug-signing-key-0.3.1.jks
./gradlew assembleDebug
```

или в пользовательском `gradle.properties`:

```properties
animeVaultDebugKeystore=/absolute/path/AnimeVault-debug-signing-key-0.3.1.jks
```

Для сохранённого debug-ключа используются стандартные значения Android:
alias `androiddebugkey`, пароль хранилища и ключа `android`. Их можно переопределить
через `ANIMEVAULT_DEBUG_STORE_PASSWORD`, `ANIMEVAULT_DEBUG_KEY_ALIAS` и
`ANIMEVAULT_DEBUG_KEY_PASSWORD` либо одноимённые Gradle-свойства в camelCase.

Без настроенного пути Gradle использует обычный пользовательский debug-ключ;
такая сборка не сможет обновить выданный APK 0.3.1. Закрытый ключ нельзя
публиковать или добавлять в репозиторий.
