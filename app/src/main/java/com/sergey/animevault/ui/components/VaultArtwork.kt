package com.sergey.animevault.ui.components

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import coil3.BitmapImage
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.allowHardware
import com.sergey.animevault.ui.theme.LocalVaultVisualSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Ordered image candidates. Failure advances to the next source, not an empty hero. */
data class VaultArtworkSource(val url: String, val isBanner: Boolean = false)

fun vaultTitleArtwork(
    aniListBanner: String? = null,
    metadataPoster: String? = null,
    providerPoster: String? = null,
): List<VaultArtworkSource> = listOfNotNull(
    aniListBanner?.trim()?.takeIf(String::isNotBlank)?.let { VaultArtworkSource(it, true) },
    metadataPoster?.trim()?.takeIf(String::isNotBlank)?.let { VaultArtworkSource(it) },
    providerPoster?.trim()?.takeIf(String::isNotBlank)?.let { VaultArtworkSource(it) },
).distinctBy(VaultArtworkSource::url)

@Composable
fun VaultArtwork(
    sources: List<VaultArtworkSource>,
    modifier: Modifier = Modifier,
    backdrop: Boolean = false,
    contentDescription: String? = null,
) {
    val context = LocalContext.current
    val colors = MaterialTheme.colorScheme
    val dynamic = backdrop && LocalVaultVisualSettings.current.dynamicArtwork
    var index by remember(sources) { mutableIntStateOf(0) }
    var bitmap by remember(sources, dynamic) { mutableStateOf<Bitmap?>(null) }
    var artworkTint by remember(sources, dynamic) { mutableStateOf(colors.primary) }
    val source = sources.getOrNull(index)
    LaunchedEffect(bitmap, dynamic) {
        val image = bitmap
        if (dynamic && image != null) {
            artworkTint = withContext(Dispatchers.Default) { sampleArtworkTint(image) } ?: colors.primary
        }
    }
    Box(modifier.background(colors.surfaceVariant)) {
        // Cheap, static fallback also remains visible while an image is loading.
        Box(Modifier.fillMaxSize().background(Brush.linearGradient(
            listOf(colors.primary.copy(alpha = 0.12f), colors.background),
        )))
        if (source != null) {
            val request = remember(source.url, backdrop, dynamic) {
                ImageRequest.Builder(context).data(source.url)
                    .size(if (backdrop) 1280 else 480, if (backdrop) 720 else 720)
                    .allowHardware(!dynamic).build()
            }
            AsyncImage(
                model = request,
                contentDescription = contentDescription,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
                onError = { index += 1; bitmap = null },
                onSuccess = { if (dynamic) bitmap = (it.result.image as? BitmapImage)?.bitmap },
            )
        }
        if (backdrop) {
            if (dynamic) {
                Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(
                    listOf(artworkTint.copy(alpha = 0.23f), Color.Transparent),
                )))
            }
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(
                0f to colors.background.copy(alpha = 0.14f),
                0.35f to colors.background.copy(alpha = if (source?.isBanner == true) 0.16f else 0.36f),
                0.7f to colors.background.copy(alpha = 0.74f),
                1f to colors.background,
            )))
        }
    }
}

/** Sample at most 256 pixels once, off the UI thread; never analyse a full bitmap. */
private fun sampleArtworkTint(bitmap: Bitmap): Color? = runCatching {
    if (bitmap.isRecycled || bitmap.width <= 0 || bitmap.height <= 0) return@runCatching null
    var r = 0L; var g = 0L; var b = 0L; var count = 0L
    val hsv = FloatArray(3)
    for (y in 0 until 16) for (x in 0 until 16) {
        val pixel = bitmap.getPixel((x * bitmap.width / 16).coerceAtMost(bitmap.width - 1),
            (y * bitmap.height / 16).coerceAtMost(bitmap.height - 1))
        android.graphics.Color.colorToHSV(pixel, hsv)
        if (hsv[1] >= 0.18f && hsv[2] in 0.18f..0.92f) {
            r += android.graphics.Color.red(pixel); g += android.graphics.Color.green(pixel)
            b += android.graphics.Color.blue(pixel); count++
        }
    }
    if (count == 0L) null else Color(android.graphics.Color.rgb((r / count).toInt(), (g / count).toInt(), (b / count).toInt()))
}.getOrNull()
