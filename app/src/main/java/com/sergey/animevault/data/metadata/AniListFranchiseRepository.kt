package com.sergey.animevault.data.metadata

import com.google.gson.Gson
import com.sergey.animevault.data.online.animeVaultUserAgent
import com.sergey.animevault.data.online.executeText
import com.sergey.animevault.data.online.onlineHeaders
import java.util.concurrent.TimeUnit
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

class AniListFranchiseRepository(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .callTimeout(25, TimeUnit.SECONDS)
        .build(),
    private val gson: Gson = Gson(),
) {
    suspend fun load(anilistId: Long): AniListFranchiseGraph {
        require(anilistId > 0L) { "Некорректный AniList ID" }
        val request = Request.Builder()
            .url(GRAPHQL_URL)
            .post(
                gson.toJson(GraphQlRequest(QUERY, mapOf("id" to anilistId)))
                    .toRequestBody(JSON_MEDIA_TYPE),
            )
            .onlineHeaders(userAgent = animeVaultUserAgent("Android; AniList franchise"))
            .header("Accept", "application/json")
            .build()
        return parseAniListFranchiseResponse(gson, client.executeText(request, "AniList"))
    }

    private companion object {
        const val GRAPHQL_URL = "https://graphql.anilist.co"
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        const val MEDIA_FIELDS = """
            id
            title { romaji english native }
            coverImage { large }
            seasonYear
            episodes
            format
            status
            siteUrl
        """

        val QUERY = """
            query AnimeVaultFranchise(${'$'}id: Int!) {
              Media(id: ${'$'}id, type: ANIME) {
                $MEDIA_FIELDS
                relations {
                  edges {
                    relationType(version: 2)
                    node {
                      $MEDIA_FIELDS
                      relations {
                        edges {
                          relationType(version: 2)
                          node { $MEDIA_FIELDS }
                        }
                      }
                    }
                  }
                }
                recommendations(sort: RATING_DESC, perPage: 12) {
                  nodes {
                    rating
                    mediaRecommendation { $MEDIA_FIELDS }
                  }
                }
              }
            }
        """
    }
}

data class AniListFranchiseNode(
    val id: Long,
    val title: String,
    val englishTitle: String?,
    val posterUrl: String?,
    val year: Int?,
    val episodes: Int?,
    val format: String?,
    val status: String?,
    val siteUrl: String?,
)

data class AniListFranchiseEdge(
    val fromId: Long,
    val toId: Long,
    val relation: AniListRelationType,
)

data class AniListRecommendation(
    val rating: Int,
    val media: AniListFranchiseNode,
)

data class AniListFranchiseGraph(
    val rootId: Long,
    val nodes: List<AniListFranchiseNode>,
    val edges: List<AniListFranchiseEdge>,
    val recommendations: List<AniListRecommendation>,
) {
    fun ordered(mode: FranchiseOrderMode): List<AniListFranchiseNode> {
        val byId = nodes.associateBy(AniListFranchiseNode::id)
        val root = byId[rootId]
        val relationByNode = edges
            .filter { it.fromId == rootId }
            .associate { it.toId to it.relation }
        val source = when (mode) {
            FranchiseOrderMode.RELEASE -> nodes
            FranchiseOrderMode.CHRONOLOGY -> nodes
            FranchiseOrderMode.MAIN_STORY -> nodes.filter { node ->
                node.id == rootId || relationByNode[node.id] in MAIN_STORY_RELATIONS
            }
        }
        return when (mode) {
            FranchiseOrderMode.RELEASE,
            FranchiseOrderMode.MAIN_STORY -> source.sortedWith(
                compareBy<AniListFranchiseNode> { it.year ?: Int.MAX_VALUE }
                    .thenBy { it.id },
            )
            FranchiseOrderMode.CHRONOLOGY -> source.sortedWith(
                compareBy<AniListFranchiseNode> { node ->
                    if (node.id == rootId) 20 else chronologyRank(relationByNode[node.id])
                }.thenBy { it.year ?: Int.MAX_VALUE }.thenBy { it.id },
            )
        }.let { ordered ->
            if (mode == FranchiseOrderMode.CHRONOLOGY && root != null && ordered.none { it.id == root.id }) {
                listOf(root) + ordered
            } else ordered
        }
    }

    companion object {
        private val MAIN_STORY_RELATIONS = setOf(
            AniListRelationType.PREQUEL,
            AniListRelationType.SEQUEL,
            AniListRelationType.PARENT,
            AniListRelationType.CONTAINS,
            AniListRelationType.COMPILATION,
        )

        private fun chronologyRank(relation: AniListRelationType?): Int = when (relation) {
            AniListRelationType.PREQUEL -> 10
            AniListRelationType.PARENT -> 15
            AniListRelationType.SEQUEL -> 30
            AniListRelationType.SIDE_STORY -> 40
            AniListRelationType.SPIN_OFF -> 45
            AniListRelationType.ALTERNATIVE -> 50
            AniListRelationType.SUMMARY,
            AniListRelationType.COMPILATION -> 55
            else -> 60
        }
    }
}

