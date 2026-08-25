package com.jitong.im.android.ui

import android.graphics.Bitmap
import android.graphics.Color
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.AdminPanelSettings
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.CameraAlt
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.GroupAdd
import androidx.compose.material.icons.outlined.Image as ImageIcon
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Logout
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material.icons.outlined.PersonAdd
import androidx.compose.material.icons.outlined.PersonRemove
import androidx.compose.material.icons.outlined.QrCode2
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material.icons.outlined.Send
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.SmartToy
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.zxing.BarcodeFormat
import com.google.zxing.MultiFormatWriter
import com.jitong.im.android.auth.SessionState
import com.jitong.im.android.contact.ContactRequestSummary
import com.jitong.im.android.contact.ContactSearchResult
import com.jitong.im.android.contact.ContactSummary
import com.jitong.im.android.ui.ContactUiState
import com.jitong.im.android.contact.ConversationSummary
import com.jitong.im.android.group.GroupGovernancePolicy
import com.jitong.im.android.group.GroupMemberSummary
import com.jitong.im.android.group.GroupSearchResult
import com.jitong.im.android.group.GroupSummary
import com.jitong.im.android.local.LocalAiActionItemEntity
import com.jitong.im.android.local.LocalMessageEntity
import java.io.ByteArrayOutputStream
import java.util.UUID

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun JitongApp(
    viewModel: AuthViewModel,
    contactViewModel: ContactViewModel,
    messageViewModel: MessageViewModel,
    aiViewModel: AiViewModel,
    avatarViewModel: AvatarViewModel,
    groupViewModel: GroupViewModel,
) {
    val sessionState by viewModel.sessionState.collectAsStateWithLifecycle()
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        when (val current = sessionState) {
            SessionState.SignedOut -> {
                LaunchedEffect(Unit) {
                    messageViewModel.clearForLogout()
                    aiViewModel.clearForLogout()
                }
                LoginScreen(viewModel)
            }
            SessionState.Restoring -> RestoringScreen()
            is SessionState.ReplacementRequired -> ReplacementScreen(current, viewModel)
            is SessionState.PasswordChangeRequired -> PasswordChangeScreen(current, viewModel)
            is SessionState.SignedIn -> HomeScreen(
                state = current,
                authViewModel = viewModel,
                contactViewModel = contactViewModel,
                messageViewModel = messageViewModel,
                aiViewModel = aiViewModel,
                avatarViewModel = avatarViewModel,
                groupViewModel = groupViewModel,
            )
            is SessionState.Error -> LoginScreen(viewModel, current.message, current.registration)
        }
    }
}

@Composable
private fun LoginScreen(
    viewModel: AuthViewModel,
    error: String? = null,
    initialRegistration: Boolean = false,
) {
    var isRegistering by rememberSaveable { mutableStateOf(initialRegistration) }
    var displayName by rememberSaveable { mutableStateOf("") }
    var accountNo by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }

    Box(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState()),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 28.dp, vertical = 40.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Box(
                Modifier
                    .size(74.dp)
                    .clip(RoundedCornerShape(24.dp))
                    .background(JitongColors.blue),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Outlined.SmartToy, contentDescription = null, tint = androidx.compose.ui.graphics.Color.White, modifier = Modifier.size(38.dp))
            }
            Text("即通", style = MaterialTheme.typography.headlineLarge)
            Text("简单、可靠的跨端聊天", color = JitongColors.secondaryText)
            Spacer(Modifier.height(10.dp))
            Surface(
                Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(22.dp),
                color = MaterialTheme.colorScheme.surface,
                shadowElevation = 2.dp,
            ) {
                Column(Modifier.padding(22.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(if (isRegistering) "注册新用户" else "登录", style = MaterialTheme.typography.titleLarge)
                            Text(
                                if (isRegistering) "设置资料后即可开始使用即通" else "使用你的即通账号继续",
                                color = JitongColors.secondaryText,
                            )
                        }
                        TextButton(onClick = {
                            isRegistering = !isRegistering
                            accountNo = ""
                            password = ""
                            displayName = ""
                        }) {
                            Text(if (isRegistering) "返回登录" else "注册账号")
                        }
                    }
                    if (isRegistering) {
                        OutlinedTextField(
                            value = displayName,
                            onValueChange = { displayName = it.take(128) },
                            label = { Text("昵称") },
                            placeholder = { Text("输入你的昵称") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    } else {
                        OutlinedTextField(
                            value = accountNo,
                            onValueChange = { accountNo = it.filter(Char::isDigit).take(11) },
                            label = { Text("账号") },
                            placeholder = { Text("输入 11 位账号") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it },
                        label = { Text(if (isRegistering) "设置密码" else "密码") },
                        placeholder = { if (isRegistering) Text("至少 8 位") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    error?.let {
                        Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }
                    Button(
                        onClick = {
                            if (isRegistering) {
                                viewModel.register(displayName, password)
                            } else {
                                viewModel.login(accountNo, password)
                            }
                        },
                        enabled = if (isRegistering) {
                            displayName.isNotBlank() && password.length >= 8
                        } else {
                            accountNo.length == 11 && password.isNotBlank()
                        },
                        modifier = Modifier.fillMaxWidth().height(50.dp),
                        shape = RoundedCornerShape(14.dp),
                    ) { Text(if (isRegistering) "注册并进入" else "登录") }
                    if (isRegistering) {
                        Text(
                            "注册成功后，系统会自动分配一个 11 位账号，请妥善保存。",
                            style = MaterialTheme.typography.bodySmall,
                            color = JitongColors.secondaryText,
                        )
                    }
                }
            }
            Text(
                "你的聊天记录保存在本机加密空间中",
                style = MaterialTheme.typography.bodySmall,
                color = JitongColors.tertiaryText,
            )
        }
    }
}

@Composable
private fun PasswordChangeScreen(state: SessionState.PasswordChangeRequired, viewModel: AuthViewModel) {
    var currentPassword by rememberSaveable { mutableStateOf("") }
    var newPassword by rememberSaveable { mutableStateOf("") }
    val canCancel = !state.temporaryPasswordRequired
    BackHandler(enabled = canCancel, onBack = viewModel::cancelPasswordChange)

    AuthFlowScaffold(
        title = if (state.temporaryPasswordRequired) "设置新密码" else "修改密码",
        subtitle = "为了保护账号安全，请完成密码设置",
        onBack = if (canCancel) viewModel::cancelPasswordChange else null,
    ) {
        Text(
            if (state.temporaryPasswordRequired) "首次登录需要把临时密码替换成长期密码。"
            else "修改后，其他设备会立即退出登录。",
            color = JitongColors.secondaryText,
        )
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            currentPassword,
            { currentPassword = it },
            label = { Text(if (state.temporaryPasswordRequired) "临时密码" else "当前密码") },
            visualTransformation = PasswordVisualTransformation(),
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            newPassword,
            { newPassword = it },
            label = { Text("新密码") },
            visualTransformation = PasswordVisualTransformation(),
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Button(
            onClick = { viewModel.changePassword(currentPassword, newPassword) },
            enabled = currentPassword.isNotBlank() && newPassword.length >= 8,
            modifier = Modifier.fillMaxWidth().height(50.dp),
            shape = RoundedCornerShape(14.dp),
        ) { Text("保存新密码") }
        if (canCancel) {
            OutlinedButton(
                onClick = viewModel::cancelPasswordChange,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("取消修改")
            }
        } else {
            OutlinedButton(
                onClick = viewModel::logout,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("退出登录")
            }
        }
    }
}

@Composable
private fun ReplacementScreen(state: SessionState.ReplacementRequired, viewModel: AuthViewModel) {
    AuthFlowScaffold(title = "替换旧设备", subtitle = "这个账号已有一台受信任的手机") {
        Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.primaryContainer) {
            Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(Icons.Outlined.Security, contentDescription = null, tint = JitongColors.blue)
                Text("确认后，旧设备下次联网时会失效并清除本地数据。", color = JitongColors.blueDark)
            }
        }
        Button(
            onClick = { viewModel.confirmReplacement(state.challenge) },
            modifier = Modifier.fillMaxWidth().height(50.dp),
            shape = RoundedCornerShape(14.dp),
        ) { Text("确认替换") }
        TextButton(onClick = viewModel::logout, modifier = Modifier.align(Alignment.CenterHorizontally)) { Text("取消") }
    }
}

