package com.sergey.animevault.data.db

import androidx.room.Database
import androidx.room.migration.Migration
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase

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
    ],
    version = 3,
    exportSchema = true,
)
abstract class AnimeVaultDatabase : RoomDatabase() {
    abstract fun libraryDao(): LibraryDao

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
    }
}
