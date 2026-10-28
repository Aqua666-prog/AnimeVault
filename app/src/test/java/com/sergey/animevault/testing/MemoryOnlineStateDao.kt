package com.sergey.animevault.testing

import com.sergey.animevault.data.online.*
import kotlinx.coroutines.flow.MutableStateFlow

class MemoryOnlineStateDao : OnlineStateDao {
    private val progress = MutableStateFlow(emptyList<OnlineProgressEntity>())
    private val library = MutableStateFlow(emptyList<OnlineLibraryEntity>())
    var beforeLibraryRead: suspend () -> Unit = {}
    override fun observeProgress() = progress
    override fun observeLibrary() = library
    override suspend fun getAllProgress() = progress.value
    override suspend fun getAllLibrary() = library.value
    override suspend fun getProgress(providerId: String, episodeId: String) = progress.value.firstOrNull {
        it.providerId == providerId && it.episodeId == episodeId
    }
    override suspend fun getLibrary(providerId: String, releaseId: String): OnlineLibraryEntity? {
        beforeLibraryRead()
        return library.value.firstOrNull { it.providerId == providerId && it.releaseId == releaseId }
    }
    override suspend fun upsertProgress(entity: OnlineProgressEntity) {
        progress.value = progress.value.filterNot { it.providerId == entity.providerId && it.episodeId == entity.episodeId } + entity
    }
    override suspend fun upsertProgress(entities: List<OnlineProgressEntity>) { entities.forEach { upsertProgress(it) } }
    override suspend fun upsertLibrary(entity: OnlineLibraryEntity) {
        library.value = library.value.filterNot { it.providerId == entity.providerId && it.releaseId == entity.releaseId } + entity
    }
    override suspend fun upsertLibrary(entities: List<OnlineLibraryEntity>) { entities.forEach { upsertLibrary(it) } }
    override suspend fun deleteLibrary(providerId: String, releaseId: String) {
        library.value = library.value.filterNot { it.providerId == providerId && it.releaseId == releaseId }
    }
    override suspend fun clearProgress() { progress.value = emptyList() }
    override suspend fun clearLibrary() { library.value = emptyList() }
}
