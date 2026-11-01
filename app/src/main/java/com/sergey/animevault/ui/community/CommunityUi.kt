package com.sergey.animevault.ui.community

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.sergey.animevault.AnimeVaultApplication
import com.sergey.animevault.data.community.*

@Composable
private fun communityServices(): CommunityServices? =
    (LocalContext.current.applicationContext as? AnimeVaultApplication)?.container?.community

/** Uses the enclosing Cinematic theme; no separate navigation graph or player ownership. */
@Composable
fun CommunityEntry(identity: CommunityIdentity, label: String = "Обсуждение") {
    val services = communityServices()
    var dialogOpen by rememberSaveable(identity.toString()) { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(16.dp)) {
        Text(label, style=MaterialTheme.typography.titleMedium)
        if (services?.configuration?.ready != true) Text("Сервис не настроен")
        else TextButton(onClick={dialogOpen=true}, modifier=Modifier.heightIn(min=48.dp)) { Text("Открыть обсуждение") }
    }
    if (dialogOpen && services != null) {
        val vm: CommentsViewModel = viewModel(key="community:$identity",factory=CommentsViewModel.Factory(services,identity))
        CommunityDiscussion(vm, onClose={ vm.persistDraft(); dialogOpen=false })
    }
}

@Composable
fun CommunityAccountEntry() {
    val services = communityServices()
    var dialogOpen by rememberSaveable { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(20.dp)) {
        Text("AnimeVault Community",style=MaterialTheme.typography.titleMedium)
        Text("Отдельный аккаунт для обсуждений. Вход Google и медиатека работают независимо.")
        TextButton(onClick={dialogOpen=true},modifier=Modifier.heightIn(min=48.dp)) { Text("Аккаунт сообщества") }
    }
    if (dialogOpen && services != null) CommunityAccountDialog(services) { dialogOpen=false }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CommunityDiscussion(vm: CommentsViewModel, onClose: () -> Unit) {
    val s by vm.state.collectAsStateWithLifecycle()
    val services = communityServices() ?: return
    var account by remember { mutableStateOf(false) }
    var reporting by remember { mutableStateOf<CommunityCommentDto?>(null) }
    var deleting by remember { mutableStateOf<CommunityCommentDto?>(null) }
    var author by remember { mutableStateOf<String?>(null) }
    val lifecycle = LocalLifecycleOwner.current
    DisposableEffect(lifecycle,vm) {
        val observer = LifecycleEventObserver { _, event -> if (event==Lifecycle.Event.ON_RESUME) vm.refresh() }
        lifecycle.lifecycle.addObserver(observer)
        onDispose { lifecycle.lifecycle.removeObserver(observer); vm.persistDraft() }
    }
    FullCommunityDialog(onClose) {
        androidx.compose.material3.pulltorefresh.PullToRefreshBox(isRefreshing=s.loading, onRefresh={vm.refresh()}, modifier=Modifier.fillMaxSize()) {
            LazyColumn(Modifier.fillMaxSize().imePadding(),contentPadding=PaddingValues(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                item(key="header") {
                    Column {
                        Text(if (s.context?.episode_id != null) "Обсуждение серии ${s.context?.episode_number ?: s.context?.episode_key.orEmpty()}" else "Обсуждение аниме",style=MaterialTheme.typography.headlineSmall)
                        Text("Комментариев: ${s.total}")
                        FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick=onClose,enabled=!s.sending) { Text("Закрыть") }
                            TextButton(onClick={account=true},enabled=!s.sending) { Text(if (s.user==null) "Войти в сообщество" else "Мой профиль") }
                            TextButton(onClick={vm.refresh()},enabled=!s.loading && !s.sending) { Text("Обновить") }
                        }
                        Text("Гости могут читать. Для публикации нужен отдельный аккаунт Community.",style=MaterialTheme.typography.bodySmall)
                        if (s.path.isEmpty()) FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                            listOf("new" to "Новые","old" to "Старые","popular" to "Популярные").forEach { (key,label) ->
                                FilterChip(selected=s.sort==key,onClick={vm.sort(key)},enabled=!s.sending,label={Text(label)})
                            }
                        } else {
                            TextButton(onClick=vm::up,enabled=!s.sending) { Text("← ${if (s.path.size==1) "Все комментарии" else "Назад к ответам"}") }
                            Text("Ответы · @${s.path.last().username}")
                            Text("Чтобы ответить, напишите сообщение ниже. Ответы идут по времени публикации.")
                        }
                        if (s.context?.episode_id != null) TextButton(onClick={ onClose() }) { Text("Вернуться к тайтлу и общему обсуждению") }
                    }
                }
                if (s.offline) item(key="offline") { Text("Нет сети · сохранённые данные могут быть неактуальны.") }
                s.message?.let { message -> item(key="message") { Text(message,color=MaterialTheme.colorScheme.error) } }
                if (s.unavailable) item(key="unavailable") { Text("Обсуждение пока недоступно для этого тайтла или серии.") }
                if (s.context != null && s.user != null && !s.unavailable && (s.path.isEmpty() || s.path.last().status=="visible")) item(key="editor") {
                    Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
                        if (s.editing!=null) {
                            Text("Редактирование комментария")
                            TextButton(onClick=vm::cancelEdit,enabled=!s.sending) { Text("Отменить редактирование") }
                        }
                        OutlinedTextField(value=s.draft.body,onValueChange={vm.draft(body=it)},label={Text(if (s.path.isEmpty()) "Комментарий" else "Ответ")},
                            supportingText={Text("${s.draft.body.codePointCount(0,s.draft.body.length)}/2000")},enabled=!s.sending,modifier=Modifier.fillMaxWidth(),minLines=3,maxLines=8)
                        Row {
                            Checkbox(checked=s.draft.spoiler,onCheckedChange={vm.draft(spoiler=it)},enabled=!s.sending)
                            Text("Содержит спойлер",Modifier.padding(top=12.dp))
                        }
                        Button(onClick=vm::send,enabled=!s.sending && !s.offline && normalizeCommunityBody(s.draft.body).isNotEmpty()) {
                            Text(if (s.sending) "Отправка…" else if (s.editing!=null) "Сохранить" else "Отправить")
                        }
                    }
                }
                if (s.context!=null && s.comments.isEmpty() && !s.loading && !s.unavailable) item(key="empty") { Text("Здесь пока нет комментариев. Начните обсуждение.") }
                items(s.comments,key={it.id}) { comment ->
                    CommunityCommentCard(comment,episodeLabel=s.context?.let { if(it.episode_id!=null) it.episode_number?.toString() ?: it.episode_key else null },
                        own=comment.author_id==s.user,authenticated=s.user!=null,canReply=s.path.size<2,busy=s.sending || comment.id in s.liking,
                        onLike={vm.like(comment)},onReply={vm.openReplies(comment)},onEdit={vm.edit(comment)},
                        onDelete={deleting=comment},onReport={reporting=comment},onAuthor={author=comment.author_id})
                }
                if (s.loading) item(key="loading") { CircularProgressIndicator(Modifier.size(32.dp)) }
                if (s.more) item(key="more") { TextButton(onClick={vm.refresh(append=true)},enabled=!s.loading && !s.sending) { Text("Загрузить ещё") } }
            }
        }
    }
    if (account) CommunityAccountDialog(services) { account=false }
    author?.let { user -> PublicCommunityProfile(services,user) { author=null } }
    deleting?.let { comment -> AlertDialog(onDismissRequest={deleting=null},title={Text("Удалить комментарий?")},text={Text("Ответы других пользователей сохранятся.")},
        confirmButton={TextButton(onClick={vm.delete(comment);deleting=null}) { Text("Удалить") }},dismissButton={TextButton(onClick={deleting=null}) {Text("Отмена")}}) }
    reporting?.let { comment ->
        var reason by remember(comment.id) { mutableStateOf("spam") }
        var description by remember(comment.id) { mutableStateOf("") }
        FullCommunityDialog({reporting=null}) {
            LazyColumn(Modifier.fillMaxSize().imePadding(),contentPadding=PaddingValues(20.dp)) {
                item { Text("Пожаловаться",style=MaterialTheme.typography.headlineSmall) }
                items(listOf("spam" to "Спам","insult" to "Оскорбления","harassment" to "Травля","illegal" to "Недопустимый контент","spoiler" to "Спойлер без предупреждения","other" to "Другое")) { (key,label) ->
                    FilterChip(selected=reason==key,onClick={reason=key},label={Text(label)},modifier=Modifier.heightIn(min=48.dp))
                }
                item { OutlinedTextField(value=description,onValueChange={if(it.length<=500) description=it},label={Text("Описание (необязательно)")},modifier=Modifier.fillMaxWidth()) }
                item { Button(onClick={vm.report(comment,reason,description);reporting=null}) {Text("Отправить жалобу")} }
                item { TextButton(onClick={reporting=null}) {Text("Отмена")} }
            }
        }
    }
}

