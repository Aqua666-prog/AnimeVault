package com.sergey.animevault.data.scanner

import android.content.Context
import android.net.Uri
import android.database.Cursor
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import kotlinx.coroutines.ensureActive
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.ArrayDeque
import java.util.Locale

class LibraryScanner(
    private val context: Context,
) {
    private val videoExtensions = setOf(
        "mp4", "mkv", "webm", "m4v", "mov", "avi", "ts", "m2ts", "3gp", "flv",
    )
    private val subtitleExtensions = setOf("srt", "ass", "ssa", "vtt")
    private val imageExtensions = setOf("jpg", "jpeg", "png", "webp", "avif")
    private val preferredPosterNames = setOf("cover", "poster", "folder", "front", "обложка")

    suspend fun scan(
        treeUri: Uri,
        groupingOverrides: Map<String, GroupingOverride> = emptyMap(),
        onProgress: (ScanProgress) -> Unit = {},
    ): FolderScanResult = withContext(Dispatchers.IO) {
        val rootId = if (DocumentsContract.isDocumentUri(context, treeUri)) {
            DocumentsContract.getDocumentId(treeUri)
        } else {
            DocumentsContract.getTreeDocumentId(treeUri)
        }
        val rootUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, rootId)
        val root = readDocuments(rootUri) { ensureActive() }.singleOrNull()
            ?.takeIf { it.id == rootId && it.mimeType == Document.MIME_TYPE_DIR }
            ?: throw IOException("Выбранная папка больше недоступна")

        val queue = ArrayDeque<DirectoryNode>()
        val visitedIds = mutableSetOf(rootId)
        val videos = mutableListOf<FoundFile>()
        val subtitles = mutableListOf<FoundFile>()
        val images = mutableListOf<FoundFile>()
        var visited = 0
        queue.add(DirectoryNode(rootId, emptyList()))

        while (queue.isNotEmpty()) {
            ensureActive()
            val node = queue.removeFirst()
            val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, node.id)
            val children = readDocuments(childrenUri) { ensureActive() }
            for (child in children) {
                if (!visitedIds.add(child.id)) continue
                visited += 1
                if (child.mimeType == Document.MIME_TYPE_DIR) {
                    queue.add(DirectoryNode(child.id, node.relativeDirectories + child.name))
                } else {
                    val extension = child.name.substringAfterLast('.', "").lowercase(Locale.ROOT)
                    val found = FoundFile(
                        uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, child.id).toString(),
                        document = child,
                        relativeDirectories = node.relativeDirectories,
                        extension = extension,
                    )
                    when {
                        extension in videoExtensions || child.mimeType.startsWith("video/") -> videos += found
                        extension in subtitleExtensions -> subtitles += found
                        extension in imageExtensions || child.mimeType.startsWith("image/") -> images += found
                    }
                }
                if (visited % 25 == 0) {
                    onProgress(ScanProgress(visited, videos.size))
                }
            }
        }

        val subtitlesByDirectory = subtitles.groupBy { it.directoryKey }
        val imagesByDirectory = images.groupBy { it.directoryKey }
        val mediaStoreIndex = MediaStoreVideoIndex.load(context, treeUri)
        val groupNames = mutableMapOf<String, String>()
        val grouped = videos.groupBy { video ->
            val override = groupingOverrides[video.uri]
            if (override != null) {
                groupNames[override.sourceKey] = override.titleName
                override.sourceKey
            } else {
                ensureActive()
                val parsed = EpisodeNameParser.parse(video.fileName)
                val title = GroupingPolicy.chooseTitle(
                    rootName = root.name,
                    relativeDirectories = video.relativeDirectories,
                    parsedTitleHint = parsed.titleHint,
                    parsedSeasonNumber = parsed.seasonNumber,
                )
                val sourceKey = "${treeUri}::${GroupingPolicy.keyFor(title)}"
                groupNames.putIfAbsent(sourceKey, title)
                sourceKey
            }
        }

        val discoveredTitles = grouped.entries
            .sortedBy { groupNames[it.key].orEmpty().lowercase(Locale.ROOT) }
            .map { (sourceKey, titleVideos) ->
                val titleName = groupNames[sourceKey] ?: "Без названия"
                DiscoveredTitle(
                    sourceKey = sourceKey,
                    suggestedName = titleName,
                    posterUri = findPoster(
                        titleName = titleName,
                        candidates = titleVideos
                            .map { GroupingPolicy.titleDirectoryKey(it.relativeDirectories) }
                            .distinct()
                            .flatMap { imagesByDirectory[it].orEmpty() },
                    ),
                    episodes = titleVideos.map { video ->
                        ensureActive()
                        val parsed = EpisodeNameParser.parse(video.fileName)
                        val mediaMetadata = mediaStoreIndex.find(
                            relativeDirectories = video.relativeDirectories,
                            fileName = video.fileName,
                            sizeBytes = video.document.sizeBytes,
                        )
                        DiscoveredEpisode(
                            fileUri = video.uri,
                            fileName = video.fileName,
                            episodeNumber = parsed.episodeNumber,
                            seasonNumber = parsed.seasonNumber,
                            durationMs = mediaMetadata?.durationMs,
                            sizeBytes = mediaMetadata?.sizeBytes ?: video.document.sizeBytes.coerceAtLeast(0L),
                            mimeType = mediaMetadata?.mimeType ?: video.document.mimeType,
                            lastModified = mediaMetadata?.lastModified
                                ?: video.document.lastModified.coerceAtLeast(0L),
                            sortName = EpisodeNameParser.normalizedStem(video.fileName),
                            subtitles = matchSubtitles(
                                video = video,
                                candidates = subtitlesByDirectory[video.directoryKey].orEmpty(),
                            ),
                        )
                    },
                )
            }

        onProgress(ScanProgress(visited, videos.size))
        FolderScanResult(
            treeUri = treeUri.toString(),
            titles = discoveredTitles,
            visitedDocuments = visited,
            videosFound = videos.size,
            subtitlesFound = subtitles.size,
            warnings = emptyList(),
        )
    }

    private fun matchSubtitles(
        video: FoundFile,
        candidates: List<FoundFile>,
    ): List<DiscoveredSubtitle> {
        val videoStem = EpisodeNameParser.normalizedStem(video.fileName)
        return candidates.mapNotNull { subtitle ->
            val subtitleStem = EpisodeNameParser.normalizedStem(subtitle.fileName)
            val isMatch = subtitleStem == videoStem ||
                subtitleStem.startsWith("$videoStem ") ||
                videoStem.startsWith("$subtitleStem ")
            if (!isMatch) return@mapNotNull null

            DiscoveredSubtitle(
                fileUri = subtitle.uri,
                fileName = subtitle.fileName,
                mimeType = subtitleMimeType(subtitle.extension),
                language = detectLanguage(subtitle.fileName),
            )
        }
    }

    private fun findPoster(
        titleName: String,
        candidates: List<FoundFile>,
    ): String? {
        if (candidates.isEmpty()) return null
        val normalizedTitle = EpisodeNameParser.normalizedStem(titleName)
        val selected = candidates.minWithOrNull(
            compareBy<FoundFile> { image ->
                val stem = EpisodeNameParser.normalizedStem(image.fileName)
                when {
                    stem in preferredPosterNames -> 0
                    stem == normalizedTitle -> 1
                    candidates.size == 1 -> 2
                    else -> 3
                }
            }.thenBy { it.fileName.lowercase(Locale.ROOT) },
        ) ?: return null
        val selectedStem = EpisodeNameParser.normalizedStem(selected.fileName)
        val isCrediblePoster = selectedStem in preferredPosterNames ||
            selectedStem == normalizedTitle ||
            candidates.size == 1
        return selected.uri.takeIf { isCrediblePoster }
    }

    private fun subtitleMimeType(extension: String): String = when (extension) {
        "srt" -> "application/x-subrip"
        "ass", "ssa" -> "text/x-ssa"
        "vtt" -> "text/vtt"
        else -> "text/plain"
    }

    private fun detectLanguage(fileName: String): String? {
        val match = Regex("(?i)(?:[._ -])(ru|rus|en|eng|ja|jpn)(?:[._ -]|$)").find(fileName)
            ?: return null
        return when (match.groupValues[1].lowercase(Locale.ROOT)) {
            "ru", "rus" -> "ru"
            "en", "eng" -> "en"
            "ja", "jpn" -> "ja"
            else -> null
        }
    }

    private fun readDocuments(uri: Uri, checkCancellation: () -> Unit): List<ScannedDocument> =
        context.contentResolver.query(uri, DOCUMENT_COLUMNS, null, null, null)
            ?.use { it.readCompleteDocuments(checkCancellation) }
            ?: throw incompleteScan()

    private data class DirectoryNode(
        val id: String,
        val relativeDirectories: List<String>,
    )

    private data class FoundFile(
        val uri: String,
        val document: ScannedDocument,
        val relativeDirectories: List<String>,
        val extension: String,
    ) {
        val fileName: String = document.name
        val directoryKey: String = relativeDirectories.joinToString("/")
    }
}