enum class FranchiseOrderMode(val label: String) {
    RELEASE("По выходу"),
    CHRONOLOGY("Хронология"),
    MAIN_STORY("Основная история"),
}

enum class AniListRelationType {
    ADAPTATION,
    PREQUEL,
    SEQUEL,
    PARENT,
    SIDE_STORY,
    CHARACTER,
    SUMMARY,
    ALTERNATIVE,
    SPIN_OFF,
    OTHER,
    SOURCE,
    COMPILATION,
    CONTAINS,
    UNKNOWN;

    companion object {
        fun parse(value: String?): AniListRelationType = entries.firstOrNull { it.name == value } ?: UNKNOWN
    }
}

internal fun parseAniListFranchiseResponse(gson: Gson, json: String): AniListFranchiseGraph {
    val response = gson.fromJson(json, FranchiseResponse::class.java)
        ?: error("AniList вернул пустой ответ")
    response.errors.orEmpty().firstOrNull()?.message?.takeIf(String::isNotBlank)?.let { error("AniList: $it") }
    val rootDto = response.data?.media ?: error("AniList не нашёл тайтл")
    val root = rootDto.toNode() ?: error("AniList вернул неполный тайтл")
    val nodes = linkedMapOf(root.id to root)
    val edges = linkedMapOf<String, AniListFranchiseEdge>()

    fun addEdge(fromId: Long, edge: RelationEdgeDto) {
        val node = edge.node?.toNode() ?: return
        nodes.putIfAbsent(node.id, node)
        val relation = AniListRelationType.parse(edge.relationType)
        val key = "$fromId:${node.id}:${relation.name}"
        edges.putIfAbsent(key, AniListFranchiseEdge(fromId, node.id, relation))
    }

    rootDto.relations?.edges.orEmpty().forEach { first ->
        addEdge(root.id, first)
        val firstNode = first.node ?: return@forEach
        val firstId = firstNode.id ?: return@forEach
        firstNode.relations?.edges.orEmpty().forEach { second -> addEdge(firstId, second) }
    }

    val recommendations = rootDto.recommendations?.nodes.orEmpty()
        .mapNotNull { recommendation ->
            val node = recommendation.mediaRecommendation?.toNode() ?: return@mapNotNull null
            AniListRecommendation(recommendation.rating ?: 0, node)
        }
        .filter { it.media.id != root.id }
        .distinctBy { it.media.id }
        .sortedByDescending(AniListRecommendation::rating)

    return AniListFranchiseGraph(
        rootId = root.id,
        nodes = nodes.values.toList(),
        edges = edges.values.toList(),
        recommendations = recommendations,
    )
}

private fun MediaDto.toNode(): AniListFranchiseNode? {
    val mediaId = id ?: return null
    val displayTitle = title?.romaji?.takeIf(String::isNotBlank)
        ?: title?.english?.takeIf(String::isNotBlank)
        ?: title?.native?.takeIf(String::isNotBlank)
        ?: return null
    return AniListFranchiseNode(
        id = mediaId,
        title = displayTitle,
        englishTitle = title?.english?.takeIf(String::isNotBlank),
        posterUrl = coverImage?.large?.takeIf(String::isNotBlank),
        year = seasonYear,
        episodes = episodes,
        format = format,
        status = status,
        siteUrl = siteUrl?.takeIf(String::isNotBlank),
    )
}

private data class GraphQlRequest(val query: String, val variables: Map<String, Any>)
private data class ErrorDto(val message: String? = null)
private data class FranchiseResponse(val data: DataDto? = null, val errors: List<ErrorDto>? = null)
private data class DataDto(@com.google.gson.annotations.SerializedName("Media") val media: MediaDto? = null)
private data class TitleDto(val romaji: String? = null, val english: String? = null, val native: String? = null)
private data class CoverDto(val large: String? = null)
private data class RelationConnectionDto(val edges: List<RelationEdgeDto>? = null)
private data class RelationEdgeDto(val relationType: String? = null, val node: MediaDto? = null)
private data class RecommendationConnectionDto(val nodes: List<RecommendationDto>? = null)
private data class RecommendationDto(val rating: Int? = null, val mediaRecommendation: MediaDto? = null)
private data class MediaDto(
    val id: Long? = null,
    val title: TitleDto? = null,
    val coverImage: CoverDto? = null,
    val seasonYear: Int? = null,
    val episodes: Int? = null,
    val format: String? = null,
    val status: String? = null,
    val siteUrl: String? = null,
    val relations: RelationConnectionDto? = null,
    val recommendations: RecommendationConnectionDto? = null,
)