@Composable
internal fun CommunityCommentCard(comment: CommunityCommentDto,episodeLabel: String?,own: Boolean,authenticated: Boolean,canReply: Boolean,busy: Boolean,
    onLike: ()->Unit,onReply: ()->Unit,onEdit: ()->Unit,onDelete: ()->Unit,onReport: ()->Unit,onAuthor: ()->Unit) {
    var revealed by rememberSaveable(comment.id,comment.updated_at) { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    Surface(shape=MaterialTheme.shapes.large,color=MaterialTheme.colorScheme.surfaceContainer) {
        Column(Modifier.fillMaxWidth().padding(16.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
            TextButton(onClick=onAuthor,enabled=comment.author_id!=null) { Text("${communityAvatar(comment.avatar_path)} ${comment.display_name} · @${comment.username}") }
            Text(comment.created_at.take(16).replace('T',' ') + " UTC" + if(comment.updated_at!=comment.created_at && comment.status=="visible") " · изменён" else "",style=MaterialTheme.typography.labelSmall)
            // The concealed body is not composed, so TalkBack cannot read it before explicit disclosure.
            if (comment.contains_spoiler && !revealed) {
                Text(if(episodeLabel==null) "Содержит спойлер" else "Спойлер к серии $episodeLabel")
                TextButton(onClick={revealed=true}) { Text("Раскрыть спойлер") }
            } else Text(comment.body)
            FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                if(comment.status=="visible") TextButton(onClick=onLike,enabled=authenticated && !busy) { Text("${if(comment.liked) "♥" else "♡"} ${comment.likes} · ${if(comment.liked) "Убрать лайк" else "Нравится"}") }
                if(canReply && (comment.reply_count>0 || comment.status=="visible")) TextButton(onClick=onReply,enabled=!busy) { Text("Ответы: ${comment.reply_count}${if(authenticated && comment.status=="visible") " · Ответить" else ""}") }
                if(authenticated && comment.status=="visible") Box {
                    TextButton(onClick={menu=true},enabled=!busy) { Text("Ещё") }
                    DropdownMenu(expanded=menu,onDismissRequest={menu=false}) {
                        if(own) {
                            DropdownMenuItem(text={Text("Редактировать")},onClick={menu=false;onEdit()})
                            DropdownMenuItem(text={Text("Удалить")},onClick={menu=false;onDelete()})
                        }
                        DropdownMenuItem(text={Text("Пожаловаться")},onClick={menu=false;onReport()})
                    }
                }
                // Authors can erase their own hidden content as well.
                if(own && comment.status=="hidden") TextButton(onClick=onDelete,enabled=!busy) { Text("Удалить") }
            }
        }
    }
}
internal fun communityAvatar(avatar: String?): String = when(avatar) { "moon" -> "☾"; "star" -> "★"; "cat" -> "🐱"; else -> "●" }

@Composable
private fun FullCommunityDialog(onClose: ()->Unit, content: @Composable ()->Unit) {
    Dialog(onDismissRequest=onClose,properties=DialogProperties(usePlatformDefaultWidth=false)) {
        Surface(Modifier.fillMaxSize().systemBarsPadding(),color=MaterialTheme.colorScheme.background) {
            Box(Modifier.fillMaxSize(), contentAlignment=androidx.compose.ui.Alignment.TopCenter) {
                Box(Modifier.widthIn(max=840.dp).fillMaxHeight()) { content() }
            }
        }
    }
}

@Composable
private fun CommunityAccountDialog(services: CommunityServices,onClose: ()->Unit) {
    val vm: CommunityAccountViewModel = viewModel(key="community-account",factory=CommunityAccountViewModel.Factory(services))
    val s by vm.state.collectAsStateWithLifecycle()
    val recovery by services.sessions.recovery.collectAsStateWithLifecycle()
    val notice by services.sessions.notice.collectAsStateWithLifecycle()
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var currentPassword by remember { mutableStateOf("") }
    var otp by remember { mutableStateOf("") }
    var passwordNonce by remember { mutableStateOf("") }
    var username by remember(s.profile) { mutableStateOf(s.profile?.username.orEmpty()) }
    var name by remember(s.profile) { mutableStateOf(s.profile?.display_name.orEmpty()) }
    var bio by remember(s.profile) { mutableStateOf(s.profile?.bio.orEmpty()) }
    var avatar by remember(s.profile) { mutableStateOf(s.profile?.avatar_path ?: "default") }
    FullCommunityDialog(onClose) {
        LazyColumn(Modifier.fillMaxSize().imePadding(),contentPadding=PaddingValues(20.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            item { Text("Аккаунт сообщества",style=MaterialTheme.typography.headlineSmall) }
            item { TextButton(onClick=onClose) {Text("Закрыть")} }
            item { Text("Этот аккаунт нужен для комментариев. Он не заменяет Google/Firebase и не связывается с ним автоматически.") }
            if(!services.configuration.ready) item {Text("Сервис не настроен")}
            notice?.let { item { Text(it) } }
            s.message?.let { item { Text(it,color=MaterialTheme.colorScheme.primary) } }
            if(s.busy) item {CircularProgressIndicator(Modifier.size(32.dp))}
            if(s.user==null && services.configuration.ready) {
                item { OutlinedTextField(value=email,onValueChange={email=it.take(254)},label={Text("Email")},singleLine=true,modifier=Modifier.fillMaxWidth()) }
                item { OutlinedTextField(value=password,onValueChange={password=it.take(256)},label={Text("Пароль: от 12 символов, буквы и цифры")},visualTransformation=PasswordVisualTransformation(),singleLine=true,modifier=Modifier.fillMaxWidth()) }
                item { FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    Button(onClick={val e=email;val p=password;password="";vm.run {services.auth.signIn(e,p)}},enabled=!s.busy && email.isNotBlank() && password.isNotBlank()) {Text("Войти")}
                    OutlinedButton(onClick={val e=email;val p=password;password="";vm.run("Проверьте почту. Подтвердите email на этом устройстве или введите код из письма.") {services.auth.register(e,p)}},enabled=!s.busy && email.isNotBlank() && validCommunityPassword(password)) {Text("Регистрация")}
                } }
                item { TextButton(onClick={val e=email;vm.run("Если адрес зарегистрирован, будет отправлено письмо. Откройте его на этом устройстве.") {services.auth.recover(e)}},enabled=!s.busy && email.isNotBlank()) {Text("Восстановить пароль")} }
                item { Text("Если ссылка открылась в браузере, вернитесь сюда и введите 8-значный код из письма. Для публичной регистрации владелец должен настроить SMTP.") }
                item { OutlinedTextField(value=otp,onValueChange={otp=it.filter(Char::isDigit).take(8)},label={Text("Код подтверждения")},singleLine=true,modifier=Modifier.fillMaxWidth()) }
                item { Button(onClick={val e=email;val code=otp;otp="";vm.run {services.auth.verifyOtp(e,code)}},enabled=!s.busy && otp.length==8 && email.isNotBlank()) {Text("Подтвердить код")} }
            }
            if(s.user!=null) {
                item { TextButton(onClick=vm::reload,enabled=!s.busy) {Text("Обновить профиль")} }
                s.profile?.let { profile -> item {
                    Text("${communityAvatar(profile.avatar_path)} @${profile.username}",style=MaterialTheme.typography.titleLarge)
                    Text("Дата регистрации: ${profile.created_at.take(10)}")
                    Text("Комментариев: ${s.stats?.comments ?: 0} · Получено лайков: ${s.stats?.likes ?: 0}")
                } }
                item { Text(if(s.profile==null) "Создать профиль" else "Редактирование профиля",style=MaterialTheme.typography.titleMedium) }
                item { OutlinedTextField(value=username,onValueChange={username=it.take(24)},label={Text("Никнейм: 3–24 латинских символа, цифры и _")},singleLine=true,modifier=Modifier.fillMaxWidth()) }
                item { OutlinedTextField(value=name,onValueChange={name=it.take(60)},label={Text("Отображаемое имя")},modifier=Modifier.fillMaxWidth()) }
                item { OutlinedTextField(value=bio,onValueChange={bio=it.take(300)},label={Text("О себе (публично)")},modifier=Modifier.fillMaxWidth()) }
                item { FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    listOf("default","moon","star","cat").forEach { key -> FilterChip(selected=avatar==key,onClick={avatar=key},label={Text("${communityAvatar(key)} $key")}) }
                } }
                item { Button(onClick={vm.save(username,name,bio,avatar)},enabled=!s.busy && username.length>=3 && name.isNotBlank()) {Text("Сохранить профиль")} }
                if(recovery) {
                    item { Text("Восстановление пароля. Выполните проверку, которую требует сервер.") }
                    item { TextButton(onClick={vm.run("Проверьте почту: код проверки смены пароля") {services.auth.reauthenticate()}},enabled=!s.busy) {Text("Запросить код смены пароля")} }
                    item { OutlinedTextField(value=passwordNonce,onValueChange={passwordNonce=it.filter(Char::isDigit).take(8)},label={Text("Код проверки (если требуется)")},modifier=Modifier.fillMaxWidth()) }
                    item { OutlinedTextField(value=currentPassword,onValueChange={currentPassword=it.take(256)},label={Text("Текущий пароль (если требуется)")},visualTransformation=PasswordVisualTransformation(),modifier=Modifier.fillMaxWidth()) }
                    item { OutlinedTextField(value=password,onValueChange={password=it.take(256)},label={Text("Новый пароль")},visualTransformation=PasswordVisualTransformation(),modifier=Modifier.fillMaxWidth()) }
                    item { Button(onClick={val p=password;val old=currentPassword;val nonce=passwordNonce;password="";currentPassword="";passwordNonce="";vm.run("Пароль обновлён") {services.auth.updatePassword(p,old,nonce)}},enabled=!s.busy && validCommunityPassword(password)) {Text("Обновить пароль")} }
                }
                item { TextButton(onClick={vm.run {services.auth.signOut()}},enabled=!s.busy) {Text("Выйти из сообщества")} }
                item { Text("Для удаления Community Account и персональных данных обратитесь к владельцу сервиса. Google/Firebase-аккаунт удаляется отдельно.") }
            }
        }
    }
}

@Composable
private fun PublicCommunityProfile(services: CommunityServices,user: String,onClose: ()->Unit) {
    var profile by remember(user) { mutableStateOf<CommunityProfileDto?>(null) }
    var stats by remember(user) { mutableStateOf<CommunityStatsDto?>(null) }
    var message by remember(user) { mutableStateOf("Загрузка…") }
    LaunchedEffect(user) {
        try { profile=services.profiles.publicProfile(user); stats=services.profiles.stats(user); message=if(profile==null) "Профиль недоступен" else "" } catch(e: kotlinx.coroutines.CancellationException) {throw e} catch(e: Exception) {message=communityMessage(e)}
    }
    AlertDialog(onDismissRequest=onClose,title={Text(profile?.let {"${communityAvatar(it.avatar_path)} ${it.display_name}"} ?: "Профиль")},
        text={Column { Text(message); profile?.let {Text("@${it.username}\nРегистрация: ${it.created_at.take(10)}\n${it.bio.orEmpty()}")}; stats?.let {Text("Комментариев: ${it.comments} · Лайков: ${it.likes}")} }},
        confirmButton={TextButton(onClick=onClose) {Text("Закрыть")}})
}
