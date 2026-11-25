package com.sergey.animevault.ui.components

import android.graphics.Bitmap
import android.util.LruCache
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.material3.Icon
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.size
import com.sergey.animevault.ui.design.VaultSize
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
    val source = sources.getOrNull(index)
    val cachedTint = remember(source, dynamic) { if (dynamic) source?.let { artworkTints.get(it.url) } else null }
    var bitmap by remember(source, dynamic) { mutableStateOf<Bitmap?>(null) }
    var sampledTint by remember(source, dynamic) { mutableStateOf(cachedTint ?: Color.Unspecified) }
    if (dynamic && source != null && bitmap != null) {
        LaunchedEffect(source, bitmap) {
            val image = bitmap ?: return@LaunchedEffect
            val tint = withContext(Dispatchers.Default) {
                artworkTints.get(source.url) ?: (sampleArtworkTint(image) ?: Color.Unspecified)
            }
            artworkTints.put(source.url, tint)
            sampledTint = tint
            bitmap = null
        }
    }
    val artworkTint = if (sampledTint == Color.Unspecified) colors.primary else sampledTint
    Box(modifier.background(colors.surfaceVariant)) {
        // The same fallback is visible on cache misses and when all candidates fail.
        Box(Modifier.matchParentSize().drawWithCache {
            val gradient = Brush.linearGradient(listOf(colors.primary.copy(alpha = 0.12f), colors.background))
            onDrawBehind { drawRect(gradient) }
        })
        if (source == null && !backdrop) {
            Icon(Icons.Outlined.Movie, contentDescription = null,
                tint = colors.onSurfaceVariant,
                modifier = Modifier.align(Alignment.Center).size(VaultSize.largeIcon))
        }
        // Previews are deterministic and never contact an image source.
        if (source != null && !LocalInspectionMode.current) {
            key(source) {
                val needsSoftwareBitmap = dynamic && cachedTint == null
                val request = remember(context, source.url, needsSoftwareBitmap) {
                    ImageRequest.Builder(context)
                        .data(source.url)
                        .allowHardware(!needsSoftwareBitmap)
                        .build()
                }
                // AsyncImage resolves the measured constraints and cancels when it leaves composition.
                AsyncImage(
                    model = request,
                    contentDescription = contentDescription,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.matchParentSize(),
                    onError = { index += 1 },
                    onSuccess = {
                        if (needsSoftwareBitmap && bitmap == null) {
                            val existingTint = artworkTints.get(source.url)
                            if (existingTint != null) sampledTint = existingTint
                            else bitmap = (it.result.image as? BitmapImage)?.bitmap
                        }
                    },
                )
            }
        }
        if (backdrop) {
            if (dynamic) {
                Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(
                    listOf(artworkTint.copy(alpha = 0.32f), Color.Transparent),
                )))
            }
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(
                0f to colors.background.copy(alpha = 0.14f),
                0.40f to colors.background.copy(alpha = 0.44f),
                0.78f to colors.background.copy(alpha = 0.90f),
                1f to colors.background,
            )))
        }
    }
}

private val artworkTints = LruCache<String, Color>(64)

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
