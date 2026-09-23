package com.sergey.animevault.data.repository

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class BackupMergePolicyTest {
    @Test fun newerWinsChoosesIncomingWhenNewer() {
        assertThat(selectByTimestamp("current", "backup", 10L, 20L, BackupMergePolicy.NEWER_WINS))
            .isEqualTo("backup")
    }

    @Test fun newerWinsKeepsCurrentWhenCurrentIsNewer() {
        assertThat(selectByTimestamp("current", "backup", 30L, 20L, BackupMergePolicy.NEWER_WINS))
            .isEqualTo("current")
    }

    @Test fun explicitPoliciesAreDeterministic() {
        assertThat(selectByTimestamp("current", "backup", 30L, 20L, BackupMergePolicy.BACKUP_WINS))
            .isEqualTo("backup")
        assertThat(selectByTimestamp("current", "backup", 10L, 20L, BackupMergePolicy.CURRENT_WINS))
            .isEqualTo("current")
    }
}
