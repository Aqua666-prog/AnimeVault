package com.sergey.animevault.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import com.sergey.animevault.R

/** Approved, unmodified kit artwork. Coil decodes off-thread and shares its memory cache.
 * Explicit pixel bounds avoid decoding a 1024px logo for every 42dp app-bar mark.
 */
@Composable
fun AnimeVaultBrandImage(
    modifier: Modifier = Modifier,
    portrait: Boolean = false,
    decodeSize: Int = 512,
    contentDescription: String? = stringResource(R.string.animevault_brand_content_description),
) {
    val context = LocalContext.current
    val resource = if (portrait) R.drawable.animevault_brand_portrait else R.drawable.animevault_brand_logo
    val request = remember(context, resource, decodeSize) {
        ImageRequest.Builder(context).data(resource).size(decodeSize, decodeSize).build()
    }
    AsyncImage(
        model = request,
        contentDescription = contentDescription,
        contentScale = ContentScale.Fit,
        modifier = modifier,
    )
}
