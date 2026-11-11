package com.sergey.animevault.ui.localai

import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal data class ModelFileSpec(val name: String, val size: Long, val sha256: String)

internal suspend fun verifyModelFile(file: File, spec: ModelFileSpec): Boolean {
    if (!file.isFile || file.length() != spec.size) return false
    val hash = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input ->
        val buffer = ByteArray(64 * 1024)
        while (true) {
            currentCoroutineContext().ensureActive()
            val n = input.read(buffer)
            if (n < 0) break
            hash.update(buffer, 0, n)
        }
    }
    return hash.digest().joinToString("") { "%02x".format(it) } == spec.sha256
}
