package com.sergey.animevault.data.update

import org.junit.Assert.assertThrows
import org.junit.Test

class ApkIdentityTest {
    private val installed = ApkIdentity(UPDATE_PACKAGE, 63, "3.0.3", 24, setOf("permanent-key"))
    private val archive = installed.copy(versionCode = 64, versionName = "3.0.4")
    private val update = AppUpdate(64, "3.0.4", 24, "ab".repeat(32),
        ReleaseAsset("AnimeVault.apk", "", 1024), GitHubRelease("", "", "", "", emptyList()))

    @Test fun acceptsSameSignerOnApi24() { verifyApkIdentity(installed, archive, update, 24) }
    @Test fun acceptsSameSignerOnModernAndroid() { verifyApkIdentity(installed, archive, update, 35) }

    @Test fun rejectsOtherCertificate() {
        assertThrows(UpdateException::class.java) {
            verifyApkIdentity(installed, archive.copy(signers = setOf("random-debug-key")), update, 35)
        }
    }

    @Test fun rejectsMissingOrAdditionalSigners() {
        listOf(emptySet(), setOf("permanent-key", "other")).forEach {
            assertThrows(UpdateException::class.java) { verifyApkIdentity(installed, archive.copy(signers = it), update, 24) }
        }
        assertThrows(UpdateException::class.java) { verifyApkIdentity(installed.copy(signers = emptySet()), archive, update, 24) }
    }

    @Test fun rejectsOtherPackage() {
        assertThrows(UpdateException::class.java) { verifyApkIdentity(installed, archive.copy(packageName = "com.other"), update, 35) }
    }

    @Test fun rejectsDowngradeOrSameVersion() {
        listOf(62L, 63L).forEach {
            assertThrows(UpdateException::class.java) {
                verifyApkIdentity(installed, archive.copy(versionCode = it), update.copy(versionCode = it), 35)
            }
        }
    }

    @Test fun rejectsMetadataMismatch() {
        assertThrows(UpdateException::class.java) { verifyApkIdentity(installed, archive.copy(versionCode = 65), update, 35) }
        assertThrows(UpdateException::class.java) { verifyApkIdentity(installed, archive.copy(versionName = "3.0.5"), update, 35) }
        assertThrows(UpdateException::class.java) { verifyApkIdentity(installed, archive.copy(minSdk = 26), update, 35) }
    }

    @Test fun rejectsUnsupportedAndroidVersion() {
        assertThrows(UpdateException::class.java) {
            verifyApkIdentity(installed, archive.copy(minSdk = 26), update.copy(minSdk = 26), 24)
        }
    }
}
