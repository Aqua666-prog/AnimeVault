# Cinematic UI 4.0 — Phase 4 handoff и итоговый аудит

База cd18a25 (3.5.0/78): Phase 1 ddc2b16, Phase 2 521f294, Phase 3 cd18a25 присутствуют. Проектный animevault-ui-designer и Cinematic specification приоритетны. Применены принципы Stark (сначала сценарий и состояния), StyleSeed (сохранение проектных решений), UI Design System (общие токены), Apple Design (ограниченная прерываемая обратная связь); сторонние скрипты не выполнялись.

## Карта плеера и границы

PlayerScreen владеет локальным ExoPlayer и его dispose/progress/preferences; OnlinePlayerScreen сохраняет native player/session/source transitions, headers и recovery. EmbeddedOnlinePlayer владеет прежним WebView и dispose. OnlineStreamSelection/SourceSwitchPolicy, PlayerSeeking, PlayerSkip, PlayerOrientation, PlayerPictureInPicture и PlayerOverlayReducer изучены и не переписываются. PlayerSeeking уже имеет preview controller и жесты; новый retriever/плеер/жесты не создаются.

PlayerChrome и PlayerControls остаются общей оболочкой. Транспорт и timeline получили stateless presentation-функции для Preview; wrapper по-прежнему читает Player и вызывает прежние play/pause/seekBack/seekForward/seekTo. Polling 250ms и владение плеером сохранены; добавлено только чтение BUFFERING для визуального статуса. Таймер, дорожки и скорость используют прежние callbacks/preferences. Качество/озвучка/source switching показывают прежние варианты и gates; для embed не добавляются недоступные native actions.

## Фактическая полировка

| Область | Решение |
|---|---|
| Плеер | Caption перенесён вверх, dock ниже caption; контрастные подложки, safe drawing controls, 48dp timeline/кнопки, крупнее Play/Pause, статическая подготовка при reduced/off, scroll content меню |
| Небольшой экран | Убрана дополнительная pause info card, перекрывавшая transport; заголовок, серия и timeline остаются. Большая карточка сохраняется на широком высоком окне |
| Главная/тайтлы | Phase 1/2 и прежние Vault components сохранены; темы проходят через MaterialTheme/LocalVaultColors без новых копий карточек |
| Библиотека/каталог/загрузки | Phase 3 сохранена; нет нового фильтра/движка. Реальная доступность источников не подтверждается UI-аудитом |
| Настройки | Вместо списка двух тем доступны все четыре; удалён дублирующий AMOLED toggle, Dynamic fallback описан пользователю |
| Темы | Новая Midnight, системная Dynamic на API 31+, fallback API24–30, чёрный OLED, фирменный Lemon action во всех режимах |

Это статический аудит реализации; экранные снимки и весь интерфейс на устройстве не просмотрены. «Финальная полировка» не означает доказанного отсутствия всех визуальных дефектов.

## Проверка APK (обязательна до публикации)

1. Открыть аниме с главной; проверить shared poster и длинное название.
2. Начать/продолжить правильную серию и сохранить позицию.
3. Переключить источник: confirmation, rollback/recovery и source-scoped progress.
4. Изменить качество/озвучку и проверить восстановление сохранённого выбора.
5. Seek мышью/касанием/TalkBack, удержание, buffered и известные OP/ED; неизвестная длительность без ложного заполнения.
6. Включить/отключить субтитры, выбрать доступную аудиодорожку.
7. Повернуть телефон при play/pause/seek/sheet; проверить insets и неизменность плеера/позиции.
8. PiP на поддерживаемом Android, затем возврат; controls/lock/fullscreen.
9. Локальное видео, downloaded файл, MP4, HLS и embed/WebView; отсутствие новой сетевой зависимости для offline.
10. Вернуться из background без потери прогресса; timer, EQ, next episode/season, ошибки и recovery.
11. Vault/Midnight/OLED/Dynamic: API24 fallback и API31+ wallpaper palette, контраст action/navigation, OLED surfaces.
12. FULL/REDUCED/OFF и системное animation scale 0: без бесконечного декоративного spinner в reduced/off, быстрые повторные нажатия и Back из каждого sheet.

Матрица: 320/360/600/840dp, landscape, font 1/1.3/2, TalkBack, системные панели. Отдельно проверить caption/dock на шрифте 2: расположение рассчитано статически, возможна необходимость дальнейшей корректировки. Контраст системной Dynamic и произвольных кадров измерять на устройстве; производительность, восстановление после rotation и декодирование медиа не проверены в Codex.
