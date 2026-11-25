package com.sergey.animevault.data.download

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.InternalCoroutinesApi
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.EmptyCoroutineContext
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import com.sergey.animevault.BuildConfig
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.security.MessageDigest
import java.util.Properties
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.math.abs

data class NativeDownloadProgress(
    val bytesDownloaded: Long,
    val contentLength: Long,
    val completedItems: Int,
    val totalItems: Int,
) {
    val percent: Float
        get() = when {
            contentLength > 0L -> (bytesDownloaded.toDouble() * 100.0 / contentLength).toFloat()
            totalItems > 0 -> completedItems.toFloat() * 100f / totalItems.toFloat()
            else -> 0f
        }.coerceIn(0f, 100f)
}

data class NativeDownloadResult(
    val file: File,
    val mimeType: String,
    val selectedQuality: Int?,
    val totalItems: Int,
)

internal class NativeDownloadHttpException(
    val code: Int,
    message: String,
) : IOException(message)

data class NativeDownloadProbeResult(
    val resolvedUrl: String,
    val selectedQuality: Int?,
    val contentLength: Long?,
    val totalItems: Int,
)

/**
 * Resumable native MP4/HLS downloader.
 *
 * HLS support includes master playlists, quality selection, init maps, AES-128,
 * byte ranges, MPEG-TS/fMP4 output, per-item retry and an on-disk resume journal.
 */
