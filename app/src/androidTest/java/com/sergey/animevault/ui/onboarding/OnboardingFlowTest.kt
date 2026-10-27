package com.sergey.animevault.ui.onboarding

import android.graphics.Bitmap
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.test.core.app.ActivityScenario
import com.sergey.animevault.MainActivity
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import com.google.firebase.FirebaseNetworkException
import com.sergey.animevault.data.auth.AccountRepository
import com.sergey.animevault.data.auth.AccountUser
import com.sergey.animevault.data.onboarding.OnboardingStore
import com.sergey.animevault.ui.preferences.AppearanceSettings
import com.sergey.animevault.ui.preferences.VaultMotionMode
import com.sergey.animevault.ui.preferences.VaultThemeMode
import com.sergey.animevault.ui.settings.AccountViewModel
import com.sergey.animevault.ui.theme.AnimeVaultTheme
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OnboardingFlowTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private lateinit var onboarding: OnboardingViewModel
    private lateinit var account: AccountViewModel

    @After fun cleanup() {
        if (::onboarding.isInitialized) onboarding.viewModelScope.cancel()
        if (::account.isInitialized) account.viewModelScope.cancel()
    }

    private fun mount(
        store: MemoryStore = MemoryStore(),
        fontScale: Float = 1f,
        token: suspend () -> String = { "ui-test-token" },
    ): MemoryStore {
        onboarding = OnboardingViewModel(store, SavedStateHandle())
        account = AccountViewModel(MemoryAccount())
        rule.runOnUiThread {
            rule.activity.enableEdgeToEdge(
                statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
                navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            )
        }
        rule.setContent {
            val current by onboarding.state.collectAsStateWithLifecycle()
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                AnimeVaultTheme(AppearanceSettings(theme = VaultThemeMode.OLED, motion = VaultMotionMode.OFF)) {
                    if (current.gate == LaunchGate.READY) {
                        Text("Главный экран", modifier = Modifier.testTag("test-home"))
                    } else {
                        OnboardingRoute(onboarding, account, requestIdToken = token, autoAdvance = false)
                    }
                }
            }
        }
        rule.waitUntil(5_000) { onboarding.state.value.gate != LaunchGate.LOADING }
        rule.waitForIdle()
        return store
    }

    private fun start() {
        rule.onNodeWithText("Начать").performScrollTo().performClick()
        rule.onNodeWithText("Продолжить с Google").performScrollTo().assertIsEnabled()
    }

    private fun screenshot(name: String) {
        rule.waitForIdle()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(checkNotNull(context.getExternalFilesDir(null)), "onboarding-preview")
        check(directory.mkdirs() || directory.isDirectory)
        val bitmap = checkNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
        val fullFile = File(directory, name + ".png")
        fullFile.outputStream().use {
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
        }
        // Small, synthetic UI previews can also be inspected from CI logs without a local SDK.
        val preview = Bitmap.createScaledBitmap(bitmap, 480, bitmap.height * 480 / bitmap.width, true)
        val previewFile = File(directory, "preview-" + name + ".png")
        previewFile.outputStream().use {
            check(preview.compress(Bitmap.CompressFormat.PNG, 100, it))
        }
        // Keep verified copies outside app-owned storage for CI artifact collection.
        shell("mkdir -p /data/local/tmp/animevault-onboarding")
        check(shell("ls -d /data/local/tmp/animevault-onboarding").trim() == "/data/local/tmp/animevault-onboarding")
        for (file in listOf(fullFile, previewFile)) {
            val destination = "/data/local/tmp/animevault-onboarding/" + file.name
            shell("cp " + file.absolutePath + " " + destination)
            check(shellBytes("cat " + destination).contentEquals(file.readBytes())) {
                "Screenshot copy failed: " + destination
            }
        }
    }

    private fun shell(command: String): String = shellBytes(command).toString(Charsets.UTF_8)

    private fun shellBytes(command: String): ByteArray {
        // API 24 exposes stdout, not the child exit code. Read to EOF; callers verify
        // the exact directory or copied PNG bytes, never a filename in an error string.
        val descriptor = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)
        return ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { it.readBytes() }
    }

    @Test fun localMediaItemReplacementKeepsSinglePlayerAndPausedPosition() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val fixture = java.io.File(context.cacheDir, "source-transition-fixture.wav")
        val byteCount = 48_000 * 2 * 5
        val bytes = java.nio.ByteBuffer.allocate(44 + byteCount).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        bytes.put("RIFF".toByteArray()).putInt(36 + byteCount).put("WAVEfmt ".toByteArray()).putInt(16)
            .putShort(1.toShort()).putShort(1.toShort()).putInt(48_000).putInt(96_000)
            .putShort(2.toShort()).putShort(16.toShort()).put("data".toByteArray()).putInt(byteCount)
        fixture.writeBytes(bytes.array())
        var player: androidx.media3.exoplayer.ExoPlayer? = null
        try {
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                player = androidx.media3.exoplayer.ExoPlayer.Builder(context).build().apply {
                    setMediaItem(androidx.media3.common.MediaItem.fromUri(android.net.Uri.fromFile(fixture)))
                    playWhenReady = false
                    prepare()
                }
            }
            rule.waitUntil(20_000) {
                var ready = false
                InstrumentationRegistry.getInstrumentation().runOnMainSync { ready = player?.playbackState == androidx.media3.common.Player.STATE_READY }
                ready
            }
            val original = player
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                player!!.setMediaItem(androidx.media3.common.MediaItem.Builder().setUri(android.net.Uri.fromFile(fixture)).setMediaId("replacement").build(), 2_000L)
                player!!.prepare()
            }
            rule.waitUntil(20_000) {
                var ready = false
                InstrumentationRegistry.getInstrumentation().runOnMainSync { ready = player?.playbackState == androidx.media3.common.Player.STATE_READY }
                ready
            }
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                org.junit.Assert.assertSame(original, player)
                org.junit.Assert.assertFalse(player!!.playWhenReady)
                org.junit.Assert.assertEquals(2_000L, player!!.currentPosition)
                org.junit.Assert.assertNull(player!!.playerError)
            }
        } finally {
            InstrumentationRegistry.getInstrumentation().runOnMainSync { player?.release() }
            fixture.delete()
        }
    }

    @Test fun productionSourceChooserCancellationAndVerifiedSwitchPreservePausedSession() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val fixture = java.io.File(context.cacheDir, "production-switch-fixture.wav")
        val count = 48_000 * 2 * 5
        val bytes = java.nio.ByteBuffer.allocate(44 + count).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        bytes.put("RIFF".toByteArray()).putInt(36 + count).put("WAVEfmt ".toByteArray()).putInt(16)
            .putShort(1.toShort()).putShort(1.toShort()).putInt(48_000).putInt(96_000)
            .putShort(2.toShort()).putShort(16.toShort()).put("data".toByteArray()).putInt(count)
        fixture.writeBytes(bytes.array())
        val firstIdentity = com.sergey.animevault.data.online.EpisodeIdentity("fixture-a", "r", "ep-a",
            com.sergey.animevault.data.online.ExternalAnimeIds(malId = 10), 1.0, "Episode 1", 5_000)
        val streams = listOf(
            com.sergey.animevault.data.online.OnlineStream("first", 720, android.net.Uri.fromFile(fixture).toString(),
                com.sergey.animevault.data.online.OnlineStreamType.MP4, translation = "Fixture voice",
                sourceName = "fixture-a", providerId = "fixture-a", providerName = "Fixture A", episodeIdentity = firstIdentity),
            com.sergey.animevault.data.online.OnlineStream("second", 720, android.net.Uri.fromFile(fixture).toString(),
                com.sergey.animevault.data.online.OnlineStreamType.MP4, translation = "Fixture voice",
                sourceName = "fixture-b", providerId = "fixture-b", providerName = "Fixture B",
                episodeIdentity = firstIdentity.copy(providerId = "fixture-b", releaseId = "r-b", episodeId = "ep-b")),
        )
        val episode = com.sergey.animevault.data.online.OnlineEpisode("fixture-a", "ep-a", "r", 1.0, "Episode 1", null, 5_000, 1.0, streams)
        val bundle = com.sergey.animevault.ui.online.OnlinePlaybackBundle("fixture-a", "Fixture A", "r", "Fixture title",
            episode, listOf(episode), com.sergey.animevault.data.online.OnlineWatchProgress(positionMs = 2_000, durationMs = 5_000), emptyMap(), null)
        val store = com.sergey.animevault.data.playback.PlaybackSessionStore(com.sergey.animevault.data.playback.PlaybackSession(
            episodeKey = bundle.playbackPlan.episodeKey, variantKey = bundle.playbackPlan.variants.first().key,
            positionMs = 2_000, durationMs = 5_000, playWhenReady = false))
        var showPlayer by mutableStateOf(true)
        val fixturePreferences = com.sergey.animevault.ui.player.PlayerPreferences(context, "online:fixture-a:r")
        fixturePreferences.preferredProviderId = null
        fixturePreferences.preferredTranslation = null
        fixturePreferences.preferredTranslationKey = null
        fixturePreferences.preferredSourceName = null
        fixturePreferences.preferredAudioLanguages = emptyList()
        fixturePreferences.preferredTextLanguages = emptyList()
        try {
            rule.setContent {
                val session by store.state.collectAsStateWithLifecycle()
                AnimeVaultTheme(AppearanceSettings(theme = VaultThemeMode.OLED, motion = VaultMotionMode.OFF)) {
                    if (showPlayer) com.sergey.animevault.ui.player.OnlineVideoPlayer(
                        playback = bundle, playbackSession = session,
                        onPlaybackSessionEvent = { store.dispatch(it) }, currentPlaybackSession = { store.state.value },
                        onSaveProgress = { _, _, _ -> }, onSelectStream = {}, onRefreshStreams = { null },
                        onPrefetchNextEpisode = { false }, onBack = {}, onPlayEpisode = {},
                        isInPictureInPictureMode = false, onEnterPictureInPicture = { false },
                    )
                }
            }
            rule.waitUntil(20_000) { store.state.value.phase in setOf(com.sergey.animevault.data.playback.PlaybackEnginePhase.PAUSED,
                com.sergey.animevault.data.playback.PlaybackEnginePhase.READY) }
            rule.onNodeWithContentDescription("Поток и озвучка").performClick()
            rule.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("Fixture B", substring = true))
            rule.onNodeWithText("Fixture B", substring = true).performClick()
            rule.onNodeWithText("Продолжить через другой источник?").assertExists()
            org.junit.Assert.assertEquals(com.sergey.animevault.data.playback.SourceTransitionPhase.AWAITING_CONFIRMATION, store.state.value.sourceTransition.phase)
            rule.onNodeWithText("Отмена").performClick()
            org.junit.Assert.assertEquals(com.sergey.animevault.data.playback.SourceTransitionPhase.CANCELLED, store.state.value.sourceTransition.phase)
            org.junit.Assert.assertEquals(bundle.playbackPlan.variants.first().key, store.state.value.variantKey)
            org.junit.Assert.assertEquals(2_000L, store.state.value.positionMs)
            org.junit.Assert.assertFalse(store.state.value.playWhenReady)
            rule.waitUntil(20_000) { store.state.value.phase in setOf(com.sergey.animevault.data.playback.PlaybackEnginePhase.PAUSED,
                com.sergey.animevault.data.playback.PlaybackEnginePhase.READY) }
            rule.onNodeWithContentDescription("Поток и озвучка").performClick()
            rule.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("Fixture B", substring = true))
            rule.onNodeWithText("Fixture B", substring = true).performClick()
            rule.onNodeWithText("Подтвердить соответствие эпизодов и монтажа").performClick()
            rule.onNodeWithText("Продолжить").performClick()
            rule.waitUntil(20_000) { store.state.value.sourceTransition.phase == com.sergey.animevault.data.playback.SourceTransitionPhase.COMPLETED }
            org.junit.Assert.assertEquals(bundle.playbackPlan.variants[1].key, store.state.value.variantKey)
            org.junit.Assert.assertFalse(store.state.value.playWhenReady)
            org.junit.Assert.assertEquals(2_000L, store.state.value.positionMs)
            org.junit.Assert.assertEquals("fixture-b", fixturePreferences.preferredProviderId)
            rule.activityRule.scenario.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)
            rule.activityRule.scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
            rule.waitForIdle()
            org.junit.Assert.assertFalse(store.state.value.playWhenReady)
            org.junit.Assert.assertEquals(bundle.playbackPlan.variants[1].key, store.state.value.variantKey)
        } finally {
            (context.applicationContext as com.sergey.animevault.AnimeVaultApplication).container.uiPreferences.clearEpisodeDecision(
                firstIdentity.fingerprint(), streams[1].episodeIdentity!!.fingerprint())
            fixturePreferences.preferredProviderId = null
            fixturePreferences.preferredTranslation = null
            fixturePreferences.preferredTranslationKey = null
            fixturePreferences.preferredSourceName = null
            rule.runOnUiThread { showPlayer = false }
            rule.waitForIdle()
            fixture.delete()
        }
    }

    @Test fun explicitAutoAndProviderOptInSurvivePreferenceRecreation() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val settings = com.sergey.animevault.ui.preferences.UiPreferences(context)
        val before = settings.automaticProviderSwitch
        try {
            settings.automaticProviderSwitch = true
            org.junit.Assert.assertTrue(com.sergey.animevault.ui.preferences.UiPreferences(context).automaticProviderSwitch)
            val first = com.sergey.animevault.ui.player.PlayerPreferences(context, "test:source-switch")
            first.preferredQuality = 720
            org.junit.Assert.assertEquals(720, com.sergey.animevault.ui.player.PlayerPreferences(context, "test:source-switch").effectiveQuality)
            first.preferredProviderId = "fixture-b"
            org.junit.Assert.assertEquals("fixture-b", com.sergey.animevault.ui.player.PlayerPreferences(context, "test:source-switch").preferredProviderId)
            first.preferredAudioLanguages = listOf("ru")
            first.preferredTextLanguages = listOf("en")
            first.subtitlesEnabled = false
            val restoredTracks = com.sergey.animevault.ui.player.PlayerPreferences(context, "test:source-switch")
            org.junit.Assert.assertEquals(listOf("ru"), restoredTracks.preferredAudioLanguages)
            org.junit.Assert.assertEquals(listOf("en"), restoredTracks.preferredTextLanguages)
            org.junit.Assert.assertFalse(restoredTracks.subtitlesEnabled)
            first.preferredQuality = null
            org.junit.Assert.assertNull(com.sergey.animevault.ui.player.PlayerPreferences(context, "test:source-switch").effectiveQuality)
        } finally { settings.automaticProviderSwitch = before }
    }

    @Test fun batchTitleCacheKeepsProviderRecordsAndClearsRemovedLocalMetadata() {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val context = object : android.content.ContextWrapper(base) {
            override fun getSharedPreferences(name: String, mode: Int): android.content.SharedPreferences =
                base.getSharedPreferences("test_batch_" + name, mode)
        }
        val job = kotlinx.coroutines.SupervisorJob()
        val scope = kotlinx.coroutines.CoroutineScope(job + kotlinx.coroutines.Dispatchers.Default)
        try {
            val store = com.sergey.animevault.data.titles.AnimeTitleStore(context, scope)
            store.put("provider:fixture:release", com.sergey.animevault.data.titles.AnimeNames(name = "Provider title"), listOf("mal:10"))
            store.putBatch(mapOf("local:999" to com.sergey.animevault.data.titles.AnimeNames(name = "Metadata title")),
                mapOf("local:999" to listOf("mal:10")), setOf(999L))
            store.putBatch(mapOf("local:999" to com.sergey.animevault.data.titles.AnimeNames.original("Filename title")),
                mapOf("local:999" to emptyList()), setOf(999L))
            org.junit.Assert.assertTrue(store.index.value.references["local:999"].orEmpty().isEmpty())
            org.junit.Assert.assertEquals("Filename title", store.index.value.names["local:999"]?.name)
            store.putBatch(emptyMap(), emptyMap(), emptySet())
            org.junit.Assert.assertFalse(store.index.value.names.containsKey("local:999"))
            org.junit.Assert.assertTrue(store.index.value.names.containsKey("provider:fixture:release"))
        } finally { job.cancel() }
    }

    @Test fun realActivityFirstLaunchGuestAndRestartUseTheExistingMainNavigation() {
        if (Build.VERSION.SDK_INT >= 33) {
            InstrumentationRegistry.getInstrumentation().uiAutomation.grantRuntimePermission(
                "com.sergey.animevault", android.Manifest.permission.POST_NOTIFICATIONS,
            )
        }
        ActivityScenario.launch(MainActivity::class.java).use {
            rule.onNodeWithText("Начать").performScrollTo().performClick()
            rule.onNodeWithText("Продолжить как гость").performScrollTo().performClick()
            rule.waitUntil(10_000) {
                rule.onAllNodesWithTag("first-launch").fetchSemanticsNodes().isEmpty()
            }
            rule.waitUntil(10_000) {
                rule.onAllNodesWithText("Главная").fetchSemanticsNodes().isNotEmpty()
            }
        }
        ActivityScenario.launch(MainActivity::class.java).use {
            rule.waitUntil(10_000) {
                rule.onAllNodesWithText("Главная").fetchSemanticsNodes().isNotEmpty()
            }
            rule.onNodeWithTag("first-launch").assertDoesNotExist()
        }
    }

    @Test fun guestFinishesAndRelaunchDoesNotShowWelcome() {
        val store = mount()
        screenshot("01-welcome")
        start()
        screenshot("02-account")
        rule.onNodeWithText("Продолжить как гость").performScrollTo().performClick()
        rule.onNodeWithText("Добро пожаловать!").assertExists()
        screenshot("03-finish-guest")
        rule.onNodeWithText("В AnimeVault").performScrollTo().performClick()
        rule.onNodeWithTag("test-home").assertExists()
        assertTrue(store.completed)
        rule.runOnUiThread { onboarding.load() }
        rule.waitUntil(5_000) { onboarding.state.value.gate == LaunchGate.READY }
        rule.onNodeWithTag("test-home").assertExists()
        rule.onNodeWithText("Начать").assertDoesNotExist()
    }

    @Test fun cancelledGoogleStaysOnAccountAndRetryShowsPersonalGreeting() {
        var attempts = 0
        mount(token = {
            attempts++
            if (attempts == 1) throw GetCredentialCancellationException()
            "ui-test-token"
        })
        start()
        rule.onNodeWithText("Продолжить с Google").performClick()
        rule.onNodeWithText("Вход отменён. Можно продолжить без аккаунта")
            .performScrollTo().assertIsDisplayed()
        assertEquals(OnboardingStep.ACCOUNT, onboarding.state.value.step)
        rule.onNodeWithText("Продолжить с Google").performScrollTo().assertIsEnabled().performClick()
        rule.onNodeWithText("Привет, Акира!").assertExists()
        screenshot("04-finish-google")
        assertEquals(2, attempts)
    }

    @Test fun networkErrorStillAllowsGuest() {
        mount(token = { throw FirebaseNetworkException("test offline") })
        start()
        rule.onNodeWithText("Продолжить с Google").performClick()
        rule.onNodeWithText("Не удалось подключиться к Google. Проверьте интернет и повторите вход")
            .performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Продолжить как гость").performScrollTo().assertIsEnabled().performClick()
        rule.onNodeWithText("В AnimeVault").performScrollTo().performClick()
        rule.onNodeWithTag("test-home").assertExists()
    }

    @Test fun guestRemainsAvailableDuringPendingGoogleRequest() {
        val pending = CompletableDeferred<String>()
        mount(token = { pending.await() })
        start()
        rule.onNodeWithText("Продолжить с Google").performClick()
        rule.onNodeWithText("Продолжить как гость").performScrollTo().assertIsEnabled().performClick()
        rule.onNodeWithText("Добро пожаловать!").assertExists()
        rule.onNodeWithText("В AnimeVault").performScrollTo().performClick()
        rule.onNodeWithTag("test-home").assertExists()
    }

    @Test fun backAndLargeTextKeepActionsReachable() {
        mount(fontScale = 1.6f)
        start()
        screenshot("05-large-text")
        rule.runOnUiThread { rule.activity.onBackPressedDispatcher.onBackPressed() }
        rule.onNodeWithText("Начать").performScrollTo().assertIsDisplayed()
        start()
        rule.onNodeWithTag("onboarding-back").performScrollTo().performClick()
        rule.onNodeWithText("Начать").performScrollTo().assertIsDisplayed()
    }

    private class MemoryStore : OnboardingStore {
        var completed = false
        override suspend fun shouldShow() = !completed
        override suspend fun complete() { completed = true }
    }

    private class MemoryAccount : AccountRepository {
        override val user = MutableStateFlow<AccountUser?>(null)
        override suspend fun signIn(idToken: String) {
            check(idToken == "ui-test-token")
            user.value = AccountUser("ui-test-user", "Акира Сато", "test@example.com", null)
        }
        override fun signOut() { user.value = null }
    }
}
