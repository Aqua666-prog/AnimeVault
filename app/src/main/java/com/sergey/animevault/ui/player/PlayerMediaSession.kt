package com.sergey.animevault.ui.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalContext
import androidx.media3.common.Player
import androidx.media3.session.MediaSession
import java.util.UUID

/** Keeps system/Bluetooth media controls bound to the currently visible native player. */
@Composable
internal fun PlayerMediaSessionEffect(
    player: Player,
    sessionScope: String,
) {
    val context = LocalContext.current.applicationContext
    DisposableEffect(player, sessionScope) {
        val session = MediaSession.Builder(context, player)
            .setId("animevault-$sessionScope-${UUID.randomUUID()}")
            .build()
        onDispose { session.release() }
    }
}
