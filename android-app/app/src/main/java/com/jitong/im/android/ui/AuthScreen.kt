package com.jitong.im.android.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.Image
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.setValue
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import android.graphics.Bitmap
import android.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.MultiFormatWriter
import java.io.ByteArrayOutputStream
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jitong.im.android.auth.SessionState
import com.jitong.im.android.contact.ConversationSummary
import com.jitong.im.android.group.GroupGovernancePolicy
import com.jitong.im.android.group.GroupMemberSummary
import com.jitong.im.android.group.GroupSummary
import java.util.UUID

@Composable
internal fun JitongApp(
    viewModel: AuthViewModel,
    contactViewModel: ContactViewModel,
    messageViewModel: MessageViewModel,
    aiViewModel: AiViewModel,
    avatarViewModel: AvatarViewModel,
    groupViewModel: GroupViewModel,
) {
    val state by viewModel.sessionState.collectAsStateWithLifecycle()
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        when (val current = state) {
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
                current,
                viewModel,
                contactViewModel,
                messageViewModel,
                aiViewModel,
                avatarViewModel,
                groupViewModel,
            )
            is SessionState.Error -> LoginScreen(viewModel, current.message)
        }
    }
}

@Composable
private fun PasswordChangeScreen(
    state: SessionState.PasswordChangeRequired,
    viewModel: AuthViewModel,
) {
    var currentPassword by rememberSaveable { mutableStateOf("") }
    var newPassword by rememberSaveable { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            if (state.temporaryPasswordRequired) "首次登录必须修改密码" else "修改密码",
            style = MaterialTheme.typography.headlineMedium,
        )
        Spacer(Modifier.height(12.dp))
        Text(
            if (state.temporaryPasswordRequired) {
                "请输入刚才登录使用的临时密码，并设置新的长期密码。修改前不能进入正常业务。"
            } else {
                "请输入当前密码并设置新的长期密码。其他设备会立即退出。"
            },
        )
        Spacer(Modifier.height(18.dp))
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                OutlinedTextField(
                    value = currentPassword,
                    onValueChange = { currentPassword = it },
                    label = { Text(if (state.temporaryPasswordRequired) "临时密码" else "当前密码") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = newPassword,
                    onValueChange = { newPassword = it },
                    label = { Text("新密码") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                )
                state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                Button(
                    onClick = { viewModel.changePassword(currentPassword, newPassword) },
                    enabled = currentPassword.isNotBlank() && newPassword.length >= 8,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("保存新密码")
                }
                OutlinedButton(
                    onClick = { viewModel.logout() },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("退出登录")
                }
            }
        }
    }
}

@Composable
private fun LoginScreen(viewModel: AuthViewModel, error: String? = null) {
    var accountNo by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("即通", style = MaterialTheme.typography.displaySmall)
        Spacer(Modifier.height(8.dp))
        Text("登录到独立加密账号空间", style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(28.dp))
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                OutlinedTextField(
                    value = accountNo,
                    onValueChange = { accountNo = it.filter(Char::isDigit).take(11) },
                    label = { Text("账号") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("密码") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                )
                if (error != null) Text(error, color = MaterialTheme.colorScheme.error)
                Button(
                    onClick = { viewModel.login(accountNo, password) },
                    enabled = accountNo.length == 11 && password.isNotBlank(),
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("登录") }
            }
        }
        Spacer(Modifier.height(18.dp))
        Text(
            "正常退出只删除令牌并保留本地加密数据；设备失信时会清除密钥、数据库和媒体缓存。",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun ReplacementScreen(
    state: SessionState.ReplacementRequired,
    viewModel: AuthViewModel,
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text("需要替换旧 MOBILE", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(12.dp))
        Text("该账号已有一台受信任的 MOBILE。确认后旧设备会在下次联机时失信并清除本地数据。")
        Spacer(Modifier.height(18.dp))
        Card(Modifier.fillMaxWidth()) {
            Text(
                "一次性确认挑战已生成，仅在本次登录流程中使用。",
                modifier = Modifier.padding(16.dp),
            )
        }
        Spacer(Modifier.height(18.dp))
        Button(
            onClick = { viewModel.confirmReplacement(state.challenge) },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("确认替换") }
        TextButton(
            onClick = { viewModel.logout() },
            modifier = Modifier.align(Alignment.CenterHorizontally),
        ) {
            Text("取消")
        }
    }
}

