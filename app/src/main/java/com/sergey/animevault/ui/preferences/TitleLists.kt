package com.sergey.animevault.ui.preferences

/** UI list membership is separate from episode progress and online favourites. */
enum class VaultTitleList(val title: String) {
    NONE("Не добавлен в список"),
    PLANNED("Буду смотреть"),
    WATCHED("Просмотрено"),
    DROPPED("Брошено"),
}

fun vaultOnlineListKey(providerId: String, releaseId: String): String = "online:$providerId:$releaseId"
fun vaultLocalListKey(titleId: Long): String = "local:$titleId"
