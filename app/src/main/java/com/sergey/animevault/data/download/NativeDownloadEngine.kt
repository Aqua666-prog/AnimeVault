package com.sergey.animevault.data.download

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
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

/**
 * Resumable native MP4/HLS downloader.
 *
 * HLS support includes master playlists, quality selection, init maps, AES-128,
 * byte ranges, MPEG-TS/fMP4 output, per-item retry and an on-disk resume journal.
 */
class NativeDownloadEngine(
    private val maxAttempts: Int = 4,
    private val initialBackoffMs: Long = 750L,
) {
    suspend fun download(
        source: DownloadMediaSource,
        targetDirectory: File,
        fileStem: String,
        preferredQuality: Int?,
        forceHls: Boolean = false,
        progress: (NativeDownloadProgress) -> Unit = {},
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

    private suspend fun downloadProgressive(
        source: DownloadMediaSource,
        targetDirectory: File,
        fileStem: String,
        progress: (NativeDownloadProgress) -> Unit,
    ): NativeDownloadResult {
        val extension = URI(source.url).path.substringAfterLast('.', "mp4")
            .lowercase()
            .takeIf { it in PROGRESSIVE_EXTENSIONS }
            ?: "mp4"
        val target = File(targetDirectory, "$fileStem.$extension")
        val partial = File(targetDirectory, "$fileStem.$extension.partial")
        var existingBytes = partial.length().coerceAtLeast(0L)

        withRetry("медиафайл") {
            currentCoroutineContext().ensureActive()
            existingBytes = partial.length().coerceAtLeast(0L)
            val range = existingBytes.takeIf { it > 0L }?.let { ByteRange(it, null) }
            val connection = open(source.url, source.headers, range)
            val append = existingBytes > 0L && connection.responseCode == HttpURLConnection.HTTP_PARTIAL
            if (!append) existingBytes = 0L
            requireSuccessful(connection)
            val responseLength = connection.contentLengthLong.takeIf { it >= 0L } ?: -1L
            val total = when {
                responseLength < 0L -> -1L
                append -> existingBytes + responseLength
                else -> responseLength
            }
            FileOutputStream(partial, append).use { output ->
                connection.readCancellable { buffer, count ->
                    output.write(buffer, 0, count)
                    existingBytes += count
                    progress(NativeDownloadProgress(existingBytes, total, 0, 1))
                }
            }
        }
        replaceAtomically(partial, target)
        progress(NativeDownloadProgress(target.length(), target.length(), 1, 1))
        return NativeDownloadResult(
            file = target,
            mimeType = if (extension == "ts") "video/mp2t" else "video/mp4",
            selectedQuality = null,
            totalItems = 1,
        )
    }

    private suspend fun downloadHls(
        source: DownloadMediaSource,
        targetDirectory: File,
        fileStem: String,
        preferredQuality: Int?,
        progress: (NativeDownloadProgress) -> Unit,
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
        val fingerprint = playlistFingerprint(resolved.mediaPlaylistUrl, outputItems)
        val journal = ResumeJournal.load(journalFile, fingerprint)
        val keyCache = mutableMapOf<String, ByteArray>()

        var downloadedBytes = outputItems.indices.sumOf { index ->
            if (journal.isCompleted(index)) File(partsDirectory, partName(index)).length() else 0L
        }
        val knownTotal = outputItems.sumOfKnownLengths()
        progress(
            NativeDownloadProgress(
                bytesDownloaded = downloadedBytes,
                contentLength = knownTotal,
                completedItems = journal.completedCount(outputItems.size),
                totalItems = outputItems.size,
            ),
        )

        outputItems.forEachIndexed { index, item ->
            currentCoroutineContext().ensureActive()
            val part = File(partsDirectory, partName(index))
            if (journal.isCompleted(index) && part.isFile && part.length() > 0L) return@forEachIndexed

            val payload = withRetry("HLS-сегмент ${index + 1}/${outputItems.size}") {
                val encrypted = requestBytes(item.uri, source.headers, item.byteRange)
                item.key?.let { key ->
                    require(key.method == "AES-128") { "HLS-шифрование ${key.method} пока не поддерживается" }
                    require(key.keyFormat == null || key.keyFormat == "identity") {
                        "HLS KEYFORMAT ${key.keyFormat} пока не поддерживается"
                    }
                    val keyBytes = keyCache[key.uri] ?: requestBytes(key.uri, source.headers, null).also {
                            require(it.size == AES_KEY_BYTES) { "Некорректная длина AES-128 ключа: ${it.size}" }
                            keyCache[key.uri] = it
                        }
                    decryptAes128(encrypted, keyBytes, key.iv ?: sequenceIv(item.sequence))
                } ?: encrypted
            }
            val temporaryPart = File(partsDirectory, "${partName(index)}.partial")
            FileOutputStream(temporaryPart).use { it.write(payload) }
            replaceAtomically(temporaryPart, part)
            journal.markCompleted(index, journalFile)
            downloadedBytes += payload.size
            progress(
                NativeDownloadProgress(
                    bytesDownloaded = downloadedBytes,
                    contentLength = knownTotal,
                    completedItems = journal.completedCount(outputItems.size),
                    totalItems = outputItems.size,
                ),
            )
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
            val text = withRetry("HLS-плейлист") { requestText(url, source.headers) }
            when (val parsed = HlsPlaylistParser.parse(text, URI(url))) {
                is HlsPlaylist.Master -> {
                    val variant = chooseHlsVariant(parsed.variants, preferredQuality)
                        ?: error("Master playlist не содержит воспроизводимых вариантов")
                    url = variant.uri
                    selectedQuality = variant.height ?: selectedQuality
                }
                is HlsPlaylist.Media -> return ResolvedMediaPlaylist(url, parsed, selectedQuality)
            }
        }
        error("Слишком глубокая цепочка HLS master playlist")
    }

    private suspend fun requestText(url: String, headers: Map<String, String>): String =
        requestBytes(url, headers, null).toString(Charsets.UTF_8)

    private suspend fun requestBytes(
        url: String,
        headers: Map<String, String>,
        byteRange: ByteRange?,
    ): ByteArray {
        currentCoroutineContext().ensureActive()
        val connection = open(url, headers, byteRange)
        requireSuccessful(connection)
        val requestedOffset = byteRange?.offset ?: 0L
        val serverHonoredRange = byteRange == null || connection.responseCode == HttpURLConnection.HTTP_PARTIAL
        val skip = if (byteRange != null && !serverHonoredRange) requestedOffset else 0L
        val limit = byteRange?.length
        val capacity = limit?.coerceAtMost(Int.MAX_VALUE.toLong())?.toInt() ?: DEFAULT_RESPONSE_CAPACITY
        val output = ByteArrayOutputStream(capacity)
        var skipped = 0L
        var written = 0L
        connection.readCancellable { buffer, count ->
            var offset = 0
            var available = count
            if (skipped < skip) {
                val toSkip = minOf(available.toLong(), skip - skipped).toInt()
                skipped += toSkip
                offset += toSkip
                available -= toSkip
            }
            if (available > 0 && (limit == null || written < limit)) {
                val allowed = if (limit == null) available else minOf(available.toLong(), limit - written).toInt()
                output.write(buffer, offset, allowed)
                written += allowed
            }
        }
        if (limit != null && written != limit) {
            throw IOException("Сервер вернул $written байт вместо $limit")
        }
        return output.toByteArray()
    }

    private fun open(url: String, headers: Map<String, String>, range: ByteRange?): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            instanceFollowRedirects = true
            requestMethod = "GET"
            useCaches = false
            headers.forEach { (key, value) -> setRequestProperty(key, value) }
            setRequestProperty("Accept", headers["Accept"] ?: "*/*")
            setRequestProperty("User-Agent", headers["User-Agent"] ?: DEFAULT_USER_AGENT)
            range?.let {
                val end = it.length?.let { length -> it.offset + length - 1L }
                setRequestProperty("Range", "bytes=${it.offset}-${end ?: ""}")
            }
        }

    private fun requireSuccessful(connection: HttpURLConnection) {
        val code = connection.responseCode
        if (code !in 200..299) {
            connection.disconnect()
            throw HttpStatusException(code, "HTTP $code при загрузке")
        }
    }

    private suspend fun HttpURLConnection.readCancellable(onChunk: (ByteArray, Int) -> Unit) {
        val job = currentCoroutineContext()[Job]
        val cancellationHandle = job?.invokeOnCompletion { cause ->
            if (cause is CancellationException) disconnect()
        }
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
                    (error !is HttpStatusException || error.code == 408 || error.code == 429 || error.code >= 500)
                if (!retryable || attempt == maxAttempts - 1) throw error
                delay(delayMs)
                delayMs = (delayMs * 2L).coerceAtMost(MAX_BACKOFF_MS)
            }
        }
        throw IOException("Не удалось загрузить $label", lastError)
    }

    private fun replaceAtomically(source: File, target: File) {
        if (target.exists() && !target.delete()) error("Не удалось заменить ${target.name}")
        if (!source.renameTo(target)) {
            source.copyTo(target, overwrite = true)
            if (!source.delete()) error("Не удалось удалить временный файл ${source.name}")
        }
    }

    private fun decryptAes128(payload: ByteArray, key: ByteArray, iv: ByteArray): ByteArray =
        Cipher.getInstance("AES/CBC/PKCS5Padding").run {
            init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
            doFinal(payload)
        }

    private fun sequenceIv(sequence: Long): ByteArray = ByteArray(AES_BLOCK_BYTES).also { iv ->
        var value = sequence
        for (index in iv.lastIndex downTo 0) {
            iv[index] = (value and 0xff).toByte()
            value = value ushr 8
        }
    }

    private data class ResolvedMediaPlaylist(
        val mediaPlaylistUrl: String,
        val playlist: HlsPlaylist.Media,
        val selectedQuality: Int?,
    )

    private class HttpStatusException(val code: Int, message: String) : IOException(message)

    private class ResumeJournal private constructor(
        private val fingerprint: String,
        private val completed: MutableSet<Int>,
    ) {
        fun isCompleted(index: Int): Boolean = index in completed

        fun completedCount(total: Int): Int = completed.count { it in 0 until total }

        fun markCompleted(index: Int, file: File) {
            completed += index
            val properties = Properties().apply {
                setProperty("fingerprint", fingerprint)
                setProperty("completed", completed.sorted().joinToString(","))
            }
            val temporary = File(file.parentFile, "${file.name}.partial")
            FileOutputStream(temporary).use { properties.store(it, "AnimeVault HLS resume journal") }
            if (file.exists()) file.delete()
            check(temporary.renameTo(file)) { "Не удалось сохранить журнал возобновления" }
        }

        companion object {
            fun load(file: File, fingerprint: String): ResumeJournal {
                if (!file.isFile) return ResumeJournal(fingerprint, mutableSetOf())
                return runCatching {
                    val properties = Properties().also { value -> FileInputStream(file).use(value::load) }
                    if (properties.getProperty("fingerprint") != fingerprint) {
                        file.parentFile?.listFiles()?.forEach { child -> if (child != file) child.delete() }
                        return@runCatching ResumeJournal(fingerprint, mutableSetOf())
                    }
                    val completed = properties.getProperty("completed").orEmpty()
                        .split(',')
                        .mapNotNull(String::toIntOrNull)
                        .toMutableSet()
                    ResumeJournal(fingerprint, completed)
                }.getOrElse { ResumeJournal(fingerprint, mutableSetOf()) }
            }
        }
    }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 15_000
        const val READ_TIMEOUT_MS = 30_000
        const val COPY_BUFFER_BYTES = 64 * 1024
        const val DEFAULT_RESPONSE_CAPACITY = 32 * 1024
        const val MAX_PLAYLIST_DEPTH = 4
        const val MAX_BACKOFF_MS = 8_000L
        const val AES_KEY_BYTES = 16
        const val AES_BLOCK_BYTES = 16
        const val DEFAULT_USER_AGENT = "AnimeVault/1.6.0"
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
        var currentRange: ByteRange? = null
        var previousRangeEnd = 0L
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
                    currentRange = ByteRange(explicitOffset ?: previousRangeEnd, length)
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
                    val mapRange = attributes["BYTERANGE"]?.let(::parseAttributeByteRange)
                    currentMap = HlsMap(baseUri.resolve(mapUri).toString(), mapRange)
                }
                !line.startsWith('#') -> {
                    segments += HlsSegment(
                        uri = baseUri.resolve(line).toString(),
                        sequence = nextSequence++,
                        durationSeconds = currentDuration,
                        byteRange = currentRange,
                        key = currentKey,
                        map = currentMap,
                    )
                    currentRange?.let { previousRangeEnd = it.offset + (it.length ?: 0L) }
                    currentDuration = null
                    currentRange = null
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

    private fun parseAttributeByteRange(value: String): ByteRange {
        val length = value.substringBefore('@').toLong()
        val offset = value.substringAfter('@', "0").toLong()
        return ByteRange(offset, length)
    }

    private fun parseIv(value: String): ByteArray {
        val normalized = value.removePrefix("0x").removePrefix("0X").padStart(32, '0')
        require(normalized.length == 32) { "Некорректный AES IV" }
        return ByteArray(16) { index -> normalized.substring(index * 2, index * 2 + 2).toInt(16).toByte() }
    }
}

internal fun chooseHlsVariant(variants: List<HlsVariant>, preferredQuality: Int?): HlsVariant? {
    if (variants.isEmpty()) return null
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