@Composable
private fun RestoringScreen() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
            CircularProgressIndicator(color = JitongColors.blue)
            Text("正在恢复登录会话", color = JitongColors.secondaryText)
        }
    }
}

@Composable
private fun AuthFlowScaffold(
    title: String,
    subtitle: String,
    onBack: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 40.dp),
        verticalArrangement = Arrangement.Center,
        content = {
            if (onBack != null) {
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Outlined.ArrowBack, contentDescription = "返回")
                    }
                    TextButton(onClick = onBack) {
                        Text("返回")
                    }
                }
                Spacer(Modifier.height(4.dp))
            }
            Text(title, style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.height(6.dp))
            Text(subtitle, color = JitongColors.secondaryText)
            Spacer(Modifier.height(24.dp))
            Surface(
                Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(22.dp),
                color = MaterialTheme.colorScheme.surface,
                shadowElevation = 2.dp,
            ) {
                Column(Modifier.padding(22.dp), verticalArrangement = Arrangement.spacedBy(14.dp), content = content)
            }
        },
    )
}

@Composable
private fun HomeScreen(
    state: SessionState.SignedIn,
    authViewModel: AuthViewModel,
    contactViewModel: ContactViewModel,
    messageViewModel: MessageViewModel,
    aiViewModel: AiViewModel,
    avatarViewModel: AvatarViewModel,
    groupViewModel: GroupViewModel,
) {
    val contactState by contactViewModel.state.collectAsStateWithLifecycle()
    val avatarState by avatarViewModel.state.collectAsStateWithLifecycle()
    val messageState by messageViewModel.state.collectAsStateWithLifecycle()
    val aiState by aiViewModel.state.collectAsStateWithLifecycle()
    val groupState by groupViewModel.state.collectAsStateWithLifecycle()
    var selectedTabName by rememberSaveable { mutableStateOf(JitongTab.Messages.name) }
    var contactSection by rememberSaveable { mutableStateOf("friends") }
    var groupSection by rememberSaveable { mutableStateOf("mine") }
    var selectedConversationId by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedGroupConversationId by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedMessageId by rememberSaveable { mutableStateOf<String?>(null) }
    var openGroupManagementId by rememberSaveable { mutableStateOf<String?>(null) }
    var showQuickAdd by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(state.session.userId) {
        selectedConversationId = null
        selectedGroupConversationId = null
        selectedMessageId = null
        messageViewModel.clearForLogout()
        aiViewModel.clearForLogout()
        contactViewModel.refresh()
        avatarViewModel.refresh()
        groupViewModel.refresh()
    }
    LaunchedEffect(groupState.autoResolveInvite) {
        if (groupState.autoResolveInvite) selectedTabName = JitongTab.Groups.name
    }

    val selectedConversation = contactState.conversations.firstOrNull { it.conversationId.toString() == selectedConversationId }
    val selectedGroup = groupState.groups.firstOrNull { it.conversationId.toString() == selectedGroupConversationId }
    if (selectedConversation != null) {
        ConversationScreen(
            conversation = selectedConversation,
            viewModel = messageViewModel,
            aiState = aiState,
            aiViewModel = aiViewModel,
            loadAvatar = avatarViewModel::loadUserAvatar,
            initialMessageId = selectedMessageId,
            onBack = { selectedConversationId = null; selectedMessageId = null },
        )
        return
    }
    if (selectedGroup != null) {
        GroupConversationScreen(
            group = selectedGroup,
            viewModel = messageViewModel,
            loadGroupAvatar = avatarViewModel::loadGroupAvatar,
            onBack = { selectedGroupConversationId = null; selectedMessageId = null },
        )
        return
    }

    val selectedTab = runCatching { JitongTab.valueOf(selectedTabName) }.getOrDefault(JitongTab.Messages)
    Scaffold(
        containerColor = if (selectedTab == JitongTab.Messages || selectedTab == JitongTab.Groups) JitongColors.page else MaterialTheme.colorScheme.background,
        bottomBar = {
            JitongBottomBar(
                selected = selectedTab,
                onSelected = {
                    selectedTabName = it.name
                    selectedConversationId = null
                    selectedGroupConversationId = null
                    selectedMessageId = null
                    openGroupManagementId = null
                    showQuickAdd = false
                },
                unreadCount = contactState.requests.count { it.incoming && it.status == "PENDING" },
            )
        },
    ) { padding ->
        when (selectedTab) {
            JitongTab.Messages -> MessagesHome(
                conversations = contactState.conversations,
                onOpen = {
                    selectedConversationId = it.toString()
                    selectedGroupConversationId = null
                },
                onAddContact = {
                    contactSection = "search"
                    selectedTabName = JitongTab.Contacts.name
                },
                onOpenQuickAdd = { showQuickAdd = true },
                loadAvatar = avatarViewModel::loadUserAvatar,
                onSearch = { query -> messageViewModel.setSearchQuery(query); messageViewModel.search() },
                messageState = messageState,
                onOpenSearchResult = { id, msgId -> selectedConversationId = id.toString(); selectedMessageId = msgId },
                modifier = Modifier.padding(padding),
            )
            JitongTab.Contacts -> ContactsHome(
                state = contactState,
                viewModel = contactViewModel,
                loadAvatar = avatarViewModel::loadUserAvatar,
                onOpenConversation = { selectedConversationId = it.toString() },
                selectedSection = contactSection,
                onSectionSelected = { contactSection = it },
                modifier = Modifier.padding(padding),
            )
            JitongTab.Groups -> GroupsHome(
                state = groupState,
                viewModel = groupViewModel,
                loadGroupAvatar = avatarViewModel::loadGroupAvatar,
                loadSearchAvatar = avatarViewModel::loadGroupAvatarUrl,
                onOpenGroup = {
                    selectedGroupConversationId = it.conversationId.toString()
                    selectedConversationId = null
                },
                onManageGroup = { openGroupManagementId = it.conversationId.toString() },
                selectedSection = groupSection,
                onSectionSelected = { groupSection = it },
                modifier = Modifier.padding(padding),
            )
            JitongTab.Me -> MeHome(
                session = state.session,
                avatarState = avatarState,
                avatarViewModel = avatarViewModel,
                authViewModel = authViewModel,
                modifier = Modifier.padding(padding),
            )
        }
    }

    val managementGroup = groupState.groups.firstOrNull { it.conversationId.toString() == openGroupManagementId }
    if (managementGroup != null) {
        GroupManagementSheet(
            group = managementGroup,
            state = groupState,
            viewModel = groupViewModel,
            onDismiss = { openGroupManagementId = null },
        )
    }
    if (showQuickAdd) {
        QuickAddSheet(
            onDismiss = { showQuickAdd = false },
            onAddContact = {
                showQuickAdd = false
                contactSection = "search"
                selectedTabName = JitongTab.Contacts.name
            },
            onSearchGroup = {
                showQuickAdd = false
                groupSection = "search"
                selectedTabName = JitongTab.Groups.name
            },
            onCreateGroup = {
                showQuickAdd = false
                groupSection = "create"
                selectedTabName = JitongTab.Groups.name
            },
        )
    }
}