class NativeDownloadEngine(
    private val maxAttempts: Int = 4,
    private val initialBackoffMs: Long = 750L,
    private val connectTimeoutMs: Int = 12_000,
    private val readTimeoutMs: Int = 24_000,
    private val probeTimeoutMs: Int = 5_000,
    private val hlsParallelism: Int = 3,
    private val qualityPolicy: SeasonQualityPolicy? = null,
) {
    suspend fun download(
        source: DownloadMediaSource,
        targetDirectory: File,
        fileStem: String,
        preferredQuality: Int?,
        forceHls: Boolean = false,
        progress: suspend (NativeDownloadProgress) -> Unit = {},
    ): NativeDownloadResult = withContext(Dispatchers.IO) {
        require(targetDirectory.exists() || targetDirectory.mkdirs()) {
            "Не удалось создать папку загрузок"
        }
        if (forceHls || source.url.isHlsUrl()) {
            downloadHls(source, targetDirectory, fileStem, preferredQuality, progress)
        } else {
            downloadProgressive(source, targetDirectory, fileStem, progress)
        }
    }

    /** Fast transport preflight used before committing a WorkManager job to a CDN. */
    suspend fun probe(
        source: DownloadMediaSource,
        preferredQuality: Int?,
        forceHls: Boolean = false,
    ): NativeDownloadProbeResult = withContext(Dispatchers.IO) {
        if (forceHls || source.url.isHlsUrl()) {
            val resolved = resolveMediaPlaylist(source, preferredQuality)
            val items = resolved.playlist.outputItems()
            val first = items.firstOrNull() ?: throw IOException("HLS playlist не содержит сегментов")
            val probeRange = first.byteRange?.let { range ->
                ByteRange(range.offset, minOf(range.length ?: PROBE_BYTES, PROBE_BYTES))
            } ?: ByteRange(0L, PROBE_BYTES)
            requestPrefix(first.uri, source.headers, probeRange, probeTimeout = true)
            NativeDownloadProbeResult(
                resolvedUrl = resolved.mediaPlaylistUrl,
                selectedQuality = resolved.selectedQuality,
                contentLength = resolved.playlist.outputItems().sumOfKnownLengths().takeIf { it >= 0L },
                totalItems = items.size,
            )
        } else {
            val connection = open(source.url, source.headers, ByteRange(0L, PROBE_BYTES), probeTimeout = true)
            try {
                requireSuccessful(connection)
                connection.inputStream.use { input ->
                    val buffer = ByteArray(PROBE_BYTES.toInt())
                    if (input.read(buffer) <= 0) throw IOException("Поток не вернул данные")
                }
                NativeDownloadProbeResult(
                    resolvedUrl = source.url,
                    selectedQuality = source.quality,
                    contentLength = connection.getHeaderField("Content-Range")
                        ?.substringAfterLast('/', "")?.toLongOrNull()
                        ?: connection.contentLengthLong.takeIf { it >= 0L },
                    totalItems = 1,
                )
            } finally {
                connection.disconnect()
            }
        }
    }

    private suspend fun downloadProgressive(
        source: DownloadMediaSource,
        targetDirectory: File,
        fileStem: String,
        progress: suspend (NativeDownloadProgress) -> Unit,
    ): NativeDownloadResult {
        val extension = URI(source.url).path.substringAfterLast('.', "mp4")
            .lowercase()
            .takeIf { it in PROGRESSIVE_EXTENSIONS }
            ?: "mp4"
        val target = File(targetDirectory, "$fileStem.$extension")
        val partial = File(targetDirectory, "$fileStem.$extension.partial")
        val partialSource = File(targetDirectory, "$fileStem.$extension.partial.source")
        withRetry("медиафайл") {
            // A legacy sidecar contains only a URL fingerprint. Its bytes cannot be resumed safely.
            val saved = partialSource.readTextOrNull()?.split('\n', limit = 2)
            val savedTag = saved?.getOrNull(1)?.takeIf(::isStrongEntityTag)
            val savedSize = partial.length().coerceAtLeast(0L)
            if (savedSize > 0 && savedTag != null) {
                val range = ByteRange(savedSize, null)
                val connection = open(source.url, source.headers, range, ifRange = savedTag)
                try {
                    val status = connection.responseCode
                    val resource = downloadCacheKey("native-progressive", connection.url.toString())
                    val contents = parseContentRange(connection.getHeaderField("Content-Range"))
                    if (status == HttpURLConnection.HTTP_PARTIAL && saved.first() == resource &&
                        connection.getHeaderField("ETag") == savedTag &&
                        contents != null && contents.total != null &&
                        contents.first == savedSize && contents.last == contents.total - 1 &&
                        connection.hasIdentityEncoding() &&
                        connection.contentLengthLong.let { it < 0 || it == contents.length }
                    ) {
                        writeProgressiveBody(connection, partial, savedSize, contents.total, progress)
                        return@withRetry
                    }
                    if (status == HttpURLConnection.HTTP_OK) {
                        restartProgressive(connection, partial, partialSource, progress)
                        return@withRetry
                    }
                    if (status != HttpURLConnection.HTTP_PARTIAL && status != HTTP_RANGE_NOT_SATISFIABLE) {
                        requireSuccessful(connection)
                    }
                } finally {
                    connection.disconnect()
                }
            }

            val connection = open(source.url, source.headers, null)
            try {
                requireSuccessful(connection)
                restartProgressive(connection, partial, partialSource, progress)
            } finally {
                connection.disconnect()
            }
        }
        currentCoroutineContext().ensureActive()
        replaceAtomically(partial, target)
        partialSource.delete()
        progress(NativeDownloadProgress(target.length(), target.length(), 1, 1))
        return NativeDownloadResult(
            file = target,
            mimeType = if (extension == "ts") "video/mp2t" else "video/mp4",
            selectedQuality = null,
            totalItems = 1,
        )
    }

    private suspend fun restartProgressive(
        connection: HttpURLConnection,
        partial: File,
        sidecar: File,
        progress: suspend (NativeDownloadProgress) -> Unit,
    ) {
        if (connection.responseCode != HttpURLConnection.HTTP_OK || !connection.hasIdentityEncoding()) {
            throw IOException("Сервер не вернул полный медиафайл")
        }
        // Invalidate the old validator before truncating. A crash can only force a full restart.
        sidecar.writeText("")
        FileOutputStream(partial, false).use { }
        val tag = connection.getHeaderField("ETag")?.takeIf(::isStrongEntityTag)
        if (tag != null) {
            val resource = downloadCacheKey("native-progressive", connection.url.toString())
            sidecar.writeText("$resource\n$tag")
        }
        writeProgressiveBody(connection, partial, 0, connection.contentLengthLong, progress)
    }

    private suspend fun writeProgressiveBody(
        connection: HttpURLConnection,
        partial: File,
        offset: Long,
        expectedTotal: Long,
        progress: suspend (NativeDownloadProgress) -> Unit,
    ) {
        var written = offset
        FileOutputStream(partial, true).use { output ->
            connection.readCancellable { buffer, count ->
                if (expectedTotal >= 0 && count.toLong() > expectedTotal - written) {
                    throw IOException("Ответ превышает заявленную длину медиафайла")
                }
                output.write(buffer, 0, count)
                written += count
                progress(NativeDownloadProgress(written, expectedTotal, 0, 1))
            }
        }
        if (written <= 0 || (expectedTotal >= 0 && written != expectedTotal)) {
            throw IOException("Медиафайл получен не полностью")
        }
    }

    private suspend fun downloadHls(
        source: DownloadMediaSource,
        targetDirectory: File,
        fileStem: String,
        preferredQuality: Int?,
        progress: suspend (NativeDownloadProgress) -> Unit,
    ): NativeDownloadResult {
        val resolved = resolveMediaPlaylist(source, preferredQuality)
        val playlist = resolved.playlist
        val extension = if (playlist.isFragmentedMp4) "mp4" else "ts"
        val mimeType = if (playlist.isFragmentedMp4) "video/mp4" else "video/mp2t"
        val target = File(targetDirectory, "$fileStem.$extension")
        val partsDirectory = File(targetDirectory, ".$fileStem-parts")
        require(partsDirectory.exists() || partsDirectory.mkdirs()) { "Не удалось создать временную папку HLS" }
        val journalFile = File(partsDirectory, "resume.properties")
        val outputItems = playlist.outputItems()
        if (outputItems.size > MAX_HLS_ITEMS) throw IOException("Слишком много HLS-сегментов")
        val fingerprint = playlistFingerprint(resolved.mediaPlaylistUrl, outputItems)
        val journal = ResumeJournal.load(journalFile, fingerprint)
        val keyCache = mutableMapOf<String, ByteArray>()

        journal.retainIndices(outputItems.size)
        val pending = outputItems.indices.filter { index ->
            !journal.keepIfComplete(index, File(partsDirectory, partName(index)))
        }
        var downloadedBytes = journal.totalBytes
        val knownTotal = outputItems.sumOfKnownLengths()
        progress(
            NativeDownloadProgress(
                bytesDownloaded = downloadedBytes,
                contentLength = knownTotal,
                completedItems = journal.completedCount,
                totalItems = outputItems.size,
            ),
        )

        val stateMutex = Mutex()
        val keyMutex = Mutex()
        val nextIndex = AtomicInteger()
        val parallelism = minOf(pending.size, hlsParallelism.coerceIn(1, MAX_HLS_PARALLELISM))
        coroutineScope {
            List(parallelism) {
                async {
                    while (true) {
                        val index = pending.getOrNull(nextIndex.getAndIncrement()) ?: break
                        val item = outputItems[index]
                        val part = File(partsDirectory, partName(index))
                        currentCoroutineContext().ensureActive()
                        val temporaryPart = File(partsDirectory, "${partName(index)}.partial")
                        val writtenBytes = withRetry("HLS-сегмент ${index + 1}/${outputItems.size}") {
                            val decryption = item.key?.let { key ->
                                require(key.method == "AES-128") {
                                    "HLS-шифрование ${key.method} пока не поддерживается"
                                }
                                require(key.keyFormat == null || key.keyFormat == "identity") {
                                    "HLS KEYFORMAT ${key.keyFormat} пока не поддерживается"
                                }
                                val keyBytes = keyMutex.withLock {
                                    keyCache[key.uri] ?: requestAesKey(key.uri, source.headers).also {
                                        keyCache[key.uri] = it
                                    }
                                }
                                AesDecryption(keyBytes, key.iv ?: sequenceIv(item.sequence))
                            }
                            streamResponseToFile(item.uri, source.headers, item.byteRange, temporaryPart, decryption)
                        }
                        if (writtenBytes <= 0) throw IOException("Пустой HLS-сегмент")
                        currentCoroutineContext().ensureActive()
                        replaceAtomically(temporaryPart, part)
                        stateMutex.withLock {
                            journal.markCompleted(index, writtenBytes, journalFile)
                            downloadedBytes += writtenBytes
                            progress(NativeDownloadProgress(downloadedBytes, knownTotal, journal.completedCount, outputItems.size))
                        }
                    }
                }
            }.awaitAll()
        }

        val assembled = File(targetDirectory, "$fileStem.$extension.assembling")
        FileOutputStream(assembled).use { output ->
            outputItems.indices.forEach { index ->
                currentCoroutineContext().ensureActive()
                FileInputStream(File(partsDirectory, partName(index))).use { input ->
                    input.copyTo(output, COPY_BUFFER_BYTES)
                }
            }
        }
        replaceAtomically(assembled, target)
        partsDirectory.deleteRecursively()
        progress(NativeDownloadProgress(target.length(), target.length(), outputItems.size, outputItems.size))
        return NativeDownloadResult(
            file = target,
            mimeType = mimeType,
            selectedQuality = resolved.selectedQuality,
            totalItems = outputItems.size,
        )
    }

    private suspend fun resolveMediaPlaylist(
        source: DownloadMediaSource,
        preferredQuality: Int?,
    ): ResolvedMediaPlaylist {
        var url = source.url
        var selectedQuality: Int? = null
        repeat(MAX_PLAYLIST_DEPTH) {
            currentCoroutineContext().ensureActive()
            val response = withRetry("HLS-плейлист") { requestTextResponse(url, source.headers) }
            url = response.url
            when (val parsed = HlsPlaylistParser.parse(response.body, URI(response.url))) {
                is HlsPlaylist.Master -> {
                    val variant = chooseHlsVariant(parsed.variants, preferredQuality, qualityPolicy)
                        ?: error("Master playlist не содержит воспроизводимых вариантов")
                    url = variant.uri
                    selectedQuality = variant.height ?: selectedQuality
                }
                is HlsPlaylist.Media -> return ResolvedMediaPlaylist(response.url, parsed, selectedQuality)
            }
        }
        error("Слишком глубокая цепочка HLS master playlist")
    }

    private suspend fun requestTextResponse(url: String, headers: Map<String, String>): TextResponse {
        currentCoroutineContext().ensureActive()
        val connection = open(url, headers, null)
        try {
            requireSuccessful(connection)
            val effectiveUrl = connection.url.toString()
            val output = ByteArrayOutputStream(DEFAULT_RESPONSE_CAPACITY)
            connection.readCancellable { buffer, count ->
                if (count > MAX_PLAYLIST_BYTES - output.size()) throw IOException("Слишком большой HLS-плейлист")
                output.write(buffer, 0, count)
            }
            return TextResponse(output.toString(Charsets.UTF_8.name()), effectiveUrl)
        } finally {
            // readCancellable already disconnects; this is deliberately idempotent.
            connection.disconnect()
        }
    }

    private suspend fun requestAesKey(url: String, headers: Map<String, String>): ByteArray {
        val connection = open(url, headers, null)
        try {
            requireSuccessful(connection)
            if (connection.responseCode != HttpURLConnection.HTTP_OK) throw IOException("Неполный AES-128 ключ")
            val output = ByteArrayOutputStream(AES_KEY_BYTES)
            connection.readCancellable { buffer, count ->
                if (count > AES_KEY_BYTES - output.size()) throw IOException("Некорректная длина AES-128 ключа")
                output.write(buffer, 0, count)
            }
            if (output.size() != AES_KEY_BYTES) throw IOException("Некорректная длина AES-128 ключа")
            return output.toByteArray()
        } finally {
            connection.disconnect()
        }
    }

    private suspend fun requestPrefix(
        url: String,
        headers: Map<String, String>,
        byteRange: ByteRange,
        probeTimeout: Boolean,
    ): Int {
        currentCoroutineContext().ensureActive()
        val connection = open(url, headers, byteRange, probeTimeout)
        try {
            requireSuccessful(connection)
            val skip = if (connection.responseCode == HttpURLConnection.HTTP_PARTIAL) {
                val received = parseContentRange(connection.getHeaderField("Content-Range"))
                if (received == null || received.first != byteRange.offset || !connection.hasIdentityEncoding()) {
                    throw IOException("Неверный Content-Range при проверке сегмента")
                }
                0L
            } else byteRange.offset
            connection.inputStream.use { input ->
                skipFullyCancellable(input, skip)
                val buffer = ByteArray(minOf(byteRange.length ?: PROBE_BYTES, PROBE_BYTES).toInt())
                val read = input.read(buffer)
                if (read <= 0) throw IOException("Поток не вернул данные")
                return read
            }
        } finally {
            connection.disconnect()
        }
    }

    private suspend fun streamResponseToFile(
        url: String,
        headers: Map<String, String>,
        byteRange: ByteRange?,
        target: File,
        decryption: AesDecryption?,
    ): Long {
        currentCoroutineContext().ensureActive()
        val connection = open(url, headers, byteRange)
        val cancellationHandle = connection.disconnectOnCancellation()
        try {
            requireSuccessful(connection)
            val partial = connection.responseCode == HttpURLConnection.HTTP_PARTIAL
            val expected = if (partial) {
                val received = parseContentRange(connection.getHeaderField("Content-Range"))
                val requestedLength = byteRange?.length
                if (byteRange == null || received == null || received.first != byteRange.offset ||
                    received.length != requestedLength || !connection.hasIdentityEncoding() ||
                    connection.contentLengthLong.let { it >= 0 && it != received.length }
                ) throw IOException("Неверный Content-Range при загрузке сегмента")
                received.length
            } else byteRange?.length ?: connection.contentLengthLong.takeIf { it >= 0 }
            val skip = if (partial) 0L else byteRange?.offset ?: 0L
            connection.inputStream.use { response ->
                skipFullyCancellable(response, skip)
                val limited = LimitedInputStream(response, byteRange?.length)
                val cipher = decryption?.let { aes ->
                    Cipher.getInstance("AES/CBC/PKCS5Padding").apply {
                        init(Cipher.DECRYPT_MODE, SecretKeySpec(aes.key, "AES"), IvParameterSpec(aes.iv))
                    }
                }
                FileOutputStream(target, false).use { output ->
                    val buffer = ByteArray(COPY_BUFFER_BYTES)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val read = limited.read(buffer)
                        if (read < 0) break
                        if (cipher == null) output.write(buffer, 0, read)
                        else cipher.update(buffer, 0, read)?.let(output::write)
                    }
                    // Surface invalid padding or a truncated encrypted block instead of marking it complete.
                    try {
                        cipher?.doFinal()?.let(output::write)
                    } catch (error: java.security.GeneralSecurityException) {
                        throw IOException("Повреждённый зашифрованный HLS-сегмент", error)
                    }
                }
                if (limited.bytesRead <= 0 || (expected != null && limited.bytesRead != expected)) {
                    throw IOException("Сервер вернул ${limited.bytesRead} байт вместо $expected")
                }
            }
        } finally {
            cancellationHandle?.dispose()
            connection.disconnect()
        }
        return target.length()
    }

    private suspend fun skipFullyCancellable(input: InputStream, count: Long) {
        var remaining = count
        val buffer = ByteArray(COPY_BUFFER_BYTES)
        while (remaining > 0L) {
            currentCoroutineContext().ensureActive()
            val read = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
            if (read < 0) throw IOException("Сервер завершил ответ до смещения $count")
            remaining -= read
        }
    }

    private fun open(
        url: String,
        headers: Map<String, String>,
        range: ByteRange?,
        probeTimeout: Boolean = false,
        ifRange: String? = null,
    ): HttpURLConnection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = if (probeTimeout) probeTimeoutMs else connectTimeoutMs
            readTimeout = if (probeTimeout) probeTimeoutMs else readTimeoutMs
            instanceFollowRedirects = true
            requestMethod = "GET"
            useCaches = false
            headers.forEach { (key, value) -> setRequestProperty(key, value) }
            setRequestProperty("Accept", headers["Accept"] ?: "*/*")
            setRequestProperty("User-Agent", headers["User-Agent"] ?: DEFAULT_USER_AGENT)
            setRequestProperty("Accept-Encoding", "identity")
            if (ifRange != null) setRequestProperty("If-Range", ifRange)
            range?.let {
                val end = it.length?.let { length -> it.offset + length - 1L }
                setRequestProperty("Range", "bytes=${it.offset}-${end ?: ""}")
            }
        }

    private fun requireSuccessful(connection: HttpURLConnection) {
        val code = connection.responseCode
        if (code !in 200..299) {
            connection.disconnect()
            throw NativeDownloadHttpException(code, "HTTP $code при загрузке")
        }
    }

    private suspend fun HttpURLConnection.readCancellable(onChunk: suspend (ByteArray, Int) -> Unit) {
        val cancellationHandle = disconnectOnCancellation()
        try {
            inputStream.use { input ->
                val buffer = ByteArray(COPY_BUFFER_BYTES)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val read = input.read(buffer)
                    if (read < 0) break
                    if (read > 0) onChunk(buffer, read)
                }
            }
        } finally {
            cancellationHandle?.dispose()
            disconnect()
        }
    }

    @OptIn(InternalCoroutinesApi::class)
    private suspend fun HttpURLConnection.disconnectOnCancellation() =
        currentCoroutineContext()[Job]?.invokeOnCompletion(onCancelling = true) { cause ->
            if (cause is CancellationException) {
                // Disconnect may block on the HTTP implementation, so never call it on the cancelling UI thread.
                Dispatchers.IO.dispatch(EmptyCoroutineContext, Runnable { disconnect() })
            }
        }

    private suspend fun <T> withRetry(label: String, block: suspend () -> T): T {
        var delayMs = initialBackoffMs
        var lastError: Throwable? = null
        repeat(maxAttempts.coerceAtLeast(1)) { attempt ->
            currentCoroutineContext().ensureActive()
            try {
                return block()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                lastError = error
                val retryable = error is IOException &&
                    (error !is NativeDownloadHttpException || error.code == 408 || error.code == 429 || error.code >= 500)
                if (!retryable || attempt == maxAttempts - 1) throw error
                delay(delayMs)
                delayMs = (delayMs * 2L).coerceAtMost(MAX_BACKOFF_MS)
            }
        }
        throw IOException("Не удалось загрузить $label", lastError)
    }

    private fun replaceAtomically(source: File, target: File) {
        check(source.parentFile == target.parentFile)
        if (!source.renameTo(target)) throw IOException("Не удалось сохранить ${target.name}")
    }

    private fun sequenceIv(sequence: Long): ByteArray = ByteArray(AES_BLOCK_BYTES).also { iv ->
        var value = sequence
        for (index in iv.lastIndex downTo 0) {
            iv[index] = (value and 0xff).toByte()
            value = value ushr 8
        }
    }

    private data class ContentRange(val first: Long, val last: Long, val total: Long?) {
        val length: Long get() = last - first + 1
    }

    private fun parseContentRange(value: String?): ContentRange? {
        val fields = value?.let(CONTENT_RANGE::matchEntire)?.groupValues ?: return null
        val first = fields[1].toLongOrNull() ?: return null
        val last = fields[2].toLongOrNull() ?: return null
        val total = if (fields[3] == "*") null else fields[3].toLongOrNull() ?: return null
        return ContentRange(first, last, total).takeIf {
            first <= last && last < Long.MAX_VALUE && (total == null || last < total)
        }
    }

    private fun HttpURLConnection.hasIdentityEncoding(): Boolean =
        getHeaderField("Content-Encoding")?.equals("identity", ignoreCase = true) ?: true

    private fun isStrongEntityTag(tag: String): Boolean =
        tag.length in 2..512 && tag.first() == '"' && tag.last() == '"' &&
            tag.substring(1, tag.lastIndex).all { it.code == 0x21 || it.code in 0x23..0x7e || it.code in 0x80..0xff }

    private data class TextResponse(val body: String, val url: String)

    private data class ResolvedMediaPlaylist(
        val mediaPlaylistUrl: String,
        val playlist: HlsPlaylist.Media,
        val selectedQuality: Int?,
    )

    private data class AesDecryption(val key: ByteArray, val iv: ByteArray)

    private class LimitedInputStream(input: InputStream, private val limit: Long?) : FilterInputStream(input) {
        var bytesRead: Long = 0L
            private set

        override fun read(): Int {
            if (limit != null && bytesRead >= limit) return -1
            return super.read().also { if (it >= 0) bytesRead++ }
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            val allowed = limit?.let { (it - bytesRead).coerceAtMost(length.toLong()).toInt() } ?: length
            if (allowed <= 0) return -1
            return super.read(buffer, offset, allowed).also { if (it > 0) bytesRead += it }
        }
    }

    private class ResumeJournal private constructor(
        private val fingerprint: String,
        private val completed: MutableMap<Int, Long>,
    ) {
        val completedCount: Int get() = completed.size
        val totalBytes: Long get() = completed.values.sum()

        fun retainIndices(total: Int) {
            completed.keys.retainAll(0 until total)
        }

        fun keepIfComplete(index: Int, part: File): Boolean {
            val expected = completed[index] ?: return false
            if (part.isFile && part.length() == expected) return true
            completed.remove(index)
            return false
        }

        fun markCompleted(index: Int, bytes: Long, file: File) {
            completed[index] = bytes
            val properties = Properties().apply {
                setProperty("fingerprint", fingerprint)
                setProperty("partSizes", completed.entries.joinToString(",") { "${it.key}:${it.value}" })
            }
            val temporary = File(file.parentFile, "${file.name}.partial")
            FileOutputStream(temporary).use { properties.store(it, "AnimeVault HLS resume journal") }
            if (!temporary.renameTo(file)) throw IOException("Не удалось сохранить журнал возобновления")
        }

        companion object {
            fun load(file: File, fingerprint: String): ResumeJournal {
                val completed = mutableMapOf<Int, Long>()
                if (file.isFile) runCatching {
                    val properties = Properties().also { value -> FileInputStream(file).use(value::load) }
                    if (properties.getProperty("fingerprint") == fingerprint) {
                        properties.getProperty("partSizes").orEmpty().split(',').forEach { record ->
                            val parts = record.split(':', limit = 2)
                            val index = parts.first().toIntOrNull()
                            val length = parts.getOrNull(1)?.toLongOrNull()
                            if (index != null && index >= 0 && length != null && length > 0) completed[index] = length
                        }
                    }
                }.onFailure { completed.clear() }
                return ResumeJournal(fingerprint, completed)
            }
        }
    }

    private companion object {
        const val COPY_BUFFER_BYTES = 64 * 1024
        const val DEFAULT_RESPONSE_CAPACITY = 32 * 1024
        const val MAX_PLAYLIST_DEPTH = 4
        const val MAX_PLAYLIST_BYTES = 2 * 1024 * 1024
        const val MAX_HLS_ITEMS = 15_000
        val CONTENT_RANGE = Regex("""bytes ([0-9]+)-([0-9]+)/([0-9]+|\*)""", RegexOption.IGNORE_CASE)
        const val MAX_HLS_PARALLELISM = 6
        const val MAX_BACKOFF_MS = 8_000L
        const val AES_KEY_BYTES = 16
        const val AES_BLOCK_BYTES = 16
        const val HTTP_RANGE_NOT_SATISFIABLE = 416
        const val PROBE_BYTES = 1_024L
        val DEFAULT_USER_AGENT = "AnimeVault/${BuildConfig.VERSION_NAME}"
        val PROGRESSIVE_EXTENSIONS = setOf("mp4", "m4v", "mov", "ts")
    }
}

