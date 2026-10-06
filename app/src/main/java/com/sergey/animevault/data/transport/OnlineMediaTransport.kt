package com.sergey.animevault.data.transport

import com.sergey.animevault.data.online.OnlineStream
import com.sergey.animevault.data.online.OnlineStreamType

/** Stable key for one concrete URL variant. Signed URL refresh intentionally changes this key. */
fun OnlineStream.transportVariantKey(): String = "${type}\u001F${url}"

/** Converts provider output into the transport contract shared by playback and downloads. */
fun OnlineStream.toMediaTransportSource(
    ownerProviderId: String,
    ownerProviderName: String? = null,
): MediaTransportSource {
    val effectiveProviderId = providerId?.takeIf(String::isNotBlank) ?: ownerProviderId
    val effectiveRoute = hostFamily ?: inferRouteFamily(url)
    return MediaTransportSource(
        key = transportVariantKey(),
        uri = url,
        kind = when (type) {
            OnlineStreamType.HLS -> TransportKind.HLS
            OnlineStreamType.MP4 -> TransportKind.MP4
            OnlineStreamType.EMBED -> TransportKind.EMBED
        },
        providerId = effectiveProviderId,
        providerName = providerName ?: ownerProviderName,
        sourceName = sourceName,
        translation = translation,
        translationKey = translationPreferenceKey,
        quality = quality,
        headers = headers,
        offlineCacheId = offlineCacheId,
        routeFamily = effectiveRoute,
        refreshable = refreshable,
        expiresAtEpochMs = expiresAtEpochMs,
        refreshIdentity = listOf(
            effectiveProviderId,
            translationPreferenceKey.orEmpty(),
            quality?.toString().orEmpty(),
            effectiveRoute.orEmpty(),
            type.name,
        ).joinToString("\u001F"),
    )
}
