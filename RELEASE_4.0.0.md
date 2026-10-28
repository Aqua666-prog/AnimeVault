# AnimeVault 4.0.0 (79) — Cinematic UI Phase 4: Player & Final Polish

Статус: кандидат выпуска, не опубликован. Phase 1–3 присутствуют в базовом main cd18a25 (3.5.0/78). Подготовлена 4.0.0/79; подписанный APK и ручная приёмка ещё не подтверждены.

## Завершено в исходниках

Общие онлайн/локальные controls получили верхний title/episode caption, более плотные scrim и surface, dock ниже заголовка и safe drawing padding. Центр использует тот же Player с bounded press scale только в FULL. Buffering/подготовка имеют текстовый вариант REDUCED/OFF без непрерывного spinner.

PlayerTimeline сохранил drag/commit seek и polling, но увеличил hit area до 48dp, добавил timestamp semantics и читаемые time labels. Buffered и OP/ED рисуются из прежних данных; при неизвестной длительности не показывается ложный заполненный progress/segments. Новые кадры-preview не генерируются. Stateless presentation wrappers позволяют Preview без ExoPlayer.

Материальные меню дорожек, масштаба, скорости и таймера имеют прокручиваемое содержимое. Controls fade использует прежние Vault durations; OFF имеет нулевые длительности. Material sheet motion не заменялся собственным animation engine; его поведение с app/system preferences нужно проверить на устройстве.

Vault сохранён; Midnight имеет отдельные холодные surface/primary roles; OLED сохраняет чёрный background; Dynamic на Android 12+ использует системные surfaces/navigation, оставляя Lemon action. Android 7–11 получает Vault fallback. Все четыре темы доступны в настройках без изменения enum/ключей; удалён дублирующий переключатель OLED. Добавлены Preview тем и player snapshot states, тесты static palettes/contrast.

## Сохранённая функциональность

ExoPlayer ownership/release, online/offline MP4/HLS, embed/WebView, headers, source selection/DRM, history/progress, recovery, next episode/season, skip settings, track selection/subtitles, PiP, fullscreen/lock/orientation, sleep timer и EQ не заменяются новой архитектурой. Network providers, WorkManager/download engine, Room, updater JSON, signing/секреты и GitHub Actions не изменены. Новые зависимости и миграции не нужны.

## Проверки

Локально только git diff --check, лексический баланс/импорты/вызовы, source-version и scope inspection. Рассчитаны выбранные пары непрозрачных статических цветов: обычный текст Vault/Midnight, action, player surface — выше 4.5:1. Это не доказательство контраста всех состояний/произвольных кадров/Dynamic wallpaper palette.

Kotlin tests VaultPalettesTest добавлены, не запускались. Preview для 320/360/600/840, landscape, font2, paused/playing/buffering/drag/unknown duration, API24/35 theme roles созданы, но не отрендерены. Gradle, APK и instrumentation в Codex не запускались. Компиляция, unit/lint, APK version/signature/JSON и API24/35 должны пройти Actions на новом SHA.

## Известные ограничения и приёмка

До выпуска обязательны [все 12 сценариев и матрица устройств](docs/design/cinematic-ui-phase4.md). Не подтверждены реальная производительность, доступность всех внешних источников, TalkBack/контраст всего UI и rotation/PiP на телефоне. Субтитры могут пересекаться с открытыми controls в зависимости от текущего потока и устройства — проверить. Caption/dock и длинные пункты меню при fontScale2 требуют визуальной приёмки. Ошибки окружения API35 не следует скрывать отключением тестов или изменением CI.

«Cinematic UI 4.0» завершён как последовательность изменений исходников, но не объявляется проверенным финальным APK. Готовность к публикации зависит от Actions и ручного QA. Тег/Release автоматически не создаются. Владелец отдельно разрешает выпуск из одного проверенного артефакта APK+JSON с прежним сертификатом по [существующему порядку](docs/AUTOMATIC_RELEASES.md).