@Composable
private fun MessagesHome(
    conversations: List<ConversationSummary>,
    onOpen: (UUID) -> Unit,
    onAddContact: () -> Unit,
    onOpenQuickAdd: () -> Unit,
    loadAvatar: suspend (UUID, Long) -> ByteArray?,
    onSearch: (String) -> Unit,
    messageState: MessageUiState,
    onOpenSearchResult: (UUID, String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var searchMode by rememberSaveable { mutableStateOf(false) }
    Column(modifier.fillMaxSize()) {
        JitongHeader(
            title = "消息",
            subtitle = "和朋友保持联系",
            actions = {
                IconButton(onClick = { searchMode = !searchMode }) { Icon(Icons.Outlined.Search, contentDescription = "搜索聊天记录") }
                IconButton(onClick = onOpenQuickAdd) { Icon(Icons.Outlined.Add, contentDescription = "添加") }
            },
        )
        if (searchMode) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                JitongSearchBar(
                    value = messageState.searchQuery,
                    onValueChange = onSearch,
                    placeholder = "搜索本地聊天记录",
                )
                if (messageState.searchQuery.isNotBlank()) {
                    LocalSearchResults(
                        state = messageState,
                        conversations = conversations,
                        onOpen = onOpenSearchResult,
                    )
                }
            }
        }
        if (!searchMode || messageState.searchQuery.isBlank()) {
            if (conversations.isEmpty()) {
                EmptyState(
                    icon = Icons.Outlined.ChatBubbleOutline,
                    title = "还没有聊天",
                    subtitle = "添加联系人后，就可以在这里开始聊天",
                    actionLabel = "添加联系人",
                    onAction = onAddContact,
                )
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 8.dp),
                ) {
                    items(conversations, key = { it.conversationId }) { conversation ->
                        ConversationRow(conversation, loadAvatar, onClick = { onOpen(conversation.conversationId) })
                    }
                }
            }
        }
    }
}

@Composable
private fun ContactsHome(
    state: ContactUiState,
    viewModel: ContactViewModel,
    loadAvatar: suspend (UUID, Long) -> ByteArray?,
    onOpenConversation: (UUID) -> Unit,
    selectedSection: String,
    onSectionSelected: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxSize()) {
        JitongHeader(
            title = "联系人",
            subtitle = "好友与联系人申请",
            actions = {
                IconButton(onClick = { onSectionSelected("search") }) {
                    Icon(Icons.Outlined.PersonAdd, contentDescription = "添加联系人")
                }
            },
        )
        HomeSectionTabs(
            items = listOf("friends" to "好友", "requests" to "申请", "search" to "添加"),
            selected = selectedSection,
            onSelected = onSectionSelected,
        )
        when (selectedSection) {
            "requests" -> ContactRequestsContent(state, viewModel)
            "search" -> ContactSearchContent(state, viewModel)
            else -> ContactListContent(
                state = state,
                viewModel = viewModel,
                loadAvatar = loadAvatar,
                onOpenConversation = onOpenConversation,
                onAddContact = { onSectionSelected("search") },
            )
        }
    }
}

@Composable
private fun GroupsHome(
    state: GroupUiState,
    viewModel: GroupViewModel,
    loadGroupAvatar: suspend (UUID, Long) -> ByteArray?,
    loadSearchAvatar: suspend (String) -> ByteArray?,
    onOpenGroup: (GroupSummary) -> Unit,
    onManageGroup: (GroupSummary) -> Unit,
    selectedSection: String,
    onSectionSelected: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    LaunchedEffect(state.autoResolveInvite) {
        if (state.autoResolveInvite) onSectionSelected("invite")
    }
    Column(modifier.fillMaxSize()) {
        JitongHeader(
            title = "群聊",
            subtitle = "一起聊天，一起协作",
            actions = {
                IconButton(onClick = { onSectionSelected("create") }) {
                    Icon(Icons.Outlined.GroupAdd, contentDescription = "创建群聊")
                }
            },
        )
        HomeSectionTabs(
            items = listOf("mine" to "我的群", "search" to "找群", "invite" to "邀请", "create" to "建群"),
            selected = selectedSection,
            onSelected = onSectionSelected,
        )
        when (selectedSection) {
            "search" -> GroupSearchContent(state, viewModel, loadSearchAvatar)
            "invite" -> GroupInviteContent(state, viewModel)
            "create" -> GroupCreateContent(state, viewModel)
            else -> GroupListContent(
                state = state,
                viewModel = viewModel,
                load = loadGroupAvatar,
                onOpenGroup = onOpenGroup,
                onManageGroup = onManageGroup,
                onCreateGroup = { onSectionSelected("create") },
            )
        }
    }
}

@Composable
private fun MeHome(
    session: com.jitong.im.android.auth.SessionSnapshot,
    avatarState: AvatarUiState,
    avatarViewModel: AvatarViewModel,
    authViewModel: AuthViewModel,
    modifier: Modifier = Modifier,
) {
    var showDeleteDialog by rememberSaveable { mutableStateOf(false) }
    var deletePassword by rememberSaveable { mutableStateOf("") }
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        JitongHeader(title = "我的", subtitle = "账号与安全")
        Surface(
            Modifier.fillMaxWidth().padding(16.dp),
            shape = RoundedCornerShape(22.dp),
            color = JitongColors.blue,
        ) {
            Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                AvatarView(
                    bytes = avatarState.bytes,
                    fallback = avatarState.profile?.avatarFallback ?: session.accountNo.takeLast(2),
                    size = 72.dp,
                )
                Column(Modifier.weight(1f)) {
                    Text("即通用户", style = MaterialTheme.typography.titleLarge, color = androidx.compose.ui.graphics.Color.White)
                    Text(session.accountNo, color = androidx.compose.ui.graphics.Color.White.copy(alpha = .8f))
                    Text("账号已受保护", style = MaterialTheme.typography.bodySmall, color = androidx.compose.ui.graphics.Color.White.copy(alpha = .75f))
                }
                Icon(Icons.Outlined.Edit, contentDescription = "编辑头像", tint = androidx.compose.ui.graphics.Color.White)
            }
        }
        SectionLabel("个人资料")
        SoftCard(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            AvatarProfilePanel(avatarState, avatarViewModel, compact = true)
        }
        SectionLabel("账号与安全")
        SoftCard(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            Column {
                SettingsRow(
                    icon = Icons.Outlined.Security,
                    title = "修改密码",
                    subtitle = "定期更新密码，保护账号",
                    onClick = authViewModel::requestPasswordChange,
                )
                ListDivider()
                SettingsRow(
                    icon = Icons.Outlined.Settings,
                    title = "登录设备",
                    subtitle = "当前设备：手机",
                    onClick = {},
                )
                ListDivider()
                SettingsRow(
                    icon = Icons.Outlined.Logout,
                    title = "退出登录",
                    subtitle = "保留本机加密历史",
                    onClick = authViewModel::logout,
                )
            }
        }
        SectionLabel("危险操作")
        SoftCard(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            Column {
                SettingsRow(
                    icon = Icons.Outlined.DeleteOutline,
                    title = "清除本机数据",
                    subtitle = "删除聊天记录和媒体缓存",
                    danger = true,
                    onClick = authViewModel::clearData,
                )
                ListDivider()
                SettingsRow(
                    icon = Icons.Outlined.WarningAmber,
                    title = "永久注销账号",
                    subtitle = "注销后账号和云端数据不可恢复",
                    danger = true,
                    onClick = { showDeleteDialog = true },
                )
            }
        }
        Spacer(Modifier.height(24.dp))
    }
    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text("永久注销账号") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("该操作不可撤销。请输入当前密码确认。", color = JitongColors.secondaryText)
                    OutlinedTextField(
                        value = deletePassword,
                        onValueChange = { deletePassword = it },
                        label = { Text("当前密码") },
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true,
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = { authViewModel.deleteAccount(deletePassword); deletePassword = ""; showDeleteDialog = false },
                    enabled = deletePassword.isNotBlank(),
                ) { Text("确认注销", color = JitongColors.danger) }
            },
            dismissButton = { TextButton(onClick = { showDeleteDialog = false }) { Text("取消") } },
        )
    }
}

