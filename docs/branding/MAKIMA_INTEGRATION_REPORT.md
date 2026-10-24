# AnimeVault — Makima Branding & Animated Launch

База: актуальный `origin/main`, `a1ec666`. Ветка: `feature/makima-branding`.

## Использованные ресурсы

Получен `AnimeVault_Makima_Compact_for_Codex.zip`. Прочитан его `README_RU.md`.
Компактный архив содержит готовые Android-экспорты, палитру и примеры интеграции;
папок `source/` и `exports/` в нём нет. Использованы предоставленные
`animevault_brand_logo.png` (1024×1024) и `animevault_brand_portrait.png` (512×512),
а также все готовые иконки. Изображения не перерисовывались и не пересохранялись.
SHA-256 архива и 19 исходных файлов зафиксированы в
`makima-asset-provenance.json`: 17 PNG и два adaptive XML.

Палитра запуска берётся из ресурсов набора: #9560FF, #E4FF55, #101014;
#FFF8F0 добавлен как именованный нейтральный цвет. Глобальная палитра Vault
Nocturne сохранена. Копия инструкции набора находится в `MAKIMA_KIT_README_RU.md`,
копия палитры — в `makima-colors.json`.

## Где заменён брендинг

- Рабочий стол: обычная и круглая иконки mdpi–xxxhdpi из набора.
- Android 8+: adaptive icon с фирменной подложкой и портретом из набора.
- Системное окно запуска: отдельная тема только для MainActivity, статический
  портрет на #101014 до Android 12 и штатная SplashScreen icon на Android 12+.
- Собственный вход: Makima Entrance вместо рисованной монограммы VA.
- Шапки, использующие `VaultLogoMark`/`AnimeBrandTitle`: портрет без мелкой надписи,
  обычный текст названия и заголовки экранов сохранены.
- Приветствие, вход, завершение onboarding и его loading/error-состояния:
  полный утверждённый логотип вместо старой монограммы и набранного wordmark.
  Обработчики входа и переходов не менялись.
- «О приложении»: полный утверждённый логотип над существующими сведениями.
- Старые `ic_launcher` и `ic_launcher_round` остаются ресурсными aliases на
  новые иконки для прежних обращений, включая уведомления. Их старые изображения
  удалены. В код уведомлений изменения не вносились.
- У набора нет монохромного изображения. Старый VA monochrome удалён; на Android
  13+ применяется новая цветная adaptive icon. Отдельный утверждённый вариант
  для themed icons не создавался.

Манифест не заменялся примером из архива: изменены только два атрибута иконки
application и тема MainActivity. PlayerActivity, permissions, intents и provider
остались прежними.

## Makima Entrance

Одна конечная `Animatable` со стандартной длительностью **1750 мс**:

| Фаза | Интервал | Эффект |
| --- | --- | --- |
| Awakening | 0–350 мс | Тёмная подложка, слабое фиолетовое свечение, три тонких кольца |
| Control | 350–900 мс | Портрет проявляется с увеличением 0.965 → 1.0 |
| AnimeVault | 900–1350 мс | Переход к полному утверждённому логотипу до 1200 мс, затем пауза; лимонная линия |
| Enter the Vault | 1350–1750 мс | Кольца и линия исчезают до 1550 мс, весь слой плавно растворяется поверх контента |

Используются Canvas и graphicsLayer. Нет видео, blur, вспышек, бесконечных
анимаций, дополнительных Activity или новых зависимостей. Никакой сетевой запрос
не управляет длительностью заставки. При стандартном системном масштабе анимации
она длится 1.75 с; системный масштаб времени Compose сохраняется.

Существующий `VaultLaunchGate` сохранён байт в байт: показ только при первом
launcher-входе за процесс, пропуск deep links и восстановления Activity.
Вход теперь накрывает также первое приветствие, не заменяя его навигацию.
Главный экран либо onboarding уже находится под слоем; прозрачность открывает
его без второй заставки и без повторного создания навигации.

В уменьшенном/выключенном режиме приложения и при системном отключении
анимации вход пропускается. Системное состояние уже учитывает существующий
`AnimeVaultTheme` на API 24+, новый механизм настроек не добавлялся.
Касание или Back завершают вход. `ON_STOP` завершает незаконченный вход,
`onNewIntent` снимает слой перед обработкой нового Intent. Под слоем временно
скрыта accessibility-семантика; у слоя доступное действие «Пропустить заставку».
Запрос уведомлений по-прежнему откладывается до завершения заставки.

Композиция вписана в квадрат не более 320dp, ограниченный обеими осями окна
и safe drawing insets. `ContentScale.Fit` сохраняет весь портрет и надпись
в портретном и альбомном окне. Существующий Coil загружает локальные ресурсы
асинхронно с общим memory cache и запросами размера 128/512/768px; декодирование
не выполняется на каждом кадре. Отображение не ожидает загрузки картинки
бесконечно: временная шкала завершается независимо от неё.

## Android 7–16

- `minSdk = 24`, `targetSdk = 37` сохранены; версия остаётся **3.0.6 (66)**.
- API 24–25: обычные PNG; фон запуска использует атрибуты layer-list API 23+.
- API 26+: готовые adaptive XML; fallback PNG для launchers без adaptive.
- API 31+ (Android 12–16): платформенные атрибуты SplashScreen в `values-v31`;
  API-вызов закрыт проверкой версии. Системный слой удаляется при готовности
  первого кадра без дополнительной exit-анимации. Тема реального окна
  переключается на прежний Nocturne перед `super.onCreate`.
