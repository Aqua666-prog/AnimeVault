package com.sergey.animevault.data.notifications

import com.google.common.truth.Truth.assertThat
import com.sergey.animevault.ui.preferences.VaultPersonalTitle
import com.sergey.animevault.ui.preferences.VaultTitleList
import org.junit.Test

class EpisodeAlertWorkerTest {
    @Test fun subscriptionsIncludeOnlyOptedInOnlineTitles() {
        val personal = mapOf("online:p:1" to VaultPersonalTitle(notifyEpisodes = true))
        val lists = mapOf("online:p:2" to VaultTitleList.WATCHING,
            "local:3" to VaultTitleList.WATCHING,
            "online:p:4" to VaultTitleList.PLANNED)
        val subs = alertSubscriptions(personal, lists, setOf(VaultTitleList.WATCHING))
        assertThat(subs.keys).containsExactly("online:p:1", "online:p:2")
    }

    @Test fun baselineDoesNotTriggerOldEpisodes() {
        assertThat(unseenEpisodeIds(VaultPersonalTitle(notifyEpisodes = true), listOf("1", "2"))).isEmpty()
    }

    @Test fun repeatedEpisodeDoesNotNotifyAgain() {
        val subscribed = VaultPersonalTitle(notifyEpisodes = true,
            baselineReady = true, seenEpisodeIds = listOf("1", "2"))
        assertThat(unseenEpisodeIds(subscribed, listOf("1", "2", "3", "3"))).containsExactly("3")
        assertThat(unseenEpisodeIds(subscribed.copy(seenEpisodeIds = listOf("1", "2", "3")),
            listOf("1", "2", "3"))).isEmpty()
    }
}