@Composable
private fun ConversationRow(conversation: ConversationSummary, loadAvatar: suspend (UUID, Long) -> ByteArray?, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 18.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        RemoteAvatar(
            userId = conversation.peerUserId,
            avatarVersion = conversation.avatarVersion,
            fallback = conversation.avatarFallback,
            load = loadAvatar,
            size = 54.dp,
        )
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(conversation.peerDisplayName, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                if (conversation.status == "READ_ONLY") "历史消息，只读"
                else "点击进入聊天",
                color = JitongColors.secondaryText,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (conversation.blockedByMe) Icon(Icons.Outlined.Block, contentDescription = "已拉黑", tint = JitongColors.tertiaryText, modifier = Modifier.size(18.dp))
    }
    Divider(color = JitongColors.divider, modifier = Modifier.padding(start = 84.dp))
}

@Composable
private fun ContactListContent(
    state: ContactUiState,
    viewModel: ContactViewModel,
    loadAvatar: suspend (UUID, Long) -> ByteArray?,
    onOpenConversation: (UUID) -> Unit,
    onAddContact: () -> Unit,
) {
    if (state.contacts.isEmpty()) {
        EmptyState(
            icon = Icons.Outlined.PersonAdd,
            title = "还没有联系人",
            subtitle = "搜索账号并发送好友申请",
            actionLabel = "添加联系人",
            onAction = onAddContact,
        )
        return
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 8.dp)) {
        items(state.contacts, key = { it.userId }) { contact -> ContactRow(contact, viewModel, loadAvatar, onOpenConversation) }
        val readOnly = state.conversations.filter { it.status == "READ_ONLY" }
        if (readOnly.isNotEmpty()) item { SectionLabel("历史联系人") }
        items(readOnly, key = { it.conversationId }) { conversation ->
            Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(conversation.peerDisplayName, style = MaterialTheme.typography.titleMedium)
                    Text("历史消息，只读", color = JitongColors.secondaryText)
                }
                if (conversation.blockedByMe) TextButton(onClick = { viewModel.unblock(conversation.peerUserId) }) { Text("解除拉黑") }
            }
        }
    }
}

@Composable
private fun ContactRow(
    contact: ContactSummary,
    viewModel: ContactViewModel,
    loadAvatar: suspend (UUID, Long) -> ByteArray?,
    onOpenConversation: (UUID) -> Unit,
) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        RemoteAvatar(contact.userId, contact.avatarVersion, contact.avatarFallback, loadAvatar, size = 52.dp)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(contact.displayName, style = MaterialTheme.typography.titleMedium)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                StatusDot()
                Text("在线联系人", style = MaterialTheme.typography.bodySmall, color = JitongColors.secondaryText)
            }
        }
        IconButton(onClick = { onOpenConversation(contact.conversationId) }) { Icon(Icons.Outlined.ChatBubbleOutline, contentDescription = "聊天", tint = JitongColors.blue) }
        IconButton(onClick = { viewModel.remove(contact.userId) }) { Icon(Icons.Outlined.PersonRemove, contentDescription = "删除联系人", tint = JitongColors.tertiaryText) }
    }
    Divider(color = JitongColors.divider, modifier = Modifier.padding(start = 82.dp))
}

@Composable
private fun ContactRequestsContent(state: ContactUiState, viewModel: ContactViewModel) {
    if (state.requests.isEmpty()) {
        EmptyState(Icons.Outlined.PersonAdd, "暂无申请", "新的联系人申请会显示在这里", null, null)
        return
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 8.dp)) {
        items(state.requests, key = { it.requestId }) { request ->
            RequestRow(request, viewModel)
        }
    }
}

@Composable
private fun RequestRow(request: ContactRequestSummary, viewModel: ContactViewModel) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        AvatarPlaceholder(request.peerDisplayName, size = 50.dp)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(request.peerDisplayName, style = MaterialTheme.typography.titleMedium)
            Text(if (request.incoming) "请求添加你为联系人" else "等待对方处理申请", color = JitongColors.secondaryText)
            request.verification.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = JitongColors.tertiaryText) }
        }
        if (request.status == "PENDING") {
            if (request.incoming) {
                IconButton(onClick = { viewModel.accept(request.requestId) }) { Icon(Icons.Outlined.Check, contentDescription = "接受", tint = JitongColors.success) }
                IconButton(onClick = { viewModel.reject(request.requestId) }) { Icon(Icons.Outlined.Close, contentDescription = "拒绝", tint = JitongColors.danger) }
            } else {
                TextButton(onClick = { viewModel.cancel(request.requestId) }) { Text("取消") }
            }
        }
    }
    Divider(color = JitongColors.divider, modifier = Modifier.padding(start = 80.dp))
}

@Composable
private fun ContactSearchContent(state: ContactUiState, viewModel: ContactViewModel) {
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("添加联系人", style = MaterialTheme.typography.titleLarge)
        Text("输入对方的完整账号，找到后发送申请。", color = JitongColors.secondaryText)
        OutlinedTextField(
            value = state.searchAccountNo,
            onValueChange = viewModel::setSearchAccountNo,
            label = { Text("11 位账号") },
            singleLine = true,
            leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
            modifier = Modifier.fillMaxWidth(),
        )
        Button(
            onClick = viewModel::search,
            enabled = state.searchAccountNo.length == 11 && !state.loading,
            modifier = Modifier.fillMaxWidth().height(48.dp),
            shape = RoundedCornerShape(14.dp),
        ) { Text(if (state.loading) "搜索中…" else "精确搜索") }
        state.searchResult?.let { result -> ContactSearchResultCard(result, viewModel) }
    }
}

@Composable
private fun ContactSearchResultCard(result: ContactSearchResult, viewModel: ContactViewModel) {
    SoftCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                AvatarPlaceholder(result.avatarFallback, size = 56.dp)
                Column(Modifier.weight(1f)) {
                    Text(result.displayName, style = MaterialTheme.typography.titleMedium)
                    Text(result.accountNo, color = JitongColors.secondaryText)
                }
            }
            if (result.relationship == "NONE" || result.relationship == "REMOVED") {
                Button(onClick = { viewModel.addContact(result.accountNo) }, modifier = Modifier.fillMaxWidth()) { Text("发送联系人申请") }
            } else {
                OutlinePill("当前状态：${result.relationship}")
            }
        }
    }
}

@Composable
private fun GroupListContent(
    state: GroupUiState,
    viewModel: GroupViewModel,
    load: suspend (UUID, Long) -> ByteArray?,
    onOpenGroup: (GroupSummary) -> Unit,
    onManageGroup: (GroupSummary) -> Unit,
    onCreateGroup: () -> Unit,
) {
    if (state.groups.isEmpty()) {
        EmptyState(
            icon = Icons.Outlined.Groups,
            title = "还没有加入群聊",
            subtitle = "创建一个群聊，或搜索群号加入",
            actionLabel = "创建群聊",
            onAction = onCreateGroup,
        )
        return
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 8.dp)) {
        items(state.groups, key = { it.conversationId }) { group ->
            GroupRow(group, viewModel, load, onOpenGroup, onManageGroup)
        }
    }
}

