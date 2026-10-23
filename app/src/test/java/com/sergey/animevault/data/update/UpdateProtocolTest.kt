package com.sergey.animevault.data.update

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class UpdateProtocolTest {
    private fun releaseJson() = JSONObject()
        .put("draft", false).put("prerelease", false)
        .put("tag_name", "v3.0.4").put("name", "AnimeVault 3.0.4").put("body", "Описание изменений")
        .put("html_url", "https://github.com/$UPDATE_REPOSITORY/releases/tag/v3.0.4")
        .put("assets", JSONArray().put(asset(UPDATE_MANIFEST, 512)).put(asset("AnimeVault.apk", 1024)))

    private fun asset(name: String, size: Long) = JSONObject()
        .put("name", name).put("size", size)
        .put("browser_download_url", "https://github.com/$UPDATE_REPOSITORY/releases/download/v3.0.4/$name")

    private fun manifest() = JSONObject()
        .put("schemaVersion", 1).put("packageName", UPDATE_PACKAGE)
        .put("versionCode", 64).put("versionName", "3.0.4").put("minSdk", 24)
        .put("apkFileName", "AnimeVault.apk").put("sha256", "ab".repeat(32))

    private fun parse(root: JSONObject) = UpdateProtocol.update(root.toString(), UpdateProtocol.release(releaseJson().toString()))

    @Test fun stableReleaseUsesExplicitVersionCodeAndNotes() {
        val update = parse(manifest())
        assertEquals(64L, update.versionCode)
        assertEquals(24, update.minSdk)
        assertEquals("Описание изменений", update.release.notes)
        assertEquals("AnimeVault.apk", update.apk.name)
    }

    @Test fun versionNameDoesNotDetermineUpdateOrder() {
        val update = parse(manifest().put("versionName", "3.0.10").put("versionCode", 65))
        assertEquals(65L, update.versionCode)
    }

    @Test fun rejectsDraftAndPrerelease() {
        listOf("draft", "prerelease").forEach { key ->
            assertThrows(UpdateException::class.java) {
                UpdateProtocol.release(releaseJson().put(key, true).toString())
            }
        }
    }

    @Test fun rejectsForeignPackage() {
        assertThrows(UpdateException::class.java) { parse(manifest().put("packageName", "com.other.app")) }
    }

    @Test fun rejectsUnsupportedSchema() {
        assertThrows(UpdateException::class.java) { parse(manifest().put("schemaVersion", 2)) }
    }

    @Test fun rejectsMalformedVersionCode() {
        listOf(0, -1, "1.5", "2147483648", "x").forEach { code ->
            assertThrows(UpdateException::class.java) { parse(manifest().put("versionCode", code)) }
        }
    }

    @Test fun requiresSha256() {
        assertThrows(UpdateException::class.java) { parse(manifest().put("sha256", "abc")) }
    }

    @Test fun rejectsPathTraversalInAssetName() {
        assertThrows(UpdateException::class.java) { parse(manifest().put("apkFileName", "../AnimeVault.apk")) }
    }

    @Test fun rejectsMissingOrDuplicateApk() {
        val missing = UpdateProtocol.release(releaseJson().toString()).copy(assets = emptyList())
        assertThrows(UpdateException::class.java) { UpdateProtocol.update(manifest().toString(), missing) }
        val release = UpdateProtocol.release(releaseJson().toString())
        val duplicate = release.copy(assets = release.assets + release.assets.last())
        assertThrows(UpdateException::class.java) { UpdateProtocol.update(manifest().toString(), duplicate) }
    }

    @Test fun rejectsOtherRepositoryAndInsecureAssets() {
        listOf("http://github.com/$UPDATE_REPOSITORY/releases/download/v3.0.4/AnimeVault.apk",
            "https://github.com/other/repo/releases/download/v3.0.4/AnimeVault.apk").forEach { url ->
            val root = releaseJson()
            root.getJSONArray("assets").getJSONObject(1).put("browser_download_url", url)
            assertThrows(UpdateException::class.java) { UpdateProtocol.release(root.toString()) }
        }
    }

    @Test fun rejectsOversizedApk() {
        val release = UpdateProtocol.release(releaseJson().toString())
        val large = release.copy(assets = release.assets.map { if (it.name.endsWith(".apk")) it.copy(size = MAX_APK_BYTES + 1) else it })
        assertThrows(UpdateException::class.java) { UpdateProtocol.update(manifest().toString(), large) }
    }

    @Test fun boundsReleaseNotes() {
        val release = UpdateProtocol.release(releaseJson().put("body", "a".repeat(30_000)).toString())
        assertEquals(24_000, release.notes.length)
    }
}
