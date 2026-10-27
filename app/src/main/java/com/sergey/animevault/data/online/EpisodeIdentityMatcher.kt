package com.sergey.animevault.data.online

import java.security.MessageDigest

data class EpisodeEvidence(val value: String? = null, val origin: String = "unknown", val reliable: Boolean = false)
data class EpisodeIdentity(
    val providerId: String,
    val releaseId: String,
    val episodeId: String,
    val titleIds: ExternalAnimeIds = ExternalAnimeIds(),
    val ordinal: Double? = null,
    val name: String? = null,
    val durationMs: Long = 0,
    val season: EpisodeEvidence = EpisodeEvidence(),
    val part: EpisodeEvidence = EpisodeEvidence(),
    val kind: EpisodeEvidence = EpisodeEvidence(),
    val edition: EpisodeEvidence = EpisodeEvidence(),
    val episodeReference: EpisodeEvidence = EpisodeEvidence(),
) {
    // Manual decisions invalidate when metadata changes; URLs and display language are excluded.
    fun fingerprint(): String = MessageDigest.getInstance("SHA-256").digest(
        listOf(providerId, releaseId, episodeId, titleIds, ordinal, season, part, kind, edition, episodeReference)
            .joinToString("\u001f").toByteArray(Charsets.UTF_8)
    ).joinToString("") { "%02x".format(it) }
}
enum class EpisodeIdentityClass { VERIFIED, POSSIBLE, UNKNOWN, CONFLICT }
data class EpisodeIdentityResult(
    val classification: EpisodeIdentityClass,
    val reasons: List<String>,
    val confirmed: List<String>,
    val conflicts: List<String>,
    val origins: List<String>,
)
object EpisodeIdentityMatcher {
    fun compare(a: EpisodeIdentity?, b: EpisodeIdentity?, manualDecision: Boolean? = null): EpisodeIdentityResult {
        if (a == null || b == null) return EpisodeIdentityResult(EpisodeIdentityClass.UNKNOWN,
            listOf("Нет данных об исходном эпизоде; совпадение монтажа не установлено"), emptyList(), emptyList(), emptyList())
        val matches = mutableListOf<String>()
        val conflicts = mutableListOf<String>()
        fun id(label: String, x: Long?, y: Long?) {
            if (x != null && y != null && x > 0 && y > 0) {
                if (x == y) matches += label else conflicts += label
            }
        }
        id("AniList", a.titleIds.anilistId, b.titleIds.anilistId)
        id("MAL", a.titleIds.malId, b.titleIds.malId)
        id("Shikimori", a.titleIds.shikimoriId, b.titleIds.shikimoriId)
        fun evidence(label: String, x: EpisodeEvidence, y: EpisodeEvidence) {
            if (!x.value.isNullOrBlank() && !y.value.isNullOrBlank()) {
                if (x.value.equals(y.value, true)) { if (x.reliable && y.reliable) matches += label }
                else conflicts += label // Even a derived explicit contradiction blocks automatic switching.
            }
        }
        evidence("Сезон", a.season, b.season); evidence("Часть", a.part, b.part)
        evidence("Тип", a.kind, b.kind); evidence("Монтаж", a.edition, b.edition)
        evidence("Эпизод", a.episodeReference, b.episodeReference)
        if (a.ordinal != null && b.ordinal != null && a.ordinal != b.ordinal) conflicts += "Номер серии"
        if (a.durationMs > 0 && b.durationMs > 0 && kotlin.math.abs(a.durationMs - b.durationMs) > maxOf(30_000L, maxOf(a.durationMs, b.durationMs) / 20)) conflicts += "Длительность/монтаж"
        if (manualDecision == false) conflicts += "Ручное разделение"
        val sameRecord = a.providerId == b.providerId && a.releaseId == b.releaseId && a.episodeId == b.episodeId
        val origins = listOf("${a.providerId}/${a.releaseId}", "${b.providerId}/${b.releaseId}") +
            listOf(a.season, b.season, a.part, b.part, a.kind, b.kind, a.edition, b.edition, a.episodeReference, b.episodeReference).map { it.origin }.distinct()
        val classification = when {
            conflicts.isNotEmpty() -> EpisodeIdentityClass.CONFLICT
            sameRecord || manualDecision == true -> EpisodeIdentityClass.VERIFIED
            "Эпизод" in matches && "Монтаж" in matches && matches.any { it in setOf("AniList", "MAL", "Shikimori") } -> EpisodeIdentityClass.VERIFIED
            matches.any { it in setOf("AniList", "MAL", "Shikimori") } && a.ordinal != null && a.ordinal == b.ordinal -> EpisodeIdentityClass.POSSIBLE
            else -> EpisodeIdentityClass.UNKNOWN
        }
        return EpisodeIdentityResult(classification, when (classification) {
            EpisodeIdentityClass.VERIFIED -> listOf(if (manualDecision == true) "Соответствие подтверждено пользователем" else "Подтверждён исходный эпизод и вариант")
            EpisodeIdentityClass.POSSIBLE -> listOf("Совпадают тайтл и номер; монтаж не проверен")
            EpisodeIdentityClass.UNKNOWN -> listOf("Недостаточно достоверных данных об эпизоде и монтаже")
            EpisodeIdentityClass.CONFLICT -> listOf("Противоречия: ${conflicts.joinToString()}")
        }, matches, conflicts, origins)
    }

    fun from(release: OnlineReleaseDetails, episode: OnlineEpisode): EpisodeIdentity {
        fun derived(regex: String) = EpisodeEvidence(Regex(regex, RegexOption.IGNORE_CASE)
            .find(listOfNotNull(release.name, release.englishName, episode.name).joinToString(" "))?.groupValues?.get(1), "provider text", false)
        val special = Regex("(?i)(ova|oad|special|recap|спешл|рекап|спецвыпуск)").find(episode.name.orEmpty())?.value
        return EpisodeIdentity(release.providerId, release.id, episode.id, release.externalIds, episode.ordinal,
            episode.name, episode.durationMs,
            derived("(?:season|сезон)\\s*(\\d+)"), derived("(?:part|часть)\\s*(\\d+)"),
            EpisodeEvidence(special?.lowercase() ?: release.type?.lowercase(), if (special != null) "episode name" else "provider type", special == null))
    }
}