@Composable
private fun GroupRow(
    group: GroupSummary,
    viewModel: GroupViewModel,
    load: suspend (UUID, Long) -> ByteArray?,
    onOpenGroup: (GroupSummary) -> Unit,
    onManageGroup: (GroupSummary) -> Unit,
) {
    Row(Modifier.fillMaxWidth().clickable { onOpenGroup(group) }.padding(horizontal = 18.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        RemoteGroupAvatar(group.conversationId, group.avatarVersion, group.name, load, size = 54.dp)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(group.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("${group.memberCount} 人 · ${group.roleLabel()}", color = JitongColors.secondaryText, style = MaterialTheme.typography.bodySmall)
            group.description.takeIf { it.isNotBlank() }?.let { Text(it, color = JitongColors.tertiaryText, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
        IconButton(onClick = { onManageGroup(group) }) { Icon(Icons.Outlined.MoreHoriz, contentDescription = "群聊设置") }
    }
    Divider(color = JitongColors.divider, modifier = Modifier.padding(start = 84.dp))
}

private fun GroupSummary.roleLabel(): String = when (role) {
    "OWNER" -> "群主"
    "ADMIN" -> "管理员"
    else -> "成员"
}

@Composable
private fun GroupSearchContent(state: GroupUiState, viewModel: GroupViewModel, load: suspend (String) -> ByteArray?) {
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedTextField(
            value = state.searchQuery,
            onValueChange = viewModel::setSearchQuery,
            label = { Text("群号或群名称") },
            singleLine = true,
            leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
            modifier = Modifier.fillMaxWidth(),
        )
        Button(onClick = viewModel::search, enabled = state.searchQuery.isNotBlank() && !state.loading, modifier = Modifier.fillMaxWidth()) { Text("搜索群聊") }
        state.searchResults.forEach { group -> GroupSearchResultRow(group, load) }
    }
}

@Composable
private fun GroupSearchResultRow(group: GroupSearchResult, load: suspend (String) -> ByteArray?) {
    SoftCard(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (group.avatarUrl != null) RemoteSearchGroupAvatar(group.avatarUrl, group.name, load, size = 52.dp) else AvatarPlaceholder(group.name, size = 52.dp)
            Column(Modifier.weight(1f)) {
                Text(group.name, style = MaterialTheme.typography.titleMedium)
                Text(group.description.ifBlank { "暂无简介" }, color = JitongColors.secondaryText, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text("${group.memberCount} 人", color = JitongColors.tertiaryText, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun GroupInviteContent(state: GroupUiState, viewModel: GroupViewModel) {
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("加入群聊", style = MaterialTheme.typography.titleLarge)
        Text("粘贴邀请链接中的令牌，查看群资料并提交申请。", color = JitongColors.secondaryText)
        OutlinedTextField(value = state.inviteToken, onValueChange = viewModel::setInviteToken, label = { Text("邀请令牌") }, modifier = Modifier.fillMaxWidth())
        Button(onClick = viewModel::resolveInvite, enabled = state.inviteToken.isNotBlank() && !state.loading, modifier = Modifier.fillMaxWidth()) { Text("打开邀请") }
        state.invite?.let { invite ->
            SoftCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(invite.name, style = MaterialTheme.typography.titleMedium)
                    Text(invite.description.ifBlank { "暂无简介" }, color = JitongColors.secondaryText)
                    Text("${invite.memberCount} 人 · ${invite.visibility}", color = JitongColors.tertiaryText)
                    Button(onClick = viewModel::requestToJoin, modifier = Modifier.fillMaxWidth()) { Text("提交入群申请") }
                }
            }
        }
    }
}

@Composable
private fun GroupCreateContent(state: GroupUiState, viewModel: GroupViewModel) {
    val context = LocalContext.current
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        viewModel.setAvatar(context.contentResolver.openInputStream(uri)?.use { readCappedBytes(it, 11 * 1024 * 1024) })
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("创建群聊", style = MaterialTheme.typography.titleLarge)
        OutlinedTextField(state.name, viewModel::setName, label = { Text("群名称") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(state.description, viewModel::setDescription, label = { Text("群简介，可选") }, modifier = Modifier.fillMaxWidth())
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            AvatarView(state.avatar, state.name.ifBlank { "群" }, size = 58.dp)
            OutlinedButton(onClick = { picker.launch("image/*") }) { Text(if (state.avatar == null) "选择群头像" else "更换群头像") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("PUBLIC" to "公开", "UNLISTED" to "不公开", "PRIVATE" to "私密").forEach { (value, label) ->
                FilterChip(selected = state.visibility == value, onClick = { viewModel.setVisibility(value) }, label = { Text(label) })
            }
        }
        Button(onClick = viewModel::create, enabled = state.name.isNotBlank() && !state.loading, modifier = Modifier.fillMaxWidth().height(48.dp)) { Text("创建群聊") }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GroupManagementSheet(group: GroupSummary, state: GroupUiState, viewModel: GroupViewModel, onDismiss: () -> Unit) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    LaunchedEffect(group.conversationId) { viewModel.loadMembers(group) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("群聊设置", style = MaterialTheme.typography.headlineSmall)
                    Text(group.name, color = JitongColors.secondaryText)
                }
                IconButton(onClick = onDismiss) { Icon(Icons.Outlined.Close, contentDescription = "关闭") }
            }
            if (GroupGovernancePolicy.canEditProfile(group.role)) {
                OutlinedTextField(state.name.ifBlank { group.name }, viewModel::setName, label = { Text("群名称") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(state.description.ifBlank { group.description }, viewModel::setDescription, label = { Text("群简介") }, modifier = Modifier.fillMaxWidth())
                Button(onClick = { viewModel.updateProfile(group) }, modifier = Modifier.fillMaxWidth()) { Text("保存群资料") }
            }
            SettingsRow(Icons.Outlined.GroupAdd, "邀请成员", "直接邀请账号加入群聊", onClick = {})
            if (GroupGovernancePolicy.canApproveJoinRequests(group.role)) {
                val pending = state.joinRequests.count { it.conversationId == group.conversationId && it.status == "PENDING" }
                SettingsRow(Icons.Outlined.Check, "入群审批", if (pending == 0) "暂无待处理申请" else "$pending 条待处理申请", onClick = { viewModel.loadJoinRequests(group) })
            }
            Text("群成员", style = MaterialTheme.typography.titleMedium)
            if (state.memberGroupId == group.conversationId && state.members.isNotEmpty()) {
                state.members.forEach { member -> MemberManagementRow(group, member, viewModel) }
            } else {
                Text("正在加载成员…", color = JitongColors.secondaryText)
            }
            if (group.role == "OWNER") {
                TextButton(onClick = { viewModel.dissolve(group); onDismiss() }, modifier = Modifier.fillMaxWidth()) { Text("解散群聊", color = JitongColors.danger) }
            } else {
                TextButton(onClick = { viewModel.leave(group); onDismiss() }, modifier = Modifier.fillMaxWidth()) { Text("退出群聊", color = JitongColors.danger) }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun QuickAddSheet(
    onDismiss: () -> Unit,
    onAddContact: () -> Unit,
    onSearchGroup: () -> Unit,
    onCreateGroup: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("添加", style = MaterialTheme.typography.headlineSmall)
                    Text("添加联系人或群聊", color = JitongColors.secondaryText)
                }
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Outlined.Close, contentDescription = "关闭")
                }
            }
            SoftCard(Modifier.fillMaxWidth()) {
                Column {
                    SettingsRow(
                        icon = Icons.Outlined.PersonAdd,
                        title = "添加联系人",
                        subtitle = "搜索账号并发送好友申请",
                        onClick = onAddContact,
                    )
                    ListDivider()
                    SettingsRow(
                        icon = Icons.Outlined.Search,
                        title = "搜索群聊",
                        subtitle = "按群号或群名称查找群聊",
                        onClick = onSearchGroup,
                    )
                    ListDivider()
                    SettingsRow(
                        icon = Icons.Outlined.GroupAdd,
                        title = "创建群聊",
                        subtitle = "创建一个新的群聊空间",
                        onClick = onCreateGroup,
                    )
                }
            }
        }
    }
}

@Composable
private fun MemberManagementRow(group: GroupSummary, member: GroupMemberSummary, viewModel: GroupViewModel) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        AvatarPlaceholder(member.avatarFallback, size = 42.dp)
        Column(Modifier.weight(1f)) {
            Text(member.displayName, style = MaterialTheme.typography.bodyLarge)
            Text(member.roleLabel(), color = JitongColors.secondaryText, style = MaterialTheme.typography.bodySmall)
        }
        if (member.role == "MEMBER" && group.role == "OWNER") {
            TextButton(onClick = { viewModel.promoteMember(group, member.userId) }) { Text("设为管理员") }
        } else if (member.role == "ADMIN" && group.role == "OWNER") {
            TextButton(onClick = { viewModel.demoteMember(group, member.userId) }) { Text("降级") }
            TextButton(onClick = { viewModel.transferOwner(group, member.userId) }) { Text("转让") }
        }
        if (GroupGovernancePolicy.canRemoveMember(group.role, member.role)) {
            IconButton(onClick = { viewModel.removeMember(group, member) }) { Icon(Icons.Outlined.PersonRemove, contentDescription = "移除", tint = JitongColors.danger) }
        }
    }
}

private fun GroupMemberSummary.roleLabel(): String = when (role) {
    "OWNER" -> "群主"
    "ADMIN" -> "管理员"
    else -> "成员"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ConversationScreen(
    conversation: ConversationSummary,
    viewModel: MessageViewModel,
    aiState: AiUiState,
    aiViewModel: AiViewModel,
    loadAvatar: suspend (UUID, Long) -> ByteArray?,
    initialMessageId: String?,
    onBack: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    var showAi by rememberSaveable { mutableStateOf(false) }
    val context = LocalContext.current
    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        context.contentResolver.openInputStream(uri)?.use { viewModel.sendImage(it.readBytes()) }
    }
    LaunchedEffect(conversation.conversationId) {
        viewModel.open(conversation.conversationId)
        aiViewModel.open(conversation.conversationId)
    }
    LaunchedEffect(state.messages, initialMessageId) {
        val index = initialMessageId?.let { id -> state.messages.indexOfFirst { it.messageId == id } } ?: state.messages.lastIndex
        if (index >= 0) listState.animateScrollToItem(index)
    }
    LaunchedEffect(state.messages.size) {
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index }.collect { index ->
            index?.let { state.messages.getOrNull(it)?.conversationSeq }?.let(viewModel::markRead)
        }
    }
    Scaffold(
        containerColor = JitongColors.chatPage,
        topBar = {
            JitongHeader(
                title = conversation.peerDisplayName,
                subtitle = if (conversation.status == "READ_ONLY") "历史消息，只读" else "聊天",
                onBack = onBack,
                actions = {
                    IconButton(onClick = { showAi = true }) { Icon(Icons.Outlined.AutoAwesome, contentDescription = "私人 AI", tint = JitongColors.ai) }
                    IconButton(onClick = {}) { Icon(Icons.Outlined.MoreHoriz, contentDescription = "更多") }
                },
            )
        },
        bottomBar = {
            ComposerBar(
                draft = state.draft,
                onDraftChange = viewModel::setDraft,
                onSend = viewModel::send,
                onImage = { imagePicker.launch("image/*") },
                enabled = conversation.status == "ACTIVE" && !state.loading,
            )
        },
    ) { padding ->
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 14.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (state.messages.isEmpty()) item { EmptyChatState(conversation.peerDisplayName) }
            items(state.messages, key = { it.messageId }) { message ->
                ChatMessageRow(
                    message = message,
                    currentUserId = state.currentUserId?.toString(),
                    loadAvatar = loadAvatar,
                    selectedForAi = runCatching { UUID.fromString(message.messageId) }.getOrNull() in aiState.selectedMessageIds,
                    onToggleAi = { runCatching { UUID.fromString(message.messageId) }.getOrNull()?.let(aiViewModel::toggleMessage) },
                    onRecall = { viewModel.recall(message) },
                    onRetry = { runCatching { UUID.fromString(message.clientMsgId) }.getOrNull()?.let(viewModel::retry) },
                    loadMedia = viewModel::loadMedia,
                )
            }
            state.message?.let { item { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = 8.dp)) } }
        }
    }
    if (showAi) {
        AiAssistantSheet(
            state = aiState,
            aiViewModel = aiViewModel,
            messageViewModel = viewModel,
            onDismiss = { showAi = false },
        )
    }
}

