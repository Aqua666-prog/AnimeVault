# Разбор Android Lint для PR #59

Источник: `app/build/reports/lint-results-debug.xml`, debug variant, исходная Android-база `12fc546e902f8d4edea2b458376d94eaad990353`.
Файлы приложения и Gradle в PR совпадают с базой; Lint config, baseline и suppressions не менялись.
Это приоритизация предупреждений для этой ревизии, не полный аудит приложения и не основание расширять scope PR.

Встроенная severity Lint: **0 Fatal/Error, 66 Warning, отдельно 5 Hint**.
Наш приоритет после просмотра групп и затронутых мест: **критических 0, высоких 0, средних 9, низких 57**.
Отсутствие high findings среди этих сообщений не доказывает отсутствие security bugs в приложении.

| ID Lint | Количество | Приоритет и обоснование |
|---|---:|---|
| ConfigurationScreenWidthHeight | 5 | Средний: размер Configuration не всегда равен доступному окну; проверить split screen, foldable и insets. |
| InlinedApi | 2 | Средний: проверить достижимость POST_NOTIFICATIONS на API24–32. Константа String инлайнится; само упоминание не доказывает crash. Callback следует запуску request, а dispatch уже учитывает API33. |
| PictureInPictureIssue | 1 | Средний: helper проверяет API26 и feature, но не задаёт autoEnter/sourceRectHint. Риск качества перехода PiP на API31+, не доказанный crash. |
| UsableSpace | 1 | Средний: usableSpace не учитывает очищаемый кэш; возможен преждевременный отказ updater при нехватке места. Предлагаемый allocateBytes требует отдельной проверки. |
| CheckResult | 2 | Низкий: оба сообщения относятся к раннему return null в private parser functions; полезный результат возвращается caller. По осмотренному коду похоже на false positive анализатора; не подавлялось. |
| ComposableNaming | 1 | Низкий: naming composable с возвращаемым значением. |
| GradleDependency | 17 | Низкий: доступна новая версия, не сообщение о CVE. Обновлять отдельной задачей с compatibility проверками. |
| ModifierParameter | 4 | Низкий: порядок optional parameters в Compose API, не изменение runtime поведения. |
| NewerVersionAvailable | 7 | Низкий: справка об обновлении, не утверждение об уязвимости; текущая сборка прошла. |
| UnusedResources | 4 | Низкий: размер/поддержка ресурсов; удаление не требуется этой настройкой. |
| UseKtx | 22 | Низкий: рекомендации idiomatic KTX; не признак неверного runtime поведения. |

5 `AutoboxingStateCreation` имеют severity Hint и низкий приоритет: primitive state APIs сокращают boxing; без замеров это не доказанный bottleneck. Они не входят в число 66.

Средние предупреждения заслуживают отдельных проверок UI/notifications/updater. Они существовали до PR; правки приложения не включены, чтобы сохранить цель интеграции навыков.

## Все 66 предупреждений

Ниже полный список для воспроизводимости. Номера строк относятся к Android-базе PR.

