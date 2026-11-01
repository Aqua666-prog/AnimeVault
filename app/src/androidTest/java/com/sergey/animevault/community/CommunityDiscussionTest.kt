package com.sergey.animevault.community

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.sergey.animevault.data.community.*
import com.sergey.animevault.ui.community.*
import com.sergey.animevault.ui.theme.AnimeVaultTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Uses the same list content as the real title tab, not a separate preview implementation. */
class CommunityDiscussionTest {
    @get:Rule val compose=createComposeRule()
    private val context=CommunityContextDto("anime",null,null,null)
    private fun render(state: CommentsUiState,onAction:(CommunityCommentAction)->Unit={}) {
        compose.setContent {
            AnimeVaultTheme {
                val discussion=CommunityDiscussionPresentation(state,onAction)
                CommunityRefreshContainer(discussion) {
                    LazyColumn(Modifier.fillMaxSize()) { communityDiscussionItems(discussion) }
                }
            }
        }
    }
    @Test fun guestReadsActualListWithoutOpeningAnotherScreen() {
        val comment=CommunityCommentDto("id",null,"author","reader","Reader",null,"Комментарий под аниме","visible",false,"2026-11-01","2026-11-01",3,false,0)
        var accountRequested=false
        render(CommentsUiState(context=context,comments=listOf(comment),total=1)) {
            if(it==CommunityCommentAction.Account) accountRequested=true
        }
        compose.onNodeWithText("Комментарий под аниме").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Открыть обсуждение").assertDoesNotExist()
        compose.onNodeWithText("Войти и написать комментарий").performScrollTo().performClick()
        compose.runOnIdle {assertTrue(accountRequested)}
    }
    @Test fun signedInUserHasComposerAndSendActionInTheTitleList() {
        var sent=false
        render(CommentsUiState(context=context,user="author",draft=CommunityDraftDto("Мой комментарий"))) {
            if(it==CommunityCommentAction.Send) sent=true
        }
        compose.onNodeWithText("Мой комментарий").performScrollTo().assertIsDisplayed()
        compose.onNode(hasSetTextAction()).performClick()
        compose.onNodeWithText("Содержит спойлер").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Отправить").performScrollTo().performClick()
        compose.runOnIdle {assertTrue(sent)}
    }
    @Test fun episodeThreadShowsCanonicalEpisodeContext() {
        render(CommentsUiState(context=context.copy(episode_id="episode12",episode_key="regular:12",episode_number=12)))
        compose.onNodeWithText("Обсуждение серии 12").assertIsDisplayed()
    }
    @Test fun missingConfigurationExplainsThatNoServerIsConnected() {
        render(CommentsUiState(configurationMissing=true))
        compose.onNodeWithText("Сервис не настроен").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("В этой сборке сервер комментариев ещё не подключён.",substring=true).performScrollTo().assertIsDisplayed()
        compose.onNode(hasSetTextAction()).assertDoesNotExist()
    }
}
