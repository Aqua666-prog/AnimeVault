package com.sergey.animevault.data.db

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AnimeVaultMigrationTest {
    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AnimeVaultDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    @Test
    fun migrate5To7_deduplicatesDownloadsAndCreatesOnlineTables() {
        helper.createDatabase(DB_NAME, 5).apply {
            execSQL(
                "INSERT INTO downloads VALUES " +
                    "('old','p','Provider','r','Release','e',1,NULL,720,NULL,NULL,NULL,'HLS','COMPLETED',100,10,10,1,1,NULL,'/missing'," +
                    "'video/mp4',1,1,NULL,NULL)",
            )
            close()
        }
        helper.runMigrationsAndValidate(
            DB_NAME,
            7,
            true,
            AnimeVaultDatabase.MIGRATION_5_6,
            AnimeVaultDatabase.MIGRATION_6_7,
        ).use { db ->
            db.query("SELECT COUNT(*) FROM downloads").use { cursor ->
                cursor.moveToFirst()
                assertEquals(1, cursor.getInt(0))
            }
            db.query("SELECT COUNT(*) FROM online_progress").close()
            db.query("SELECT COUNT(*) FROM online_library").close()
        }
    }

    private companion object { const val DB_NAME = "migration-test" }
}