@Composable
private fun GroupConversationScreen(
    group: GroupSummary,
    viewModel: MessageViewModel,
    loadGroupAvatar: suspend (UUID, Long) -> ByteArray?,
    onBack: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    val context = LocalContext.current
    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        context.contentResolver.openInputStream(uri)?.use { viewModel.sendImage(it.readBytes()) }
    }
    LaunchedEffect(group.conversationId) { viewModel.open(group.conversationId) }
    LaunchedEffect(state.messages) {
        if (state.messages.isNotEmpty()) listState.animateScrollToItem(state.messages.lastIndex)
    }
    Scaffold(
        containerColor = JitongColors.chatPage,
        topBar = {
            JitongHeader(
                title = group.name,
                subtitle = "${group.memberCount} 人 · ${group.roleLabel()}",
                onBack = onBack,
                actions = { IconButton(onClick = {}) { Icon(Icons.Outlined.MoreHoriz, contentDescription = "群设置") } },
            )
        },
        bottomBar = {
            ComposerBar(
                draft = state.draft,
                onDraftChange = viewModel::setDraft,
                onSend = viewModel::send,
                onImage = { imagePicker.launch("image/*") },
                enabled = !state.loading,
            )
        },
    ) { padding ->
        LazyColumn(state = listState, modifier = Modifier.fillMaxSize().padding(padding), contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 14.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (state.messages.isEmpty()) item { EmptyChatState(group.name) }
            items(state.messages, key = { it.messageId }) { message ->
                GroupChatMessageRow(message, state.currentUserId?.toString(), loadGroupAvatar, viewModel::loadMedia, onRecall = { viewModel.recall(message) })
            }
            state.message?.let { item { Text(it, color = MaterialTheme.colorScheme.error) } }
        }
    }
}

