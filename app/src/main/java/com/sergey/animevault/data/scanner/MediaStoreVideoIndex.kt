package com.sergey.animevault.data.scanner

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import java.util.Locale

/**
 * Дополняет SAF-результаты длительностью и MIME из системного MediaStore.
 * SAF остаётся источником истины: без разрешения на общий медиакаталог индекс
 * просто пуст, а рекурсивное сканирование выбранной папки продолжает работать.
 */
internal class MediaStoreVideoIndex private constructor(
    private val rootRelativePath: String,
    private val byFileName: Map<String, List<MediaStoreVideoMetadata>>,
) {
    fun find(
        relativeDirectories: List<String>,
        fileName: String,
        sizeBytes: Long,
    ): MediaStoreVideoMetadata? {
        val candidates = byFileName[fileName.lowercase(Locale.ROOT)].orEmpty()
        if (candidates.isEmpty()) return null
        val expectedDirectory = buildString {
            append(rootRelativePath)
            relativeDirectories.forEach { directory -> append(directory.trim('/')).append('/') }
        }.normalizePath()
        return candidates.minByOrNull { metadata ->
            var score = 0
            if (sizeBytes > 0L && metadata.sizeBytes != sizeBytes) score += 4
            if (expectedDirectory.isNotBlank() && !metadata.relativePath.normalizePath().endsWith(expectedDirectory)) {
                score += 2
            }
            score
        }
    }

    companion object {
        fun load(context: Context, treeUri: Uri): MediaStoreVideoIndex {
            if (!context.hasMediaReadPermission()) return empty()
            val rootPath = runCatching {
                DocumentsContract.getTreeDocumentId(treeUri)
                    .substringAfter(':', "")
                    .trim('/')
                    .let { if (it.isBlank()) "" else "$it/" }
            }.getOrDefault("")
            val result = mutableListOf<MediaStoreVideoMetadata>()
            val projection = buildList {
                add(MediaStore.Video.Media.DISPLAY_NAME)
                add(MediaStore.Video.Media.SIZE)
                add(MediaStore.Video.Media.DURATION)
                add(MediaStore.Video.Media.MIME_TYPE)
                add(MediaStore.Video.Media.DATE_MODIFIED)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    add(MediaStore.Video.Media.RELATIVE_PATH)
                } else {
                    @Suppress("DEPRECATION")
                    add(MediaStore.Video.Media.DATA)
                }
            }.toTypedArray()
            val (selection, args) = if (rootPath.isNotBlank()) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    "${MediaStore.Video.Media.RELATIVE_PATH} LIKE ?" to arrayOf("$rootPath%")
                } else {
                    @Suppress("DEPRECATION")
                    "${MediaStore.Video.Media.DATA} LIKE ?" to arrayOf("%/$rootPath%")
                }
            } else {
                null to null
            }
            runCatching {
                context.contentResolver.query(
                    MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                    projection,
                    selection,
                    args,
                    null,
                )?.use { cursor ->
                    val nameColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
                    val sizeColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE)
                    val durationColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION)
                    val mimeColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.MIME_TYPE)
                    val modifiedColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_MODIFIED)
                    val pathColumn = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        cursor.getColumnIndexOrThrow(MediaStore.Video.Media.RELATIVE_PATH)
                    } else {
                        @Suppress("DEPRECATION")
                        cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATA)
                    }
                    while (cursor.moveToNext()) {
                        val name = cursor.getString(nameColumn) ?: continue
                        val rawPath = cursor.getString(pathColumn).orEmpty()
                        val relativePath = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                            rawPath
                        } else {
                            rawPath.substringBeforeLast('/', "")
                        }
                        result += MediaStoreVideoMetadata(
                            fileName = name,
                            relativePath = relativePath,
                            sizeBytes = cursor.getLong(sizeColumn).coerceAtLeast(0L),
                            durationMs = cursor.getLong(durationColumn).takeIf { it > 0L },
                            mimeType = cursor.getString(mimeColumn),
                            lastModified = cursor.getLong(modifiedColumn)
                                .takeIf { it > 0L }
                                ?.times(1_000L),
                        )
                    }
                }
            }
            return MediaStoreVideoIndex(
                rootRelativePath = rootPath,
                byFileName = result.groupBy { it.fileName.lowercase(Locale.ROOT) },
            )
        }

        private fun empty() = MediaStoreVideoIndex("", emptyMap())
    }
}

internal data class MediaStoreVideoMetadata(
    val fileName: String,
    val relativePath: String,
    val sizeBytes: Long,
    val durationMs: Long?,
    val mimeType: String?,
    val lastModified: Long?,
)

private fun Context.hasMediaReadPermission(): Boolean {
    val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        Manifest.permission.READ_MEDIA_VIDEO
    } else {
        Manifest.permission.READ_EXTERNAL_STORAGE
    }
    return ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
}

private fun String.normalizePath(): String = replace('\\', '/')
    .trim('/')
    .let { if (it.isBlank()) "" else "$it/" }
