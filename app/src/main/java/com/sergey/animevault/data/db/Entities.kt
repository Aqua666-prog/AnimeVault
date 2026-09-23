package com.sergey.animevault.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "library_folders")
data class LibraryFolderEntity(
    @PrimaryKey
    @ColumnInfo(name = "tree_uri")
    val treeUri: String,
    @ColumnInfo(name = "display_name")
    val displayName: String,
    @ColumnInfo(name = "added_at")
    val addedAt: Long,
    @ColumnInfo(name = "last_scanned_at")
    val lastScannedAt: Long? = null,
)

@Entity(
    tableName = "titles",
    foreignKeys = [
        ForeignKey(
            entity = LibraryFolderEntity::class,
            parentColumns = ["tree_uri"],
            childColumns = ["root_tree_uri"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["source_key"], unique = true),
        Index(value = ["root_tree_uri"]),
    ],
)
data class AnimeTitleEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    @ColumnInfo(name = "source_key")
    val sourceKey: String,
    @ColumnInfo(name = "root_tree_uri")
    val rootTreeUri: String,
    val name: String,
    @ColumnInfo(name = "poster_uri")
    val posterUri: String? = null,
    @ColumnInfo(name = "is_name_user_edited")
    val isNameUserEdited: Boolean = false,
    @ColumnInfo(name = "date_added")
    val dateAdded: Long,
    @ColumnInfo(name = "last_scanned_at")
    val lastScannedAt: Long,
)

