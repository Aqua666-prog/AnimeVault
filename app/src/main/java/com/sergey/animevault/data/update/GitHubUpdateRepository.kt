package com.sergey.animevault.data.update

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.pm.PackageInfoCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

interface UpdateRepository {
    var automaticChecks: Boolean
    fun shouldCheckAutomatically(now: Long): Boolean
    fun recordAutomaticCheck(now: Long)
    fun skip(versionCode: Long)
    fun isSkipped(versionCode: Long): Boolean
    suspend fun latest(): AppUpdate?
    suspend fun cachedApk(update: AppUpdate): File?
    suspend fun download(update: AppUpdate, onProgress: (Long, Long) -> Unit): File
    suspend fun verify(file: File, update: AppUpdate)
}

class GitHubUpdateRepository(context: Context, baseClient: OkHttpClient) : UpdateRepository {
    private val appContext = context.applicationContext
    private val preferences = appContext.getSharedPreferences("animevault_updates", Context.MODE_PRIVATE)
    private val directory get() = File(appContext.cacheDir, "app_updates")
    private val client = baseClient.newBuilder()
        .followRedirects(false)
        .followSslRedirects(false)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .callTimeout(10, TimeUnit.MINUTES)
        .build()

    override var automaticChecks: Boolean
        get() = preferences.getBoolean("automatic", true)
        set(value) { preferences.edit().putBoolean("automatic", value).apply() }

    override fun shouldCheckAutomatically(now: Long): Boolean {
        val last = preferences.getLong("last_check", 0L)
        return automaticChecks && (last == 0L || now < last || now - last >= TimeUnit.DAYS.toMillis(1))
    }

    override fun recordAutomaticCheck(now: Long) {
        preferences.edit().putLong("last_check", now).apply()
    }

    override fun skip(versionCode: Long) {
        preferences.edit().putLong("skipped_version", versionCode).apply()
    }

    override fun isSkipped(versionCode: Long) = preferences.getLong("skipped_version", -1) == versionCode

    override suspend fun latest(): AppUpdate? = withContext(Dispatchers.IO) {
        val releaseText = getText("https://api.github.com/repos/$UPDATE_REPOSITORY/releases/latest", 512 * 1024, allowNotFound = true)
            ?: return@withContext null
        val release = UpdateProtocol.release(releaseText)
        val manifest = release.assets.singleOrNull { it.name == UPDATE_MANIFEST }
            ?: throw UpdateException("Релиз пока не подготовлен для обновления: отсутствует $UPDATE_MANIFEST.")
        if (manifest.size > 32 * 1024) throw UpdateException("Метаданные обновления слишком велики.")
        val update = UpdateProtocol.update(getText(manifest.url, 32 * 1024)!!, release)
        if (update.versionCode <= installedVersionCode()) return@withContext null
        if (update.minSdk > Build.VERSION.SDK_INT) {
            throw UpdateException("Новая версия требует Android API ${update.minSdk}. Текущая версия приложения продолжит работать.")
        }
        update
    }

    override suspend fun cachedApk(update: AppUpdate): File? = withContext(Dispatchers.IO) {
        val file = target(update)
        if (!file.isFile) return@withContext null
        try {
            verify(file, update)
            file
        } catch (error: UpdateException) {
            file.delete()
            null
        }
    }

    override suspend fun download(update: AppUpdate, onProgress: (Long, Long) -> Unit): File =
        withContext(Dispatchers.IO) {
            if (!directory.isDirectory && !directory.mkdirs()) throw IOException("Cannot create update directory")
            // Only updater-owned cache is cleaned; media downloads are stored elsewhere.
            directory.listFiles()?.forEach { it.delete() }
            if (directory.usableSpace < update.apk.size + 16L * 1024 * 1024) {
                throw UpdateException("Недостаточно свободного места для обновления.")
            }
            val partial = File(directory, "download.part")
            val destination = target(update)
            try {
                request(update.apk.url).use { response ->
                    checkStatus(response)
                    val body = response.body ?: throw IOException("Empty APK response")
                    if (body.contentLength() > 0 && body.contentLength() != update.apk.size) {
                        throw UpdateException("Размер APK не совпадает с данными релиза.")
                    }
                    body.byteStream().use { input ->
                        partial.outputStream().use { output ->
                            val buffer = ByteArray(64 * 1024)
                            var received = 0L
                            var reported = 0L
                            while (true) {
                                currentCoroutineContext().ensureActive()
                                val count = input.read(buffer)
                                if (count < 0) break
                                received += count
                                if (received > update.apk.size || received > MAX_APK_BYTES) {
                                    throw UpdateException("APK превышает заявленный размер.")
                                }
                                output.write(buffer, 0, count)
                                if (received - reported >= 256 * 1024 || received == update.apk.size) {
                                    onProgress(received, update.apk.size)
                                    reported = received
                                }
                            }
                            if (received != update.apk.size) throw UpdateException("APK загружен не полностью. Повторите загрузку.")
                        }
                    }
                }
                verifyContents(partial, update)
                currentCoroutineContext().ensureActive()
                if (!partial.renameTo(destination)) throw IOException("Cannot finalize update APK")
                destination
            } finally {
                partial.delete()
            }
        }

