package com.sergey.animevault.data.download

import android.media.MediaExtractor
import android.media.MediaFormat
import java.io.File
import java.io.IOException

internal data class MediaVerificationResult(
    val trackCount: Int,
    val hasVideoTrack: Boolean,
)

/** Final guard against marking a truncated/HTML error response as an offline episode. */
internal object DownloadedMediaVerifier {
    private const val MIN_MEDIA_BYTES = 64L * 1024L

    fun verify(file: File): MediaVerificationResult {
        if (!file.isFile) throw IOException("Скачанный файл не найден")
        if (file.length() < MIN_MEDIA_BYTES) {
            throw IOException("Скачанный файл подозрительно мал: ${file.length()} байт")
        }
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(file.absolutePath)
            var hasVideo = false
            repeat(extractor.trackCount) { index ->
                val mime = extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME).orEmpty()
                if (mime.startsWith("video/")) hasVideo = true
            }
            if (extractor.trackCount <= 0 || !hasVideo) {
                throw IOException("Медиафайл не содержит видеодорожку")
            }
            MediaVerificationResult(extractor.trackCount, hasVideo)
        } finally {
            extractor.release()
        }
    }
}