internal data class ScannedDocument(
    val id: String,
    val name: String,
    val mimeType: String,
    val sizeBytes: Long,
    val lastModified: Long,
)

private val DOCUMENT_COLUMNS = arrayOf(
    Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE,
    Document.COLUMN_SIZE, Document.COLUMN_LAST_MODIFIED,
)

private fun incompleteScan() =
    IOException("Хранилище вернуло неполный список файлов. Медиатека сохранена, повторите сканирование.")

internal fun Cursor.readCompleteDocuments(checkCancellation: () -> Unit): List<ScannedDocument> {
    val (id, name, mimeType, sizeColumn, modified) = DOCUMENT_COLUMNS.map(::getColumnIndex)

    fun checkProviderStatus() {
        val status = extras
        if (status.getBoolean(DocumentsContract.EXTRA_LOADING) ||
            status.getString(DocumentsContract.EXTRA_ERROR) != null
        ) throw incompleteScan()
    }

    if (id < 0 || name < 0 || mimeType < 0) throw incompleteScan()
    checkProviderStatus()
    val expectedCount = count
    return buildList {
        while (moveToNext()) {
            checkCancellation()
            val documentId = getString(id)?.takeIf(String::isNotBlank) ?: throw incompleteScan()
            val displayName = getString(name)?.takeIf(String::isNotBlank) ?: throw incompleteScan()
            val type = getString(mimeType)?.takeIf(String::isNotBlank) ?: throw incompleteScan()
            add(ScannedDocument(
                id = documentId,
                name = displayName,
                mimeType = type,
                sizeBytes = if (sizeColumn >= 0) getLong(sizeColumn).coerceAtLeast(0L) else 0L,
                lastModified = if (modified >= 0) getLong(modified).coerceAtLeast(0L) else 0L,
            ))
        }
        checkCancellation()
        checkProviderStatus()
        if (this.size != expectedCount) throw incompleteScan()
    }
}
