package com.sergey.animevault.data.db

import androidx.room.Database
import androidx.room.migration.Migration
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import com.sergey.animevault.data.download.DownloadDao
import com.sergey.animevault.data.download.DownloadEntity

@Database(
    entities = [
        LibraryFolderEntity::class,
        AnimeTitleEntity::class,
        EpisodeEntity::class,
        ExternalSubtitleEntity::class,
        WatchProgressEntity::class,
        EpisodeGroupingOverrideEntity::class,
        OfflineOnlineLinkEntity::class,
        TitleMetadataEntity::class,
        DownloadEntity::class,
    ],
    version = 5,
    exportSchema = true,
)
abstract class AnimeVaultDatabase : RoomDatabase() {
    abstract fun libraryDao(): LibraryDao
    abstract fun downloadDao(): DownloadDao

    companion object {
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `episode_grouping_overrides` (
                        `file_uri` TEXT NOT NULL,
                        `root_tree_uri` TEXT NOT NULL,
                        `target_source_key` TEXT NOT NULL,
                        `target_title_name` TEXT NOT NULL,
                        `created_at` INTEGER NOT NULL,
                        PRIMARY KEY(`file_uri`),
                        FOREIGN KEY(`root_tree_uri`) REFERENCES `library_folders`(`tree_uri`)
                            ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_episode_grouping_overrides_root_tree_uri` " +
                        "ON `episode_grouping_overrides` (`root_tree_uri`)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_episode_grouping_overrides_target_source_key` " +
                        "ON `episode_grouping_overrides` (`target_source_key`)",
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `offline_online_links` (
                        `offline_title_id` INTEGER NOT NULL,
                        `provider_id` TEXT NOT NULL,
                        `online_release_id` TEXT NOT NULL,
                        `online_title_name` TEXT NOT NULL,
                        `online_alias` TEXT,
                        `poster_url` TEXT,
                        `mal_id` TEXT,
                        `kodik_id` TEXT,
                        `linked_at` INTEGER NOT NULL,
                        PRIMARY KEY(`offline_title_id`, `provider_id`, `online_release_id`),
                        FOREIGN KEY(`offline_title_id`) REFERENCES `titles`(`id`)
                            ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_offline_online_links_offline_title_id` " +
                        "ON `offline_online_links` (`offline_title_id`)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_offline_online_links_provider_id_online_release_id` " +
                        "ON `offline_online_links` (`provider_id`, `online_release_id`)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_offline_online_links_mal_id` " +
                        "ON `offline_online_links` (`mal_id`)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_offline_online_links_kodik_id` " +
                        "ON `offline_online_links` (`kodik_id`)",
                )
            }
        }


        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `title_metadata` (
                        `title_id` INTEGER NOT NULL,
                        `provider` TEXT NOT NULL,
                        `external_id` INTEGER NOT NULL,
                        `mal_id` INTEGER,
                        `canonical_title` TEXT,
                        `english_title` TEXT,
                        `native_title` TEXT,
                        `poster_url` TEXT,
                        `banner_url` TEXT,
                        `description` TEXT,
                        `year` INTEGER,
                        `episode_count` INTEGER,
                        `format` TEXT,
                        `status` TEXT,
                        `genres` TEXT,
                        `average_score` INTEGER,
                        `site_url` TEXT,
                        `updated_at` INTEGER NOT NULL,
                        PRIMARY KEY(`title_id`),
                        FOREIGN KEY(`title_id`) REFERENCES `titles`(`id`)
                            ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_title_metadata_provider_external_id` " +
                        "ON `title_metadata` (`provider`, `external_id`)",
                )
            }
        }


        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `watch_progress` ADD COLUMN `first_played_at` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `watch_progress` ADD COLUMN `completed_at` INTEGER")
                db.execSQL("ALTER TABLE `watch_progress` ADD COLUMN `play_count` INTEGER NOT NULL DEFAULT 0")
                // Existing history predates the richer schema. Preserve its useful timestamp.
                db.execSQL(
                    "UPDATE `watch_progress` SET `first_played_at` = `last_watched_at` " +
                        "WHERE `last_watched_at` > 0 AND `first_played_at` = 0"
                )
                db.execSQL(
                    "UPDATE `watch_progress` SET `completed_at` = `last_watched_at` " +
                        "WHERE `is_completed` = 1 AND `last_watched_at` > 0 AND `completed_at` IS NULL"
                )
                db.execSQL(
                    "UPDATE `watch_progress` SET `play_count` = 1 " +
                        "WHERE `last_watched_at` > 0 AND `play_count` = 0"
                )
            }
        }

        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `downloads` (
                        `id` TEXT NOT NULL,
                        `provider_id` TEXT NOT NULL,
                        `provider_name` TEXT NOT NULL,
                        `release_id` TEXT NOT NULL,
                        `release_name` TEXT NOT NULL,
                        `episode_id` TEXT NOT NULL,
                        `episode_ordinal` REAL,
                        `episode_name` TEXT,
                        `quality` INTEGER,
                        `translation` TEXT,
                        `translation_key` TEXT,
                        `source_name` TEXT,
                        `stream_type` TEXT NOT NULL,
                        `status` TEXT NOT NULL,
                        `progress_percent` REAL NOT NULL,
                        `bytes_downloaded` INTEGER NOT NULL,
                        `content_length` INTEGER NOT NULL,
                        `created_at` INTEGER NOT NULL,
                        `updated_at` INTEGER NOT NULL,
                        `operation_token` TEXT,
                        `local_file_path` TEXT,
                        `local_mime_type` TEXT,
                        `completed_items` INTEGER NOT NULL,
                        `total_items` INTEGER NOT NULL,
                        `diagnostic_stage` TEXT,
                        `error_message` TEXT,
                        PRIMARY KEY(`id`)
                    )
                    """.trimIndent(),
                )
            }
        }
    }
}
