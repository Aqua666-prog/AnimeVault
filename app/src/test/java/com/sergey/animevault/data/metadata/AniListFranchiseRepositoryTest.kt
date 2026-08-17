package com.sergey.animevault.data.metadata

import com.google.common.truth.Truth.assertThat
import com.google.gson.Gson
import org.junit.Test

class AniListFranchiseRepositoryTest {
    @Test
    fun parser_flattensTwoRelationLevelsAndRecommendations() {
        val json = """
          {"data":{"Media":{"id":2,"title":{"romaji":"Root"},"seasonYear":2020,
            "relations":{"edges":[{"relationType":"PREQUEL","node":{"id":1,"title":{"romaji":"Pre"},"seasonYear":2018,
              "relations":{"edges":[{"relationType":"SIDE_STORY","node":{"id":3,"title":{"romaji":"Side"},"seasonYear":2019}}]}}}]},
            "recommendations":{"nodes":[{"rating":42,"mediaRecommendation":{"id":9,"title":{"romaji":"Rec"},"seasonYear":2021}}]}
          }}}
        """.trimIndent()
        val graph = parseAniListFranchiseResponse(Gson(), json)
        assertThat(graph.nodes.map { it.id }).containsExactly(2L, 1L, 3L)
        assertThat(graph.edges).hasSize(2)
        assertThat(graph.recommendations.single().media.id).isEqualTo(9L)
    }

    @Test
    fun chronology_placesPrequelBeforeRoot() {
        val graph = AniListFranchiseGraph(
            rootId = 2,
            nodes = listOf(
                AniListFranchiseNode(2, "Root", null, null, 2020, 12, null, null, null),
                AniListFranchiseNode(1, "Pre", null, null, 2019, 12, null, null, null),
                AniListFranchiseNode(3, "Seq", null, null, 2021, 12, null, null, null),
            ),
            edges = listOf(
                AniListFranchiseEdge(2, 1, AniListRelationType.PREQUEL),
                AniListFranchiseEdge(2, 3, AniListRelationType.SEQUEL),
            ),
            recommendations = emptyList(),
        )
        assertThat(graph.ordered(FranchiseOrderMode.CHRONOLOGY).map { it.id })
            .containsExactly(1L, 2L, 3L).inOrder()
    }
}