@Entity(
    tableName = "episodes",
    foreignKeys = [
        ForeignKey(
            entity = AnimeTitleEntity::class,
            parentColumns = ["id"],
            childColumns = ["title_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["title_id"]),
        Index(value = ["file_uri"], unique = true),
    ],
)
data class EpisodeEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    @ColumnInfo(name = "title_id")
    val titleId: Long,
    @ColumnInfo(name = "file_uri")
    val fileUri: String,
    @ColumnInfo(name = "file_name")
    val fileName: String,
    @ColumnInfo(name = "episode_number")
    val episodeNumber: Double? = null,
    @ColumnInfo(name = "season_number")
    val seasonNumber: Int? = null,
    @ColumnInfo(name = "duration_ms")
    val durationMs: Long? = null,
    @ColumnInfo(name = "size_bytes")
    val sizeBytes: Long = 0,
    @ColumnInfo(name = "mime_type")
    val mimeType: String? = null,
    @ColumnInfo(name = "last_modified")
    val lastModified: Long = 0,
    @ColumnInfo(name = "sort_name")
    val sortName: String,
)

@Entity(
    tableName = "external_subtitles",
    foreignKeys = [
        ForeignKey(
            entity = EpisodeEntity::class,
            parentColumns = ["id"],
            childColumns = ["episode_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["episode_id"]),
        Index(value = ["file_uri"], unique = true),
    ],
)
data class ExternalSubtitleEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    @ColumnInfo(name = "episode_id")
    val episodeId: Long,
    @ColumnInfo(name = "file_uri")
    val fileUri: String,
    @ColumnInfo(name = "file_name")
    val fileName: String,
    @ColumnInfo(name = "mime_type")
    val mimeType: String,
    val language: String? = null,
)

@Entity(
    tableName = "watch_progress",
    foreignKeys = [
        ForeignKey(
            entity = EpisodeEntity::class,
            parentColumns = ["id"],
            childColumns = ["episode_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class WatchProgressEntity(
    @PrimaryKey
    @ColumnInfo(name = "episode_id")
    val episodeId: Long,
    @ColumnInfo(name = "position_ms")
    val positionMs: Long,
    @ColumnInfo(name = "is_completed")
    val isCompleted: Boolean,
    @ColumnInfo(name = "last_watched_at")
    val lastWatchedAt: Long,
    @ColumnInfo(name = "first_played_at")
    val firstPlayedAt: Long = 0L,
    @ColumnInfo(name = "completed_at")
    val completedAt: Long? = null,
    @ColumnInfo(name = "play_count")
    val playCount: Int = 0,
)

/**
 * Ручное решение пользователя о том, к какому тайтлу относится конкретный файл.
 *
 * Таблица намеренно не ссылается на episodes: запись должна пережить временное
 * исчезновение файла и восстановиться при следующем сканировании.
 */
@Entity(
    tableName = "episode_grouping_overrides",
    foreignKeys = [
        ForeignKey(
            entity = LibraryFolderEntity::class,
            parentColumns = ["tree_uri"],
            childColumns = ["root_tree_uri"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["root_tree_uri"]),
        Index(value = ["target_source_key"]),
    ],
)
data class EpisodeGroupingOverrideEntity(
    @PrimaryKey
    @ColumnInfo(name = "file_uri")
    val fileUri: String,
    @ColumnInfo(name = "root_tree_uri")
    val rootTreeUri: String,
    @ColumnInfo(name = "target_source_key")
    val targetSourceKey: String,
    @ColumnInfo(name = "target_title_name")
    val targetTitleName: String,
    @ColumnInfo(name = "created_at")
    val createdAt: Long,
)

/** Many-to-many связь локального тайтла с релизами разных онлайн-источников. */
@Entity(
    tableName = "offline_online_links",
    primaryKeys = ["offline_title_id", "provider_id", "online_release_id"],
    foreignKeys = [
        ForeignKey(
            entity = AnimeTitleEntity::class,
            parentColumns = ["id"],
            childColumns = ["offline_title_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["offline_title_id"]),
        Index(value = ["provider_id", "online_release_id"]),
        Index(value = ["mal_id"]),
        Index(value = ["kodik_id"]),
    ],
)
data class OfflineOnlineLinkEntity(
    @ColumnInfo(name = "offline_title_id")
    val offlineTitleId: Long,
    @ColumnInfo(name = "provider_id")
    val providerId: String,
    @ColumnInfo(name = "online_release_id")
    val onlineReleaseId: String,
    @ColumnInfo(name = "online_title_name")
    val onlineTitleName: String,
    @ColumnInfo(name = "online_alias")
    val onlineAlias: String? = null,
    @ColumnInfo(name = "poster_url")
    val posterUrl: String? = null,
    @ColumnInfo(name = "mal_id")
    val malId: String? = null,
    @ColumnInfo(name = "kodik_id")
    val kodikId: String? = null,
    @ColumnInfo(name = "linked_at")
    val linkedAt: Long,
)


/**
 * Выбранные пользователем метаданные локального тайтла.
 *
 * Отдельная таблица не смешивает сведения из внешнего каталога с результатами
 * файлового сканера: папки и серии остаются offline-first, а метаданные можно
 * безопасно заменить или удалить без пересканирования видеотеки.
 */
@Entity(
    tableName = "title_metadata",
    foreignKeys = [
        ForeignKey(
            entity = AnimeTitleEntity::class,
            parentColumns = ["id"],
            childColumns = ["title_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["provider", "external_id"]),
    ],
)
data class TitleMetadataEntity(
    @PrimaryKey
    @ColumnInfo(name = "title_id")
    val titleId: Long,
    val provider: String,
    @ColumnInfo(name = "external_id")
    val externalId: Long,
    @ColumnInfo(name = "mal_id")
    val malId: Long? = null,
    @ColumnInfo(name = "canonical_title")
    val canonicalTitle: String? = null,
    @ColumnInfo(name = "english_title")
    val englishTitle: String? = null,
    @ColumnInfo(name = "native_title")
    val nativeTitle: String? = null,
    @ColumnInfo(name = "poster_url")
    val posterUrl: String? = null,
    @ColumnInfo(name = "banner_url")
    val bannerUrl: String? = null,
    val description: String? = null,
    val year: Int? = null,
    @ColumnInfo(name = "episode_count")
    val episodeCount: Int? = null,
    val format: String? = null,
    val status: String? = null,
    /** Жанры хранятся через служебный разделитель, чтобы не вводить TypeConverter. */
    val genres: String? = null,
    @ColumnInfo(name = "average_score")
    val averageScore: Int? = null,
    @ColumnInfo(name = "site_url")
    val siteUrl: String? = null,
    @ColumnInfo(name = "updated_at")
    val updatedAt: Long,
) {
    companion object {
        const val GENRE_SEPARATOR = "\u001F"
    }
}
