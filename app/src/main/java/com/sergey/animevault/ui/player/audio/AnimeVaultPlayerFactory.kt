package com.sergey.animevault.ui.player.audio

import android.content.Context
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink

internal object AnimeVaultPlayerFactory {
    fun builder(context: Context, engine: AnimeVaultAudioEngine): ExoPlayer.Builder =
        ExoPlayer.Builder(context, AnimeVaultRenderersFactory(context, engine))
}

internal class AnimeVaultRenderersFactory(
    context: Context,
    private val engine: AnimeVaultAudioEngine,
) : DefaultRenderersFactory(context) {
    override fun buildAudioSink(
        context: Context,
        enableFloatOutput: Boolean,
        enableAudioOutputPlaybackParams: Boolean,
    ): AudioSink = DefaultAudioSink.Builder(context)
        // The custom chain converts to float itself and returns int16 only after the final limiter.
        // Media3's float-output fast path can bypass audio processing, so keep it disabled here.
        // Playback speed must stay in Sonic inside our chain so the final limiter remains last.
        .setEnableFloatOutput(false)
        .setEnableAudioOutputPlaybackParameters(false)
        .setAudioProcessorChain(AnimeVaultAudioProcessorChain(engine))
        .build()
}
