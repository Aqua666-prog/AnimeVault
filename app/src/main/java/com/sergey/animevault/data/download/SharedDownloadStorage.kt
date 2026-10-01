package com.sergey.animevault.data.download

import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/**
 * Publishes verified downloads to user-visible shared storage on Android 10+.
 *
 * NativeDownloadEngine still downloads into app-private storage so resume journals, HLS parts,
 * atomic assembly and verification keep working exactly as before. Only the verified final file
 * is copied into Movies/AnimeVault/<title> through MediaStore.
 *
 * On Android 9 and older the original private-file behaviour is preserved because writing to the
 * public media collection there requires the legacy runtime WRITE_EXTERNAL_STORAGE permission.
 */
object SharedDownloadStorage {
    data class PublishedDownload(
        val location: String,
        val displayName: String,
        val mimeType: String,
        val sizeBytes: Long,
        val lastModified: Long,
        val isShared: Boolean,
    )

    fun legacy(
        file: File,
        mimeType: String,
    ): PublishedDownload {
        require(file.isFile && file.length() > 0L) { "Загруженный файл отсутствует" }
        return PublishedDownload(
            location = file.absolutePath,
            displayName = file.name,
            mimeType = mimeType,
            sizeBytes = file.length(),
            lastModified = file.lastModified().coerceAtLeast(System.currentTimeMillis()),
            isShared = false,
        )
    }

    suspend fun publish(
        context: Context,
        entry: DownloadEntry,
        result: NativeDownloadResult,
    ): PublishedDownload = withContext(Dispatchers.IO) {
        val source = result.file
        require(source.isFile && source.length() > 0L) { "Загруженный файл отсутствует" }

        val extension = source.extension
            .takeIf(String::isNotBlank)
            ?: if (result.mimeType == "video/mp2t") "ts" else "mp4"
        val displayName = buildDisplayName(entry, result.selectedQuality, extension)

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return@withContext PublishedDownload(
                location = source.absolutePath,
                displayName = source.name,
                mimeType = result.mimeType,
                sizeBytes = source.length(),
                lastModified = source.lastModified().coerceAtLeast(System.currentTimeMillis()),
                isShared = false,
            )
        }

        val resolver = context.contentResolver
        val relativePath = buildString {
            append(Environment.DIRECTORY_MOVIES)
            append("/AnimeVault/")
            append(sanitizePathComponent(entry.releaseName, "Anime"))
            append('/')
        }
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, result.mimeType)
            put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val collection = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val uri = resolver.insert(collection, values)
            ?: throw IOException("Не удалось создать файл в памяти телефона")

        try {
            resolver.openOutputStream(uri, "w")?.use { output ->
                source.inputStream().use { input -> input.copyTo(output, COPY_BUFFER_BYTES) }
            } ?: throw IOException("Не удалось открыть файл в памяти телефона для записи")

            resolver.update(
                uri,
                ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) },
                null,
                null,
            )
            PublishedDownload(
                location = uri.toString(),
                displayName = displayName,
                mimeType = result.mimeType,
                sizeBytes = querySize(resolver, uri) ?: source.length(),
                lastModified = System.currentTimeMillis(),
                isShared = true,
            )
        } catch (error: Throwable) {
            runCatching { resolver.delete(uri, null, null) }
            throw error
        }
    }

    fun exists(context: Context, location: String): Boolean {
        val uri = Uri.parse(location)
        return when (uri.scheme) {
            "content" -> runCatching {
                context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { descriptor ->
                    descriptor.length != 0L
                } == true
            }.getOrDefault(false)
            "file" -> uri.path?.let(::File)?.let { it.isFile && it.length() > 0L } == true
            else -> File(location).let { it.isFile && it.length() > 0L }
        }
    }

    fun delete(context: Context, location: String) {
        val uri = Uri.parse(location)
        when (uri.scheme) {
            "content" -> runCatching { context.contentResolver.delete(uri, null, null) }
            "file" -> uri.path?.let(::File)?.delete()
            else -> File(location).delete()
        }
    }

    fun playbackUrl(location: String): String {
        val uri = Uri.parse(location)
        return if (uri.scheme == "content" || uri.scheme == "file") {
            location
        } else {
            File(location).toURI().toString()
        }
    }

    fun libraryUri(location: String): String {
        val uri = Uri.parse(location)
        return if (uri.scheme == "content" || uri.scheme == "file") {
            location
        } else {
            Uri.fromFile(File(location)).toString()
        }
    }

    private fun querySize(resolver: ContentResolver, uri: Uri): Long? = runCatching {
        resolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            if (!cursor.moveToFirst()) return@use null
            cursor.getLong(cursor.getColumnIndexOrThrow(OpenableColumns.SIZE)).takeIf { it > 0L }
        }
    }.getOrNull()

    private fun buildDisplayName(entry: DownloadEntry, selectedQuality: Int?, extension: String): String {
        val episode = sanitizePathComponent(entry.episodeLabel, "Серия")
        val quality = selectedQuality ?: entry.quality
        return buildString {
            append(episode)
            quality?.let { append(" - ${it}p") }
            append('.')
            append(extension.lowercase())
        }
    }

    private fun sanitizePathComponent(value: String, fallback: String): String {
        val cleaned = value.map { char ->
            if (char.code < 32 || char in INVALID_FILE_CHARS) '_' else char
        }.joinToString("").trim().trim('.').take(MAX_COMPONENT_LENGTH)
        return cleaned.ifBlank { fallback }
    }

    private const val COPY_BUFFER_BYTES = 256 * 1024
    private const val MAX_COMPONENT_LENGTH = 96
    private const val INVALID_FILE_CHARS = "\\/:*?\"<>|"
}