@Composable
private fun RestoringScreen() {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CircularProgressIndicator()
        Spacer(Modifier.height(14.dp))
        Text("正在恢复登录会话")
    }
}

@Composable
private fun HomeScreen(
    state: SessionState.SignedIn,
    viewModel: AuthViewModel,
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
    var selectedTab by rememberSaveable { mutableStateOf("contacts") }
    var selectedConversationId by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedGroupConversationId by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedMessageId by rememberSaveable { mutableStateOf<String?>(null) }
    var deleteAccountPassword by rememberSaveable { mutableStateOf("") }
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
        if (groupState.autoResolveInvite) {
            selectedConversationId = null
            selectedMessageId = null
            selectedTab = "groups"
        }
    }
    val selectedConversation = contactState.conversations
        .firstOrNull { it.conversationId.toString() == selectedConversationId }
    val selectedGroup = groupState.groups
        .firstOrNull { it.conversationId.toString() == selectedGroupConversationId }
    if (selectedConversation != null) {
        ConversationScreen(
            conversation = selectedConversation,
            viewModel = messageViewModel,
            aiState = aiState,
            aiViewModel = aiViewModel,
            loadAvatar = avatarViewModel::loadUserAvatar,
            initialMessageId = selectedMessageId,
            onBack = {
                selectedConversationId = null
                selectedMessageId = null
            },
        )
        return
    }
    if (selectedGroup != null) {
        GroupConversationScreen(
            group = selectedGroup,
            viewModel = messageViewModel,
            loadGroupAvatar = avatarViewModel::loadGroupAvatar,
            onBack = {
                selectedGroupConversationId = null
                selectedMessageId = null
            },
        )
        return
    }
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("联系人与会话", style = MaterialTheme.typography.headlineMedium)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = { selectedTab = "contacts" },
                modifier = Modifier.weight(1f),
            ) { Text("联系人") }
            OutlinedButton(
                onClick = { selectedTab = "requests" },
                modifier = Modifier.weight(1f),
            ) { Text("申请") }
            OutlinedButton(
                onClick = { selectedTab = "search" },
                modifier = Modifier.weight(1f),
            ) { Text("搜索") }
            OutlinedButton(
                onClick = { selectedTab = "history" },
                modifier = Modifier.weight(1f),
            ) { Text("查历史") }
            OutlinedButton(
                onClick = { selectedTab = "groups" },
                modifier = Modifier.weight(1f),
            ) { Text("群聊") }
        }
        when (selectedTab) {
            "search" -> ContactSearchPanel(contactState, contactViewModel)
            "history" -> LocalSearchPanel(
                state = messageState,
                conversations = contactState.conversations,
                viewModel = messageViewModel,
                onOpenConversation = { conversationId, messageId ->
                    selectedConversationId = conversationId.toString()
                    selectedMessageId = messageId
                },
            )
            "groups" -> GroupPanel(
                state = groupState,
                viewModel = groupViewModel,
                loadGroupAvatar = avatarViewModel::loadGroupAvatar,
                loadSearchAvatar = avatarViewModel::loadGroupAvatarUrl,
                onOpenGroup = {
                    selectedGroupConversationId = it.conversationId.toString()
                    selectedMessageId = null
                },
            )
            "requests" -> ContactRequestsPanel(contactState, contactViewModel)
            else -> ContactListPanel(
                state = contactState,
                viewModel = contactViewModel,
                loadAvatar = avatarViewModel::loadUserAvatar,
                onOpenConversation = {
                    selectedConversationId = it.toString()
                    selectedMessageId = null
                },
            )
        }
        contactState.message?.let {
            Text(it, color = MaterialTheme.colorScheme.error)
        }
        AvatarProfilePanel(avatarState, avatarViewModel)
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("账号 ${state.session.accountNo}", style = MaterialTheme.typography.titleMedium)
                Text("设备类型 ${state.session.deviceClass}")
                Text("本地空间：独立 SQLCipher 数据库")
                Text("退出后仍保留加密历史，受保护页面仅在有效会话下可访问。")
                OutlinedButton(
                    onClick = { viewModel.requestPasswordChange() },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("修改密码")
                }
                OutlinedTextField(
                    value = deleteAccountPassword,
                    onValueChange = { deleteAccountPassword = it },
                    label = { Text("永久注销需再次输入当前密码") },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Button(
                    onClick = {
                        viewModel.deleteAccount(deleteAccountPassword)
                        deleteAccountPassword = ""
                    },
                    enabled = deleteAccountPassword.isNotBlank(),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("永久注销账号")
                }
            }
        }
        Spacer(Modifier.weight(1f))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(onClick = { viewModel.logout() }, modifier = Modifier.weight(1f)) {
                Text("正常退出")
            }
            Button(onClick = { viewModel.clearData() }, modifier = Modifier.weight(1f)) {
                Text("清除本机数据")
            }
        }
    }
}

