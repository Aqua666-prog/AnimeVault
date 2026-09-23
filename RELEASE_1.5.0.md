# AnimeVault 1.5.0 — Final Vault Polish

1.5.0 закрывает оставшиеся системные части редизайна и hardening после ветки 1.4.x. Это финальный консолидирующий патч: он не меняет Room schema и не требует пересканирования локальной медиатеки.

## Shared transitions

- `NavHost` размещён внутри `SharedTransitionLayout`;
- локальные и online poster cards используют устойчивые `VaultSharedPosterKey`;
- единый title hero использует тот же ключ и принимает отдельный `posterModifier`;
- при `VaultMotionMode.MINIMAL` shared element отключается автоматически.

## Statistics

Добавлен отдельный экран `Статистика` и `StatisticsViewModel`. Он строится из уже существующих данных:

- число локальных тайтлов и серий;
- завершённые серии и накопленный локальный watch progress;
- online history/favorites и зафиксированный online progress;
- средний AniList score локальных метаданных;
- top genres;
- 28-дневная heatmap по последним отметкам просмотра.

Важно: текущая модель данных хранит последнюю отметку по серии/релизу, а не отдельное событие каждого повторного просмотра. UI прямо обозначает это ограничение.

## Player polish

- локальный и online player показывают контекстную pause-card с названием, серией, оставшимся временем и наличием следующей серии;
- `EqualizerDialog` заменён на `EqualizerSheet`;
- `SkipSettingsDialog` заменён на `SkipSettingsSheet`;
- bottom sheets используют общий `VaultSheetHeader` и учитывают navigation bars.

## Provider hardening

- remote endpoint проходит provider-specific host allowlist;
- произвольный HTTPS-домен больше не может стать origin провайдерского API;
- runtime disabled provider исключается из unified catalog/release/stream resolution;
- отключённый persisted provider не выбирается активным при следующем старте;
- provider `priority` участвует в порядке unified requests и stream ranking;
- Source Picker отображает runtime enable/disable состояние.

## AniList OAuth

- перед открытием authorization URL создаётся одноразовый random `state`;
- callback принимается только при точном совпадении `state`;
- значение удаляется после callback и при sign-out.

## Compatibility

- `versionCode`: 41;
- `versionName`: 1.5.0;
- `minSdk`: 24;
- Room database version не менялась;
- новый backup format не вводился.

## Verification

Перед публикацией APK выполнить:

```bash
bash tools/verify-final.sh
bash tools/verify-1.5.0.sh
./gradlew testDebugUnitTest --stacktrace
./gradlew lintDebug --stacktrace
./gradlew assembleDebug --stacktrace
```
