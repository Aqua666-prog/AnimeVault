package com.sergey.animevault.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import androidx.room.Upsert
import com.sergey.animevault.data.model.EpisodeRow
import com.sergey.animevault.data.model.BackupMetadataRow
import com.sergey.animevault.data.model.BackupOnlineLinkRow
import com.sergey.animevault.data.model.BackupProgressRow
import com.sergey.animevault.data.model.ContinueWatchingRow
import com.sergey.animevault.data.model.LibraryTitleRow
import com.sergey.animevault.data.model.GroupingTargetRow
import com.sergey.animevault.data.model.OfflineOnlineLinkRow
import com.sergey.animevault.data.model.PlaybackEpisodeRow
import com.sergey.animevault.data.model.SubtitleRow
import com.sergey.animevault.data.model.TitleMetadataRow
import kotlinx.coroutines.flow.Flow

@Dao
abstract class LibraryDao {
    @Query(
        """
        SELECT
            t.id AS id,
            t.name AS name,
            COALESCE(
                t.poster_uri,
                m.poster_url,
                (SELECT l.poster_url
                 FROM offline_online_links AS l
                 WHERE l.offline_title_id = t.id AND l.poster_url IS NOT NULL
                 ORDER BY l.linked_at DESC
                 LIMIT 1)
            ) AS posterUri,
            t.date_added AS dateAdded,
            COUNT(e.id) AS episodeCount,
            COALESCE(SUM(CASE WHEN p.is_completed = 1 THEN 1 ELSE 0 END), 0) AS completedCount,
            MAX(p.last_watched_at) AS lastWatchedAt,
            (SELECT COUNT(*) FROM offline_online_links AS l WHERE l.offline_title_id = t.id) AS onlineLinkCount,
            COALESCE(SUM(e.size_bytes), 0) AS totalBytes,
            COALESCE(SUM(CASE WHEN p.is_completed = 1 THEN e.size_bytes ELSE 0 END), 0) AS completedBytes,
            COALESCE(SUM(
                CASE
                    WHEN p.is_completed = 1 THEN COALESCE(e.duration_ms, 0)
                    ELSE COALESCE(p.position_ms, 0)
                END
            ), 0) AS watchedTimeMs
        FROM titles AS t
        LEFT JOIN episodes AS e ON e.title_id = t.id
        LEFT JOIN watch_progress AS p ON p.episode_id = e.id
        LEFT JOIN title_metadata AS m ON m.title_id = t.id
        GROUP BY t.id
        """,
    )
    abstract fun observeLibrary(): Flow<List<LibraryTitleRow>>

    @Query(
        """
        SELECT
            e.id AS episodeId,
            e.title_id AS titleId,
            t.name AS titleName,
            COALESCE(
                t.poster_uri,
                m.poster_url,
                (SELECT l.poster_url
                 FROM offline_online_links AS l
                 WHERE l.offline_title_id = t.id AND l.poster_url IS NOT NULL
                 ORDER BY l.linked_at DESC
                 LIMIT 1)
            ) AS posterUri,
            e.episode_number AS episodeNumber,
            e.season_number AS seasonNumber,
            p.position_ms AS positionMs,
            COALESCE(e.duration_ms, 0) AS durationMs,
            p.last_watched_at AS lastWatchedAt
        FROM watch_progress AS p
        JOIN episodes AS e ON e.id = p.episode_id
        JOIN titles AS t ON t.id = e.title_id
        LEFT JOIN title_metadata AS m ON m.title_id = t.id
        WHERE p.is_completed = 0
          AND p.position_ms > 0
          AND p.last_watched_at = (
              SELECT MAX(p2.last_watched_at)
              FROM watch_progress AS p2
              JOIN episodes AS e2 ON e2.id = p2.episode_id
              WHERE e2.title_id = e.title_id
                AND p2.is_completed = 0
                AND p2.position_ms > 0
          )
        ORDER BY p.last_watched_at DESC
        LIMIT 20
        """,
    )
    abstract fun observeHomeContinueWatching(): Flow<List<ContinueWatchingRow>>

    @Query("SELECT * FROM library_folders ORDER BY added_at")
    abstract fun observeFolders(): Flow<List<LibraryFolderEntity>>

