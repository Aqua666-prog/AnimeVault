package com.sergey.animevault.ui.player

import android.app.Activity
import android.app.PictureInPictureParams
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Rational
import androidx.annotation.RequiresApi

internal fun isPlayerPictureInPictureSupported(context: Context): Boolean =
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
        context.packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)

internal fun enterPlayerPictureInPicture(context: Context): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return false
    if (!isPlayerPictureInPictureSupported(context)) return false
    val activity = context.findPlayerActivity() ?: return false
    return enterPlayerPictureInPictureApi26(activity)
}

@RequiresApi(Build.VERSION_CODES.O)
private fun enterPlayerPictureInPictureApi26(activity: Activity): Boolean {
    return runCatching {
        val builder = PictureInPictureParams.Builder()
            .setAspectRatio(Rational(16, 9))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builder.setSeamlessResizeEnabled(true)
        }
        activity.enterPictureInPictureMode(builder.build())
    }.getOrDefault(false)
}
