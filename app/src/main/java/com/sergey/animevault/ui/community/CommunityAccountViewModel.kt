package com.sergey.animevault.ui.community

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.sergey.animevault.data.community.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

data class CommunityAccountState(val busy: Boolean = false, val message: String? = null,
    val user: String? = null, val profile: CommunityProfileDto? = null, val stats: CommunityStatsDto? = null)
class CommunityAccountViewModel(val services: CommunityServices) : ViewModel() {
    private val mutable = MutableStateFlow(CommunityAccountState())
    val state = mutable.asStateFlow()
    private var operationRunning=false
    init {
        viewModelScope.launch {
            services.sessions.session.map { it?.user }.distinctUntilChanged().collectLatest { user ->
                mutable.value = CommunityAccountState(user=user,busy=operationRunning)
                if (user != null) {
                    try {
                        val profile = services.profiles.own()
                        val stats = profile?.let { services.profiles.stats(it.user_id) }
                        mutable.update { it.copy(profile=profile,stats=stats) }
                    } catch (e: CancellationException) { throw e } catch (e: Exception) { mutable.update { it.copy(message=communityMessage(e)) } }
                }
            }
        }
    }
    fun run(success: String? = null, operation: suspend () -> Unit) {
        if (operationRunning) return
        operationRunning=true
        mutable.update { it.copy(busy=true,message=null) }
        viewModelScope.launch {
            try { operation(); mutable.update { it.copy(busy=false,message=success) } } catch (e: CancellationException) { throw e } catch (e: Exception) { mutable.update { it.copy(busy=false,message=communityMessage(e)) } }
            finally { operationRunning=false;mutable.update {it.copy(busy=false)} }
        }
    }
    fun reload() {
        val user=mutable.value.user ?: return
        run {
            val profile=services.profiles.own()
            val stats=profile?.let {services.profiles.stats(it.user_id)}
            mutable.update {if(it.user==user) it.copy(profile=profile,stats=stats) else it}
        }
    }
    fun save(username: String, name: String, bio: String, avatar: String) {
        val user=mutable.value.user ?: return
        run("Профиль сохранён") {
            val profile = services.profiles.save(username,name,bio,avatar,user)
            val stats=services.profiles.stats(profile.user_id)
            mutable.update { if (it.user==user) it.copy(profile=profile,stats=stats) else it }
        }
    }
    class Factory(private val services: CommunityServices) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST") override fun <T : ViewModel> create(modelClass: Class<T>): T = CommunityAccountViewModel(services) as T
    }
}
