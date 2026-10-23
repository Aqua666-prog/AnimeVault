package com.sergey.animevault.data.update

import org.json.JSONObject
import java.net.URI

const val UPDATE_REPOSITORY = "Aqua666-prog/AnimeVault"
const val UPDATE_PACKAGE = "com.sergey.animevault"
const val UPDATE_MANIFEST = "animevault-update.json"
const val MAX_APK_BYTES = 300L * 1024 * 1024

class UpdateException(message: String) : Exception(message)

data class ReleaseAsset(val name: String, val url: String, val size: Long)

data class GitHubRelease(
    val tag: String,
    val title: String,
    val notes: String,
    val pageUrl: String,
    val assets: List<ReleaseAsset>,
)

data class AppUpdate(
    val versionCode: Long,
    val versionName: String,
    val minSdk: Int,
    val sha256: String,
    val apk: ReleaseAsset,
    val release: GitHubRelease,
)

/** Only stable, public releases and assets belonging to this repository are accepted. */
object UpdateProtocol {
    fun release(json: String): GitHubRelease {
        val root = JSONObject(json)
        requireUpdate(!root.getBoolean("draft") && !root.getBoolean("prerelease"),
            "Предварительные версии не устанавливаются автоматически.")
        val tag = root.getString("tag_name")
        requireUpdate(tag.isNotBlank(), "В релизе отсутствует tag.")
        val page = root.getString("html_url")
        val uri = URI(page)
        requireUpdate(uri.scheme == "https" && uri.host == "github.com" &&
            uri.rawUserInfo == null && uri.port == -1 &&
            uri.path == "/$UPDATE_REPOSITORY/releases/tag/$tag" &&
            uri.rawQuery == null && uri.rawFragment == null,
            "Некорректная ссылка на релиз.")
        val list = root.getJSONArray("assets")
        val assets = (0 until list.length()).map { index ->
            val asset = list.getJSONObject(index)
            ReleaseAsset(
                name = asset.getString("name"),
                url = asset.getString("browser_download_url"),
                size = positiveLong(asset, "size"),
            ).also {
                val assetUri = URI(it.url)
                requireUpdate(assetUri.scheme == "https" && assetUri.host == "github.com" &&
                    assetUri.rawUserInfo == null && assetUri.port == -1 &&
                    assetUri.path == "/$UPDATE_REPOSITORY/releases/download/$tag/${it.name}" &&
                    assetUri.rawQuery == null && assetUri.rawFragment == null,
                    "Файл обновления находится вне репозитория AnimeVault.")
            }
        }
        return GitHubRelease(
            tag = tag,
            title = (root.optString("name").takeUnless { it.isBlank() || it == "null" } ?: tag).take(160),
            notes = root.optString("body").takeUnless { it == "null" }.orEmpty().take(24_000),
            pageUrl = page,
            assets = assets,
        )
    }

    fun update(json: String, release: GitHubRelease): AppUpdate {
        val root = JSONObject(json)
        requireUpdate(positiveLong(root, "schemaVersion") == 1L, "Формат обновления не поддерживается.")
        requireUpdate(root.getString("packageName") == UPDATE_PACKAGE, "Обновление предназначено для другого приложения.")
        val code = positiveLong(root, "versionCode")
        requireUpdate(code <= Int.MAX_VALUE.toLong(), "Некорректный versionCode обновления.")
        val version = root.getString("versionName")
        requireUpdate(version.isNotBlank() && version.length <= 80, "Некорректное название версии.")
        val minSdk = positiveLong(root, "minSdk")
        requireUpdate(minSdk in 24L..Int.MAX_VALUE.toLong(), "Некорректная версия Android в обновлении.")
        val sha = root.getString("sha256").lowercase()
        requireUpdate(sha.matches(Regex("[0-9a-f]{64}")), "В релизе отсутствует корректная SHA-256.")
        val name = root.getString("apkFileName")
        requireUpdate(name.matches(Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,140}\\.apk")),
            "Некорректное имя APK.")
        val apk = release.assets.singleOrNull { it.name == name }
            ?: throw UpdateException("В релизе отсутствует единственный APK, указанный в метаданных.")
        requireUpdate(apk.size in 1..MAX_APK_BYTES, "Недопустимый размер APK.")
        return AppUpdate(code, version, minSdk.toInt(), sha, apk, release)
    }

    private fun positiveLong(root: JSONObject, key: String): Long {
        val raw = root.get(key).toString()
        requireUpdate(raw.matches(Regex("[1-9][0-9]*")), "Некорректное поле $key в метаданных.")
        return raw.toLongOrNull() ?: throw UpdateException("Слишком большое значение $key.")
    }

    private fun requireUpdate(value: Boolean, message: String) {
        if (!value) throw UpdateException(message)
    }
}
