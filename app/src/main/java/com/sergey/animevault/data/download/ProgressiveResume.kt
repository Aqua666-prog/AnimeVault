package com.sergey.animevault.data.download

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.util.Properties

/** A partial MP4 can be appended only if its source has a verifiable identity and length. */
internal data class ProgressiveResumeCheckpoint(
    val validator: String?,
    val totalLength: Long?,
) {
    fun canResume(existingBytes: Long): Boolean =
        !validator.isNullOrBlank() && totalLength != null &&
            existingBytes > 0L && existingBytes < totalLength

    fun save(file: File) {
        val properties = Properties().apply {
            setProperty("validator", validator.orEmpty())
            setProperty("totalLength", totalLength?.toString().orEmpty())
        }
        FileOutputStream(file).use { properties.store(it, "AnimeVault progressive MP4 checkpoint") }
    }

    companion object {
        fun load(file: File): ProgressiveResumeCheckpoint? {
            if (!file.isFile) return null
            return runCatching {
                val properties = Properties().also { data ->
                    FileInputStream(file).use(data::load)
                }
                ProgressiveResumeCheckpoint(
                    validator = properties.getProperty("validator")?.takeIf(String::isNotBlank),
                    totalLength = properties.getProperty("totalLength")?.toLongOrNull()
                        ?.takeIf { it > 0L },
                )
            }.getOrNull()
        }

        /** If-Range requires a strong ETag or an HTTP Last-Modified date, never a weak ETag. */
        fun responseValidator(connection: HttpURLConnection): String? {
            val strongEtag = connection.getHeaderField("ETag")?.trim()
                ?.takeIf { it.isNotEmpty() && !it.startsWith("W/", ignoreCase = true) }
            return strongEtag ?: connection.getHeaderField("Last-Modified")?.trim()
                ?.takeIf(String::isNotBlank)
        }
    }
}

internal data class ProgressiveContentRange(
    val start: Long,
    val end: Long,
    val totalLength: Long,
) {
    val length: Long get() = end - start + 1L
}

/** Reject malformed, unsatisfied and wildcard ranges: they cannot prove a complete MP4 suffix. */
internal fun parseProgressiveContentRange(header: String?): ProgressiveContentRange? {
    val match = Regex("""bytes\s+(\d+)-(\d+)/(\d+)""", RegexOption.IGNORE_CASE)
        .matchEntire(header?.trim().orEmpty()) ?: return null
    val start = match.groupValues[1].toLongOrNull() ?: return null
    val end = match.groupValues[2].toLongOrNull() ?: return null
    val total = match.groupValues[3].toLongOrNull() ?: return null
    if (start < 0L || end < start || total <= 0L || end >= total) return null
    return ProgressiveContentRange(start, end, total)
}
