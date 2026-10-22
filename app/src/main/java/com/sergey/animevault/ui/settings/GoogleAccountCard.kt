package com.sergey.animevault.ui.settings

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccountCircle
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.SubcomposeAsyncImage
import com.sergey.animevault.data.auth.GoogleCredentialClient
import com.sergey.animevault.ui.design.VaultPanel
import com.sergey.animevault.ui.design.VaultSurfaceRole
import kotlinx.coroutines.launch

@Composable
fun GoogleAccountRoute(viewModel: AccountViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val client = remember(context.applicationContext) { GoogleCredentialClient(context.applicationContext) }
    GoogleAccountCard(
        state = state,
        onSignIn = {
            scope.launch {
                viewModel.signIn {
                    client.getIdToken(checkNotNull(context.findActivity()))
                }
            }
        },
        onSignOut = { scope.launch { viewModel.signOut(client::clearState) } },
    )
}

@Composable
internal fun GoogleAccountCard(
    state: AccountUiState,
    onSignIn: () -> Unit,
    onSignOut: () -> Unit,
) {
    VaultPanel(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 3.dp),
        role = VaultSurfaceRole.Card,
        shape = RoundedCornerShape(22.dp),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            val user = state.user
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (user?.avatarUrl != null) {
                    SubcomposeAsyncImage(
                        model = user.avatarUrl,
                        contentDescription = "Аватар аккаунта Google",
                        modifier = Modifier.size(56.dp).clip(CircleShape),
                        contentScale = ContentScale.Crop,
                        loading = { AccountAvatar() },
                        error = { AccountAvatar() },
                    )
                } else {
                    AccountAvatar()
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(user?.name?.takeIf(String::isNotBlank) ?: if (user != null) "Аккаунт Google" else "Гостевой режим",
                        style = MaterialTheme.typography.titleMedium)
                    user?.email?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                }
            }
            Text(
                "Вход необязателен. Каталог, просмотр, загрузки и библиотека доступны без аккаунта",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (user != null) {
                Text(
                    "При выходе библиотека и скачанные видео останутся на устройстве",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedButton(onClick = onSignOut, enabled = !state.busy) { Text("Выйти из аккаунта") }
            } else {
                Button(onClick = onSignIn, enabled = !state.busy) { Text("Войти через Google") }
            }
            if (state.busy) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                Text(if (user == null) "Входим через Google…" else "Выходим из аккаунта…",
                    style = MaterialTheme.typography.bodySmall)
            }
            state.message?.let {
                Text(
                    it,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (state.isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

@Composable
private fun AccountAvatar() {
    Icon(
        Icons.Outlined.AccountCircle,
        contentDescription = null,
        modifier = Modifier.size(56.dp),
        tint = MaterialTheme.colorScheme.secondary,
    )
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