@Composable
private fun LocalSearchPanel(
    state: MessageUiState,
    conversations: List<ConversationSummary>,
    viewModel: MessageViewModel,
    onOpenConversation: (UUID, String) -> Unit,
) {
    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            OutlinedTextField(
                value = state.searchQuery,
                onValueChange = viewModel::setSearchQuery,
                label = { Text("搜索本地历史") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Button(
                onClick = viewModel::search,
                enabled = state.searchQuery.isNotBlank() && !state.searchLoading,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (state.searchLoading) "搜索中…" else "搜索")
            }
        }
        if (state.searchResults.isEmpty() &&
            state.searchQuery.isNotBlank() &&
            !state.searchLoading
        ) {
            item { Text("没有匹配的授权历史") }
        }
        items(state.searchResults, key = { it.messageId }) { result ->
            val conversation = conversations.firstOrNull {
                it.conversationId.toString() == result.conversationId
            }
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        onOpenConversation(
                            UUID.fromString(result.conversationId),
                            result.messageId,
                        )
                    },
            ) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        conversation?.peerDisplayName ?: result.conversationId,
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(result.text)
                    Text(
                        "序号 ${result.conversationSeq ?: "待确认"}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
}

@Composable
private fun GroupPanel(
    state: GroupUiState,
    viewModel: GroupViewModel,
    loadGroupAvatar: suspend (UUID, Long) -> ByteArray?,
    loadSearchAvatar: suspend (String) -> ByteArray?,
    onOpenGroup: (GroupSummary) -> Unit,
) {
    var selectedSection by rememberSaveable { mutableStateOf("owned") }
    LaunchedEffect(state.autoResolveInvite) {
        if (state.autoResolveInvite) {
            selectedSection = "invite"
            viewModel.resolveInvite()
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { selectedSection = "owned" }, modifier = Modifier.weight(1f)) { Text("我的群") }
            OutlinedButton(onClick = { selectedSection = "search" }, modifier = Modifier.weight(1f)) { Text("找群") }
            OutlinedButton(onClick = { selectedSection = "invite" }, modifier = Modifier.weight(1f)) { Text("邀请") }
            OutlinedButton(onClick = { selectedSection = "create" }, modifier = Modifier.weight(1f)) { Text("建群") }
        }
        when (selectedSection) {
            "search" -> GroupSearchPanel(state, viewModel, loadSearchAvatar)
            "invite" -> GroupInvitePanel(state, viewModel)
            "create" -> GroupCreatePanel(state, viewModel)
            else -> GroupListPanel(state, viewModel, loadGroupAvatar, onOpenGroup)
        }
    }
}

