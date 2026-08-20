package com.sergey.animevault.data.download

import android.content.Context
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.FileDataSource
import androidx.media3.datasource.cache.CacheDataSink
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheKeyFactory
import androidx.media3.datasource.cache.NoOpCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import java.io.File

@UnstableApi
object OfflineMediaCache {
    @Volatile
    private var cache: SimpleCache? = null

    fun downloadFactory(
        context: Context,
        headers: Map<String, String>,
        downloadId: String,
    ): CacheDataSource.Factory {
        val appContext = context.applicationContext
        val sharedCache = getCache(appContext)
        val http = DefaultHttpDataSource.Factory()
            .setUserAgent(DEFAULT_USER_AGENT)
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(30_000)
            .setAllowCrossProtocolRedirects(true)
            .setDefaultRequestProperties(
                buildMap {
                    putAll(headers)
                    putIfAbsent("Accept", "*/*")
                    putIfAbsent("User-Agent", DEFAULT_USER_AGENT)
                    putIfAbsent("Connection", "keep-alive")
                },
            )
        val network = DefaultDataSource.Factory(appContext, http)
        val legacyReadThrough = legacyReadThroughFactory(sharedCache, network)
        return CacheDataSource.Factory()
            .setCache(sharedCache)
            .setCacheKeyFactory(downloadCacheKeyFactory(downloadId))
            .setCacheReadDataSourceFactory(FileDataSource.Factory())
            .setCacheWriteDataSinkFactory(CacheDataSink.Factory().setCache(sharedCache))
            // Read old 1.6.0 URI-keyed spans first and migrate them into the stable namespace.
            .setUpstreamDataSourceFactory(legacyReadThrough)
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
    }

    /** Factory used only to remove URI-keyed cache entries made by an earlier 1.6.0 build. */
    fun legacyFactory(context: Context): CacheDataSource.Factory = CacheDataSource.Factory()
        .setCache(getCache(context.applicationContext))
        .setCacheReadDataSourceFactory(FileDataSource.Factory())
        .setCacheWriteDataSinkFactory(null)

    /** Read completed/partial downloads first, then fall back to the normal playback upstream. */
    fun readThroughFactory(
        context: Context,
        upstreamFactory: DataSource.Factory,
        downloadId: String? = null,
    ): DataSource.Factory {
        val sharedCache = getCache(context.applicationContext)
        val legacy = legacyReadThroughFactory(sharedCache, upstreamFactory)
        if (downloadId == null) return legacy
        return CacheDataSource.Factory()
            .setCache(sharedCache)
            .setCacheKeyFactory(downloadCacheKeyFactory(downloadId))
            .setCacheReadDataSourceFactory(FileDataSource.Factory())
            .setCacheWriteDataSinkFactory(null)
            .setUpstreamDataSourceFactory(legacy)
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
    }

    private fun legacyReadThroughFactory(
        cache: SimpleCache,
        upstreamFactory: DataSource.Factory,
    ): CacheDataSource.Factory = CacheDataSource.Factory()
        .setCache(cache)
        .setCacheReadDataSourceFactory(FileDataSource.Factory())
        .setCacheWriteDataSinkFactory(null)
        .setUpstreamDataSourceFactory(upstreamFactory)
        .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)

    private fun downloadCacheKeyFactory(downloadId: String): CacheKeyFactory = CacheKeyFactory { dataSpec ->
        downloadCacheKey(downloadId, dataSpec.uri.toString())
    }

    private fun getCache(context: Context): SimpleCache = cache ?: synchronized(this) {
        cache ?: SimpleCache(
            File(context.filesDir, "offline-media-cache"),
            NoOpCacheEvictor(),
            StandaloneDatabaseProvider(context),
        ).also { cache = it }
    }

    private const val DEFAULT_USER_AGENT = "AnimeVault/Media3"
}
