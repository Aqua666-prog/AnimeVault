package com.sergey.animevault.ui.online

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Color as AndroidColor
import android.net.Uri
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import coil3.compose.AsyncImage
import com.sergey.animevault.data.metadata.TitleCharacter
import com.sergey.animevault.data.metadata.TitleExtraVideo
import com.sergey.animevault.data.metadata.TitleExtras
import com.sergey.animevault.data.metadata.InvidiousVideoResolver
import com.sergey.animevault.data.metadata.ResolvedInvidiousStream
import com.sergey.animevault.data.metadata.TenraiAnimeOverview
import com.sergey.animevault.data.metadata.TenraiAnimeStatistics
import com.sergey.animevault.data.metadata.TenraiRecommendation
import com.sergey.animevault.data.metadata.TenraiRelationItem
import com.sergey.animevault.data.metadata.TenraiStaffMember
import com.sergey.animevault.data.metadata.TenraiHealthSnapshot
import com.sergey.animevault.data.metadata.TenraiNamedLink
import com.sergey.animevault.data.metadata.TenraiPicture
import com.sergey.animevault.data.metadata.displayLabel

@Composable
internal fun OnlineTitleExtrasSection(
    extras: TitleExtras?,
    isLoading: Boolean,
    errorMessage: String?,
    onRetry: () -> Unit,
) {
    var selectedCharacter by remember { mutableStateOf<TitleCharacter?>(null) }
    var selectedVideo by remember { mutableStateOf<TitleExtraVideo?>(null) }
    var selectedPictureUrl by remember { mutableStateOf<String?>(null) }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        shape = RoundedCornerShape(22.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.88f),
    ) {
        Column(
            modifier = Modifier.padding(vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column {
                    Text(
                        text = "Дополнительно",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                    )
                    extras?.sources?.takeIf { it.isNotEmpty() }?.let { sources ->
                        Text(
                            text = sources.joinToString(" · "),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (isLoading) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                }
            }

            when {
                isLoading && extras == null -> {
                    Text(
                        text = "Загружаем персонажей, трейлеры и опенинги…",
                        modifier = Modifier.padding(horizontal = 16.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                extras == null -> {
                    Column(
                        modifier = Modifier.padding(horizontal = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            text = errorMessage ?: "Дополнительные материалы не найдены",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        TextButton(onClick = onRetry) {
                            Icon(Icons.Outlined.Refresh, contentDescription = null)
                            Spacer(Modifier.width(6.dp))
                            Text("Повторить")
                        }
                    }
                }

                else -> {
                    if (extras.characters.isNotEmpty()) {
                        Text(
                            text = "Персонажи",
                            modifier = Modifier.padding(horizontal = 16.dp),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                        )
                        LazyRow(
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            items(extras.characters.take(MAX_CHARACTER_CARDS), key = TitleCharacter::id) { character ->
                                CharacterCard(
                                    character = character,
                                    onClick = { selectedCharacter = character },
                                )
                            }
                        }
                    }

                    extras.tenraiOverview?.let { overview ->
                        TenraiOverviewCard(
                            overview = overview,
                            statistics = extras.tenraiStatistics,
                            health = extras.tenraiHealth,
                        )
                    }

                    if (extras.tenraiStaff.isNotEmpty()) {
                        Text(
                            text = "Создатели",
                            modifier = Modifier.padding(horizontal = 16.dp),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                        )
                        LazyRow(
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            items(extras.tenraiStaff.take(MAX_TENRAI_STAFF_CARDS), key = TenraiStaffMember::malId) { member ->
                                TenraiStaffCard(member)
                            }
                        }
                    }

                    extras.tenraiOverview?.relations?.takeIf { it.isNotEmpty() }?.let { relations ->
                        Text(
                            text = "Связанные произведения",
                            modifier = Modifier.padding(horizontal = 16.dp),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                        )
                        LazyRow(
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            items(relations.take(MAX_TENRAI_RELATION_CARDS), key = { item -> "${item.relation}:${item.malId}:${item.type.orEmpty()}" }) { item ->
                                TenraiRelationCard(item)
                            }
                        }
                    }

                    if (extras.tenraiRecommendations.isNotEmpty()) {
                        Text(
                            text = "Похожие",
                            modifier = Modifier.padding(horizontal = 16.dp),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                        )
                        LazyRow(
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            items(extras.tenraiRecommendations.take(MAX_TENRAI_RECOMMENDATION_CARDS), key = TenraiRecommendation::malId) { item ->
                                TenraiRecommendationCard(item)
                            }
                        }
                    }

                    if (extras.tenraiPictures.isNotEmpty()) {
                        Text(
                            text = "Галерея",
                            modifier = Modifier.padding(horizontal = 16.dp),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                        )
                        LazyRow(
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            items(
                                extras.tenraiPictures.take(MAX_TENRAI_PICTURE_CARDS),
                                key = { picture -> picture.largeImageUrl ?: picture.imageUrl.orEmpty() },
                            ) { picture ->
                                TenraiPictureCard(
                                    picture = picture,
                                    onClick = { selectedPictureUrl = picture.largeImageUrl ?: picture.imageUrl },
                                )
                            }
                        }
                    }

                    extras.tenraiOverview?.let { overview ->
                        val links = (overview.streamingLinks + overview.externalLinks)
                            .filter { link -> link.url.startsWith("https://") || link.url.startsWith("http://") }
                            .distinctBy { it.url }
                            .take(MAX_TENRAI_LINKS)
                        if (links.isNotEmpty()) {
                            TenraiLinksRow(links)
                        }
                    }

                    if (extras.videos.isNotEmpty()) {
                        Text(
                            text = "Видео",
                            modifier = Modifier.padding(horizontal = 16.dp),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                        )
                        LazyRow(
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            items(extras.videos.take(MAX_VIDEO_CARDS), key = TitleExtraVideo::id) { video ->
                                VideoCard(
                                    video = video,
                                    onClick = { selectedVideo = video },
                                )
                            }
                        }
                    }

                    if (
                        extras.characters.isEmpty() &&
                        extras.videos.isEmpty() &&
                        extras.tenraiOverview == null &&
                        extras.tenraiStaff.isEmpty() &&
                        extras.tenraiRecommendations.isEmpty()
                    ) {
                        Text(
                            text = "Tenrai и Shikimori пока не дали дополнительных материалов для этого тайтла.",
                            modifier = Modifier.padding(horizontal = 16.dp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }
        }
    }

    selectedCharacter?.let { character ->
        CharacterDetailsSheet(
            character = character,
            onDismiss = { selectedCharacter = null },
        )
    }
    selectedVideo?.let { video ->
        TitleVideoSheet(
            video = video,
            onDismiss = { selectedVideo = null },
        )
    }
    selectedPictureUrl?.let { pictureUrl ->
        ModalBottomSheet(onDismissRequest = { selectedPictureUrl = null }) {
            AsyncImage(
                model = pictureUrl,
                contentDescription = "Изображение из Tenrai",
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 720.dp)
                    .padding(start = 16.dp, end = 16.dp, bottom = 28.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentScale = ContentScale.Fit,
            )
        }
    }
}

@Composable
private fun TenraiOverviewCard(
    overview: TenraiAnimeOverview,
    statistics: TenraiAnimeStatistics?,
    health: TenraiHealthSnapshot?,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.56f),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            Text(
                text = buildString {
                    append(overview.metadataSource)
                    if (overview.isStale) append(" · сохранённые данные")
                },
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Bold,
            )
            health?.let { snapshot ->
                Text(
                    text = snapshot.displayLabel(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            val ranking = buildList {
                overview.score?.let { add("Оценка $it") }
                overview.rank?.let { add("Ранг #$it") }
                overview.popularity?.let { add("Популярность #$it") }
            }
            if (ranking.isNotEmpty()) {
                Text(
                    text = ranking.joinToString(" · "),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
            }
            val production = buildList {
                overview.type?.let(::add)
                overview.year?.let { add(it.toString()) }
                overview.status?.let(::add)
                overview.studios.firstOrNull()?.name?.let(::add)
                overview.source?.let(::add)
            }.distinct()
            if (production.isNotEmpty()) {
                Text(
                    text = production.joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            overview.broadcast?.let { broadcast ->
                Text(
                    text = broadcast,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            statistics?.let { stats ->
                Text(
                    text = "Смотрят ${stats.watching} · Завершили ${stats.completed} · Планируют ${stats.planToWatch}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (overview.openings.isNotEmpty()) {
                Text(
                    text = "OP: ${overview.openings.take(2).joinToString(" · ")}",
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (overview.endings.isNotEmpty()) {
                Text(
                    text = "ED: ${overview.endings.take(2).joinToString(" · ")}",
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun TenraiStaffCard(member: TenraiStaffMember) {
    Surface(
        modifier = Modifier.width(126.dp),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.62f),
    ) {
        Column {
            AsyncImage(
                model = member.imageUrl,
                contentDescription = member.name,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(150.dp)
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentScale = ContentScale.Crop,
            )
            Column(
                modifier = Modifier.padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                Text(
                    text = member.name,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = member.positions.take(2).joinToString(" · ").ifBlank { "Staff" },
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun TenraiRelationCard(item: TenraiRelationItem) {
    Surface(
        modifier = Modifier.width(132.dp),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.62f),
    ) {
        Column {
            AsyncImage(
                model = item.imageUrl,
                contentDescription = item.name,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(170.dp)
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentScale = ContentScale.Crop,
            )
            Column(Modifier.padding(10.dp)) {
                Text(
                    text = item.relation,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = item.name,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                )
                (item.mediaType ?: item.type)?.let { mediaType ->
                    Text(
                        text = mediaType,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun TenraiRecommendationCard(item: TenraiRecommendation) {
    Surface(
        modifier = Modifier.width(132.dp),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.62f),
    ) {
        Column {
            AsyncImage(
                model = item.imageUrl,
                contentDescription = item.title,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(170.dp)
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentScale = ContentScale.Crop,
            )
            Column(Modifier.padding(10.dp)) {
                Text(
                    text = item.title,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                )
                if (item.votes > 0) {
                    Text(
                        text = "Рекомендаций: ${item.votes}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun TenraiPictureCard(
    picture: TenraiPicture,
    onClick: () -> Unit,
) {
    val imageUrl = picture.largeImageUrl ?: picture.imageUrl ?: return
    Surface(
        modifier = Modifier
            .width(150.dp)
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.62f),
    ) {
        AsyncImage(
            model = imageUrl,
            contentDescription = "Изображение",
            modifier = Modifier
                .fillMaxWidth()
                .height(210.dp),
            contentScale = ContentScale.Crop,
        )
    }
}

@Composable
private fun TenraiLinksRow(links: List<TenraiNamedLink>) {
    val context = LocalContext.current
    Column(
        modifier = Modifier.padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        Text(
            text = "Ссылки",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
        )
        links.forEach { link ->
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        runCatching {
                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(link.url)))
                        }
                    },
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.48f),
            ) {
                Text(
                    text = link.name,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}

@Composable
private fun CharacterCard(
    character: TitleCharacter,
    onClick: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .width(126.dp)
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.62f),
    ) {
        Column {
            AsyncImage(
                model = character.imageUrl,
                contentDescription = character.displayName,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(160.dp)
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentScale = ContentScale.Crop,
            )
            Column(
                modifier = Modifier.padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                Text(
                    text = character.displayName,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = character.role,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

@Composable
private fun VideoCard(
    video: TitleExtraVideo,
    onClick: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .width(238.dp)
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.62f),
    ) {
        Column {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f)
                    .background(Color.Black),
                contentAlignment = Alignment.Center,
            ) {
                AsyncImage(
                    model = video.thumbnailUrl,
                    contentDescription = video.title,
                    modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f),
                    contentScale = ContentScale.Crop,
                )
                Surface(
                    shape = RoundedCornerShape(50),
                    color = Color.Black.copy(alpha = 0.58f),
                ) {
                    Icon(
                        Icons.Outlined.PlayArrow,
                        contentDescription = null,
                        modifier = Modifier.padding(10.dp).size(28.dp),
                        tint = Color.White,
                    )
                }
            }
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                Text(
                    text = video.kindLabel,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = video.title,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = buildList {
                        add(video.source)
                        video.qualityLabel?.let(::add)
                    }.joinToString(" · "),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun CharacterDetailsSheet(
    character: TitleCharacter,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 720.dp)
                .verticalScroll(rememberScrollState())
                .padding(start = 20.dp, end = 20.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                AsyncImage(
                    model = character.imageUrl,
                    contentDescription = character.displayName,
                    modifier = Modifier
                        .width(126.dp)
                        .height(178.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                    contentScale = ContentScale.Crop,
                )
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    Text(
                        text = character.displayName,
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Black,
                    )
                    if (character.displayName != character.name) {
                        Text(
                            text = character.name,
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    character.japaneseName?.let { name ->
                        Text(
                            text = name,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.primaryContainer,
                    ) {
                        Text(
                            text = character.role,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                    }
                }
            }

            Text(
                text = "Описание",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = character.description ?: "Для этого персонажа русская справка пока отсутствует.",
                style = MaterialTheme.typography.bodyLarge,
                color = if (character.description == null) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
            )

            if (character.aliases.isNotEmpty()) {
                Text(
                    text = "Другие имена: ${character.aliases.take(5).joinToString(" · ")}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (character.voiceActors.isNotEmpty()) {
                Text(
                    text = "Сэйю",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(character.voiceActors, key = { actor -> actor.malId ?: actor.name }) { actor ->
                        Surface(
                            modifier = Modifier.width(116.dp),
                            shape = RoundedCornerShape(14.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.62f),
                        ) {
                            Column {
                                AsyncImage(
                                    model = actor.imageUrl,
                                    contentDescription = actor.name,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(126.dp),
                                    contentScale = ContentScale.Crop,
                                )
                                Column(Modifier.padding(9.dp)) {
                                    Text(
                                        text = actor.name,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = FontWeight.Bold,
                                    )
                                    actor.language?.let { language ->
                                        Text(
                                            text = language,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            Text(
                text = "Данные: Shikimori · Tenrai",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun TitleVideoSheet(
    video: TitleExtraVideo,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = video.kindLabel,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = video.title,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = buildList {
                    add(video.source)
                    video.qualityLabel?.let(::add)
                }.joinToString(" · "),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f),
                shape = RoundedCornerShape(18.dp),
                color = Color.Black,
            ) {
                when {
                    !video.directUrl.isNullOrBlank() -> DirectTitleVideoPlayer(video.directUrl, headers = video.headers)
                    !video.youtubeId.isNullOrBlank() -> YouTubeTitleVideoPlayer(video.youtubeId, video.externalUrl)
                    !video.embedUrl.isNullOrBlank() -> GenericEmbedVideoPlayer(video.embedUrl)
                    else -> Box(contentAlignment = Alignment.Center) {
                        Text("Видео недоступно", color = Color.White)
                    }
                }
            }
        }
    }
}

@Composable
private fun DirectTitleVideoPlayer(
    url: String,
    mimeType: String? = null,
    headers: Map<String, String> = emptyMap(),
    onPlaybackError: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val player = remember(url, mimeType, headers) {
        val httpFactory = DefaultHttpDataSource.Factory().setDefaultRequestProperties(headers)
        val dataSourceFactory = DefaultDataSource.Factory(context.applicationContext, httpFactory)
        ExoPlayer.Builder(context.applicationContext)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory))
            .build().apply {
            val mediaItem = MediaItem.Builder()
                .setUri(url)
                .apply {
                    mimeType
                        ?.substringBefore(';')
                        ?.trim()
                        ?.takeIf(String::isNotBlank)
                        ?.let(::setMimeType)
                }
                .build()
            setMediaItem(mediaItem)
            prepare()
            playWhenReady = true
        }
    }
    DisposableEffect(player, onPlaybackError) {
        val listener = object : androidx.media3.common.Player.Listener {
            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                onPlaybackError?.invoke()
            }
        }
        player.addListener(listener)
        onDispose {
            player.removeListener(listener)
            player.release()
        }
    }
    AndroidView(
        factory = { ctx ->
            PlayerView(ctx).apply {
                useController = true
                resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                this.player = player
            }
        },
        update = { it.player = player },
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun YouTubeTitleVideoPlayer(
    videoId: String,
    externalUrl: String?,
) {
    val context = LocalContext.current
    val resolver = remember { InvidiousVideoResolver() }
    var excludedInstances by remember(videoId) { mutableStateOf(emptySet<String>()) }
    var state by remember(videoId) {
        mutableStateOf<YouTubeResolveState>(YouTubeResolveState.Loading)
    }

    LaunchedEffect(videoId, excludedInstances) {
        state = YouTubeResolveState.Loading
        state = resolver.resolve(videoId, excludedInstances)
            ?.let(YouTubeResolveState::Ready)
            ?: YouTubeResolveState.Failed
    }

    when (val current = state) {
        YouTubeResolveState.Loading -> {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    CircularProgressIndicator(color = Color.White)
                    Text(
                        text = if (excludedInstances.isEmpty()) {
                            "Получаем прямой видеопоток…"
                        } else {
                            "Пробуем другой видеосервер…"
                        },
                        color = Color.White,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }

        is YouTubeResolveState.Ready -> {
            DirectTitleVideoPlayer(
                url = current.stream.url,
                mimeType = current.stream.mimeType,
                onPlaybackError = {
                    excludedInstances = excludedInstances + current.stream.instance
                },
            )
        }

        YouTubeResolveState.Failed -> {
            val targetUrl = externalUrl
                ?.takeIf { it.startsWith("https://") || it.startsWith("http://") }
                ?: "https://www.youtube.com/watch?v=$videoId"

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(20.dp),
                contentAlignment = Alignment.Center,
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(
                        text = "Встроенное воспроизведение сейчас недоступно",
                        color = Color.White,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Button(
                        onClick = {
                            runCatching {
                                context.startActivity(
                                    Intent(Intent.ACTION_VIEW, Uri.parse(targetUrl)),
                                )
                            }
                        },
                    ) {
                        Text("Открыть видео")
                    }
                }
            }
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun GenericEmbedVideoPlayer(url: String) {
    var webView by remember(url) { mutableStateOf<WebView?>(null) }
    DisposableEffect(url) {
        onDispose {
            webView?.stopLoading()
            webView?.loadUrl("about:blank")
            webView?.destroy()
            webView = null
        }
    }
    AndroidView(
        factory = { context ->
            WebView(context).apply {
                setBackgroundColor(AndroidColor.BLACK)
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.mediaPlaybackRequiresUserGesture = false
                webChromeClient = WebChromeClient()
                webViewClient = WebViewClient()
                loadUrl(url)
                webView = this
            }
        },
        modifier = Modifier.fillMaxWidth(),
    )
}

private sealed interface YouTubeResolveState {
    data object Loading : YouTubeResolveState
    data class Ready(val stream: ResolvedInvidiousStream) : YouTubeResolveState
    data object Failed : YouTubeResolveState
}

private const val MAX_CHARACTER_CARDS = 30
private const val MAX_VIDEO_CARDS = 30
private const val MAX_TENRAI_STAFF_CARDS = 16
private const val MAX_TENRAI_RELATION_CARDS = 20
private const val MAX_TENRAI_RECOMMENDATION_CARDS = 16
private const val MAX_TENRAI_PICTURE_CARDS = 18
private const val MAX_TENRAI_LINKS = 8
