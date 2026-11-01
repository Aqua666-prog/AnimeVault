package com.sergey.animevault.ui.community

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.sergey.animevault.data.community.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.util.UUID

data class CommentsUiState(
    val context: CommunityContextDto? = null, val unavailable: Boolean = false,
    val comments: List<CommunityCommentDto> = emptyList(), val total: Int = 0,
    val path: List<CommunityCommentDto> = emptyList(), val sort: String = "new",
    val loading: Boolean = false, val sending: Boolean = false, val more: Boolean = false,
    val offline: Boolean = false, val message: String? = null, val draft: CommunityDraftDto = CommunityDraftDto(),
    val editing: CommunityCommentDto? = null, val user: String? = null, val liking: Set<String> = emptySet(),
)
class CommentsViewModel(private val services: CommunityServices, private val identity: CommunityIdentity) : ViewModel() {
    private val mutable = MutableStateFlow(CommentsUiState())
    val state = mutable.asStateFlow()
    private var loader: Job? = null
    private var draftSaver: Job? = null
    private var loadedCount = 0
    private var owner = "guest"
    init {
        viewModelScope.launch {
            services.sessions.session.map { it?.user }.distinctUntilChanged().collect { user ->
                draftSaver?.cancel(); owner = user ?: "guest"
                mutable.update { it.copy(user=user, editing=null, draft=CommunityDraftDto(), comments=emptyList()) }
                refresh()
            }
        }
    }
    private fun draftKey(s: CommentsUiState = mutable.value) = (s.context?.key ?: identity.toString()) + ":" + s.path.lastOrNull()?.id
    fun refresh(append: Boolean = false) {
        if (mutable.value.sending || mutable.value.liking.isNotEmpty()) return
        loader?.cancel()
        loader = viewModelScope.launch {
            val before = mutable.value
            mutable.update { it.copy(loading=true, message=null, offline=false, unavailable=false) }
            try {
                if (!services.configuration.ready) throw CommunityFailure("configuration")
                val context = services.identity.resolve(identity)
                if (context == null) {
                    mutable.update { it.copy(context=null,comments=emptyList(),loading=false,unavailable=true) }
                    return@launch
                }
                mutable.update { it.copy(context=context) }
                val offset = if (append) loadedCount else 0
                val page = services.comments.page(context,before.path.lastOrNull()?.id,before.sort,offset)
                loadedCount = offset + page.comments.size
                mutable.update { it.copy(comments=(if (append) it.comments+page.comments else page.comments).distinctBy { c -> c.id },total=page.total,
                    loading=false,more=page.comments.size==20,offline=false) }
                if (!append && before.editing == null) restoreDraft(before.draft.request)
            } catch (e: CancellationException) { throw e } catch (e: Exception) {
                // Only transport failures may show a cached mapping; a server rejection cannot resurrect a revoked alias.
                val cachedContext = if ((e as? CommunityFailure)?.kind == "offline") services.identity.cached(identity) else null
                val cached = cachedContext?.let { services.comments.cached(it,before.path.lastOrNull()?.id,before.sort,if (append) loadedCount else 0) }
                mutable.update { it.copy(context=cachedContext ?: it.context,loading=false,message=communityMessage(e),offline=cached!=null,
                    comments=if (cached != null) (if (append) it.comments+cached.comments else cached.comments).distinctBy { c -> c.id } else it.comments,
                    total=cached?.total ?: it.total,more=false) }
                if (cached != null && before.editing == null) restoreDraft(before.draft.request)
            }
        }
    }
    private suspend fun restoreDraft(expectedRequest: String? = null) {
        val savedOwner=owner
        val key=draftKey()
        val restored = services.cache.draft(savedOwner,key)
        if (owner==savedOwner && draftKey()==key) mutable.update {
            if (expectedRequest==null || it.draft.request==expectedRequest) it.copy(draft=restored) else it
        }
    }
    fun sort(value: String) { if (mutable.value.sending) return; mutable.update { it.copy(sort=value,comments=emptyList(),more=false) }; refresh() }
    fun openReplies(comment: CommunityCommentDto) {
        if (mutable.value.sending || mutable.value.path.size >= 2) return
        persistDraft()
        mutable.update { it.copy(path=it.path+comment,comments=emptyList(),editing=null,draft=CommunityDraftDto()) }; refresh()
    }
    fun up() {
        if (mutable.value.sending) return
        persistDraft()
        mutable.update { it.copy(path=it.path.dropLast(1),comments=emptyList(),editing=null,draft=CommunityDraftDto()) }; refresh()
    }
    fun draft(body: String = mutable.value.draft.body, spoiler: Boolean = mutable.value.draft.spoiler) {
        if (body.codePointCount(0,body.length)>2000 || mutable.value.sending) return
        mutable.update { it.copy(draft=it.draft.copy(body=body,spoiler=spoiler,request=UUID.randomUUID().toString())) }
        persistDraft()
    }
    fun persistDraft() {
        draftSaver?.cancel()
        val s = mutable.value
        if (s.editing != null) return
        val savedOwner = owner; val key = draftKey(s)
        draftSaver = viewModelScope.launch { services.cache.saveDraft(savedOwner,key,s.draft) }
    }
    fun edit(comment: CommunityCommentDto) {
        persistDraft()
        mutable.update { it.copy(editing=comment,draft=CommunityDraftDto(comment.body,comment.contains_spoiler)) }
    }
    fun cancelEdit() { mutable.update { it.copy(editing=null) }; viewModelScope.launch { restoreDraft() } }
    fun send() {
        val s = mutable.value
        val context = s.context ?: return
        val writer = s.user ?: return
        if (s.sending || normalizeCommunityBody(s.draft.body).isEmpty()) return
        mutable.update { it.copy(sending=true,message=null) }
        viewModelScope.launch {
            try {
                if (s.editing != null) services.comments.edit(s.editing.id,s.draft.body,s.draft.spoiler,writer)
                else services.comments.create(context,s.path.lastOrNull()?.id,s.draft,writer)
                draftSaver?.cancel()
                services.cache.saveDraft(writer,draftKey(s),CommunityDraftDto())
                mutable.update { if (it.user==writer) it.copy(draft=CommunityDraftDto(),editing=null,sending=false) else it.copy(sending=false) }
                refresh()
            } catch (e: CancellationException) { throw e } catch (e: Exception) { mutable.update { it.copy(sending=false,message=communityMessage(e)) } }
        }
    }
    fun delete(comment: CommunityCommentDto) = action { user -> services.comments.delete(comment.id,user) }
    fun report(comment: CommunityCommentDto, reason: String, description: String) = action { user -> services.moderation.report(comment.id,reason,description,user) }
    private fun action(block: suspend (String) -> Unit) {
        val writer=mutable.value.user ?: return
        if (mutable.value.sending) return
        mutable.update { it.copy(sending=true) }
        viewModelScope.launch {
            try { block(writer); mutable.update { it.copy(sending=false) }; refresh() } catch (e: CancellationException) { throw e } catch (e: Exception) { mutable.update { it.copy(sending=false,message=communityMessage(e)) } }
        }
    }
    fun like(comment: CommunityCommentDto) {
        val writer=mutable.value.user ?: return
        if (mutable.value.user == null || mutable.value.loading || mutable.value.sending || comment.id in mutable.value.liking) return
        mutable.update { s -> s.copy(liking=s.liking+comment.id,comments=s.comments.map { if (it.id==comment.id) it.copy(liked=!comment.liked,likes=comment.likes+if (comment.liked) -1 else 1) else it }) }
        viewModelScope.launch {
            try { services.likes.set(comment.id,!comment.liked,writer) } catch (e: CancellationException) { throw e } catch (e: Exception) { mutable.update { s -> s.copy(message=communityMessage(e),comments=s.comments.map { if (it.id==comment.id) comment else it }) } }
            finally { mutable.update { it.copy(liking=it.liking-comment.id) }; if (mutable.value.liking.isEmpty()) refresh() }
        }
    }
    class Factory(private val services: CommunityServices, private val identity: CommunityIdentity) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST") override fun <T : ViewModel> create(modelClass: Class<T>): T = CommentsViewModel(services,identity) as T
    }
}
