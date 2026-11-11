package com.sergey.animevault.localai

/** AnimeVault JNI bridge to the pinned official C++ API, not an MNN Java SDK. */
internal object MnnNative {
    fun loadLibrary() = System.loadLibrary("animevault_ai")
    external fun create(configPath: ByteArray): Long
    external fun begin(handle: Long, roles: Array<ByteArray>, texts: Array<ByteArray>, maxInputTokens: Int)
    external fun step(handle: Long): ByteArray
    external fun finished(handle: Long): Boolean
    external fun metrics(handle: Long): LongArray
    external fun release(handle: Long)
}
