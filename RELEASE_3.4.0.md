# AnimeVault 3.4.0 (77) — Cinematic UI Phase 2: Anime Details

База: origin/main 20be310 (3.3.0/76). Повышены только канонические поля версии: 3.4.0/77. Это подготовка, не автоматическая публикация релиза.

## Карта реализации

| Область | Переиспользование и фактическое изменение |
|---|---|
| Hero | UnifiedTitleOverview → VaultAdaptiveHero/VaultArtwork; отдельный cinematic-вариант увеличивает фон и постер, сохраняя Phase 1 Home по умолчанию |
| Метаданные | Существующий UnifiedTitleUiModel и animeTitle resolver; показ сезона и всех жанров, альтернативного названия, рейтинга и статуса без новых запросов |
| Прогресс | VaultWatchSummary вынесен из скрытой панели; счётчики и идентификаторы не пересчитываются по новой модели |
| Источники | Прежняя availability panel и callbacks; кликабельные источники получают 48dp высоты |
| Серии | Прежние EpisodeCard/OnlineEpisodeCard, progress и download actions; масштабируемая плотность, многострочные метаданные, кнопка локального воспроизведения, подписи состояний |
| Вкладки/описание | Общий VaultFilterChip, bounded color transition через VaultMotion; animateContentSize описания учитывает duration preferences |
| Личное | PersonalTitlePanel сохраняет rating/note/notify callbacks; rating 48dp и доступные имена, горизонтальная прокрутка оценок, вертикальные вторичные кнопки |
| Extras | Существующие персонажи, staff, relations, recommendations, pictures, videos и музыка; общие заголовки, масштабирование карточек, пустое состояние с прежним retry |
| Персонаж | Прежний ModalBottomSheet и loader, компактный портрет при крупном тексте и bounded opacity feedback; dismiss/Back не заменялись |

Связывание онлайн/офлайн, выбор озвучки, списки и избранное, share, группировка/разделение, сезонные и массовые действия, контекстные меню и download callbacks сохранены. SeasonDownloadSheet изучен и переиспользован без изменений. Сетевые API/парсеры Tenrai/AniList/Shikimori/AnimeThemes, ViewModel и хранилища, Media3, WorkManager и Room не изменены. Миграции не нужны. Shared poster keys остаются прежними, переходы включены только в FULL; новая идентичность эпизодов не вводится.

## Motion, адаптивность и темы

В title cinematic hero увеличены размеры обложки/постера; при ширине <360dp или fontScale >1.3 используется вертикальная компоновка. Высота зависит от текста; CTA ограничен шириной 600dp. Прокрутка использует прежние fading backdrop, FULL-only parallax и компактный top bar, без дорогого blur или нового collapsing manager.

Новые цветовые/opacity-анимации и длительность раскрытия описания используют VaultMotion/vaultMotionDuration. При REDUCED нет нового декоративного перемещения, при OFF длительности нулевые. Sheet использует прежний Material механизм, а не новый движок переходов.

Все настройки тем сохранены. Отдельные палитры Midnight/Dynamic по-прежнему не реализованы, и их готовность не заявляется. Цвета/контраст поверх реальных изображений требуют измерения на устройстве.

## Preview и проверки

TitleDetailsPreviews содержит синтетические online/local/long/missing models, resume/finished episodes, partial metadata и retry error. Размеры: 320, 360, 600, 840dp, landscape, fontScale 2; REDUCED и OFF. Нет ViewModel или repository, previewUrl отсутствует, VaultArtwork не делает запросы в inspection mode. Preview созданы, но не отрендерены в Codex.

Локально: git diff --check, проверка повторных импортов и лексического баланса скобок, инспекция вызовов и неизменности callback/ID-потоков, source version validator. Это не компиляция. Gradle, APK и Android tests локально не запускались.

GitHub Actions должен проверить Kotlin, unit tests, lint, сборку и подпись APK, согласованность versionName/versionCode/update JSON и штатные API 24/35 tests. Плеер, updater schema, секреты, signing configs и Actions-файлы не менялись. Релиз вручную по [существующему порядку](docs/AUTOMATIC_RELEASES.md), только APK и JSON из одного проверенного SHA. Автопубликация не разрешена.

## Обязательное QA на телефоне

- Preview/устройство: 320–360dp, 600dp, 840dp+, landscape и шрифт до 2.0; проверить правую колонку episode actions и длинные названия.
- Проверить FULL/REDUCED/OFF и системное отключение анимаций, Back из sheet/dialog, scroll и shared poster переходы.
- TalkBack: название/статус серии, воспроизведение, контекстные действия, рейтинг, вкладки и ссылки; измерить hit targets и контраст.
- Продолжение правильной online/local серии с сохранением позиции, озвучка, сезон, download/pause/resume/remove, mark watched.
- Избранное, список, оценка/заметка, share, связывание и unlink, объединение/разделение и массовая группировка.
- Ошибка/пустые extras, retry персонажа, актёры, staff, gallery, relations, recommendations и AnimeThemes: прежние запросы и действия должны работать.

Полноценный редизайн карточек staff/галереи, новый механизм загрузки, новые сведения для офлайн-тайтла без метаданных и измеренное ускорение не заявляются.
