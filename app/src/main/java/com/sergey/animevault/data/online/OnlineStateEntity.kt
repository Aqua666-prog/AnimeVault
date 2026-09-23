package com.sergey.animevault.data.online

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "online_progress", primaryKeys = ["provider_id", "episode_id"])
data class OnlineProgressEntity(
    @ColumnInfo(name = "provider_id") val providerId: String,
    @ColumnInfo(name = "episode_id") val episodeId: String,
    @ColumnInfo(name = "position_ms") val positionMs: Long,
    @ColumnInfo(name = "duration_ms") val durationMs: Long,
    @ColumnInfo(name = "is_completed") val isCompleted: Boolean,
    @ColumnInfo(name = "last_watched_at") val lastWatchedAt: Long,
    @ColumnInfo(name = "first_played_at") val firstPlayedAt: Long,
    @ColumnInfo(name = "completed_at") val completedAt: Long?,
    @ColumnInfo(name = "play_count") val playCount: Int,
)

@Entity(tableName = "online_library", primaryKeys = ["provider_id", "release_id"])
data class OnlineLibraryEntity(
    @ColumnInfo(name = "provider_id") val providerId: String,
    @ColumnInfo(name = "provider_name") val providerName: String,
    @ColumnInfo(name = "release_id") val releaseId: String,
    val name: String,
    @ColumnInfo(name = "english_name") val englishName: String?,
    @ColumnInfo(name = "poster_url") val posterUrl: String?,
    val year: Int?,
    val type: String?,
    val season: String?,
    @ColumnInfo(name = "episode_count") val episodeCount: Int?,
    @ColumnInfo(name = "is_ongoing") val isOngoing: Boolean,
    @ColumnInfo(name = "is_favorite") val isFavorite: Boolean,
    @ColumnInfo(name = "favorite_added_at") val favoriteAddedAt: Long,
    @ColumnInfo(name = "first_opened_at") val firstOpenedAt: Long,
    @ColumnInfo(name = "last_opened_at") val lastOpenedAt: Long,
    @ColumnInfo(name = "last_watched_at") val lastWatchedAt: Long,
    @ColumnInfo(name = "last_episode_id") val lastEpisodeId: String?,
    @ColumnInfo(name = "last_episode_ordinal") val lastEpisodeOrdinal: Double?,
    @ColumnInfo(name = "last_position_ms") val lastPositionMs: Long,
    @ColumnInfo(name = "last_duration_ms") val lastDurationMs: Long,
    @ColumnInfo(name = "last_episode_completed") val lastEpisodeCompleted: Boolean,
)

@Dao
interface OnlineStateDao {
    @Query("SELECT * FROM online_progress")
    fun observeProgress(): Flow<List<OnlineProgressEntity>>

    @Query("SELECT * FROM online_progress")
    suspend fun getAllProgress(): List<OnlineProgressEntity>

    @Query("SELECT * FROM online_progress WHERE provider_id = :providerId AND episode_id = :episodeId LIMIT 1")
    suspend fun getProgress(providerId: String, episodeId: String): OnlineProgressEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertProgress(entity: OnlineProgressEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertProgress(entities: List<OnlineProgressEntity>)

    @Query("DELETE FROM online_progress")
    suspend fun clearProgress()

    @Query("SELECT * FROM online_library")
    fun observeLibrary(): Flow<List<OnlineLibraryEntity>>

    @Query("SELECT * FROM online_library")
    suspend fun getAllLibrary(): List<OnlineLibraryEntity>

    @Query("SELECT * FROM online_library WHERE provider_id = :providerId AND release_id = :releaseId LIMIT 1")
    suspend fun getLibrary(providerId: String, releaseId: String): OnlineLibraryEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertLibrary(entity: OnlineLibraryEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertLibrary(entities: List<OnlineLibraryEntity>)

    @Query("DELETE FROM online_library WHERE provider_id = :providerId AND release_id = :releaseId")
    suspend fun deleteLibrary(providerId: String, releaseId: String)

    @Query("DELETE FROM online_library")
    suspend fun clearLibrary()
}

internal fun OnlineProgressEntity.toModel() = OnlineWatchProgress(
    positionMs, durationMs, isCompleted, lastWatchedAt, firstPlayedAt, completedAt, playCount,
)

internal fun OnlineWatchProgress.toEntity(providerId: String, episodeId: String) = OnlineProgressEntity(
    providerId, episodeId, positionMs, durationMs, isCompleted, lastWatchedAt, firstPlayedAt, completedAt, playCount,
)

internal fun OnlineLibraryEntity.toModel() = OnlineLibraryEntry(
    providerId, providerName, releaseId, name, englishName, posterUrl, year, type, season, episodeCount,
    isOngoing, isFavorite, favoriteAddedAt, firstOpenedAt, lastOpenedAt, lastWatchedAt, lastEpisodeId,
    lastEpisodeOrdinal, lastPositionMs, lastDurationMs, lastEpisodeCompleted,
)

internal fun OnlineLibraryEntry.toEntity() = OnlineLibraryEntity(
    providerId, providerName, releaseId, name, englishName, posterUrl, year, type, season, episodeCount,
    isOngoing, isFavorite, favoriteAddedAt, firstOpenedAt, lastOpenedAt, lastWatchedAt, lastEpisodeId,
    lastEpisodeOrdinal, lastPositionMs, lastDurationMs, lastEpisodeCompleted,
)