internal sealed interface HlsPlaylist {
    data class Master(val variants: List<HlsVariant>) : HlsPlaylist

    data class Media(
        val segments: List<HlsSegment>,
        val mediaSequence: Long,
    ) : HlsPlaylist {
        val isFragmentedMp4: Boolean
            get() = segments.any { it.map != null } || segments.any { segment ->
                URI(segment.uri).path.lowercase().let { it.endsWith(".m4s") || it.endsWith(".mp4") }
            }

        fun outputItems(): List<HlsOutputItem> = buildList {
            var lastMap: HlsMap? = null
            segments.forEach { segment ->
                if (segment.map != null && segment.map != lastMap) {
                    add(
                        HlsOutputItem(
                            uri = segment.map.uri,
                            byteRange = segment.map.byteRange,
                            key = segment.key,
                            sequence = segment.sequence,
                        ),
                    )
                    lastMap = segment.map
                }
                add(
                    HlsOutputItem(
                        uri = segment.uri,
                        byteRange = segment.byteRange,
                        key = segment.key,
                        sequence = segment.sequence,
                    ),
                )
            }
        }
    }
}

internal data class HlsVariant(
    val uri: String,
    val bandwidth: Long?,
    val width: Int?,
    val height: Int?,
    val codecs: String?,
)