    @Query("SELECT * FROM library_folders ORDER BY added_at")
    abstract suspend fun getFolders(): List<LibraryFolderEntity>

    @Query("SELECT * FROM titles WHERE id = :titleId")
    abstract fun observeTitle(titleId: Long): Flow<AnimeTitleEntity?>

    @Query("SELECT * FROM titles WHERE id = :titleId")
    abstract suspend fun getTitle(titleId: Long): AnimeTitleEntity?

    @Query(
        """
        SELECT title_id AS titleId,
               provider AS provider,
               external_id AS externalId,
               mal_id AS malId,
               canonical_title AS canonicalTitle,
               english_title AS englishTitle,
               native_title AS nativeTitle,
               poster_url AS posterUrl,
               banner_url AS bannerUrl,
               description AS description,
               year AS year,
               episode_count AS episodeCount,
               format AS format,
               status AS status,
               genres AS genres,
               average_score AS averageScore,
               site_url AS siteUrl,
               updated_at AS updatedAt
        FROM title_metadata
        WHERE title_id = :titleId
        """,
    )
    abstract fun observeTitleMetadata(titleId: Long): Flow<TitleMetadataRow?>

    @Upsert
    abstract suspend fun upsertTitleMetadata(metadata: TitleMetadataEntity)

    @Query("SELECT * FROM title_metadata WHERE title_id = :titleId")
    abstract suspend fun getTitleMetadataEntity(titleId: Long): TitleMetadataEntity?

    @Query("DELETE FROM title_metadata WHERE title_id = :titleId")
    abstract suspend fun deleteTitleMetadata(titleId: Long)

    @Query(
        """
        SELECT
            e.id AS id,
            e.title_id AS titleId,
            e.file_uri AS fileUri,
            e.file_name AS fileName,
            e.episode_number AS episodeNumber,
            e.season_number AS seasonNumber,
            e.duration_ms AS durationMs,
            e.size_bytes AS sizeBytes,
            e.mime_type AS mimeType,
            e.sort_name AS sortName,
            COALESCE(p.position_ms, 0) AS positionMs,
            COALESCE(p.is_completed, 0) AS isCompleted,
            p.last_watched_at AS lastWatchedAt
        FROM episodes AS e
        LEFT JOIN watch_progress AS p ON p.episode_id = e.id
        WHERE e.title_id = :titleId
        ORDER BY
            CASE WHEN e.season_number IS NULL THEN 1 ELSE 0 END,
            e.season_number,
            CASE WHEN e.episode_number IS NULL THEN 1 ELSE 0 END,
            e.episode_number,
            e.sort_name COLLATE NOCASE
        """,
    )
    abstract fun observeEpisodes(titleId: Long): Flow<List<EpisodeRow>>

    @Query(
        """
        SELECT
            e.id AS id,
            e.title_id AS titleId,
            t.name AS titleName,
            e.file_uri AS fileUri,
            e.file_name AS fileName,
            e.episode_number AS episodeNumber,
            e.season_number AS seasonNumber,
            e.duration_ms AS durationMs,
            COALESCE(p.position_ms, 0) AS positionMs,
            COALESCE(p.is_completed, 0) AS isCompleted
        FROM episodes AS e
        JOIN titles AS t ON t.id = e.title_id
        LEFT JOIN watch_progress AS p ON p.episode_id = e.id
        WHERE e.id = :episodeId
        """,
    )
    abstract suspend fun getPlaybackEpisode(episodeId: Long): PlaybackEpisodeRow?

    @Query(
        """
        SELECT
            file_uri AS fileUri,
            file_name AS fileName,
            mime_type AS mimeType,
            language AS language
        FROM external_subtitles
        WHERE episode_id = :episodeId
        ORDER BY file_name COLLATE NOCASE
        """,
    )
    abstract suspend fun getSubtitles(episodeId: Long): List<SubtitleRow>

    @Query("SELECT * FROM episodes WHERE title_id = :titleId")
    abstract suspend fun getEpisodeEntities(titleId: Long): List<EpisodeEntity>

    @Query(
        """
        SELECT e.* FROM episodes AS e
        JOIN watch_progress AS p ON p.episode_id = e.id
        WHERE p.is_completed = 1
        ORDER BY e.title_id, e.id
        """,
    )
    abstract suspend fun getCompletedEpisodeEntities(): List<EpisodeEntity>

