package com.sergey.animevault.ui.design

/** Finite timeline shared by rendering, previews and boundary tests. */
internal object RaidenMotion {
    const val splashMillis = 1600
    const val selectionMillis = 760

    fun phase(progress: Float, start: Float, end: Float): Float {
        val x = ((progress - start) / (end - start)).coerceIn(0f, 1f)
        return x * x * (3f - 2f * x)
    }

    fun arrival(progress: Float) = phase(progress, 0f, .30f)
    fun wave(progress: Float) = phase(progress, .12f, .74f)
    fun title(progress: Float) = phase(progress, .28f, .58f)
    fun exit(progress: Float) = 1f - phase(progress, .82f, 1f)
}