internal data class HlsSegment(
    val uri: String,
    val sequence: Long,
    val durationSeconds: Double?,
    val byteRange: ByteRange?,
    val key: HlsKey?,
    val map: HlsMap?,
)

internal data class HlsKey(
    val method: String,
    val uri: String,
    val iv: ByteArray?,
    val keyFormat: String?,
) {
    override fun equals(other: Any?): Boolean = other is HlsKey &&
        method == other.method && uri == other.uri && iv.contentEqualsNullable(other.iv) && keyFormat == other.keyFormat

    override fun hashCode(): Int = 31 * (31 * (31 * method.hashCode() + uri.hashCode()) + (iv?.contentHashCode() ?: 0)) +
        (keyFormat?.hashCode() ?: 0)
}

internal data class HlsMap(val uri: String, val byteRange: ByteRange?)

internal data class ByteRange(val offset: Long, val length: Long?)

internal data class HlsOutputItem(
    val uri: String,
    val byteRange: ByteRange?,
    val key: HlsKey?,
    val sequence: Long,
)

internal object HlsPlaylistParser {
    fun parse(text: String, baseUri: URI): HlsPlaylist {
        val lines = text.lineSequence().map(String::trim).filter(String::isNotBlank).toList()
        require(lines.firstOrNull() == "#EXTM3U") { "Некорректный HLS playlist: нет #EXTM3U" }
        return if (lines.any { it.startsWith("#EXT-X-STREAM-INF:") }) {
            parseMaster(lines, baseUri)
        } else {
            parseMedia(lines, baseUri)
        }
    }

