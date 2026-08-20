package com.sergey.animevault.ui.player

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.sergey.animevault.AnimeVaultApplication
import com.sergey.animevault.ui.online.OnlinePlayerViewModel
import com.sergey.animevault.ui.theme.AnimeVaultTheme

/**
 * Отдельная полноэкранная Activity для онлайн-видео.
 *
 * Основной режим получает идентификаторы серии и сам загружает потоки через
 * OnlineRepository. Дополнительный режим позволяет открыть готовый m3u8 или
 * ссылку Kodik напрямую через Intent — удобно для новых источников и отладки.
 */
class PlayerActivity : ComponentActivity() {
    private var isPlayerInPictureInPicture by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        val onlineRequest = OnlinePlayerRequest.from(intent)
        val directRequest = DirectPlaybackRequest.from(intent)
        val downloadRequest = DownloadPlaybackRequest.from(intent)
        val application = applicationContext as AnimeVaultApplication
        val downloaded = downloadRequest?.let { application.container.downloadRepository.playbackSource(it.downloadId) }
        if (onlineRequest == null && directRequest == null && downloaded == null) {
            Toast.makeText(this, "Не передана ссылка или серия для просмотра", Toast.LENGTH_LONG).show()
            finish()
            return
        }

        setContent {
            AnimeVaultTheme {
                if (downloaded != null) {
                    val (entry, source) = downloaded
                    DownloadedPlayerRoute(
                        entry = entry,
                        source = source,
                        onBack = ::finish,
                        onSaveProgress = { positionMs, durationMs, ended ->
                            application.container.onlineRepository.saveProgress(
                                providerId = entry.providerId,
                                episodeId = entry.episodeId,
                                positionMs = positionMs,
                                durationMs = durationMs,
                                ended = ended,
                            )
                        },
                        isInPictureInPictureMode = isPlayerInPictureInPicture,
                        onEnterPictureInPicture = { enterPlayerPictureInPicture(this) },
                    )
                } else if (onlineRequest != null) {
                    val repository = application.container.onlineRepository
                    val libraryRepository = application.container.libraryRepository
                    val aniListSyncRepository = application.container.aniListSyncRepository
                    val factory = remember(onlineRequest, repository, libraryRepository, aniListSyncRepository) {
                        OnlinePlayerViewModel.Factory(
                            providerId = onlineRequest.providerId,
                            releaseId = onlineRequest.releaseId,
                            episodeId = onlineRequest.episodeId,
                            repository = repository,
                            libraryRepository = libraryRepository,
                            aniListSyncRepository = aniListSyncRepository,
                        )
                    }
                    val playerViewModel: OnlinePlayerViewModel = viewModel(factory = factory)
                    OnlinePlayerRoute(
                        viewModel = playerViewModel,
                        onBack = ::finish,
                        isInPictureInPictureMode = isPlayerInPictureInPicture,
                        onEnterPictureInPicture = { enterPlayerPictureInPicture(this) },
                        onPlayEpisode = { nextEpisodeId ->
                            startActivity(
                                onlineIntent(
                                    context = this,
                                    providerId = onlineRequest.providerId,
                                    releaseId = onlineRequest.releaseId,
                                    episodeId = nextEpisodeId,
                                ),
                            )
                            finish()
                        },
                    )
                } else if (directRequest != null) {
                    DirectPlayerRoute(
                        request = directRequest,
                        onBack = ::finish,
                        isInPictureInPictureMode = isPlayerInPictureInPicture,
                        onEnterPictureInPicture = { enterPlayerPictureInPicture(this) },
                    )
                }
            }
        }
    }

    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        newConfig: Configuration,
    ) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        isPlayerInPictureInPicture = isInPictureInPictureMode
    }

    internal data class OnlinePlayerRequest(
        val providerId: String,
        val releaseId: String,
        val episodeId: String,
    ) {
        companion object {
            fun from(intent: Intent): OnlinePlayerRequest? {
                val providerId = intent.getStringExtra(EXTRA_PROVIDER_ID)?.takeIf(String::isNotBlank)
                val releaseId = intent.getStringExtra(EXTRA_RELEASE_ID)?.takeIf(String::isNotBlank)
                val episodeId = intent.getStringExtra(EXTRA_EPISODE_ID)?.takeIf(String::isNotBlank)
                return if (providerId != null && releaseId != null && episodeId != null) {
                    OnlinePlayerRequest(providerId, releaseId, episodeId)
                } else {
                    null
                }
            }
        }
    }

    companion object {
        const val EXTRA_TITLE = "player.title"
        const val EXTRA_M3U8_URL = "player.m3u8_url"
        const val EXTRA_KODIK_LINK = "player.kodik_link"
        const val EXTRA_VOICE = "player.voice"
        const val EXTRA_QUALITY = "player.quality"
        const val EXTRA_REFERER = "player.referer"
        const val EXTRA_USER_AGENT = "player.user_agent"

        private const val EXTRA_PROVIDER_ID = "player.provider_id"
        private const val EXTRA_RELEASE_ID = "player.release_id"
        private const val EXTRA_EPISODE_ID = "player.episode_id"
        private const val EXTRA_DOWNLOAD_ID = "player.download_id"
        internal const val EXTRA_DOWNLOAD_ID_FOR_REQUEST = EXTRA_DOWNLOAD_ID

        fun onlineIntent(
            context: Context,
            providerId: String,
            releaseId: String,
            episodeId: String,
        ): Intent = Intent(context, PlayerActivity::class.java).apply {
            putExtra(EXTRA_PROVIDER_ID, providerId)
            putExtra(EXTRA_RELEASE_ID, releaseId)
            putExtra(EXTRA_EPISODE_ID, episodeId)
        }

        fun downloadIntent(
            context: Context,
            downloadId: String,
        ): Intent = Intent(context, PlayerActivity::class.java).apply {
            putExtra(EXTRA_DOWNLOAD_ID, downloadId)
        }

        /**
         * Пример для нового источника: передайте m3u8Url. Если прямого HLS нет,
         * передайте kodikLink — Activity попробует извлечь 360/480/720p сама.
         */
        fun directIntent(
            context: Context,
            title: String,
            m3u8Url: String? = null,
            kodikLink: String? = null,
            voice: String? = null,
            quality: Int? = null,
            referer: String? = null,
            userAgent: String? = null,
        ): Intent = Intent(context, PlayerActivity::class.java).apply {
            require(!m3u8Url.isNullOrBlank() || !kodikLink.isNullOrBlank()) {
                "Нужна прямая m3u8-ссылка или ссылка Kodik"
            }
            putExtra(EXTRA_TITLE, title)
            m3u8Url?.let { putExtra(EXTRA_M3U8_URL, it) }
            kodikLink?.let { putExtra(EXTRA_KODIK_LINK, it) }
            voice?.let { putExtra(EXTRA_VOICE, it) }
            quality?.let { putExtra(EXTRA_QUALITY, it) }
            referer?.let { putExtra(EXTRA_REFERER, it) }
            userAgent?.let { putExtra(EXTRA_USER_AGENT, it) }
        }
    }
}