- Совместимость по ресурсам и использованию API проверена чтением исходников.
  Работа на конкретных устройствах, включая Samsung Galaxy A17, не измерялась.

## Выполненные лёгкие проверки

| Проверка | Результат |
| --- | --- |
| `python3 tools/verify-makima-branding.py` | Успешно: 19 хешей, 17 RGBA8 PNG, CRC/распаковка/размеры |
| XML через ElementTree | Все 16 XML приложения разбираются |
| drawable/mipmap и XML-ссылки | Отсутствующие ссылки не найдены |
| Manifest, aliases и palette | Иконки/тема/цвета соответствуют комплекту |
| `bash tools/verify-source.sh` | Успешно: XML, известные проблемные шаблоны, whitespace |
| Python-синтаксис проверочного скрипта через `ast.parse` | Успешно |
| `git diff --check` | Без замечаний |
| Сравнение `app/build.gradle.kts` и `VaultLaunchGate.kt` с main | Байты совпадают |
| Структурное сравнение манифеста с main | Только три разрешённых атрибута |
| Ручной просмотр diff | Только брендинг и вход; функциональные подсистемы/workflows не изменены |

## Что не проверялось

Gradle, SDK, эмуляторы, APK/AAB, установка, Android unit/instrumentation tests и
lint в Codex **не запускались**. Компиляция Kotlin и линковка Android-ресурсов
здесь не подтверждены. Сборка остаётся в существующем GitHub Actions workflow;
сам workflow не изменён и вручную не запускался.

После сборки нужны проверки на устройстве: cold/warm start, первый onboarding,
deep link и возврат из авторизации, восстановление Activity, сворачивание во
время входа, уменьшение анимации, TalkBack/Back/касание, узкое/альбомное окно,
маски launcher, переход системной заставки на API 31+, кадровая плавность
и память на Galaxy A17. У старого установщика/launcher может потребоваться
обновление кеша иконки после установки. Ни скриншоты Android, ни замеры FPS
или памяти не выдаются за выполненные.

## Изменённые файлы

Полный список относительно базы main (включая удалённые старые ресурсы):

- `app/src/main/AndroidManifest.xml`
- `app/src/main/java/com/sergey/animevault/MainActivity.kt`
- `app/src/main/java/com/sergey/animevault/ui/components/AnimeBrandTitle.kt`
- `app/src/main/java/com/sergey/animevault/ui/components/AnimeVaultBrandImage.kt`
- `app/src/main/java/com/sergey/animevault/ui/onboarding/OnboardingScreen.kt`
- `app/src/main/java/com/sergey/animevault/ui/settings/SettingsScreen.kt`
- `app/src/main/java/com/sergey/animevault/ui/startup/AnimeVaultLaunchIntro.kt`
- `app/src/main/res/drawable-nodpi/animevault_brand_logo.png`
- `app/src/main/res/drawable-nodpi/animevault_brand_portrait.png`
- `app/src/main/res/drawable/animevault_launch_background.xml`
- `app/src/main/res/drawable/ic_launcher_foreground.xml`
- `app/src/main/res/drawable/ic_launcher_monochrome.xml`
- `app/src/main/res/mipmap-anydpi-v26/ic_animevault.xml`
- `app/src/main/res/mipmap-anydpi-v26/ic_animevault_round.xml`
- `app/src/main/res/mipmap-anydpi-v26/ic_launcher.xml`
- `app/src/main/res/mipmap-anydpi-v26/ic_launcher_round.xml`
- `app/src/main/res/mipmap-anydpi-v33/ic_launcher.xml`
- `app/src/main/res/mipmap-anydpi-v33/ic_launcher_round.xml`
- `app/src/main/res/mipmap-anydpi/ic_launcher.xml`
- `app/src/main/res/mipmap-anydpi/ic_launcher_round.xml`
- `app/src/main/res/mipmap-hdpi/ic_animevault.png`
- `app/src/main/res/mipmap-hdpi/ic_animevault_foreground.png`
- `app/src/main/res/mipmap-hdpi/ic_animevault_round.png`
- `app/src/main/res/mipmap-mdpi/ic_animevault.png`
- `app/src/main/res/mipmap-mdpi/ic_animevault_foreground.png`
- `app/src/main/res/mipmap-mdpi/ic_animevault_round.png`
- `app/src/main/res/mipmap-xhdpi/ic_animevault.png`
- `app/src/main/res/mipmap-xhdpi/ic_animevault_foreground.png`
- `app/src/main/res/mipmap-xhdpi/ic_animevault_round.png`
- `app/src/main/res/mipmap-xxhdpi/ic_animevault.png`
- `app/src/main/res/mipmap-xxhdpi/ic_animevault_foreground.png`
- `app/src/main/res/mipmap-xxhdpi/ic_animevault_round.png`
- `app/src/main/res/mipmap-xxxhdpi/ic_animevault.png`
- `app/src/main/res/mipmap-xxxhdpi/ic_animevault_foreground.png`
- `app/src/main/res/mipmap-xxxhdpi/ic_animevault_round.png`
- `app/src/main/res/values-en/strings.xml`
- `app/src/main/res/values-v31/launch_theme.xml`
- `app/src/main/res/values/animevault_brand.xml`
- `app/src/main/res/values/launch_theme.xml`
- `app/src/main/res/values/launcher_aliases.xml`
- `app/src/main/res/values/strings.xml`
- `docs/branding/MAKIMA_INTEGRATION_REPORT.md`
- `docs/branding/MAKIMA_KIT_README_RU.md`
- `docs/branding/makima-asset-provenance.json`
- `docs/branding/makima-colors.json`
- `tools/verify-makima-branding.py`