    private fun parseMaster(lines: List<String>, baseUri: URI): HlsPlaylist.Master {
        val variants = mutableListOf<HlsVariant>()
        var index = 0
        while (index < lines.size) {
            val line = lines[index]
            if (line.startsWith("#EXT-X-STREAM-INF:")) {
                val attributes = parseAttributeList(line.substringAfter(':'))
                val uriLine = lines.drop(index + 1).firstOrNull { !it.startsWith('#') }
                    ?: error("После #EXT-X-STREAM-INF отсутствует URI")
                variants += HlsVariant(
                    uri = baseUri.resolve(uriLine).toString(),
                    bandwidth = attributes["AVERAGE-BANDWIDTH"]?.toLongOrNull()
                        ?: attributes["BANDWIDTH"]?.toLongOrNull(),
                    width = attributes["RESOLUTION"]?.substringBefore('x')?.toIntOrNull(),
                    height = attributes["RESOLUTION"]?.substringAfter('x', "")?.toIntOrNull(),
                    codecs = attributes["CODECS"],
                )
            }
            index++
        }
        return HlsPlaylist.Master(variants)
    }

    private fun parseMedia(lines: List<String>, baseUri: URI): HlsPlaylist.Media {
        var mediaSequence = 0L
        var nextSequence = 0L
        var currentDuration: Double? = null
        var pendingRangeLength: Long? = null
        var pendingRangeOffset: Long? = null
        val previousRangeEndByUri = mutableMapOf<String, Long>()
        val previousMapRangeEndByUri = mutableMapOf<String, Long>()
        var currentKey: HlsKey? = null
        var currentMap: HlsMap? = null
        val segments = mutableListOf<HlsSegment>()

        lines.forEach { line ->
            when {
                line.startsWith("#EXT-X-MEDIA-SEQUENCE:") -> {
                    mediaSequence = line.substringAfter(':').trim().toLongOrNull() ?: 0L
                    nextSequence = mediaSequence
                }
                line.startsWith("#EXTINF:") -> {
                    currentDuration = line.substringAfter(':').substringBefore(',').trim().toDoubleOrNull()
                }
                line.startsWith("#EXT-X-BYTERANGE:") -> {
                    val value = line.substringAfter(':').trim()
                    val length = value.substringBefore('@').toLong()
                    val explicitOffset = value.substringAfter('@', "").toLongOrNull()
                    pendingRangeLength = length
                    pendingRangeOffset = explicitOffset
                }
                line.startsWith("#EXT-X-KEY:") -> {
                    val attributes = parseAttributeList(line.substringAfter(':'))
                    val method = attributes["METHOD"]?.uppercase() ?: error("HLS key без METHOD")
                    currentKey = if (method == "NONE") {
                        null
                    } else {
                        val keyUri = attributes["URI"] ?: error("HLS key без URI")
                        HlsKey(
                            method = method,
                            uri = baseUri.resolve(keyUri).toString(),
                            iv = attributes["IV"]?.let(::parseIv),
                            keyFormat = attributes["KEYFORMAT"],
                        )
                    }
                }
                line.startsWith("#EXT-X-MAP:") -> {
                    val attributes = parseAttributeList(line.substringAfter(':'))
                    val mapUri = attributes["URI"] ?: error("HLS map без URI")
                    val resolvedMapUri = baseUri.resolve(mapUri).toString()
                    val mapRange = attributes["BYTERANGE"]?.let { value ->
                        parseAttributeByteRange(value, previousMapRangeEndByUri[resolvedMapUri]).also { range ->
                            previousMapRangeEndByUri[resolvedMapUri] = range.offset + (range.length ?: 0L)
                        }
                    }
                    currentMap = HlsMap(resolvedMapUri, mapRange)
                }
                !line.startsWith('#') -> {
                    val resolvedUri = baseUri.resolve(line).toString()
                    val currentRange = pendingRangeLength?.let { length ->
                        val offset = pendingRangeOffset ?: requireNotNull(previousRangeEndByUri[resolvedUri]) {
                            "Неявный HLS BYTERANGE допустим только после диапазона того же URI"
                        }
                        ByteRange(offset, length).also { range ->
                            previousRangeEndByUri[resolvedUri] = range.offset + length
                        }
                    }
                    segments += HlsSegment(
                        uri = resolvedUri,
                        sequence = nextSequence++,
                        durationSeconds = currentDuration,
                        byteRange = currentRange,
                        key = currentKey,
                        map = currentMap,
                    )
                    currentDuration = null
                    pendingRangeLength = null
                    pendingRangeOffset = null
                }
            }
        }
        require(segments.isNotEmpty()) { "HLS playlist не содержит медиасегментов" }
        return HlsPlaylist.Media(segments, mediaSequence)
    }