internal data class DownloadPlaybackRequest(
    val downloadId: String,
) {
    companion object {
        fun from(intent: Intent): DownloadPlaybackRequest? = intent
            .getStringExtra(PlayerActivity.EXTRA_DOWNLOAD_ID_FOR_REQUEST)
            ?.takeIf(String::isNotBlank)
            ?.let(::DownloadPlaybackRequest)
    }
}

internal data class DirectPlaybackRequest(
    val title: String,
    val m3u8Url: String?,
    val kodikLink: String?,
    val voice: String?,
    val quality: Int?,
    val referer: String?,
    val userAgent: String?,
) {
    companion object {
        fun from(intent: Intent): DirectPlaybackRequest? {
            val m3u8Url = intent.getStringExtra(PlayerActivity.EXTRA_M3U8_URL)
                ?.takeIf(String::isNotBlank)
            val kodikLink = intent.getStringExtra(PlayerActivity.EXTRA_KODIK_LINK)
                ?.takeIf(String::isNotBlank)
            if (m3u8Url == null && kodikLink == null) return null
            return DirectPlaybackRequest(
                title = intent.getStringExtra(PlayerActivity.EXTRA_TITLE)
                    ?.takeIf(String::isNotBlank)
                    ?: "Онлайн-видео",
                m3u8Url = m3u8Url,
                kodikLink = kodikLink,
                voice = intent.getStringExtra(PlayerActivity.EXTRA_VOICE)?.takeIf(String::isNotBlank),
                quality = intent.getIntExtra(PlayerActivity.EXTRA_QUALITY, -1).takeIf { it > 0 },
                referer = intent.getStringExtra(PlayerActivity.EXTRA_REFERER)?.takeIf(String::isNotBlank),
                userAgent = intent.getStringExtra(PlayerActivity.EXTRA_USER_AGENT)?.takeIf(String::isNotBlank),
            )
        }
    }
}
