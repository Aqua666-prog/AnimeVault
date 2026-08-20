package com.sergey.animevault.data.download

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query

@Entity(tableName = "downloads")
data class DownloadEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "provider_id") val providerId: String,
    @ColumnInfo(name = "provider_name") val providerName: String,
    @ColumnInfo(name = "release_id") val releaseId: String,
    @ColumnInfo(name = "release_name") val releaseName: String,
    @ColumnInfo(name = "episode_id") val episodeId: String,
    @ColumnInfo(name = "episode_ordinal") val episodeOrdinal: Double?,
    @ColumnInfo(name = "episode_name") val episodeName: String?,
    val quality: Int?,
    val translation: String?,
    @ColumnInfo(name = "translation_key") val translationKey: String?,
    @ColumnInfo(name = "source_name") val sourceName: String?,
    @ColumnInfo(name = "stream_type") val streamType: String,
    val status: String,
    @ColumnInfo(name = "progress_percent") val progressPercent: Float,
    @ColumnInfo(name = "bytes_downloaded") val bytesDownloaded: Long,
    @ColumnInfo(name = "content_length") val contentLength: Long,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
    @ColumnInfo(name = "operation_token") val operationToken: String?,
    @ColumnInfo(name = "local_file_path") val localFilePath: String?,
    @ColumnInfo(name = "local_mime_type") val localMimeType: String?,
    @ColumnInfo(name = "completed_items") val completedItems: Int,
    @ColumnInfo(name = "total_items") val totalItems: Int,
    @ColumnInfo(name = "diagnostic_stage") val diagnosticStage: String?,
    @ColumnInfo(name = "error_message") val errorMessage: String?,
)

@Dao
interface DownloadDao {
    @Query("SELECT * FROM downloads ORDER BY created_at DESC")
    suspend fun getAll(): List<DownloadEntity>

    @Query("SELECT * FROM downloads WHERE id = :id LIMIT 1")
    suspend fun get(id: String): DownloadEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: DownloadEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(entities: List<DownloadEntity>)

    @Query("DELETE FROM downloads WHERE id = :id")
    suspend fun delete(id: String)
}
