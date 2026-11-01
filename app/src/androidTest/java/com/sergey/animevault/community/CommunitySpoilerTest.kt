package com.sergey.animevault.community

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.sergey.animevault.data.community.CommunityCommentDto
import com.sergey.animevault.ui.community.CommunityCommentCard
import com.sergey.animevault.ui.theme.AnimeVaultTheme
import org.junit.Rule
import org.junit.Test

class CommunitySpoilerTest {
    @get:Rule val compose=createComposeRule()
    @Test fun spoilerBodyIsAbsentFromSemanticsUntilRevealed() {
        val comment=CommunityCommentDto("id",null,"author","username","User",null,"SECRET SPOILER","visible",true,"2026-01-01","2026-01-01",0,false,0)
        compose.setContent { AnimeVaultTheme {
            CommunityCommentCard(comment,"12",false,true,true,false,{},{},{},{},{},{})
        } }
        compose.onNodeWithText("SECRET SPOILER",useUnmergedTree=true).assertDoesNotExist()
        compose.onNodeWithText("Спойлер к серии 12").assertIsDisplayed()
        compose.onNodeWithText("Раскрыть спойлер").performClick()
        compose.onNodeWithText("SECRET SPOILER").assertIsDisplayed()
    }
}
