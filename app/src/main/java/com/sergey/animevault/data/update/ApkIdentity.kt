package com.sergey.animevault.data.update

internal data class ApkIdentity(
    val packageName: String,
    val versionCode: Long,
    val versionName: String?,
    val minSdk: Int,
    val signers: Set<String>,
)

internal fun verifyApkIdentity(installed: ApkIdentity, archive: ApkIdentity, update: AppUpdate, sdk: Int) {
    if (archive.packageName != UPDATE_PACKAGE || archive.packageName != installed.packageName) {
        throw UpdateException("APK предназначен для другого приложения.")
    }
    if (archive.versionCode != update.versionCode || archive.versionCode <= installed.versionCode ||
        archive.versionName != update.versionName) {
        throw UpdateException("Версия внутри APK не совпадает с релизом или уже установлена.")
    }
    if (archive.minSdk != update.minSdk || archive.minSdk > sdk) {
        throw UpdateException("APK несовместим с этой версией Android.")
    }
    if (installed.signers.isEmpty() || installed.signers != archive.signers) {
        throw UpdateException("Сертификат APK отличается от установленного AnimeVault. Обновление отклонено; удалять приложение не требуется.")
    }
}
