package com.sergey.animevault.localai

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import java.io.File
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class MnnLocalAiEngine(context: Context) : LocalAiEngine {
    private val app = context.applicationContext
    private val worker = Dispatchers.IO.limitedParallelism(1)
    private val mutex = Mutex()
    // An epoch makes stop/unload invalidate requests even while they wait for the mutex.
    private val epoch = AtomicLong()
    private var handle = 0L
    private val _statistics = MutableStateFlow<AiStatistics?>(null)
    override val statistics = _statistics.asStateFlow()
    private val modelRoot = File(app.noBackupFilesDir, "local-ai/model")

    override suspend fun load(config: File) = withContext(worker) {
        mutex.withLock {
            require(config.canonicalPath.startsWith(modelRoot.canonicalPath + File.separator)) {
                "Model must be inside the application's private local-ai/model directory"
            }
            require(config.isFile) { "Missing model config" }
            if (!Build.SUPPORTED_ABIS.contains("arm64-v8a") || !android.os.Process.is64Bit()) {
                throw LocalAiException(LocalAiException.Reason.UNSUPPORTED_DEVICE, "MNN requires a 64-bit ARM process")
            }
            checkMemory()
            if (handle != 0L) MnnNative.release(handle)
            handle = 0L
            try {
                MnnNative.loadLibrary()
                handle = MnnNative.create(config.canonicalPath.toByteArray(Charsets.UTF_8))
                check(handle != 0L) { "MNN returned an empty engine" }
            } catch (e: OutOfMemoryError) {
                throw LocalAiException(LocalAiException.Reason.LOW_MEMORY, "Insufficient memory to load MNN", e)
            } catch (e: LinkageError) {
                throw LocalAiException(LocalAiException.Reason.LOAD_FAILED, "MNN native runtime could not be linked", e)
            } catch (e: Exception) {
                throw LocalAiException(LocalAiException.Reason.LOAD_FAILED, "MNN model could not be loaded", e)
            }
        }
    }

    override fun generate(messages: List<AiMessage>, limits: AiLimits): Flow<String> {
        // Snapshot caller-owned history before switching threads.
        val history = messages.toList()
        val requestEpoch = epoch.get()
        return flow {
            require(history.isNotEmpty() && history.last().role == AiMessage.Role.USER)
            require(history.size <= 100 && history.sumOf { it.content.length.toLong() } <= 32768L) {
                "Conversation exceeds the input allocation limit"
            }
            mutex.withLock {
                if (epoch.get() != requestEpoch) return@withLock
                if (handle == 0L) throw LocalAiException(LocalAiException.Reason.NOT_LOADED, "Load a model first")
                checkMemory()
                _statistics.value = null
                try {
                    // Native tokenizer checks the complete template, including special tokens.
                    MnnNative.begin(handle, history.map { it.role.wireName.toByteArray() }.toTypedArray(),
                        history.map { it.content.toByteArray(Charsets.UTF_8) }.toTypedArray(),
                        limits.contextTokens - limits.responseTokens)
                    val utf8 = Utf8Deltas()
                    for (index in 0 until limits.responseTokens) {
                        currentCoroutineContext().ensureActive()
                        if (epoch.get() != requestEpoch || MnnNative.finished(handle)) break
                        val text = utf8.append(MnnNative.step(handle))
                        if (text.isNotEmpty()) emit(text)
                    }
                    // Incomplete UTF-8 on cancellation is intentionally dropped, never replaced.
                } catch (e: OutOfMemoryError) {
                    releaseLocked()
                    throw LocalAiException(LocalAiException.Reason.LOW_MEMORY, "Insufficient memory during generation", e)
                } catch (e: IllegalArgumentException) {
                    throw LocalAiException(LocalAiException.Reason.CONTEXT_TOO_LONG, "Shorten the conversation", e)
                } catch (e: IllegalStateException) {
                    releaseLocked()
                    throw LocalAiException(LocalAiException.Reason.GENERATION_FAILED, "MNN generation failed", e)
                } finally {
                    if (handle != 0L) {
                        val metrics = MnnNative.metrics(handle)
                        _statistics.value = AiStatistics(metrics[0], metrics[1], metrics[2], metrics[3])
                    }
                }
            }
        }.flowOn(worker)
    }

    override fun stop() { epoch.incrementAndGet() }

    override suspend fun unload() {
        stop()
        // Cancellation of the UI owner must not leak the native engine.
        withContext(NonCancellable + worker) { mutex.withLock { releaseLocked() } }
    }

    private fun releaseLocked() {
        if (handle != 0L) MnnNative.release(handle)
        handle = 0L
    }

    private fun checkMemory() {
        val info = ActivityManager.MemoryInfo()
        (app.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(info)
        // Conservative admission check, not a measured Pocket memory requirement.
        if (info.lowMemory || info.availMem < 512L * 1024 * 1024) {
            throw LocalAiException(LocalAiException.Reason.LOW_MEMORY, "At least 512 MiB of available RAM is required to attempt loading")
        }
    }
}