@Composable
private fun ChatMessageRow(
    message: LocalMessageEntity,
    currentUserId: String?,
    loadAvatar: suspend (UUID, Long) -> ByteArray?,
    selectedForAi: Boolean,
    onToggleAi: () -> Unit,
    onRecall: () -> Unit,
    onRetry: () -> Unit,
    loadMedia: suspend (LocalMessageEntity, Boolean) -> ByteArray?,
) {
    if (message.type == "SYSTEM" || message.state == "RECALLED" || message.state == "MODERATED") {
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Text(
                message.groupMessageText(),
                modifier = Modifier.clip(RoundedCornerShape(50)).background(JitongColors.tertiaryText.copy(alpha = .16f)).padding(horizontal = 12.dp, vertical = 6.dp),
                color = JitongColors.secondaryText,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        return
    }
    val isMine = message.senderId == currentUserId
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (isMine) Arrangement.End else Arrangement.Start, verticalAlignment = Alignment.Top) {
        if (!isMine) {
            val userId = runCatching { UUID.fromString(message.senderId) }.getOrNull()
            if (userId != null) RemoteAvatar(userId, 0, message.senderDisplayName.ifBlank { "?" }, loadAvatar, size = 40.dp) else AvatarPlaceholder(message.senderDisplayName, size = 40.dp)
            Spacer(Modifier.width(8.dp))
        }
        Column(horizontalAlignment = if (isMine) Alignment.End else Alignment.Start, verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxWidth(.82f)) {
            if (!isMine && message.senderDisplayName.isNotBlank()) Text(message.senderDisplayName, style = MaterialTheme.typography.bodySmall, color = JitongColors.secondaryText)
            Surface(
                shape = RoundedCornerShape(18.dp),
                color = if (isMine) JitongColors.outgoingBubble else JitongColors.incomingBubble,
                shadowElevation = if (isMine) 0.dp else 1.dp,
            ) {
                Column(Modifier.padding(horizontal = 13.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (message.type == "IMAGE") {
                        ImageMessageContent(message, loadMedia)
                    } else {
                        Text(message.text, color = if (isMine) androidx.compose.ui.graphics.Color.White else JitongColors.text)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            messageStatusLabel(message),
                            style = MaterialTheme.typography.bodySmall,
                            color = if (isMine) androidx.compose.ui.graphics.Color.White.copy(alpha = .72f) else JitongColors.tertiaryText,
                        )
                        if (message.state == "ACTIVE" && message.messageId.isNotBlank()) {
                            TextButton(onClick = onToggleAi, contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp), modifier = Modifier.height(26.dp)) {
                                Icon(Icons.Outlined.AutoAwesome, contentDescription = "选择 AI 依据", tint = if (selectedForAi) JitongColors.ai else if (isMine) androidx.compose.ui.graphics.Color.White.copy(alpha = .75f) else JitongColors.secondaryText, modifier = Modifier.size(15.dp))
                                Spacer(Modifier.width(3.dp))
                                Text(if (selectedForAi) "已选" else "AI", color = if (selectedForAi) JitongColors.ai else if (isMine) androidx.compose.ui.graphics.Color.White.copy(alpha = .75f) else JitongColors.secondaryText, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (message.state == "ACTIVE" && message.localState == "SENT" && isMine) TextButton(onClick = onRecall, contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 4.dp, vertical = 0.dp)) { Text("撤回", style = MaterialTheme.typography.bodySmall) }
                if (message.localState == "MANUAL_RETRY") TextButton(onClick = onRetry, contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 4.dp, vertical = 0.dp)) { Text("重试", style = MaterialTheme.typography.bodySmall, color = JitongColors.danger) }
            }
        }
        if (isMine) {
            Spacer(Modifier.width(8.dp))
            AvatarPlaceholder("我", size = 40.dp)
        }
    }
}

@Composable
private fun GroupChatMessageRow(
    message: LocalMessageEntity,
    currentUserId: String?,
    loadAvatar: suspend (UUID, Long) -> ByteArray?,
    loadMedia: suspend (LocalMessageEntity, Boolean) -> ByteArray?,
    onRecall: () -> Unit,
) {
    if (message.type == "SYSTEM" || message.state == "RECALLED" || message.state == "MODERATED") {
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Text(message.groupMessageText(), modifier = Modifier.clip(RoundedCornerShape(50)).background(JitongColors.tertiaryText.copy(alpha = .16f)).padding(horizontal = 12.dp, vertical = 6.dp), color = JitongColors.secondaryText, style = MaterialTheme.typography.bodySmall)
        }
        return
    }
    val userId = runCatching { UUID.fromString(message.senderId) }.getOrNull()
    val isMine = message.senderId == currentUserId
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (isMine) Arrangement.End else Arrangement.Start, verticalAlignment = Alignment.Top) {
        if (!isMine) {
            if (userId != null) RemoteAvatar(userId, 0, message.senderDisplayName, loadAvatar, size = 40.dp) else AvatarPlaceholder(message.senderDisplayName, size = 40.dp)
            Spacer(Modifier.width(8.dp))
        }
        Column(Modifier.fillMaxWidth(.82f), horizontalAlignment = if (isMine) Alignment.End else Alignment.Start, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (!isMine) Text(message.senderDisplayName, style = MaterialTheme.typography.bodySmall, color = JitongColors.secondaryText)
            Surface(shape = RoundedCornerShape(18.dp), color = if (isMine) JitongColors.outgoingBubble else JitongColors.incomingBubble, shadowElevation = if (isMine) 0.dp else 1.dp) {
                if (message.type == "IMAGE") ImageMessageContent(message, loadMedia) else Text(message.text, modifier = Modifier.padding(horizontal = 13.dp, vertical = 10.dp), color = if (isMine) androidx.compose.ui.graphics.Color.White else JitongColors.text)
            }
            if (isMine && message.localState == "SENT") TextButton(onClick = onRecall, contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 4.dp, vertical = 0.dp)) { Text("撤回", style = MaterialTheme.typography.bodySmall) }
        }
        if (isMine) { Spacer(Modifier.width(8.dp)); AvatarPlaceholder("我", size = 40.dp) }
    }
}

private fun messageStatusLabel(message: LocalMessageEntity): String = when {
    message.localState == "SENDING" -> "发送中"
    message.localState == "QUEUED" -> "等待网络"
    message.localState == "MANUAL_RETRY" -> "发送失败"
    message.state == "RECALLED" -> "已撤回"
    message.state == "MODERATED" -> "已处理"
    else -> ""
}

