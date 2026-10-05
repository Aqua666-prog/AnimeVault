package com.sergey.animevault.data.metadata

import android.content.Context
import com.google.gson.Gson
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

data class CachedTenraiResponse(
    val body: String,
    val storedAtMs: Long,
)

interface TenraiResponseCache {
    suspend fun get(key: String): CachedTenraiResponse?
    suspend fun put(key: String, body: String)
    suspend fun clear()
}

/**
 * Tiny persistent response cache for Tenrai.
 *
 * It stores successful JSON responses in no-backup app storage. The cache is not a
 * source of truth: TenraiClient only uses it after network/rate-limit/server failures.
 * Entries are bounded by count and total disk usage and are safe to discard at any time.
 */
class TenraiFileResponseCache(
    context: Context,
    private val gson: Gson = Gson(),
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val maxEntries: Int = 160,
    private val maxBytes: Long = 8L * 1024L * 1024L,
    private val maxEntryBytes: Int = 1_500_000,
) : TenraiResponseCache {
    private val directory = File(context.noBackupFilesDir, "tenrai_response_cache")
    private val mutex = Mutex()

    init {
        require(maxEntries > 0)
        require(maxBytes > 0L)
        require(maxEntryBytes > 0)
    }

    override suspend fun get(key: String): CachedTenraiResponse? = withContext(Dispatchers.IO) {
        mutex.withLock {
            val file = fileFor(key)
            if (!file.isFile) return@withLock null
            val record = try {
                gson.fromJson(file.readText(), CacheRecord::class.java)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                file.delete()
                return@withLock null
            }
            if (record == null || record.storedAtMs <= 0L || record.body.isBlank()) {
                file.delete()
                return@withLock null
            }
            file.setLastModified(nowMs())
            CachedTenraiResponse(record.body, record.storedAtMs)
        }
    }

    override suspend fun put(key: String, body: String) = withContext(Dispatchers.IO) {
        if (body.isBlank() || body.toByteArray(Charsets.UTF_8).size > maxEntryBytes) return@withContext
        mutex.withLock {
            directory.mkdirs()
            val target = fileFor(key)
            val temp = File(directory, target.name + ".tmp")
            val record = CacheRecord(storedAtMs = nowMs(), body = body)
            temp.writeText(gson.toJson(record))
            if (!temp.renameTo(target)) {
                target.delete()
                if (!temp.renameTo(target)) {
                    temp.delete()
                    return@withLock
                }
            }
            target.setLastModified(record.storedAtMs)
            pruneLocked()
        }
    }

    override suspend fun clear() {
        withContext(Dispatchers.IO) {
            mutex.withLock {
                directory.listFiles()?.forEach(File::delete)
                Unit
            }
        }
    }

    private fun fileFor(key: String): File = File(directory, sha256(key) + ".json")

    private fun pruneLocked() {
        val files = directory.listFiles { file -> file.isFile && file.name.endsWith(".json") }
            ?.sortedByDescending(File::lastModified)
            .orEmpty()
        var totalBytes = files.sumOf(File::length)
        files.forEachIndexed { index, file ->
            if (index >= maxEntries || totalBytes > maxBytes) {
                val size = file.length()
                if (file.delete()) totalBytes -= size
            }
        }
    }

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }

    private data class CacheRecord(
        val storedAtMs: Long = 0L,
        val body: String = "",
    )
}
