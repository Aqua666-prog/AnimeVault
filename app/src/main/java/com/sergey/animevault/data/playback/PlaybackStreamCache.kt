package com.sergey.animevault.data.playback

import android.content.Context
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.FileDataSource
import androidx.media3.datasource.cache.CacheDataSink
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import java.io.File

/** App-wide small read-through cache for online Media3 playback. */
object PlaybackStreamCache {
    const val DEFAULT_CACHE_BYTES: Long = 192L * 1024L * 1024L

    @Volatile
    private var cache: SimpleCache? = null

    fun wrap(
        context: Context,
        upstreamFactory: DataSource.Factory,
    ): DataSource.Factory {
        val appContext = context.applicationContext
        val sharedCache = cache ?: synchronized(this) {
            cache ?: createCache(appContext).also { cache = it }
        }
        return CacheDataSource.Factory()
            .setCache(sharedCache)
            .setCacheReadDataSourceFactory(FileDataSource.Factory())
            .setCacheWriteDataSinkFactory(CacheDataSink.Factory().setCache(sharedCache))
            .setUpstreamDataSourceFactory(upstreamFactory)
            // Cache is an optimisation. Corruption or a filesystem error must never kill playback.
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
    }

    private fun createCache(context: Context): SimpleCache = SimpleCache(
        File(context.cacheDir, "stream-cache"),
        LeastRecentlyUsedCacheEvictor(DEFAULT_CACHE_BYTES),
        StandaloneDatabaseProvider(context),
    )
}
