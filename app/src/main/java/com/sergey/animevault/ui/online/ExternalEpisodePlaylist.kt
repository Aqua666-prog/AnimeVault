package com.sergey.animevault.ui.online

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import com.sergey.animevault.data.online.OnlineEpisode
import com.sergey.animevault.data.online.OnlineReleaseDetails
import com.sergey.animevault.data.online.OnlineRepository
import com.sergey.animevault.data.online.OnlineStream
import com.sergey.animevault.data.online.OnlineStreamType
import com.sergey.animevault.data.playback.OnlineStreamPreference
import com.sergey.animevault.data.playback.OnlineStreamResolver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File

private const val MAX_EXTERNAL_EPISODES = 12

data class ExternalPlaylistEntry(
    val title: String,
    val url: String,
    val headers: Map<String, String> = emptyMap(),
)

/** Standard M3U: compatible with playlist-capable external players, but cannot carry HTTP headers. */
fun buildExternalM3u(entries: List<ExternalPlaylistEntry>): String {
    require(entries.isNotEmpty() && entries.size <= MAX_EXTERNAL_EPISODES)
    return buildString {
        append("#EXTM3U\n")
        entries.forEach { item ->
            require(item.url.startsWith("https://", ignoreCase = true) &&
                '\r' !in item.url && '\n' !in item.url)
            append("#EXTINF:-1,")
            append(item.title.replace(Regex("[\\r\\n]+"), " ").take(160))
            append('\n')
            item.headers.forEach { (key, value) ->
                // VLC-specific directives. Other M3U players may ignore them.
                val option = when (key.lowercase()) {
                    "referer", "referrer" -> "http-referrer"
                    "user-agent" -> "http-user-agent"
                    else -> null
                }
                if (option != null && '\r' !in value && '\n' !in value) {
                    append("#EXTVLCOPT:").append(option).append('=').append(value.take(512)).append('\n')
                }
            }
            append(item.url)
            append('\n')
        }
    }
}

private fun selectExternalStream(streams: List<OnlineStream>, preferredQuality: Int?): OnlineStream? {
    val native = streams.filter {
        it.type != OnlineStreamType.EMBED && it.url.startsWith("https://", ignoreCase = true) &&
            it.headers.keys.none { header -> header.equals("authorization", ignoreCase = true) }
    }
    if (native.isEmpty()) return null
    return OnlineStreamResolver.selectPreferred(
        native,
        OnlineStreamPreference(quality = preferredQuality),
    )
}

/** Resolves a capped queue just before sharing so CDN links have the freshest possible expiry. */
suspend fun exportExternalPlaylist(
    context: Context,
    release: OnlineReleaseDetails,
    repository: OnlineRepository,
    preferredQuality: Int?,
): Pair<Intent, Int> {
    val episodes = release.episodes.filter(OnlineEpisode::hasStream)
        .distinctBy(OnlineEpisode::id).take(MAX_EXTERNAL_EPISODES)
    val gate = Semaphore(3) // Bound provider requests and avoid flooding API/CDN hosts.
    val entries = supervisorScope {
        episodes.mapIndexed { index, episode ->
            async {
                gate.withPermit {
                    val streams = try {
                        repository.resolveStreams(release.providerId, release.id, episode)
                    } catch (cancel: kotlinx.coroutines.CancellationException) {
                        throw cancel
                    } catch (_: Exception) {
                        return@withPermit null
                    }
                    val stream = selectExternalStream(streams, preferredQuality) ?: return@withPermit null
                    ExternalPlaylistEntry(
                        "${release.name} — серия ${episode.ordinal?.toInt() ?: (index + 1)}",
                        stream.url,
                        stream.headers,
                    )
                }
            }
        }.awaitAll().filterNotNull()
    }
    if (entries.isEmpty()) {
        throw IllegalStateException("Нет прямых HTTPS-потоков для внешнего плеера")
    }
    val uri = withContext(Dispatchers.IO) {
        val folder = File(context.cacheDir, "external_playlists")
        if (!folder.exists() && !folder.mkdirs()) throw IllegalStateException("Нет доступа к кешу плейлистов")
        // Use unique files: an older chooser may still be reading a previous playlist.
        folder.listFiles()?.filter { it.isFile && System.currentTimeMillis() - it.lastModified() > 86_400_000L }
            ?.forEach { it.delete() }
        val file = File.createTempFile("animevault_queue_", ".m3u", folder)
        file.writeText(buildExternalM3u(entries), Charsets.UTF_8)
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    }
    val openPlaylist = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, "audio/x-mpegurl")
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    // MX Player has an explicit Uri[] playlist contract, unlike regular Android ACTION_VIEW.
    fun mxIntent(packageName: String): Intent = Intent(Intent.ACTION_VIEW).apply {
        setPackage(packageName)
        setDataAndType(Uri.parse(entries.first().url), "video/*")
        putExtra("video_list", entries.map { Uri.parse(it.url) }.toTypedArray())
        putExtra("video_list.name", entries.map { it.title }.toTypedArray())
        putExtra("video_list_is_explicit", true)
    }
    val chooser = Intent.createChooser(openPlaylist, "Передать очередь во внешний плеер")
    chooser.putExtra(
        Intent.EXTRA_INITIAL_INTENTS,
        arrayOf(mxIntent("com.mxtech.videoplayer.ad"), mxIntent("com.mxtech.videoplayer.pro")),
    )
    return chooser to entries.size
}