    override suspend fun verify(file: File, update: AppUpdate) = withContext(Dispatchers.IO) {
        if (file.canonicalFile != target(update).canonicalFile) throw UpdateException("Некорректный файл обновления.")
        verifyContents(file, update)
    }

    private suspend fun verifyContents(file: File, update: AppUpdate) {
        if (!file.isFile || file.length() != update.apk.size) throw UpdateException("Файл обновления отсутствует или повреждён. Загрузите его заново.")
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                currentCoroutineContext().ensureActive()
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        if (digest.digest().hex() != update.sha256) {
            throw UpdateException("Контрольная сумма APK не совпадает. Установка отменена.")
        }
        validateArchive(file, update)
    }

    @Suppress("DEPRECATION")
    private fun validateArchive(file: File, update: AppUpdate) {
        val manager = appContext.packageManager
        val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        val archive = manager.getPackageArchiveInfo(file.absolutePath, flags)
            ?: throw UpdateException("Android не распознал файл как APK.")
        val installed = manager.getPackageInfo(appContext.packageName, flags)
        verifyApkIdentity(identity(installed), identity(archive), update, Build.VERSION.SDK_INT)
    }

    private fun identity(info: PackageInfo) = ApkIdentity(
        packageName = info.packageName,
        versionCode = PackageInfoCompat.getLongVersionCode(info),
        versionName = info.versionName,
        minSdk = info.applicationInfo?.minSdkVersion
            ?: throw UpdateException("Не удалось прочитать требования APK."),
        signers = signerDigests(info),
    )

    @Suppress("DEPRECATION")
    private fun signerDigests(info: PackageInfo): Set<String> {
        val signatures = if (Build.VERSION.SDK_INT >= 28) info.signingInfo?.apkContentsSigners else info.signatures
        return signatures.orEmpty().map { MessageDigest.getInstance("SHA-256").digest(it.toByteArray()).hex() }.toSet()
    }

    @Suppress("DEPRECATION")
    private fun installedVersionCode(): Long =
        PackageInfoCompat.getLongVersionCode(appContext.packageManager.getPackageInfo(appContext.packageName, 0))

    private fun target(update: AppUpdate) = File(directory, "update-${update.versionCode}-${update.sha256.take(12)}.apk")

    private suspend fun getText(url: String, limit: Int, allowNotFound: Boolean = false): String? {
        request(url).use { response ->
            if (allowNotFound && response.code == 404) return null
            checkStatus(response)
            val body = response.body ?: throw IOException("Empty response")
            body.byteStream().use { input ->
                val output = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(4096)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (output.size() + count > limit) throw UpdateException("Ответ сервера обновлений слишком велик.")
                    output.write(buffer, 0, count)
                }
                return output.toString(Charsets.UTF_8.name())
            }
        }
    }

    /** Redirects may go only to GitHub's HTTPS asset storage; no credentials are sent. */
    private suspend fun request(initialUrl: String): Response {
        var url = initialUrl
        repeat(6) {
            currentCoroutineContext().ensureActive()
            val uri = java.net.URI(url)
            if (uri.scheme != "https" || uri.rawUserInfo != null || uri.port !in listOf(-1, 443) ||
                uri.host !in setOf("api.github.com", "github.com", "release-assets.githubusercontent.com",
                    "objects.githubusercontent.com", "github-releases.githubusercontent.com")) {
                throw UpdateException("Небезопасная ссылка на обновление.")
            }
            val request = Request.Builder().url(url)
                .header("Accept", if (uri.host == "api.github.com") "application/vnd.github+json" else "application/octet-stream")
                .header("User-Agent", "AnimeVault-Updater")
                .build()
            val response = client.newCall(request).execute()
            if (response.code !in listOf(301, 302, 303, 307, 308)) return response
            val next = response.header("Location")?.let { response.request.url.resolve(it)?.toString() }
            response.close()
            url = next ?: throw UpdateException("Сервер вернул некорректное перенаправление.")
        }
        throw UpdateException("Слишком много перенаправлений при загрузке обновления.")
    }

    private fun checkStatus(response: Response) {
        if (response.code == 403 || response.code == 429) throw UpdateException("GitHub временно ограничил запросы. Попробуйте позже.")
        if (!response.isSuccessful) throw UpdateException("GitHub недоступен (HTTP ${response.code}). Попробуйте позже.")
    }
}

private fun ByteArray.hex(): String = joinToString("") { "%02x".format(it.toInt() and 0xff) }