    internal fun parseAttributeList(raw: String): Map<String, String> {
        val result = linkedMapOf<String, String>()
        var cursor = 0
        while (cursor < raw.length) {
            val equals = raw.indexOf('=', cursor).takeIf { it >= 0 } ?: break
            val key = raw.substring(cursor, equals).trim().uppercase()
            cursor = equals + 1
            val value = if (cursor < raw.length && raw[cursor] == '"') {
                val end = raw.indexOf('"', cursor + 1).takeIf { it >= 0 } ?: raw.lastIndex
                raw.substring(cursor + 1, end).also { cursor = end + 1 }
            } else {
                val comma = raw.indexOf(',', cursor).takeIf { it >= 0 } ?: raw.length
                raw.substring(cursor, comma).trim().also { cursor = comma }
            }
            result[key] = value
            while (cursor < raw.length && (raw[cursor] == ',' || raw[cursor].isWhitespace())) cursor++
        }
        return result
    }

    private fun parseAttributeByteRange(value: String, implicitOffset: Long?): ByteRange {
        val length = value.substringBefore('@').toLong()
        val offset = value.substringAfter('@', "").toLongOrNull()
            ?: requireNotNull(implicitOffset) { "Неявный EXT-X-MAP BYTERANGE без предыдущего диапазона URI" }
        return ByteRange(offset, length)
    }

