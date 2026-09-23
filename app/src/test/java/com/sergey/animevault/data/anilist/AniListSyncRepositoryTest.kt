package com.sergey.animevault.data.anilist

import com.google.common.truth.Truth.assertThat
import com.google.gson.Gson
import org.junit.Test

class AniListSyncRepositoryTest {
    private val gson = Gson()

    @Test
    fun fragmentParser_decodesToken() {
        val parsed = parseFragmentParameters("access_token=abc%20123&token_type=Bearer")
        assertThat(parsed["access_token"]).isEqualTo("abc 123")
    }

    @Test
    fun viewerParser_readsViewer() {
        val viewer = parseViewerResponse(
            gson,
            """{"data":{"Viewer":{"id":42,"name":"Sergey","avatar":{"medium":"https://img"}}}}""",
        )
        assertThat(viewer?.id).isEqualTo(42)
        assertThat(viewer?.name).isEqualTo("Sergey")
    }

    @Test
    fun listParser_readsProgress() {
        val entry = parseMediaListQueryResponse(
            gson,
            """{"data":{"Media":{"mediaListEntry":{"id":7,"mediaId":21,"status":"CURRENT","progress":4,"score":8.5,"repeat":0,"updatedAt":123}}}}""",
        )
        assertThat(entry?.status).isEqualTo(AniListListStatus.CURRENT)
        assertThat(entry?.progress).isEqualTo(4)
        assertThat(entry?.score).isEqualTo(8.5)
    }

    @Test(expected = IllegalStateException::class)
    fun graphqlError_isNotSilenced() {
        parseViewerResponse(gson, """{"errors":[{"message":"Invalid token"}]}""")
    }
}
