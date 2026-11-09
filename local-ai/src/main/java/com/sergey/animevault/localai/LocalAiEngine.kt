package com.sergey.animevault.localai

import java.io.File
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/** Inference only. Implementations must never perform network requests. */
interface LocalAiEngine {
    val statistics: StateFlow<AiStatistics?>
    suspend fun load(config: File)
    /** Emits UTF-8 text deltas. The caller owns durable conversation history. */
    fun generate(messages: List<AiMessage>, limits: AiLimits = AiLimits()): Flow<String>
    /** Cooperative: takes effect after the current native prefill/token operation returns. */
    fun stop()
    /** Stops generation, waits for native work, then releases weights and KV cache. */
    suspend fun unload()
}

data class AiMessage(val role: Role, val content: String) {
    enum class Role(val wireName: String) { SYSTEM("system"), USER("user"), ASSISTANT("assistant") }
}

data class AiLimits(val contextTokens: Int = 2048, val responseTokens: Int = 256) {
    init {
        require(contextTokens in 256..4096)
        require(responseTokens in 1..512 && responseTokens < contextTokens)
    }
}

class LocalAiException(val reason: Reason, message: String, cause: Throwable? = null) :
    Exception(message, cause) {
    enum class Reason { UNSUPPORTED_DEVICE, LOW_MEMORY, LOAD_FAILED, NOT_LOADED, CONTEXT_TOO_LONG, GENERATION_FAILED }
}

/** Counts are from MNN, never inferred from text chunk or character counts. */
data class AiStatistics(
    val loadMicros: Long,
    val prefillMicros: Long,
    val decodeMicros: Long,
    val generatedTokens: Long,
)