    @Query("DELETE FROM episodes WHERE id = :episodeId")
    abstract suspend fun deleteEpisodeById(episodeId: Long)

    @Query(
        """
        SELECT e.* FROM episodes AS e
        JOIN titles AS t ON t.id = e.title_id
        WHERE t.root_tree_uri = :treeUri
        """,
    )
    abstract suspend fun getEpisodeEntitiesInFolder(treeUri: String): List<EpisodeEntity>

    @Query("SELECT * FROM titles WHERE root_tree_uri = :treeUri")
    abstract suspend fun getTitlesInFolder(treeUri: String): List<AnimeTitleEntity>

    @Query(
        """
        SELECT id, source_key AS sourceKey, root_tree_uri AS rootTreeUri, name
        FROM titles
        ORDER BY name COLLATE NOCASE
        """,
    )
    abstract fun observeGroupingTargets(): Flow<List<GroupingTargetRow>>

    @Query("SELECT * FROM titles WHERE source_key = :sourceKey LIMIT 1")
    abstract suspend fun getTitleBySourceKey(sourceKey: String): AnimeTitleEntity?

    @Query("SELECT * FROM episodes WHERE id IN (:episodeIds)")
    abstract suspend fun getEpisodesByIds(episodeIds: List<Long>): List<EpisodeEntity>

    @Query("SELECT * FROM episode_grouping_overrides WHERE root_tree_uri = :treeUri")
    abstract suspend fun getGroupingOverridesInFolder(treeUri: String): List<EpisodeGroupingOverrideEntity>

    @Query("SELECT * FROM episode_grouping_overrides WHERE file_uri IN (:fileUris)")
    abstract suspend fun getGroupingOverridesForFiles(fileUris: List<String>): List<EpisodeGroupingOverrideEntity>

    @Upsert
    abstract suspend fun upsertGroupingOverrides(overrides: List<EpisodeGroupingOverrideEntity>)

    @Query("DELETE FROM episode_grouping_overrides WHERE file_uri IN (:fileUris)")
    abstract suspend fun deleteGroupingOverrides(fileUris: List<String>)

    @Query("DELETE FROM episode_grouping_overrides WHERE root_tree_uri = :treeUri AND file_uri NOT IN (:keptUris)")
    abstract suspend fun deleteStaleGroupingOverrides(treeUri: String, keptUris: List<String>)

    @Query("DELETE FROM episode_grouping_overrides WHERE root_tree_uri = :treeUri")
    abstract suspend fun deleteGroupingOverridesInFolder(treeUri: String)

    @Query("DELETE FROM titles WHERE id = :titleId")
    abstract suspend fun deleteTitle(titleId: Long)

    @Upsert
    abstract suspend fun upsertOfflineOnlineLink(link: OfflineOnlineLinkEntity)

    @Query("SELECT * FROM offline_online_links WHERE offline_title_id = :titleId")
    abstract suspend fun getOfflineOnlineLinkEntities(titleId: Long): List<OfflineOnlineLinkEntity>

    @Query(
        """
        SELECT provider_id AS providerId,
               online_release_id AS onlineReleaseId,
               online_title_name AS onlineTitleName,
               online_alias AS onlineAlias,
               poster_url AS posterUrl,
               mal_id AS malId,
               kodik_id AS kodikId,
               linked_at AS linkedAt
        FROM offline_online_links
        WHERE offline_title_id = :titleId
        ORDER BY linked_at DESC
        """,
    )
    abstract fun observeOfflineOnlineLinks(titleId: Long): Flow<List<OfflineOnlineLinkRow>>

    @Query(
        """
        DELETE FROM offline_online_links
        WHERE offline_title_id = :titleId
          AND provider_id = :providerId
          AND online_release_id = :releaseId
        """,
    )
    abstract suspend fun deleteOfflineOnlineLink(titleId: Long, providerId: String, releaseId: String)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    abstract suspend fun insertTitle(title: AnimeTitleEntity): Long

    @Update
    abstract suspend fun updateTitle(title: AnimeTitleEntity)

    @Upsert
    abstract suspend fun upsertFolder(folder: LibraryFolderEntity)