@Composable
private fun GroupListPanel(
    state: GroupUiState,
    viewModel: GroupViewModel,
    load: suspend (UUID, Long) -> ByteArray?,
    onOpenGroup: (GroupSummary) -> Unit,
) {
    if (state.groups.isEmpty()) { Text("还没有加入群聊。"); return }
    state.groups.forEach { group ->
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                RemoteGroupAvatar(
                    conversationId = group.conversationId,
                    avatarVersion = group.avatarVersion,
                    fallback = group.name.take(2),
                    load = load,
                    size = 56.dp,
                )
                Text(group.name, style = MaterialTheme.typography.titleMedium)
                Text("群号 ${group.groupNo}")
                Text(group.description.ifBlank { "暂无简介" })
                Text("${group.visibility} · ${group.role} · ${group.memberCount} 人")
                Button(onClick = { onOpenGroup(group) }) {
                    Text("进入群聊")
                }
                if (GroupGovernancePolicy.canEditProfile(group.role)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { viewModel.createInvite(group) }) { Text("生成邀请") }
                        OutlinedButton(onClick = { viewModel.loadJoinRequests(group) }) { Text("入群审批") }
                        OutlinedButton(onClick = { viewModel.loadMembers(group) }) { Text("群管理") }
                    }
                    OutlinedTextField(
                        value = state.directInviteAccountNo,
                        onValueChange = viewModel::setDirectInviteAccountNo,
                        label = { Text("直接邀请账号") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Button(
                        onClick = { viewModel.directInvite(group) },
                        enabled = !state.loading,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("无需审批直接加入") }
                } else {
                    OutlinedButton(
                        onClick = { viewModel.leave(group) },
                        enabled = !state.loading,
                    ) { Text("退出群聊") }
                }
                if (group.role == "OWNER") {
                    OutlinedButton(
                        onClick = { viewModel.dissolve(group) },
                        enabled = !state.loading,
                    ) { Text("解散群聊") }
                }
                state.createdInvite
                    ?.takeIf { it.conversationId == group.conversationId }
                    ?.let { invite ->
                        Text("邀请链接：${invite.deepLink}", style = MaterialTheme.typography.bodySmall)
                        InviteQrCode(invite.qrPayload)
                    }
                state.joinRequests
                    .filter { it.conversationId == group.conversationId && it.status == "PENDING" }
                    .forEach { request ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text("${request.displayName} 申请入群")
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                Button(onClick = { viewModel.approveJoinRequest(request) }) {
                                    Text("通过")
                                }
                                OutlinedButton(onClick = { viewModel.rejectJoinRequest(request) }) {
                                    Text("拒绝")
                                }
                            }
                        }
                    }
                if (state.memberGroupId == group.conversationId) {
                    GroupManagementPanel(
                        group = group,
                        members = state.members,
                        state = state,
                        viewModel = viewModel,
                    )
                }
            }
        }
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
    val imagePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val bytes = context.contentResolver.openInputStream(uri)?.use { input -> input.readBytes() }
            ?: return@rememberLauncherForActivityResult
        viewModel.sendImage(bytes)
    }
    LaunchedEffect(group.conversationId) {
        viewModel.open(group.conversationId)
    }
    LaunchedEffect(state.messages, group.conversationId) {
        val lastIndex = state.messages.lastIndex
        if (lastIndex >= 0) listState.animateScrollToItem(lastIndex)
    }
    LaunchedEffect(state.messages.size, group.conversationId) {
        val lastSequence = state.messages.lastOrNull()?.conversationSeq ?: return@LaunchedEffect
        viewModel.markRead(lastSequence)
    }
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        TextButton(onClick = onBack) { Text("返回群列表") }
        RemoteGroupAvatar(
            conversationId = group.conversationId,
            avatarVersion = group.avatarVersion,
            fallback = group.name.take(2),
            load = loadGroupAvatar,
            size = 64.dp,
        )
        Text(group.name, style = MaterialTheme.typography.headlineMedium)
        Text("群号 ${group.groupNo} · ${group.memberCount} 人")
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f, fill = false)
                .heightIn(max = 420.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (state.messages.isEmpty()) {
                item { Text("还没有群消息") }
            }
            items(state.messages, key = { it.messageId }) { message ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp)) {
                        when {
                            message.type == "SYSTEM" -> Text(message.groupMessageText())
                            message.state == "RECALLED" -> Text(message.groupMessageText())
                            message.state == "MODERATED" -> Text(message.groupMessageText())
                            message.type == "IMAGE" -> ImageMessageContent(message) { item, thumbnail ->
                                viewModel.loadMedia(item, thumbnail)
                            }
                            else -> Text(message.text)
                        }
                        Text(
                            if (message.conversationSeq != null) {
                                "序号 ${message.conversationSeq}"
                            } else {
                                "等待确认"
                            },
                            style = MaterialTheme.typography.bodySmall,
                        )
                        if (message.state == "ACTIVE"
                            && message.localState == "SENT"
                            && message.senderId == state.currentUserId?.toString()
                        ) {
                            TextButton(onClick = { viewModel.recall(message) }) { Text("撤回") }
                        }
                    }
                }
            }
        }
        OutlinedTextField(
            value = state.draft,
            onValueChange = viewModel::setDraft,
            enabled = !state.loading,
            label = { Text("输入群消息") },
            modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = viewModel::send,
                enabled = state.draft.isNotBlank() && !state.loading,
                modifier = Modifier.weight(1f),
            ) { Text("发送") }
            OutlinedButton(
                onClick = { imagePicker.launch("image/*") },
                enabled = !state.loading,
                modifier = Modifier.weight(1f),
            ) { Text("发送图片") }
        }
        state.message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }
}

