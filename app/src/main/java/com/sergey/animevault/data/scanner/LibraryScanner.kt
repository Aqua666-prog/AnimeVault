package com.sergey.animevault.data.scanner

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
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
        val root = DocumentFile.fromTreeUri(context, treeUri)
            ?: error("Не удалось открыть выбранную папку")
        require(root.exists() && root.isDirectory) { "Выбранная папка больше недоступна" }

        val rootName = root.name?.takeIf(String::isNotBlank) ?: "Видеотека"
        val queue = ArrayDeque<DirectoryNode>()
        val videos = mutableListOf<FoundFile>()
        val subtitles = mutableListOf<FoundFile>()
        val images = mutableListOf<FoundFile>()
        val warnings = mutableListOf<String>()
        var visited = 0
        queue.add(DirectoryNode(root, emptyList()))

        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            val children = runCatching { node.document.listFiles().toList() }
                .getOrElse { error ->
                    warnings += "Не прочитана папка ${node.document.name ?: "без имени"}: ${error.message ?: "ошибка доступа"}"
                    emptyList()
                }

            for (child in children) {
                visited += 1
                when {
                    child.isDirectory -> {
                        val name = child.name ?: continue
                        queue.add(DirectoryNode(child, node.relativeDirectories + name))
                    }
                    child.isFile -> {
                        val name = child.name ?: continue
                        val extension = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
                        val found = FoundFile(
                            document = child,
                            relativeDirectories = node.relativeDirectories,
                            extension = extension,
                        )
                        when {
                            extension in videoExtensions || child.type?.startsWith("video/") == true -> videos += found
                            extension in subtitleExtensions -> subtitles += found
                            extension in imageExtensions || child.type?.startsWith("image/") == true -> images += found
                        }
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
            val override = groupingOverrides[video.document.uri.toString()]
            if (override != null) {
                groupNames[override.sourceKey] = override.titleName
                override.sourceKey
            } else {
                val parsed = EpisodeNameParser.parse(video.fileName)
                val title = GroupingPolicy.chooseTitle(
                    rootName = rootName,
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
                        val parsed = EpisodeNameParser.parse(video.fileName)
                        val mediaMetadata = mediaStoreIndex.find(
                            relativeDirectories = video.relativeDirectories,
                            fileName = video.fileName,
                            sizeBytes = video.document.length(),
                        )
                        DiscoveredEpisode(
                            fileUri = video.document.uri.toString(),
                            fileName = video.fileName,
                            episodeNumber = parsed.episodeNumber,
                            seasonNumber = parsed.seasonNumber,
                            durationMs = mediaMetadata?.durationMs,
                            sizeBytes = mediaMetadata?.sizeBytes ?: video.document.length().coerceAtLeast(0L),
                            mimeType = mediaMetadata?.mimeType ?: video.document.type,
                            lastModified = mediaMetadata?.lastModified
                                ?: video.document.lastModified().coerceAtLeast(0L),
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
            warnings = warnings.take(20),
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
                fileUri = subtitle.document.uri.toString(),
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
        return selected.document.uri.toString().takeIf { isCrediblePoster }
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

    private data class DirectoryNode(
        val document: DocumentFile,
        val relativeDirectories: List<String>,
    )

    private data class FoundFile(
        val document: DocumentFile,
        val relativeDirectories: List<String>,
        val extension: String,
    ) {
        val fileName: String = document.name.orEmpty()
        val directoryKey: String = relativeDirectories.joinToString("/")
    }
}