    private fun parseIv(value: String): ByteArray {
        val normalized = value.removePrefix("0x").removePrefix("0X").padStart(32, '0')
        require(normalized.length == 32) { "Некорректный AES IV" }
        return ByteArray(16) { index -> normalized.substring(index * 2, index * 2 + 2).toInt(16).toByte() }
    }
}

internal fun chooseHlsVariant(
    variants: List<HlsVariant>,
    preferredQuality: Int?,
    policy: SeasonQualityPolicy? = null,
): HlsVariant? {
    if (variants.isEmpty()) return null
    if (preferredQuality != null && policy == SeasonQualityPolicy.STRICT) {
        return variants.filter { it.height == preferredQuality }
            .maxByOrNull { it.bandwidth ?: 0L }
    }
    if (preferredQuality != null && policy == SeasonQualityPolicy.LOWER) {
        return variants.filter { it.height != null && it.height <= preferredQuality }
            .maxWithOrNull(compareBy<HlsVariant> { it.height ?: 0 }.thenBy { it.bandwidth ?: 0L })
            ?: variants.filter { it.height == null }.maxByOrNull { it.bandwidth ?: 0L }
    }
    return if (preferredQuality == null) {
        variants.maxWithOrNull(compareBy<HlsVariant> { it.height ?: 0 }.thenBy { it.bandwidth ?: 0L })
    } else {
        variants.minWithOrNull(
            compareBy<HlsVariant> { abs((it.height ?: 0) - preferredQuality) }
                .thenBy { if ((it.height ?: 0) <= preferredQuality) 0 else 1 }
                .thenByDescending { it.bandwidth ?: 0L },
        )
    }
}

