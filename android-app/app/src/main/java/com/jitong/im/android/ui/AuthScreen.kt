package com.jitong.im.android.ui

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jitong.im.android.auth.SessionState
import com.jitong.im.android.contact.ConversationSummary

@Composable
internal fun JitongApp(
    viewModel: AuthViewModel,
    contactViewModel: ContactViewModel,
    messageViewModel: MessageViewModel,
) {
    val state by viewModel.sessionState.collectAsStateWithLifecycle()
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        when (val current = state) {
            SessionState.SignedOut -> LoginScreen(viewModel)
            SessionState.Restoring -> RestoringScreen()
            is SessionState.ReplacementRequired -> ReplacementScreen(current, viewModel)
            is SessionState.SignedIn -> HomeScreen(current, viewModel, contactViewModel, messageViewModel)
            is SessionState.Error -> LoginScreen(viewModel, current.message)
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
) {
    val contactState by contactViewModel.state.collectAsStateWithLifecycle()
    var selectedTab by rememberSaveable { mutableStateOf("contacts") }
    var selectedConversationId by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(state.session.userId) {
        contactViewModel.refresh()
    }
    val selectedConversation = contactState.conversations
        .firstOrNull { it.conversationId.toString() == selectedConversationId }
    if (selectedConversation != null) {
        ConversationScreen(
            conversation = selectedConversation,
            viewModel = messageViewModel,
            onBack = { selectedConversationId = null },
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
        }
        when (selectedTab) {
            "search" -> ContactSearchPanel(contactState, contactViewModel)
            "requests" -> ContactRequestsPanel(contactState, contactViewModel)
            else -> ContactListPanel(
                state = contactState,
                viewModel = contactViewModel,
                onOpenConversation = { selectedConversationId = it.toString() },
            )
        }
        contactState.message?.let {
            Text(it, color = MaterialTheme.colorScheme.error)
        }
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("账号 ${state.session.accountNo}", style = MaterialTheme.typography.titleMedium)
                Text("设备类型 ${state.session.deviceClass}")
                Text("本地空间：独立 SQLCipher 数据库")
                Text("退出后仍保留加密历史，受保护页面仅在有效会话下可访问。")
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
    onOpenConversation: (java.util.UUID) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (state.contacts.isEmpty()) {
            Text("还没有联系人，先搜索完整账号发起申请。")
        }
        state.contacts.forEach { contact ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
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
    onBack: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    LaunchedEffect(conversation.conversationId) {
        viewModel.open(conversation.conversationId)
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
        Text(conversation.peerDisplayName, style = MaterialTheme.typography.headlineMedium)
        Text("账号 ${conversation.peerAccountNo}")
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
                        Text(message.text)
                        Text(
                            when {
                                message.localState == "SENDING" -> "发送中"
                                message.conversationSeq != null -> "序号 ${message.conversationSeq}"
                                else -> "等待确认"
                            },
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
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
        state.message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }
}