| № | ID | Приоритет | Место | Сообщение |
|---:|---|---|---|---|
| 1 | CheckResult | Низкий | [AnimetkaJson.kt:241](../app/src/main/java/com/sergey/animevault/data/animetka/AnimetkaJson.kt#L241) | The result of `null` is not used |
| 2 | CheckResult | Низкий | [DreamerscastProvider.kt:356](../app/src/main/java/com/sergey/animevault/data/dreamerscast/DreamerscastProvider.kt#L356) | The result of `null` is not used |
| 3 | InlinedApi | Средний | [NotificationAccess.kt:63](../app/src/main/java/com/sergey/animevault/ui/notifications/NotificationAccess.kt#L63) | Field requires API level 33 (current min is 24): `android.Manifest.permission#POST_NOTIFICATIONS` |
| 4 | InlinedApi | Средний | [NotificationAccess.kt:101](../app/src/main/java/com/sergey/animevault/ui/notifications/NotificationAccess.kt#L101) | Field requires API level 33 (current min is 24): `android.Manifest.permission#POST_NOTIFICATIONS` |
| 5 | PictureInPictureIssue | Средний | [AndroidManifest.xml:21](../app/src/main/AndroidManifest.xml#L21) | An activity in this app supports picture-in-picture and the targetSdkVersion is 31 or above; it is therefore strongly recommended to call both `setAutoEnterEnabled(true)` and `setSourceRectHint(...)` |
| 6 | GradleDependency | Низкий | [build.gradle.kts:144](../app/build.gradle.kts#L144) | A newer version of androidx.datastore:datastore-preferences than 1.1.7 is available: 1.3.0 |
| 7 | GradleDependency | Низкий | [build.gradle.kts:145](../app/build.gradle.kts#L145) | A newer version of androidx.core:core-ktx than 1.18.0 is available: 1.19.1 |
| 8 | GradleDependency | Низкий | [build.gradle.kts:149](../app/build.gradle.kts#L149) | A newer version of androidx.navigation:navigation-compose than 2.9.8 is available: 2.10.2 |
| 9 | GradleDependency | Низкий | [build.gradle.kts:158](../app/build.gradle.kts#L158) | A newer version of androidx.room:room-runtime than 2.8.4 is available: 2.8.5 |
| 10 | GradleDependency | Низкий | [build.gradle.kts:159](../app/build.gradle.kts#L159) | A newer version of androidx.room:room-ktx than 2.8.4 is available: 2.8.5 |
| 11 | GradleDependency | Низкий | [build.gradle.kts:160](../app/build.gradle.kts#L160) | A newer version of androidx.room:room-compiler than 2.8.4 is available: 2.8.5 |
| 12 | GradleDependency | Низкий | [build.gradle.kts:165](../app/build.gradle.kts#L165) | A newer version of com.google.firebase:firebase-bom than 34.0.0 is available: 35.1.0 |
| 13 | GradleDependency | Низкий | [build.gradle.kts:167](../app/build.gradle.kts#L167) | A newer version of androidx.credentials:credentials than 1.5.0 is available: 1.6.0 |
| 14 | GradleDependency | Низкий | [build.gradle.kts:168](../app/build.gradle.kts#L168) | A newer version of androidx.credentials:credentials-play-services-auth than 1.5.0 is available: 1.6.0 |
| 15 | GradleDependency | Низкий | [build.gradle.kts:169](../app/build.gradle.kts#L169) | A newer version of com.google.android.libraries.identity.googleid:googleid than 1.1.1 is available: 1.2.1 |
| 16 | GradleDependency | Низкий | [build.gradle.kts:174](../app/build.gradle.kts#L174) | A newer version of androidx.work:work-runtime-ktx than 2.11.2 is available: 2.12.0 |
| 17 | GradleDependency | Низкий | [build.gradle.kts:182](../app/build.gradle.kts#L182) | A newer version of androidx.media3:media3-exoplayer than 1.11.0 is available: 1.11.1 |
| 18 | GradleDependency | Низкий | [build.gradle.kts:183](../app/build.gradle.kts#L183) | A newer version of androidx.media3:media3-exoplayer-hls than 1.11.0 is available: 1.11.1 |
| 19 | GradleDependency | Низкий | [build.gradle.kts:184](../app/build.gradle.kts#L184) | A newer version of androidx.media3:media3-ui than 1.11.0 is available: 1.11.1 |
| 20 | GradleDependency | Низкий | [build.gradle.kts:185](../app/build.gradle.kts#L185) | A newer version of androidx.media3:media3-session than 1.11.0 is available: 1.11.1 |
| 21 | GradleDependency | Низкий | [build.gradle.kts:194](../app/build.gradle.kts#L194) | A newer version of androidx.room:room-testing than 2.8.4 is available: 2.8.5 |
| 22 | GradleDependency | Низкий | [build.gradle.kts:195](../app/build.gradle.kts#L195) | A newer version of androidx.work:work-testing than 2.11.2 is available: 2.12.0 |
| 23 | NewerVersionAvailable | Низкий | [build.gradle.kts:162](../app/build.gradle.kts#L162) | A newer version of org.jetbrains.kotlinx:kotlinx-coroutines-android than 1.10.2 is available: 1.11.0 |
| 24 | NewerVersionAvailable | Низкий | [build.gradle.kts:163](../app/build.gradle.kts#L163) | A newer version of org.jetbrains.kotlinx:kotlinx-coroutines-play-services than 1.10.2 is available: 1.11.0 |
| 25 | NewerVersionAvailable | Низкий | [build.gradle.kts:175](../app/build.gradle.kts#L175) | A newer version of io.coil-kt.coil3:coil-compose than 3.5.0 is available: 3.6.3 |
| 26 | NewerVersionAvailable | Низкий | [build.gradle.kts:176](../app/build.gradle.kts#L176) | A newer version of io.coil-kt.coil3:coil-network-okhttp than 3.5.0 is available: 3.6.3 |
| 27 | NewerVersionAvailable | Низкий | [build.gradle.kts:177](../app/build.gradle.kts#L177) | A newer version of com.squareup.okio:okio-jvm than 3.17.0 is available: 3.18.2 |
| 28 | NewerVersionAvailable | Низкий | [build.gradle.kts:188](../app/build.gradle.kts#L188) | A newer version of org.json:json than 20250517 is available: 20260814 |
| 29 | NewerVersionAvailable | Низкий | [build.gradle.kts:189](../app/build.gradle.kts#L189) | A newer version of org.jetbrains.kotlinx:kotlinx-coroutines-test than 1.10.2 is available: 1.11.0 |
| 30 | ComposableNaming | Низкий | [CommunityUi.kt:98](../app/src/main/java/com/sergey/animevault/ui/community/CommunityUi.kt#L98) | Composable functions with a return type should start with a lowercase letter |
| 31 | ConfigurationScreenWidthHeight | Средний | [OnlineCatalogScreen.kt:235](../app/src/main/java/com/sergey/animevault/ui/online/OnlineCatalogScreen.kt#L235) | Using Configuration.screenHeightDp instead of LocalWindowInfo.current.containerSize |
| 32 | ConfigurationScreenWidthHeight | Средний | [PlayerChrome.kt:106](../app/src/main/java/com/sergey/animevault/ui/player/PlayerChrome.kt#L106) | Using Configuration.screenWidthDp instead of LocalWindowInfo.current.containerSize |
| 33 | ConfigurationScreenWidthHeight | Средний | [PlayerChrome.kt:115](../app/src/main/java/com/sergey/animevault/ui/player/PlayerChrome.kt#L115) | Using Configuration.screenHeightDp instead of LocalWindowInfo.current.containerSize |
| 34 | ConfigurationScreenWidthHeight | Средний | [PlayerChrome.kt:186](../app/src/main/java/com/sergey/animevault/ui/player/PlayerChrome.kt#L186) | Using Configuration.screenHeightDp instead of LocalWindowInfo.current.containerSize |
| 35 | ConfigurationScreenWidthHeight | Средний | [PlayerChrome.kt:186](../app/src/main/java/com/sergey/animevault/ui/player/PlayerChrome.kt#L186) | Using Configuration.screenWidthDp instead of LocalWindowInfo.current.containerSize |
| 36 | ModifierParameter | Низкий | [PlayerChrome.kt:130](../app/src/main/java/com/sergey/animevault/ui/player/PlayerChrome.kt#L130) | Modifier parameter should be the first optional parameter |
| 37 | ModifierParameter | Низкий | [VaultComponents.kt:75](../app/src/main/java/com/sergey/animevault/ui/components/VaultComponents.kt#L75) | Modifier parameter should be the first optional parameter |
| 38 | ModifierParameter | Низкий | [VaultComponents.kt:157](../app/src/main/java/com/sergey/animevault/ui/components/VaultComponents.kt#L157) | Modifier parameter should be the first optional parameter |
| 39 | ModifierParameter | Низкий | [VaultComponents.kt:501](../app/src/main/java/com/sergey/animevault/ui/components/VaultComponents.kt#L501) | Modifier parameter should be the first optional parameter |
| 40 | UnusedResources | Низкий | [colors.xml:3](../app/src/main/res/values/colors.xml#L3) | The resource `R.color.launcher_background` appears to be unused |
| 41 | UnusedResources | Низкий | [launcher_aliases.xml:4](../app/src/main/res/values/launcher_aliases.xml#L4) | The resource `R.mipmap.ic_launcher_round` appears to be unused |
| 42 | UnusedResources | Низкий | [strings.xml:9](../app/src/main/res/values/strings.xml#L9) | The resource `R.string.download_missing_file` appears to be unused |
| 43 | UnusedResources | Низкий | [strings.xml:10](../app/src/main/res/values/strings.xml#L10) | The resource `R.string.download_retry_required` appears to be unused |
| 44 | UsableSpace | Средний | [GitHubUpdateRepository.kt:95](../app/src/main/java/com/sergey/animevault/data/update/GitHubUpdateRepository.kt#L95) | Consider also using `StorageManager#getAllocatableBytes` and `allocateBytes` which will consider clearable cached data |
| 45 | UseKtx | Низкий | [AniListSyncRepository.kt:68](../app/src/main/java/com/sergey/animevault/data/anilist/AniListSyncRepository.kt#L68) | Use the KTX extension function `String.toUri` instead? |
| 46 | UseKtx | Низкий | [AnimeCalendarScreen.kt:152](../app/src/main/java/com/sergey/animevault/ui/calendar/AnimeCalendarScreen.kt#L152) | Use the KTX extension function `String.toUri` instead? |
| 47 | UseKtx | Низкий | [DownloadRepository.kt:32](../app/src/main/java/com/sergey/animevault/data/download/DownloadRepository.kt#L32) | Use the KTX extension function `SharedPreferences.edit` instead? |
| 48 | UseKtx | Низкий | [DownloadRepository.kt:209](../app/src/main/java/com/sergey/animevault/data/download/DownloadRepository.kt#L209) | Use the KTX extension function `SharedPreferences.edit` instead? |
| 49 | UseKtx | Низкий | [DownloadRepository.kt:276](../app/src/main/java/com/sergey/animevault/data/download/DownloadRepository.kt#L276) | Use the KTX extension function `SharedPreferences.edit` instead? |
| 50 | UseKtx | Низкий | [EmbeddedOnlinePlayer.kt:89](../app/src/main/java/com/sergey/animevault/ui/player/EmbeddedOnlinePlayer.kt#L89) | Use the KTX extension function `String.toUri` instead? |
| 51 | UseKtx | Низкий | [ExternalEpisodePlaylist.kt:122](../app/src/main/java/com/sergey/animevault/ui/online/ExternalEpisodePlaylist.kt#L122) | Use the KTX extension function `String.toUri` instead? |
| 52 | UseKtx | Низкий | [ExternalEpisodePlaylist.kt:123](../app/src/main/java/com/sergey/animevault/ui/online/ExternalEpisodePlaylist.kt#L123) | Use the KTX extension function `String.toUri` instead? |
| 53 | UseKtx | Низкий | [GitHubUpdateRepository.kt:46](../app/src/main/java/com/sergey/animevault/data/update/GitHubUpdateRepository.kt#L46) | Use the KTX extension function `SharedPreferences.edit` instead? |
| 54 | UseKtx | Низкий | [GitHubUpdateRepository.kt:54](../app/src/main/java/com/sergey/animevault/data/update/GitHubUpdateRepository.kt#L54) | Use the KTX extension function `SharedPreferences.edit` instead? |
| 55 | UseKtx | Низкий | [GitHubUpdateRepository.kt:58](../app/src/main/java/com/sergey/animevault/data/update/GitHubUpdateRepository.kt#L58) | Use the KTX extension function `SharedPreferences.edit` instead? |
| 56 | UseKtx | Низкий | [NotificationAccess.kt:145](../app/src/main/java/com/sergey/animevault/ui/notifications/NotificationAccess.kt#L145) | Use the KTX extension function `String.toUri` instead? |
| 57 | UseKtx | Низкий | [OnlineTitleExtrasSection.kt:614](../app/src/main/java/com/sergey/animevault/ui/online/OnlineTitleExtrasSection.kt#L614) | Use the KTX extension function `String.toUri` instead? |
| 58 | UseKtx | Низкий | [OnlineTitleExtrasSection.kt:1089](../app/src/main/java/com/sergey/animevault/ui/online/OnlineTitleExtrasSection.kt#L1089) | Use the KTX extension function `String.toUri` instead? |
| 59 | UseKtx | Низкий | [SettingsScreen.kt:578](../app/src/main/java/com/sergey/animevault/ui/settings/SettingsScreen.kt#L578) | Use the KTX extension function `String.toUri` instead? |
| 60 | UseKtx | Низкий | [SharedDownloadStorage.kt:118](../app/src/main/java/com/sergey/animevault/data/download/SharedDownloadStorage.kt#L118) | Use the KTX extension function `String.toUri` instead? |
| 61 | UseKtx | Низкий | [SharedDownloadStorage.kt:131](../app/src/main/java/com/sergey/animevault/data/download/SharedDownloadStorage.kt#L131) | Use the KTX extension function `String.toUri` instead? |
| 62 | UseKtx | Низкий | [SharedDownloadStorage.kt:140](../app/src/main/java/com/sergey/animevault/data/download/SharedDownloadStorage.kt#L140) | Use the KTX extension function `String.toUri` instead? |
| 63 | UseKtx | Низкий | [SharedDownloadStorage.kt:149](../app/src/main/java/com/sergey/animevault/data/download/SharedDownloadStorage.kt#L149) | Use the KTX extension function `String.toUri` instead? |
| 64 | UseKtx | Низкий | [ShikimoriCollectionsScreen.kt:87](../app/src/main/java/com/sergey/animevault/ui/shikimori/ShikimoriCollectionsScreen.kt#L87) | Use the KTX extension function `String.toUri` instead? |
| 65 | UseKtx | Низкий | [UpdaterUi.kt:104](../app/src/main/java/com/sergey/animevault/ui/update/UpdaterUi.kt#L104) | Use the KTX extension function `String.toUri` instead? |
| 66 | UseKtx | Низкий | [VaultArtwork.kt:116](../app/src/main/java/com/sergey/animevault/ui/components/VaultArtwork.kt#L116) | Use the KTX extension function `Bitmap.get` instead? |