@Composable
private fun ComposerBar(draft: String, onDraftChange: (String) -> Unit, onSend: () -> Unit, onImage: () -> Unit, enabled: Boolean) {
    Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 2.dp) {
        Row(
            Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.ime).windowInsetsPadding(WindowInsets.navigationBars).padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            IconButton(onClick = onImage, enabled = enabled) { Icon(Icons.Outlined.CameraAlt, contentDescription = "图片", tint = JitongColors.text) }
            TextField(
                value = draft,
                onValueChange = onDraftChange,
                enabled = enabled,
                placeholder = { Text("输入消息…", color = JitongColors.tertiaryText) },
                modifier = Modifier.weight(1f),
                maxLines = 4,
                shape = RoundedCornerShape(18.dp),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = JitongColors.chatPage,
                    unfocusedContainerColor = JitongColors.chatPage,
                    disabledContainerColor = JitongColors.chatPage,
                    focusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                    unfocusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                    disabledIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                ),
            )
            IconButton(onClick = onSend, enabled = enabled && draft.isNotBlank()) {
                Icon(Icons.Outlined.Send, contentDescription = "发送", tint = if (draft.isNotBlank()) JitongColors.blue else JitongColors.tertiaryText)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AiAssistantSheet(state: AiUiState, aiViewModel: AiViewModel, messageViewModel: MessageViewModel, onDismiss: () -> Unit) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState, containerColor = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(42.dp).clip(CircleShape).background(JitongColors.ai.copy(alpha = .14f)), contentAlignment = Alignment.Center) {
                    Icon(Icons.Outlined.AutoAwesome, contentDescription = null, tint = JitongColors.ai)
                }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text("私人 AI", style = MaterialTheme.typography.headlineSmall)
                    Text("只对你可见，帮助你更快处理聊天内容", color = JitongColors.secondaryText)
                }
                IconButton(onClick = onDismiss) { Icon(Icons.Outlined.Close, contentDescription = "关闭") }
            }
            Surface(shape = RoundedCornerShape(16.dp), color = JitongColors.ai.copy(alpha = .08f)) {
                Row(Modifier.padding(14.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Top) {
                    Icon(Icons.Outlined.Security, contentDescription = null, tint = JitongColors.ai, modifier = Modifier.size(19.dp))
                    Text(
                        when {
                            state.enabledForBoth -> "双方已同意使用私人 AI，生成内容仅保存在你的账号空间。"
                            state.consentEnabled -> "你已同意，等待对方开启后才能处理这段聊天。"
                            else -> "需要双方都同意，AI 才能读取这段聊天。"
                        },
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
            Button(onClick = { aiViewModel.updateConsent(!state.consentEnabled) }, enabled = !state.loading, modifier = Modifier.fillMaxWidth()) {
                Text(if (state.consentEnabled) "关闭我的同意" else "同意使用私人 AI")
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = aiViewModel::requestSmartReplies, enabled = state.enabledForBoth && !state.loading, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Outlined.AutoAwesome, contentDescription = null, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)); Text("智能回复")
                }
                OutlinedButton(onClick = aiViewModel::extractSelected, enabled = state.enabledForBoth && state.selectedMessageIds.isNotEmpty() && !state.loading, modifier = Modifier.weight(1f)) {
                    Text("提取信息${if (state.selectedMessageIds.isEmpty()) "" else " (${state.selectedMessageIds.size})"}")
                }
            }
            if (state.drafts.isNotEmpty()) {
                Text("智能回复草稿", style = MaterialTheme.typography.titleMedium)
                state.drafts.forEachIndexed { index, draft ->
                    SoftCard(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(draft.tone, color = JitongColors.ai, style = MaterialTheme.typography.labelLarge)
                            OutlinedTextField(draft.text, { aiViewModel.updateDraft(index, it) }, modifier = Modifier.fillMaxWidth(), minLines = 2)
                            TextButton(onClick = { messageViewModel.setDraft(draft.text) }, modifier = Modifier.align(Alignment.End)) { Text("放入输入框") }
                        }
                    }
                }
            }
            if (state.keyFacts.isNotEmpty()) {
                Text("提取出的信息", style = MaterialTheme.typography.titleMedium)
                state.keyFacts.forEach { fact ->
                    SoftCard(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                            Text(fact.category, style = MaterialTheme.typography.labelLarge, color = JitongColors.blue)
                            Text(fact.content)
                            TextButton(onClick = { aiViewModel.deleteArtifact(fact.artifactId) }, modifier = Modifier.align(Alignment.End)) { Text("删除") }
                        }
                    }
                }
            }
            if (state.actionItems.isNotEmpty()) {
                Text("待办事项", style = MaterialTheme.typography.titleMedium)
                state.actionItems.forEach { item -> ActionItemCard(item, aiViewModel) }
            }
            state.message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }
}

@Composable
private fun ActionItemCard(item: LocalAiActionItemEntity, aiViewModel: AiViewModel) {
    SoftCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(item.title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                OutlinePill(if (item.status == "COMPLETED") "已完成" else item.priority, color = if (item.status == "COMPLETED") JitongColors.success else JitongColors.warning)
            }
            Text(item.details, color = JitongColors.secondaryText)
            TextButton(onClick = { aiViewModel.setActionItemCompleted(item.actionItemId, item.status != "COMPLETED") }, modifier = Modifier.align(Alignment.End)) { Text(if (item.status == "COMPLETED") "重新打开" else "标记完成") }
        }
    }
}

@Composable
private fun LocalSearchResults(state: MessageUiState, conversations: List<ConversationSummary>, onOpen: (UUID, String) -> Unit) {
    if (state.searchLoading) {
        CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
        return
    }
    if (state.searchResults.isEmpty()) {
        Text("没有找到匹配的聊天记录", color = JitongColors.secondaryText)
        return
    }
    state.searchResults.forEach { result ->
        val conversation = conversations.firstOrNull { it.conversationId.toString() == result.conversationId }
        SoftCard(Modifier.fillMaxWidth().clickable { onOpen(UUID.fromString(result.conversationId), result.messageId) }) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(conversation?.peerDisplayName ?: "聊天记录", style = MaterialTheme.typography.titleMedium)
                Text(result.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun HomeSectionTabs(items: List<Pair<String, String>>, selected: String, onSelected: (String) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
        items.forEach { (key, label) ->
            TextButton(onClick = { onSelected(key) }) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(label, color = if (selected == key) JitongColors.blue else JitongColors.secondaryText, fontWeight = if (selected == key) FontWeight.SemiBold else FontWeight.Normal)
                    Spacer(Modifier.height(4.dp))
                    Box(Modifier.width(28.dp).height(3.dp).clip(RoundedCornerShape(50)).background(if (selected == key) JitongColors.blue else androidx.compose.ui.graphics.Color.Transparent))
                }
            }
        }
    }
}

@Composable
private fun SettingsRow(icon: ImageVector, title: String, subtitle: String, danger: Boolean = false, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = if (danger) JitongColors.danger else JitongColors.blue,
            modifier = Modifier.size(22.dp),
        )
        Column(
            Modifier
                .weight(1f)
                .padding(vertical = 1.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Text(
                text = title,
                color = if (danger) JitongColors.danger else JitongColors.text,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = subtitle,
                color = JitongColors.secondaryText,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Icon(
            Icons.Outlined.ChevronRight,
            contentDescription = "进入",
            tint = JitongColors.tertiaryText,
            modifier = Modifier.size(20.dp),
        )
    }
}

@Composable
private fun EmptyState(icon: ImageVector, title: String, subtitle: String, actionLabel: String? = null, onAction: (() -> Unit)? = null) {
    Box(Modifier.fillMaxSize().padding(28.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.size(68.dp).clip(CircleShape).background(JitongColors.blue.copy(alpha = .1f)), contentAlignment = Alignment.Center) {
                Icon(icon, contentDescription = null, tint = JitongColors.blue, modifier = Modifier.size(32.dp))
            }
            Text(title, style = MaterialTheme.typography.titleLarge)
            Text(subtitle, color = JitongColors.secondaryText, textAlign = TextAlign.Center)
            if (actionLabel != null && onAction != null) Button(onClick = onAction, shape = RoundedCornerShape(12.dp)) { Text(actionLabel) }
        }
    }
}

@Composable
private fun EmptyChatState(title: String) {
    Box(Modifier.fillMaxWidth().padding(vertical = 120.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("开始和${title}聊天", style = MaterialTheme.typography.titleMedium)
            Text("消息会显示在这里", color = JitongColors.secondaryText)
        }
    }
}

@Composable
private fun AvatarProfilePanel(state: AvatarUiState, viewModel: AvatarViewModel, compact: Boolean = false) {
    val context = LocalContext.current
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        context.contentResolver.openInputStream(uri)?.use { viewModel.replace(it.readBytes()) }
    }
    Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        AvatarView(state.bytes, state.profile?.avatarFallback ?: "我", size = if (compact) 58.dp else 72.dp)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(if (compact) "我的头像" else "头像", style = MaterialTheme.typography.titleMedium)
            Text("更新你的个人头像", color = JitongColors.secondaryText, style = MaterialTheme.typography.bodySmall)
        }
        OutlinedButton(onClick = { picker.launch("image/*") }, enabled = !state.loading) { Text("更换") }
    }
    if (state.message != null) Text(state.message, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
}

private fun readCappedBytes(input: java.io.InputStream, maximumBytes: Int): ByteArray? {
    val output = ByteArrayOutputStream()
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    var total = 0
    while (true) {
        val read = input.read(buffer)
        if (read < 0) return output.toByteArray()
        total += read
        if (total > maximumBytes) return null
        output.write(buffer, 0, read)
    }
}
