package com.sergey.animevault.data.community

import org.junit.Assert.*
import org.junit.Test
import com.google.gson.Gson

class CommunityContractTest {
    @Test fun absentConfigurationDisablesCommunityWithoutAffectingFirebase() {
        assertFalse(CommunityConfiguration(key="",enabled=true).ready)
        assertFalse(CommunityConfiguration(key="service_role",enabled=true).ready)
        assertFalse(CommunityConfiguration(url="https://evil.invalid",key="sb_publishable_example",enabled=true).ready)
        assertTrue(CommunityConfiguration(url="https://kgvkxocbkklisqrqmcjx.supabase.co",key="sb_publishable_example",enabled=true).ready)
    }
    @Test fun passwordsRequireTwelveCharactersLettersAndDigits() {
        assertFalse(validCommunityPassword("short12"))
        assertFalse(validCommunityPassword("abcdefghijkl"))
        assertFalse(validCommunityPassword("123456789012"))
        assertTrue(validCommunityPassword("abcdefghijkl12"))
    }
    @Test fun plainTextRetainsNewlinesAndDoesNotRenderMarkup() {
        assertEquals("hello\n<script>alert(1)</script>",normalizeCommunityBody(" \r\nhello\r\n<script>alert(1)</script>\t "))
        assertEquals("",normalizeCommunityBody(" \t\r\n"))
    }
    @Test fun retryKeepsRequestIdUntilPayloadChanges() {
        val draft = CommunityDraftDto("message")
        assertEquals(draft.request,draft.copy().request)
        assertNotEquals(draft.request,CommunityDraftDto("message").request)
    }
    @Test fun episodeContextNeverSharesGeneralCacheKey() {
        val general = CommunityContextDto("anime",null,null,null)
        val episode = CommunityContextDto("anime","episode-12","regular:12",12)
        assertNotEquals(general.key,episode.key)
        assertNotEquals(episode.key,episode.copy(anime_id="season-two").key)
    }
    @Test fun dtoMapsServerCountersAndLikeState() {
        val page = Gson().fromJson("""{"comments":[{"id":"c","username":"user","display_name":"User","body":"text","status":"visible","contains_spoiler":true,"created_at":"2026-01-01","updated_at":"2026-01-01","likes":4,"liked":true,"reply_count":2}],"total":1}""",CommunityPageDto::class.java)
        assertEquals(4,page.comments.single().likes)
        assertTrue(page.comments.single().liked)
        assertTrue(page.comments.single().contains_spoiler)
    }
    @Test fun safeErrorMessagesNeverIncludeServerPayload() {
        listOf("rate","banned","session","callback","configuration","offline","forbidden","server").forEach {
            assertFalse(communityMessage(CommunityFailure(it)).isBlank())
        }
        assertFalse(communityMessage(IllegalStateException("secret-jwt")).contains("secret-jwt"))
    }
}