    @Upsert
    abstract suspend fun upsertEpisodes(episodes: List<EpisodeEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun insertSubtitles(subtitles: List<ExternalSubtitleEntity>)

    @Upsert
    abstract suspend fun upsertProgress(progress: WatchProgressEntity)

    @Query("DELETE FROM external_subtitles WHERE episode_id = :episodeId")
    abstract suspend fun deleteSubtitlesForEpisode(episodeId: Long)

    @Query("DELETE FROM external_subtitles WHERE episode_id IN (SELECT id FROM episodes WHERE title_id = :titleId)")
    abstract suspend fun deleteSubtitlesForTitle(titleId: Long)

    @Query("DELETE FROM episodes WHERE title_id = :titleId")
    abstract suspend fun deleteEpisodesForTitle(titleId: Long)

    @Query("DELETE FROM episodes WHERE title_id = :titleId AND file_uri NOT IN (:keptUris)")
    abstract suspend fun deleteEpisodesNotIn(titleId: Long, keptUris: List<String>)

    @Query("DELETE FROM titles WHERE id IN (:titleIds)")
    abstract suspend fun deleteTitles(titleIds: List<Long>)

    @Query("DELETE FROM library_folders WHERE tree_uri = :treeUri")
    abstract suspend fun deleteFolder(treeUri: String)

    @Query("UPDATE library_folders SET last_scanned_at = :timestamp WHERE tree_uri = :treeUri")
    abstract suspend fun updateFolderScanTime(treeUri: String, timestamp: Long)

    @Query("UPDATE episodes SET duration_ms = :durationMs WHERE id = :episodeId")
    abstract suspend fun updateEpisodeDuration(episodeId: Long, durationMs: Long)

    @Query("UPDATE titles SET poster_uri = :posterUri WHERE id = :titleId")
    abstract suspend fun updateTitlePoster(titleId: Long, posterUri: String?)

    @Query("SELECT * FROM titles")
    abstract suspend fun getAllTitleEntities(): List<AnimeTitleEntity>

    @Query("SELECT * FROM episodes")
    abstract suspend fun getAllEpisodeEntities(): List<EpisodeEntity>

    @Query("SELECT * FROM episode_grouping_overrides")
    abstract suspend fun getAllGroupingOverrideEntities(): List<EpisodeGroupingOverrideEntity>

    @Query(
        """
        SELECT e.file_uri AS fileUri,
               p.position_ms AS positionMs,
               p.is_completed AS isCompleted,
               p.last_watched_at AS lastWatchedAt
        FROM watch_progress AS p
        JOIN episodes AS e ON e.id = p.episode_id
        """,
    )
    abstract suspend fun getBackupProgressRows(): List<BackupProgressRow>

    @Query(
        """
        SELECT t.source_key AS sourceKey,
               m.provider AS provider, m.external_id AS externalId, m.mal_id AS malId,
               m.canonical_title AS canonicalTitle, m.english_title AS englishTitle,
               m.native_title AS nativeTitle, m.poster_url AS posterUrl, m.banner_url AS bannerUrl,
               m.description AS description, m.year AS year, m.episode_count AS episodeCount,
               m.format AS format, m.status AS status, m.genres AS genres,
               m.average_score AS averageScore, m.site_url AS siteUrl, m.updated_at AS updatedAt
        FROM title_metadata AS m
        JOIN titles AS t ON t.id = m.title_id
        """,
    )
    abstract suspend fun getBackupMetadataRows(): List<BackupMetadataRow>

    @Query(
        """
        SELECT t.source_key AS sourceKey,
               l.provider_id AS providerId, l.online_release_id AS onlineReleaseId,
               l.online_title_name AS onlineTitleName, l.online_alias AS onlineAlias,
               l.poster_url AS posterUrl, l.mal_id AS malId, l.kodik_id AS kodikId,
               l.linked_at AS linkedAt
        FROM offline_online_links AS l
        JOIN titles AS t ON t.id = l.offline_title_id
        """,
    )
    abstract suspend fun getBackupOnlineLinkRows(): List<BackupOnlineLinkRow>

    @Query("DELETE FROM watch_progress")
    abstract suspend fun clearProgress()

    @Query("DELETE FROM library_folders")
    abstract suspend fun clearLibrary()
}