private fun File.readTextOrNull(): String? = runCatching { if (isFile) readText() else null }.getOrNull()

private fun String.isHlsUrl(): Boolean = runCatching {
    URI(this).path.endsWith(".m3u8", ignoreCase = true)
}.getOrDefault(contains(".m3u8", ignoreCase = true))

private fun List<HlsOutputItem>.sumOfKnownLengths(): Long {
    val lengths = map { it.byteRange?.length }
    return if (lengths.all { it != null }) lengths.sumOf { it ?: 0L } else -1L
}

private fun playlistFingerprint(url: String, items: List<HlsOutputItem>): String {
    val canonical = buildString {
        append(downloadCacheKey("native-hls", url))
        items.forEach { item ->
            append('\n').append(downloadCacheKey("native-hls", item.uri))
            append('|').append(item.byteRange?.offset ?: -1L)
            append('|').append(item.byteRange?.length ?: -1L)
            append('|').append(item.key?.method.orEmpty())
            append('|').append(item.key?.uri?.let { downloadCacheKey("native-hls-key", it) }.orEmpty())
            append('|').append(item.key?.iv?.joinToString(",").orEmpty())
            append('|').append(item.sequence)
        }
    }
    return MessageDigest.getInstance("SHA-256")
        .digest(canonical.toByteArray())
        .joinToString("") { "%02x".format(it) }
}

private fun partName(index: Int): String = index.toString().padStart(6, '0') + ".part"

private fun ByteArray?.contentEqualsNullable(other: ByteArray?): Boolean = when {
    this == null && other == null -> true
    this == null || other == null -> false
    else -> contentEquals(other)
}
