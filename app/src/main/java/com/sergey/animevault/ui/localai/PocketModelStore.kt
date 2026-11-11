package com.sergey.animevault.ui.localai

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import java.io.File
import java.io.InputStream
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** Only the audited, pinned official export is accepted. Never execute imported configuration. */
class PocketModelStore(private val context: Context) {
    private val root = File(context.noBackupFilesDir, "local-ai").apply { mkdirs() }
    init {
        val previous = File(root, "previous")
        val installed = File(root, "model")
        if (!installed.exists() && previous.exists()) previous.renameTo(installed)
    }
    val config get() = File(root, "model/config.json")
    private val manifest = JSONObject(context.assets.open("local-ai/manifest.json").bufferedReader().use { it.readText() })
    private val files = manifest.getJSONArray("files").let { rows ->
        (0 until rows.length()).map { rows.getJSONObject(it).let { row -> ModelFileSpec(row.getString("name"), row.getLong("size"), row.getString("sha256")) } }
    }
    val totalBytes = files.sumOf { it.size }
    private val client = OkHttpClient.Builder().connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS).callTimeout(10, TimeUnit.MINUTES).build()

    suspend fun verified(): Boolean = withContext(Dispatchers.IO) {
        files.all { row -> verifyModelFile(File(root, "model/${row.name}"), row) }
    }

    suspend fun install(uri: Uri?, progress: (Long, Long) -> Unit) = withContext(Dispatchers.IO) {
        val stage = File(root, "staging").apply { mkdirs() }
        require(root.usableSpace >= totalBytes + 32L * 1024 * 1024) { "Недостаточно места: освободите 210 МБ." }
        val tree = uri?.let { DocumentFile.fromTreeUri(context, it) ?: error("Не удалось открыть папку") }
        var done = 0L
        for (row in files) {
            currentCoroutineContext().ensureActive()
            val name = row.name
            val target = File(stage, name)
            if (!verifyModelFile(target, row)) {
                target.delete()
                if (tree != null) {
                    val document = tree.findFile(name) ?: error("В папке отсутствует $name")
                    val input = context.contentResolver.openInputStream(document.uri) ?: error("Нет доступа к $name")
                    input.use { copy(it, target, row.size) { bytes -> progress(done + bytes, totalBytes) } }
                } else {
                    val url = "https://huggingface.co/${manifest.getString("repository")}/resolve/${manifest.getString("revision")}/$name"
                    coroutineScope {
                        val call = client.newCall(Request.Builder().url(url).build())
                        val cancellation = launch(start = CoroutineStart.UNDISPATCHED) {
                            try { awaitCancellation() } finally { call.cancel() }
                        }
                        try {
                            call.execute().use { response ->
                                check(response.isSuccessful) { "HTTP ${response.code}: $url" }
                                checkNotNull(response.body) { "Пустой HTTP-ответ: $url" }.byteStream().use {
                                    copy(it, target, row.size) { bytes -> progress(done + bytes, totalBytes) }
                                }
                            }
                        } finally { cancellation.cancel() }
                    }
                }
                check(verifyModelFile(target, row)) { "Ошибка целостности $name. Повторите загрузку или выберите официальный комплект." }
            }
            done += row.size
            progress(done, totalBytes)
        }
        currentCoroutineContext().ensureActive()
        // Caller unloads runtime before installation. Old verified model survives any copy failure.
        val installed = File(root, "model")
        val previous = File(root, "previous")
        previous.deleteRecursively()
        if (installed.exists()) check(installed.renameTo(previous)) { "Не удалось заменить модель" }
        if (!stage.renameTo(installed)) {
            previous.renameTo(installed)
            error("Не удалось установить модель")
        }
        previous.deleteRecursively()
    }

    suspend fun delete() = withContext(Dispatchers.IO) {
        check(File(root, "model").let { !it.exists() || it.deleteRecursively() }) { "Не удалось удалить модель" }
        File(root, "staging").deleteRecursively()
        File(root, "previous").deleteRecursively()
    }

    private suspend fun copy(input: InputStream, file: File, expected: Long, progress: (Long) -> Unit) {
        file.outputStream().use { output ->
            val buffer = ByteArray(64 * 1024)
            var count = 0L
            while (true) {
                currentCoroutineContext().ensureActive()
                val read = input.read(buffer)
                if (read < 0) break
                count += read
                check(count <= expected) { "Размер файла превышает официальный" }
                output.write(buffer, 0, read)
                progress(count)
            }
            check(count == expected) { "Загрузка оборвалась: $count из $expected байт" }
            output.fd.sync()
        }
    }

}