@Composable
private fun GroupManagementPanel(
    group: GroupSummary,
    members: List<GroupMemberSummary>,
    state: GroupUiState,
    viewModel: GroupViewModel,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(
            Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text("群管理", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(
                value = state.name.ifBlank { group.name },
                onValueChange = viewModel::setName,
                label = { Text("群名称") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = state.description.ifBlank { group.description },
                onValueChange = viewModel::setDescription,
                label = { Text("群简介") },
                modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("PUBLIC", "UNLISTED", "PRIVATE").forEach { visibility ->
                    OutlinedButton(onClick = { viewModel.setVisibility(visibility) }) {
                        Text(if (group.visibility == visibility) "✓ $visibility" else visibility)
                    }
                }
            }
            Button(
                onClick = { viewModel.updateProfile(group) },
                enabled = !state.loading,
            ) { Text("保存群资料") }
            members.forEach { member ->
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("${member.displayName} · ${member.role}")
                        Text("账号 ${member.accountNo}", style = MaterialTheme.typography.bodySmall)
                    }
                    if (member.role == "MEMBER" && group.role == "OWNER") {
                        OutlinedButton(onClick = { viewModel.promoteMember(group, member.userId) }) {
                            Text("任命管理员")
                        }
                    } else if (member.role == "ADMIN" && group.role == "OWNER") {
                        OutlinedButton(onClick = { viewModel.demoteMember(group, member.userId) }) {
                            Text("降级")
                        }
                        OutlinedButton(onClick = { viewModel.transferOwner(group, member.userId) }) {
                            Text("转让群主")
                        }
                    }
                    if (GroupGovernancePolicy.canRemoveMember(group.role, member.role)) {
                        OutlinedButton(onClick = { viewModel.removeMember(group, member) }) {
                            Text("移除")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun InviteQrCode(payload: String) {
    val bitmap = remember(payload) { createQrBitmap(payload) } ?: return
    Image(
        bitmap = bitmap.asImageBitmap(),
        contentDescription = "群邀请二维码",
        modifier = Modifier.size(220.dp),
    )
}

private fun createQrBitmap(payload: String): Bitmap? = runCatching {
    val matrix = MultiFormatWriter().encode(payload, BarcodeFormat.QR_CODE, 512, 512)
    val bitmap = Bitmap.createBitmap(matrix.width, matrix.height, Bitmap.Config.ARGB_8888)
    for (x in 0 until matrix.width) {
        for (y in 0 until matrix.height) {
            bitmap.setPixel(x, y, if (matrix[x, y]) Color.BLACK else Color.WHITE)
        }
    }
    bitmap
}.getOrNull()

@Composable
private fun GroupInvitePanel(
    state: GroupUiState,
    viewModel: GroupViewModel,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = state.inviteToken,
            onValueChange = viewModel::setInviteToken,
            label = { Text("邀请令牌") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Button(
            onClick = viewModel::resolveInvite,
            enabled = state.inviteToken.isNotBlank() && !state.loading,
            modifier = Modifier.fillMaxWidth(),
        ) { Text("打开邀请页") }
        state.invite?.let { invite ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(invite.name, style = MaterialTheme.typography.titleMedium)
                    Text(invite.description.ifBlank { "暂无简介" })
                    Text("${invite.visibility} · ${invite.memberCount} 人")
                    Button(onClick = viewModel::requestToJoin) { Text("提交入群申请") }
                }
            }
        }
    }
}

@Composable
private fun GroupSearchPanel(state: GroupUiState, viewModel: GroupViewModel, load: suspend (String) -> ByteArray?) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(value = state.searchQuery, onValueChange = viewModel::setSearchQuery, label = { Text("群号或 PUBLIC 群名称") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Button(onClick = viewModel::search, enabled = state.searchQuery.isNotBlank() && !state.loading, modifier = Modifier.fillMaxWidth()) { Text("搜索群") }
        state.searchResults.forEach { group ->
            Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                group.avatarUrl?.let {
                    RemoteSearchGroupAvatar(
                        avatarUrl = it,
                        fallback = group.name.take(2),
                        load = load,
                        size = 56.dp,
                    )
                } ?: AvatarView(
                    bytes = null,
                    fallback = group.name.take(2),
                    size = 56.dp,
                )
                Text(group.name, style = MaterialTheme.typography.titleMedium)
                Text(group.description.ifBlank { "暂无简介" })
                Text("${group.memberCount} 人")
            }}
        }
    }
}

@Composable
private fun GroupCreatePanel(state: GroupUiState, viewModel: GroupViewModel) {
    val context = LocalContext.current
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val bytes = context.contentResolver.openInputStream(uri)?.use { readCappedBytes(it, 11 * 1024 * 1024) }
        viewModel.setAvatar(bytes)
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(state.name, viewModel::setName, label = { Text("群名称") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(state.description, viewModel::setDescription, label = { Text("群简介") }, modifier = Modifier.fillMaxWidth())
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            AvatarView(
                bytes = state.avatar,
                fallback = state.name.firstOrNull()?.toString() ?: "?",
                size = 56.dp,
            )
            OutlinedButton(onClick = { picker.launch("image/*") }) { Text(if (state.avatar == null) "选择群头像" else "更换群头像") }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) { listOf("PUBLIC", "UNLISTED", "PRIVATE").forEach { visibility -> OutlinedButton(onClick = { viewModel.setVisibility(visibility) }, modifier = Modifier.weight(1f)) { Text(visibility) } } }
        Button(onClick = viewModel::create, enabled = !state.loading && state.name.isNotBlank(), modifier = Modifier.fillMaxWidth()) { Text("创建群") }
    }
}

private fun readCappedBytes(input: java.io.InputStream, maximumBytes: Int): ByteArray? {
    val output = ByteArrayOutputStream(); val buffer = ByteArray(DEFAULT_BUFFER_SIZE); var total = 0
    while (true) { val read = input.read(buffer); if (read < 0) return output.toByteArray(); total += read; if (total > maximumBytes) return null; output.write(buffer, 0, read) }
}

@Composable
private fun ContactSearchPanel(state: ContactUiState, viewModel: ContactViewModel) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = state.searchAccountNo,
            onValueChange = viewModel::setSearchAccountNo,
            label = { Text("完整账号") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Button(
            onClick = viewModel::search,
            enabled = state.searchAccountNo.length == 11 && !state.loading,
            modifier = Modifier.fillMaxWidth(),
        ) { Text("精确搜索") }
        state.searchResult?.let { result ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    AvatarView(
                        bytes = null,
                        fallback = result.avatarFallback,
                        size = 48.dp,
                    )
                    Text(result.displayName, style = MaterialTheme.typography.titleMedium)
                    Text("账号 ${result.accountNo}")
                    Text("关系 ${result.relationship}")
                    if (result.relationship == "NONE" || result.relationship == "REMOVED") {
                        Button(onClick = { viewModel.addContact(result.accountNo) }) {
                            Text("申请联系人")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ContactRequestsPanel(state: ContactUiState, viewModel: ContactViewModel) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        state.requests.forEach { request ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("${request.peerDisplayName} · ${request.peerAccountNo}")
                    Text(if (request.incoming) "收到联系人申请" else "已发出联系人申请")
                    Text(request.verification.ifBlank { "无验证信息" })
                    if (request.status == "PENDING") {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (request.incoming) {
                                Button(onClick = { viewModel.accept(request.requestId) }) { Text("接受") }
                                OutlinedButton(onClick = { viewModel.reject(request.requestId) }) { Text("拒绝") }
                            } else {
                                OutlinedButton(onClick = { viewModel.cancel(request.requestId) }) { Text("取消") }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ContactListPanel(
    state: ContactUiState,
    viewModel: ContactViewModel,
    loadAvatar: suspend (UUID, Long) -> ByteArray?,
    onOpenConversation: (java.util.UUID) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (state.contacts.isEmpty()) {
            Text("还没有联系人，先搜索完整账号发起申请。")
        }
        state.contacts.forEach { contact ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    RemoteAvatar(
                        userId = contact.userId,
                        avatarVersion = contact.avatarVersion,
                        fallback = contact.avatarFallback,
                        load = loadAvatar,
                    )
                    Text(contact.displayName, style = MaterialTheme.typography.titleMedium)
                    Text("账号 ${contact.accountNo}")
                    Text("会话 ${contact.conversationId}")
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { onOpenConversation(contact.conversationId) }) {
                            Text("进入聊天")
                        }
                        OutlinedButton(onClick = { viewModel.remove(contact.userId) }) { Text("删除") }
                        OutlinedButton(onClick = { viewModel.block(contact.userId) }) { Text("拉黑") }
                    }
                }
            }
        }
        state.conversations.filter { it.status == "READ_ONLY" }.forEach { conversation ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("${conversation.peerDisplayName}：历史只读")
                if (conversation.blockedByMe) {
                    OutlinedButton(
                        onClick = { viewModel.unblock(conversation.peerUserId) },
                    ) { Text("解除拉黑") }
                }
            }
        }
    }
}

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
    val context = LocalContext.current
    val imagePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val bytes = context.contentResolver.openInputStream(uri)?.use { input ->
            input.readBytes()
        } ?: return@rememberLauncherForActivityResult
        viewModel.sendImage(bytes)
    }
    LaunchedEffect(conversation.conversationId) {
        viewModel.open(conversation.conversationId)
        aiViewModel.open(conversation.conversationId)
    }
    LaunchedEffect(state.messages, initialMessageId) {
        val messageId = initialMessageId ?: return@LaunchedEffect
        val index = state.messages.indexOfFirst { it.messageId == messageId }
        if (index >= 0) {
            listState.animateScrollToItem(index)
        }
    }
    LaunchedEffect(state.messages.size) {
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index }
            .collect { lastVisibleIndex ->
                val readSeq = lastVisibleIndex
                    ?.let { state.messages.getOrNull(it)?.conversationSeq }
                    ?: return@collect
                viewModel.markRead(readSeq)
            }
    }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        TextButton(onClick = onBack) { Text("返回联系人") }
        RemoteAvatar(
            userId = conversation.peerUserId,
            avatarVersion = conversation.avatarVersion,
            fallback = conversation.avatarFallback,
            load = loadAvatar,
            size = 64.dp,
        )
        Text(conversation.peerDisplayName, style = MaterialTheme.typography.headlineMedium)
        conversation.peerAccountNo?.let { Text("账号 $it") }
        Text("会话 ID ${conversation.conversationId}", style = MaterialTheme.typography.bodySmall)
        if (conversation.status == "READ_ONLY") {
            Text("联系人关系已结束，历史消息保持只读。")
        }
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f, fill = false)
                .heightIn(max = 420.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (state.messages.isEmpty()) {
                item { Text("还没有消息") }
            }
            items(state.messages, key = { it.messageId }) { message ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp)) {
                        if (message.senderDisplayName.isNotBlank()) {
                            Text(
                                message.senderDisplayName,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        if (message.state == "RECALLED") {
                            Text("消息已撤回")
                        } else if (message.state == "MODERATED") {
                            Text("消息已被治理")
                        } else if (message.type == "IMAGE") {
                            ImageMessageContent(message) { item, thumbnail ->
                                viewModel.loadMedia(item, thumbnail)
                            }
                        } else {
                            Text(message.text)
                        }
                        Text(
                            when {
                                message.localState == "SENDING" -> "发送中"
                                message.localState == "QUEUED" -> "等待网络"
                                message.localState == "MANUAL_RETRY" -> "需手动重试"
                                message.state == "RECALLED" -> "撤回"
                                message.state == "MODERATED" -> "已被治理"
                                message.conversationSeq != null -> "序号 ${message.conversationSeq}"
                                else -> "等待确认"
                            },
                            style = MaterialTheme.typography.bodySmall,
                        )
                        if (message.state == "ACTIVE" &&
                            message.localState == "SENT" &&
                            message.senderId == state.currentUserId?.toString()
                        ) {
                            TextButton(onClick = { viewModel.recall(message) }) {
                                Text("撤回")
                            }
                        }
                        if (message.localState == "MANUAL_RETRY") {
                            TextButton(onClick = { viewModel.retry(UUID.fromString(message.clientMsgId)) }) {
                                Text("重试")
                            }
                        }
                        if (message.state == "ACTIVE" && message.messageId.isNotBlank()) {
                            val messageId = runCatching { UUID.fromString(message.messageId) }.getOrNull()
                            if (messageId != null) {
                                OutlinedButton(onClick = { aiViewModel.toggleMessage(messageId) }) {
                                    Text(if (messageId in aiState.selectedMessageIds) "已选作证据" else "选择作证据")
                                }
                            }
                        }
                    }
                }
            }
        }
        Card(Modifier.fillMaxWidth()) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = 320.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("私人 AI", style = MaterialTheme.typography.titleMedium)
                Text(
                    when {
                        aiState.enabledForBoth -> "双方已同意，结果仅自己可见"
                        aiState.consentEnabled -> "已同意，等待对方开启"
                        else -> "需要双方同意后才能使用"
                    },
                )
                OutlinedButton(
                    onClick = { aiViewModel.updateConsent(!aiState.consentEnabled) },
                    enabled = !aiState.loading,
                ) { Text(if (aiState.consentEnabled) "关闭我的同意" else "开启我的同意") }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = aiViewModel::requestSmartReplies,
                        enabled = aiState.enabledForBoth && !aiState.loading,
                    ) { Text("生成 3 条回复") }
                    Button(
                        onClick = aiViewModel::extractSelected,
                        enabled = aiState.enabledForBoth &&
                            aiState.selectedMessageIds.isNotEmpty() &&
                            !aiState.loading,
                    ) { Text("提取所选信息") }
                }
                aiState.drafts.forEachIndexed { index, reply ->
                    OutlinedTextField(
                        value = reply.text,
                        onValueChange = { aiViewModel.updateDraft(index, it) },
                        label = { Text("${reply.tone} 可编辑草稿") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Button(onClick = { viewModel.setDraft(reply.text) }) { Text("放入输入框") }
                }
                aiState.keyFacts.forEach { fact ->
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(10.dp)) {
                            Text("${fact.category} · 置信度 ${fact.confidence}")
                            Text(fact.content)
                            Text("证据 ${fact.sourceMessageIds.joinToString()}", style = MaterialTheme.typography.bodySmall)
                            TextButton(onClick = { aiViewModel.deleteArtifact(fact.artifactId) }) {
                                Text("删除关键信息")
                            }
                        }
                    }
                }
                aiState.actionItems.forEach { item ->
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(10.dp)) {
                            Text("${item.title} · ${item.priority}")
                            Text(item.details)
                            item.assigneeUserId?.let {
                                Text("AI 识别负责人 $it；待办仍只属于你")
                            }
                            Text("证据 ${item.sourceMessageIdsJson}", style = MaterialTheme.typography.bodySmall)
                            Row {
                                TextButton(onClick = {
                                    aiViewModel.setActionItemCompleted(
                                        item.actionItemId,
                                        item.status != "COMPLETED",
                                    )
                                }) { Text(if (item.status == "COMPLETED") "重新打开" else "完成") }
                                TextButton(onClick = { aiViewModel.deleteActionItem(item.actionItemId) }) {
                                    Text("删除")
                                }
                            }
                        }
                    }
                }
                aiState.message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        }
        OutlinedTextField(
            value = state.draft,
            onValueChange = viewModel::setDraft,
            enabled = conversation.status == "ACTIVE",
            label = { Text("输入文本") },
            modifier = Modifier.fillMaxWidth(),
        )
        Button(
            onClick = viewModel::send,
            enabled = conversation.status == "ACTIVE" &&
                state.draft.isNotBlank() &&
                !state.loading,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("发送")
        }
        OutlinedButton(
            onClick = { imagePicker.launch("image/*") },
            enabled = conversation.status == "ACTIVE" && !state.loading,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("发送图片")
        }
        state.message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }
}

@Composable
private fun AvatarProfilePanel(
    state: AvatarUiState,
    viewModel: AvatarViewModel,
) {
    val context = LocalContext.current
    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: return@rememberLauncherForActivityResult
        viewModel.replace(bytes)
    }
    Card(Modifier.fillMaxWidth()) {
        Column(
            Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("我的头像", style = MaterialTheme.typography.titleMedium)
            AvatarView(
                bytes = state.bytes,
                fallback = state.profile?.avatarFallback ?: "?",
                size = 72.dp,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { picker.launch("image/*") },
                    enabled = !state.loading,
                ) { Text("更换头像") }
                OutlinedButton(
                    onClick = viewModel::remove,
                    enabled = !state.loading,
                ) { Text("移除头像") }
            }
            state.message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }
}
