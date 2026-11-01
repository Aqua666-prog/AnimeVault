package com.sergey.animevault.data.community

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import android.util.Base64
import com.google.gson.Gson
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Only encrypted bytes in noBackupFilesDir. Never shared with Firebase, Room or backup/export. */
class CommunitySessionManager(context: Context) {
    private val file = AtomicFile(File(context.noBackupFilesDir, "community-session"))
    private val pendingFile = AtomicFile(File(context.noBackupFilesDir, "community-pkce"))
    private val gson = Gson()
    private val alias = "animevault-community-v1"
    private val current = MutableStateFlow(read(file, StoredCommunitySessionDto::class.java))
    val session = current.asStateFlow()
    val notice = MutableStateFlow<String?>(null)
    val recovery = MutableStateFlow(current.value?.recovery ?: false)
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        return (store.getKey(alias, null) as? SecretKey) ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
            generateKey()
        }
    }
    @Synchronized private fun <T> read(source: AtomicFile, type: Class<T>): T? = try {
        val bytes = source.readFully()
        require(bytes.size in 29..65536)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
        gson.fromJson(String(cipher.doFinal(bytes.copyOfRange(12, bytes.size)), Charsets.UTF_8), type)
    } catch (_: Exception) { null }
    @Synchronized private fun write(target: AtomicFile, value: Any?) {
        if (value == null) { target.delete(); return }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        val bytes = cipher.iv + cipher.doFinal(gson.toJson(value).toByteArray(Charsets.UTF_8))
        val output = target.startWrite()
        try { output.write(bytes); target.finishWrite(output) } catch (e: Exception) { target.failWrite(output); throw e }
    }
    @Synchronized fun save(session: CommunitySessionDto, isRecovery: Boolean = current.value?.recovery ?: false) {
        val stored = StoredCommunitySessionDto(session.access_token, session.refresh_token,
            System.currentTimeMillis() + session.expires_in * 1000, session.user.id, isRecovery)
        write(file, stored)
        current.value = stored
        recovery.value = stored.recovery
    }
    @Synchronized fun clearRecovery() {
        current.value?.let { stored ->
            val changed = stored.copy(recovery=false)
            write(file,changed); current.value=changed
        }
        recovery.value=false
    }
    @Synchronized fun clear() {
        write(file, null); write(pendingFile, null); current.value = null; recovery.value = false
    }
    fun savePending(pending: CommunityPendingAuthDto) = write(pendingFile, pending)
    fun pending(): CommunityPendingAuthDto? = read(pendingFile, CommunityPendingAuthDto::class.java)
    fun clearPending() = write(pendingFile, null)
    companion object {
        fun challenge(verifier: String): String = Base64.encodeToString(
            java.security.MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)),
            Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING,
        )
    }
}
