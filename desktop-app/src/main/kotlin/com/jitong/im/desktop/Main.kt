package com.jitong.im.desktop

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.Image
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toPainter
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import com.jitong.im.desktop.auth.AuthApiException
import com.jitong.im.desktop.auth.AuthClient
import com.jitong.im.desktop.auth.DesktopAuthStore
import com.jitong.im.desktop.auth.DesktopSession
import com.jitong.im.desktop.auth.LoginOutcome
import com.jitong.im.desktop.conversation.ConversationApiException
import com.jitong.im.desktop.conversation.ConversationClient
import com.jitong.im.desktop.conversation.DesktopContactRequestSummary
import com.jitong.im.desktop.conversation.DesktopContactSearchResult
import com.jitong.im.desktop.conversation.DesktopRealtimeClient
import com.jitong.im.desktop.conversation.RealtimeCommandException
import com.jitong.im.desktop.conversation.SyncGapException
import com.jitong.im.desktop.local.LocalConversation
import com.jitong.im.desktop.local.LocalDatabaseManager
import com.jitong.im.desktop.local.LocalMessage
import com.jitong.im.desktop.local.MacOsKeychain
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.nio.file.Path
import java.util.UUID
import java.io.ByteArrayInputStream
import javax.imageio.ImageIO

fun main() = application {
    val authStore = rememberDesktopAuthStore()
    val conversationClient = rememberDesktopConversationClient()
    Window(
        onCloseRequest = {
            authStore.close()
            exitApplication()
        },
        title = "Jitong") {
        MaterialTheme {
            Surface(modifier = Modifier.fillMaxSize()) {
                DesktopApp(authStore, conversationClient)
            }
        }
    }
}

@Composable
private fun rememberDesktopConversationClient(): ConversationClient = remember {
    ConversationClient(
        System.getenv("JITONG_SERVER_URL")
            ?: "https://127.0.0.1:8443")
}

@Composable
private fun rememberDesktopAuthStore(): DesktopAuthStore = remember {
    val accountDirectory = Path.of(
        System.getProperty("user.home"),
        "Library",
        "Application Support",
        "Jitong",
        "accounts")
    DesktopAuthStore(
        authClient = AuthClient(
            System.getenv("JITONG_SERVER_URL")
                ?: "https://127.0.0.1:8443"),
        databaseManager = LocalDatabaseManager(accountDirectory, MacOsKeychain()),
        installationId = installationId(accountDirectory))
}

private data class DesktopData(
    val conversations: List<LocalConversation> = emptyList(),
    val messages: List<LocalMessage> = emptyList(),
    val requests: List<DesktopContactRequestSummary> = emptyList(),
)

@Composable
private fun DesktopApp(
    authStore: DesktopAuthStore,
    conversationClient: ConversationClient,
) {
    var accountNo by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var challenge by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var session by remember { mutableStateOf(authStore.session) }
    var restoring by remember { mutableStateOf(true) }
    var data by remember { mutableStateOf(DesktopData()) }
    var selectedConversationId by remember { mutableStateOf<String?>(null) }
    var searchAccountNo by remember { mutableStateOf("") }
    var searchResult by remember { mutableStateOf<DesktopContactSearchResult?>(null) }
    var draft by remember { mutableStateOf("") }
    var online by remember { mutableStateOf(false) }
    var avatarBytes by remember { mutableStateOf<Map<String, ByteArray>>(emptyMap()) }
    val uiScope = androidx.compose.runtime.rememberCoroutineScope()
    val syncMutex = remember { Mutex() }

    fun refreshLocal() {
        val local = authStore.localDatabase() ?: return
        val conversations = local.listConversations()
        data = data.copy(
            conversations = conversations,
            messages = selectedConversationId?.let(local::listMessages).orEmpty())
        val token = authStore.session?.accessToken ?: session?.accessToken
        if (token != null) {
            conversations
                .filter { it.peerAvatarVersion > 0 }
                .forEach { conversation ->
                    conversationClient.loadUserAvatar(
                        token,
                        local,
                        conversation.peerUserId,
                        conversation.peerAvatarVersion)
                        ?.let { bytes ->
                            avatarBytes = avatarBytes + (conversation.peerUserId to bytes)
                        }
                }
        }
    }

    fun refreshRequests() {
        val current = authStore.session ?: session ?: return
        uiScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    conversationClient.listContactRequests(current.accessToken)
                }
            }.onSuccess { data = data.copy(requests = it) }
                .onFailure { error = messageFor(it) }
        }
    }

    LaunchedEffect(authStore) {
        runCatching {
            withContext(Dispatchers.IO) { authStore.restore() }
        }.onSuccess {
            session = it
        }.onFailure {
            error = messageFor(it)
        }
        restoring = false
    }

    LaunchedEffect(session?.deviceId) {
        val current = session ?: return@LaunchedEffect
        val local = authStore.localDatabase() ?: return@LaunchedEffect
        var knownHighWatermark: Long? = null
        refreshLocal()
        withContext(Dispatchers.IO) {
            runCatching {
                knownHighWatermark = synchronize(
                    conversationClient,
                    local,
                    authStore.session ?: current)
                local.listConversations().forEach { conversation ->
                    conversationClient.restoreReadStates(
                        (authStore.session ?: current).accessToken,
                        local,
                        conversation.conversationId)
                }
            }.onFailure { error = messageFor(it) }
        }
        refreshLocal()
        refreshRequests()
        val realtime = DesktopRealtimeClient(
            baseUrl = serverUrl(),
            accessToken = { authStore.session?.accessToken },
            onEnvelope = { envelope ->
                uiScope.launch {
                    runCatching {
                        syncMutex.withLock {
                            withContext(Dispatchers.IO) {
                                if (envelope.operation == "sync.ready") {
                                    val highWatermark = envelope.body?.highWatermark
                                        ?: return@withContext
                                    knownHighWatermark = highWatermark
                                    runCatching {
                                        synchronize(
                                            conversationClient,
                                            local,
                                            authStore.session ?: current,
                                            highWatermark)
                                    }.onFailure {
                                        if (it is ConversationApiException && it.statusCode == 409) {
                                            conversationClient.fullRestore(
                                                (authStore.session ?: current).accessToken,
                                                local,
                                                (authStore.session ?: current).userId,
                                                highWatermark)
                                        } else {
                                            throw it
                                        }
                                    }
                                } else {
                                    conversationClient.applyRealtime(
                                        local,
                                        envelope,
                                        (authStore.session ?: current).userId)
                                }
                                if (envelope.operation != "sync.ready"
                                    && envelope.body?.syncSeq != null) {
                                    conversationClient.acknowledge(
                                        (authStore.session ?: current).accessToken,
                                        envelope.body.syncSeq)
                                }
                            }
                        }
                        refreshLocal()
                    }.onFailure {
                        if (it is SyncGapException) {
                            runCatching {
                                syncMutex.withLock {
                                    withContext(Dispatchers.IO) {
                                        val latest = authStore.session ?: current
                                        if (knownHighWatermark == null) {
                                            throw it
                                        }
                                        conversationClient.fullRestore(
                                            latest.accessToken,
                                            local,
                                            latest.userId,
                                            knownHighWatermark!!)
                                    }
                                }
                                refreshLocal()
                            }.onFailure { resetFailure -> error = messageFor(resetFailure) }
                        } else {
                            error = messageFor(it)
                        }
                    }
                }
            },
            onConnectionState = { online = it })
        realtime.connect()
        try {
            while (true) {
                runCatching {
                    syncMutex.withLock {
                        withContext(Dispatchers.IO) {
                            knownHighWatermark = synchronize(
                                conversationClient,
                                local,
                                authStore.session ?: current)
                        }
                    }
                    refreshLocal()
                    refreshRequests()
                }.onFailure {
                    if ((it is ConversationApiException && it.statusCode == 409)
                        || it is SyncGapException) {
                        syncMutex.withLock {
                            withContext(Dispatchers.IO) {
                                val latest = authStore.session ?: current
                                val highWatermark = knownHighWatermark
                                    ?: throw it
                                conversationClient.fullRestore(
                                    latest.accessToken,
                                    local,
                                    latest.userId,
                                    highWatermark)
                            }
                        }
                        refreshLocal()
                    } else {
                        error = messageFor(it)
                    }
                }
                delay(30_000)
                runCatching {
                    withContext(Dispatchers.IO) { authStore.validateAccess() }
                    session = authStore.session
                    realtime.reconnect()
                }.onFailure {
                    error = messageFor(it)
                    if (authStore.session == null) {
                        session = null
                        return@LaunchedEffect
                    }
                }
            }
        } finally {
            realtime.close()
        }
    }

    if (restoring) {
        CenteredMessage("Restoring your secure session…")
    } else if (session == null) {
        LoginScreen(
            accountNo = accountNo,
            password = password,
            challenge = challenge,
            error = error,
            onAccountNoChange = { accountNo = it.filter(Char::isDigit).take(11) },
            onPasswordChange = { password = it },
            onLogin = {
                error = null
                runCatching { authStore.login(accountNo, password) }
                    .onSuccess { result ->
                        when (result) {
                            is LoginOutcome.Authenticated -> session = result.session
                            is LoginOutcome.ReplacementRequired -> challenge = result.challenge
                        }
                    }
                    .onFailure { error = messageFor(it) }
            },
            onConfirmReplacement = {
                runCatching { authStore.confirmReplacement(challenge.orEmpty()) }
                    .onSuccess {
                        session = it
                        challenge = null
                    }
                    .onFailure { error = messageFor(it) }
            },
            onCancelReplacement = { challenge = null })
    } else {
        MainScreen(
            session = session!!,
            online = online,
            data = data,
            selectedConversationId = selectedConversationId,
            searchAccountNo = searchAccountNo,
            searchResult = searchResult,
            avatarBytes = avatarBytes,
            draft = draft,
            error = error,
            onSearchAccountNoChange = { searchAccountNo = it.filter(Char::isDigit).take(11) },
            onSearch = {
                error = null
                uiScope.launch {
                    runCatching {
                        withContext(Dispatchers.IO) {
                            conversationClient.search(
                                authStore.session?.accessToken ?: session!!.accessToken,
                                searchAccountNo)
                        }
                    }.onSuccess { searchResult = it }
                        .onFailure { error = messageFor(it) }
                }
            },
            onAddContact = {
                error = null
                uiScope.launch {
                    runCatching {
                        withContext(Dispatchers.IO) {
                            conversationClient.createContactRequest(
                                authStore.session?.accessToken ?: session!!.accessToken,
                                searchResult?.accountNo.orEmpty())
                        }
                    }.onSuccess {
                        searchResult = searchResult?.copy(relationship = "PENDING_OUTGOING")
                        refreshRequests()
                    }.onFailure { error = messageFor(it) }
                }
            },
            onAcceptRequest = { requestId ->
                uiScope.launch {
                    runCatching {
                        withContext(Dispatchers.IO) {
                            conversationClient.acceptContactRequest(
                                authStore.session?.accessToken ?: session!!.accessToken,
                                requestId)
                        }
                    }.onSuccess {
                        refreshRequests()
                        refreshLocal()
                    }.onFailure { error = messageFor(it) }
                }
            },
            onRejectRequest = { requestId ->
                uiScope.launch {
                    runCatching {
                        withContext(Dispatchers.IO) {
                            conversationClient.rejectContactRequest(
                                authStore.session?.accessToken ?: session!!.accessToken,
                                requestId)
                        }
                    }.onSuccess {
                        refreshRequests()
                    }.onFailure { error = messageFor(it) }
                }
            },
            onCancelRequest = { requestId ->
                uiScope.launch {
                    runCatching {
                        withContext(Dispatchers.IO) {
                            conversationClient.cancelContactRequest(
                                authStore.session?.accessToken ?: session!!.accessToken,
                                requestId)
                        }
                    }.onSuccess {
                        refreshRequests()
                    }.onFailure { error = messageFor(it) }
                }
            },
            onRemoveContact = { userId ->
                uiScope.launch {
                    runCatching {
                        withContext(Dispatchers.IO) {
                            conversationClient.removeContact(
                                authStore.session?.accessToken ?: session!!.accessToken,
                                userId)
                            synchronize(
                                conversationClient,
                                authStore.localDatabase()
                                    ?: error("PC local database is not open"),
                                authStore.session ?: session!!)
                        }
                        refreshLocal()
                    }.onFailure { error = messageFor(it) }
                }
            },
            onBlockContact = { userId ->
                uiScope.launch {
                    runCatching {
                        withContext(Dispatchers.IO) {
                            conversationClient.block(
                                authStore.session?.accessToken ?: session!!.accessToken,
                                userId)
                            synchronize(
                                conversationClient,
                                authStore.localDatabase()
                                    ?: error("PC local database is not open"),
                                authStore.session ?: session!!)
                        }
                        refreshLocal()
                    }.onFailure { error = messageFor(it) }
                }
            },
            onUnblockContact = { userId ->
                uiScope.launch {
                    runCatching {
                        withContext(Dispatchers.IO) {
                            conversationClient.unblock(
                                authStore.session?.accessToken ?: session!!.accessToken,
                                userId)
                            synchronize(
                                conversationClient,
                                authStore.localDatabase()
                                    ?: error("PC local database is not open"),
                                authStore.session ?: session!!)
                        }
                        refreshLocal()
                    }.onFailure { error = messageFor(it) }
                }
            },
            onSelectConversation = { conversationId ->
                selectedConversationId = conversationId
                uiScope.launch {
                    runCatching {
                        withContext(Dispatchers.IO) {
                            val local = authStore.localDatabase()
                                ?: error("PC local database is not open")
                            conversationClient.restoreConversation(
                                authStore.session?.accessToken ?: session!!.accessToken,
                                local,
                                conversationId,
                                authStore.session?.userId ?: session!!.userId)
                            conversationClient.restoreReadStates(
                                authStore.session?.accessToken ?: session!!.accessToken,
                                local,
                                conversationId)
                        }
                        refreshLocal()
                    }.onFailure { error = messageFor(it) }
                }
            },
            onDraftChange = { draft = it.take(4000) },
            onSend = {
                val conversationId = selectedConversationId ?: return@MainScreen
                val text = draft.trim()
                if (text.isBlank()) {
                    error = "Enter a message before sending."
                } else if (!online) {
                    error = "You are offline. History is available, but sending is disabled."
                } else {
                    val clientMsgId = UUID.randomUUID().toString()
                    uiScope.launch {
                        runCatching {
                            val activeConversation = data.conversations.firstOrNull {
                                it.conversationId == conversationId
                            }
                            if (activeConversation == null
                                || activeConversation.status != "ACTIVE"
                                || activeConversation.relationship != "ACTIVE"
                                || activeConversation.blockedByMe) {
                                error = "This conversation is read-only. Sending is disabled."
                                return@launch
                            }
                            withContext(Dispatchers.IO) {
                                val local = authStore.localDatabase()
                                    ?: error("PC local database is not open")
                                conversationClient.newPendingMessage(
                                    local,
                                    conversationId,
                                    authStore.session?.userId ?: session!!.userId,
                                    clientMsgId,
                                    text)
                                val sent = conversationClient.sendMessage(
                                    authStore.session?.accessToken ?: session!!.accessToken,
                                    conversationId,
                                    clientMsgId,
                                    text)
                                conversationClient.applyMessage(
                                    local,
                                    sent,
                                    authStore.session?.userId ?: session!!.userId)
                            }
                            refreshLocal()
                        }.onSuccess {
                            draft = ""
                        }.onFailure {
                            withContext(Dispatchers.IO) {
                                authStore.localDatabase()?.markMessageFailed(
                                    conversationId,
                                    clientMsgId)
                            }
                            error = messageFor(it)
                        }
                    }
                }
            },
            onMarkRead = { readSeq ->
                val conversationId = selectedConversationId ?: return@MainScreen
                uiScope.launch {
                    runCatching {
                        withContext(Dispatchers.IO) {
                            val page = conversationClient.markRead(
                                authStore.session?.accessToken ?: session!!.accessToken,
                                conversationId,
                                readSeq)
                            val local = authStore.localDatabase()
                                ?: error("PC local database is not open")
                            page.states.forEach { conversationClient.applyReadState(local, it) }
                        }
                        refreshLocal()
                    }.onFailure { error = messageFor(it) }
                }
            },
            onLogout = {
                authStore.logout()
                session = null
                data = DesktopData()
                selectedConversationId = null
            },
            onClearLocalData = {
                authStore.clearUntrustedLocalData()
                session = null
                data = DesktopData()
                selectedConversationId = null
            })
    }
}

@Composable
private fun CenteredMessage(message: String) {
    Column(
        modifier = Modifier.fillMaxSize().padding(56.dp),
        verticalArrangement = Arrangement.Center) {
        Text(message, style = MaterialTheme.typography.titleLarge)
    }
}

@Composable
private fun LoginScreen(
    accountNo: String,
    password: String,
    challenge: String?,
    error: String?,
    onAccountNoChange: (String) -> Unit,
    onPasswordChange: (String) -> Unit,
    onLogin: () -> Unit,
    onConfirmReplacement: () -> Unit,
    onCancelReplacement: () -> Unit,
) {
    Column(Modifier.fillMaxSize().padding(56.dp)) {
        Text("Jitong", style = MaterialTheme.typography.displaySmall)
        Spacer(Modifier.height(8.dp))
        Text("Sign in to your PC device", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(28.dp))
        OutlinedTextField(
            modifier = Modifier.fillMaxWidth(),
            value = accountNo,
            onValueChange = onAccountNoChange,
            label = { Text("Account number") },
            singleLine = true)
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            modifier = Modifier.fillMaxWidth(),
            value = password,
            onValueChange = onPasswordChange,
            label = { Text("Password") },
            visualTransformation = PasswordVisualTransformation(),
            singleLine = true)
        Spacer(Modifier.height(20.dp))
        Button(
            modifier = Modifier.fillMaxWidth(),
            enabled = accountNo.length == 11 && password.isNotBlank(),
            onClick = onLogin) {
            Text("Sign in")
        }
        if (challenge != null) {
            Spacer(Modifier.height(24.dp))
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(20.dp)) {
                    Text("A PC is already active", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    Text("Confirm replacement to sign in here. Your active MOBILE device is not affected.")
                    Spacer(Modifier.height(16.dp))
                    Row {
                        Button(onClick = onConfirmReplacement) { Text("Replace PC") }
                        Spacer(Modifier.width(12.dp))
                        OutlinedButton(onClick = onCancelReplacement) { Text("Cancel") }
                    }
                }
            }
        }
        error?.let {
            Spacer(Modifier.height(16.dp))
            Text(it, color = MaterialTheme.colorScheme.error)
        }
    }
}

@Composable
private fun MainScreen(
    session: DesktopSession,
    online: Boolean,
    data: DesktopData,
    selectedConversationId: String?,
    searchAccountNo: String,
    searchResult: DesktopContactSearchResult?,
    avatarBytes: Map<String, ByteArray>,
    draft: String,
    error: String?,
    onSearchAccountNoChange: (String) -> Unit,
    onSearch: () -> Unit,
    onAddContact: () -> Unit,
    onAcceptRequest: (String) -> Unit,
    onRejectRequest: (String) -> Unit,
    onCancelRequest: (String) -> Unit,
    onRemoveContact: (String) -> Unit,
    onBlockContact: (String) -> Unit,
    onUnblockContact: (String) -> Unit,
    onSelectConversation: (String) -> Unit,
    onDraftChange: (String) -> Unit,
    onSend: () -> Unit,
    onMarkRead: (Long) -> Unit,
    onLogout: () -> Unit,
    onClearLocalData: () -> Unit,
) {
    Column(Modifier.fillMaxSize().padding(28.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Column {
                Text("Jitong", style = MaterialTheme.typography.displaySmall)
                Text("PC · ${session.accountNo}")
                Text(
                    if (online) "Online and syncing" else "Offline · history available, sending disabled",
                    color = if (online) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.error)
            }
            Row {
                OutlinedButton(onClick = onLogout) { Text("Log out") }
                Spacer(Modifier.width(8.dp))
                OutlinedButton(onClick = onClearLocalData) { Text("Clear local data") }
            }
        }
        Spacer(Modifier.height(20.dp))
        Row(Modifier.fillMaxWidth()) {
            Column(Modifier.width(280.dp)) {
                Text("Contacts", style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    modifier = Modifier.fillMaxWidth(),
                    value = searchAccountNo,
                    onValueChange = onSearchAccountNoChange,
                    label = { Text("Search exact account") },
                    singleLine = true)
                Spacer(Modifier.height(8.dp))
                Button(
                    modifier = Modifier.fillMaxWidth(),
                    enabled = searchAccountNo.length == 11,
                    onClick = onSearch) { Text("Search") }
                searchResult?.let {
                    Card(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                        Column(Modifier.padding(12.dp)) {
                            DefaultAvatar(it.avatarFallback)
                            Text("${it.displayName} · ${it.accountNo}")
                            Text(it.relationship)
                            if (it.relationship == "NONE") {
                                Button(onClick = onAddContact) { Text("Add contact") }
                            }
                        }
                    }
                }
                Spacer(Modifier.height(16.dp))
                Text("Requests", style = MaterialTheme.typography.titleMedium)
                data.requests.forEach { request ->
                    Card(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                        Column(Modifier.padding(12.dp)) {
                            Text("${request.peerDisplayName} · ${request.peerAccountNo}")
                            Text(request.status)
                            if (request.incoming && request.status == "PENDING") {
                                Row {
                                    Button(onClick = { onAcceptRequest(request.requestId) }) {
                                        Text("Accept")
                                    }
                                    Spacer(Modifier.width(8.dp))
                                    OutlinedButton(onClick = { onRejectRequest(request.requestId) }) {
                                        Text("Reject")
                                    }
                                }
                            } else if (!request.incoming && request.status == "PENDING") {
                                OutlinedButton(onClick = { onCancelRequest(request.requestId) }) {
                                    Text("Cancel")
                                }
                            }
                        }
                    }
                }
                Spacer(Modifier.height(16.dp))
                Text("Conversations", style = MaterialTheme.typography.titleMedium)
                data.conversations.forEach { conversation ->
                    Card(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                        Column(Modifier.padding(8.dp)) {
                            DesktopAvatar(
                                bytes = avatarBytes[conversation.peerUserId],
                                fallback = conversation.peerAvatarFallback)
                            OutlinedButton(
                                modifier = Modifier.fillMaxWidth(),
                                onClick = { onSelectConversation(conversation.conversationId) }) {
                                Text("${conversation.peerDisplayName} · ${conversation.peerAccountNo}")
                            }
                            Row {
                                if (conversation.blockedByMe) {
                                    OutlinedButton(
                                        onClick = { onUnblockContact(conversation.peerUserId) }) {
                                        Text("Unblock")
                                    }
                                } else {
                                    OutlinedButton(
                                        onClick = { onBlockContact(conversation.peerUserId) }) {
                                        Text("Block")
                                    }
                                }
                                Spacer(Modifier.width(8.dp))
                                OutlinedButton(
                                    onClick = { onRemoveContact(conversation.peerUserId) }) {
                                    Text("Remove")
                                }
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.width(24.dp))
            ConversationPane(
                modifier = Modifier.weight(1f),
                selectedConversation = data.conversations.firstOrNull {
                    it.conversationId == selectedConversationId
                },
                messages = data.messages,
                avatarBytes = avatarBytes,
                draft = draft,
                online = online,
                error = error,
                onDraftChange = onDraftChange,
                onSend = onSend,
                onMarkRead = onMarkRead)
        }
    }
}

@Composable
private fun ConversationPane(
    modifier: Modifier,
    selectedConversation: LocalConversation?,
    messages: List<LocalMessage>,
    avatarBytes: Map<String, ByteArray>,
    draft: String,
    online: Boolean,
    error: String?,
    onDraftChange: (String) -> Unit,
    onSend: () -> Unit,
    onMarkRead: (Long) -> Unit,
) {
    if (selectedConversation == null) {
        Column(modifier.padding(28.dp)) {
            Text("Select a conversation to view local history.")
        }
        return
    }
    Column(modifier) {
        DesktopAvatar(
            bytes = avatarBytes[selectedConversation.peerUserId],
            fallback = selectedConversation.peerAvatarFallback,
            size = 64.dp)
        Text(
            "${selectedConversation.peerDisplayName} · ${selectedConversation.peerAccountNo}",
            style = MaterialTheme.typography.titleLarge)
        Text("${selectedConversation.status} · ${selectedConversation.relationship}")
        Spacer(Modifier.height(8.dp))
        LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth()) {
            items(messages, key = { it.messageId }) { message ->
                Card(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Column(Modifier.padding(12.dp)) {
                        Text(if (message.localState == "SENDING") "Sending…" else message.localState)
                        Text(message.text)
                        message.conversationSeq?.let {
                            Text("Message #$it")
                        }
                    }
                }
            }
        }
        if (!online) {
            Text(
                "Offline: you can browse history, but sending is disabled.",
                color = MaterialTheme.colorScheme.error)
        }
        Row(Modifier.fillMaxWidth().padding(top = 8.dp)) {
            OutlinedTextField(
                modifier = Modifier.weight(1f),
                value = draft,
                onValueChange = onDraftChange,
                enabled = online,
                label = { Text("Message") },
                singleLine = true)
            Spacer(Modifier.width(8.dp))
            Button(enabled = online, onClick = onSend) { Text("Send") }
        }
        val latestSequence = messages.mapNotNull { it.conversationSeq }.maxOrNull()
        LaunchedEffect(
            selectedConversation.conversationId,
            selectedConversation.readSeq,
            latestSequence,
            online) {
            if (online && latestSequence != null
                && latestSequence > selectedConversation.readSeq) {
                onMarkRead(latestSequence)
            }
        }
        error?.let {
            Text(it, color = MaterialTheme.colorScheme.error)
        }
    }
}

@Composable
private fun DefaultAvatar(fallback: String) {
    Card {
        Text(
            fallback.take(2),
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
private fun DesktopAvatar(
    bytes: ByteArray?,
    fallback: String,
    size: androidx.compose.ui.unit.Dp = 48.dp,
) {
    val painter = remember(bytes) {
        bytes?.let { ImageIO.read(ByteArrayInputStream(it))?.toPainter() }
    }
    if (painter == null) {
        DefaultAvatar(fallback)
    } else {
        Image(
            painter = painter,
            contentDescription = "Avatar",
            modifier = Modifier.size(size))
    }
}

private suspend fun synchronize(
    client: ConversationClient,
    local: com.jitong.im.desktop.local.LocalDatabase,
    current: DesktopSession,
    requestedUntil: Long? = null,
): Long {
    var afterSeq = local.lastSyncSeq()
    var untilSeq: Long? = requestedUntil
    var highWatermark = local.lastSyncSeq()
    do {
        val page = client.sync(current.accessToken, afterSeq, untilSeq)
        highWatermark = page.highWatermark
        untilSeq = page.untilSeq
        page.events
            .also { client.applySyncProfileEvents(current.accessToken, local, it) }
            .mapNotNull { it.conversationId }
            .distinct()
            .forEach { conversationId ->
                client.restoreConversation(
                    current.accessToken,
                    local,
                    conversationId,
                    current.userId)
                client.restoreReadStates(current.accessToken, local, conversationId)
            }
        afterSeq = page.nextAfterSeq
    } while (page.hasMore)
    val conversations = client.list(current.accessToken)
    client.replaceConversations(local, conversations)
    client.acknowledge(current.accessToken, afterSeq)
    local.saveLastSyncSeq(afterSeq)
    return highWatermark
}

private fun serverUrl(): String =
    System.getenv("JITONG_SERVER_URL") ?: "https://127.0.0.1:8443"

private fun installationId(directory: Path): String {
    val file = directory.resolve("installation-id")
    return if (file.toFile().exists()) {
        file.toFile().readText().trim()
    } else {
        directory.toFile().mkdirs()
        UUID.randomUUID().toString().also { file.toFile().writeText(it) }
    }
}

private fun messageFor(throwable: Throwable): String = when (throwable) {
    is AuthApiException -> throwable.error.message
    is ConversationApiException -> when (throwable.statusCode) {
        409 -> "The server requires a synchronization reset."
        401 -> "Your PC session expired. Please sign in again."
        else -> throwable.message ?: "The request could not be completed"
    }
    is RealtimeCommandException -> throwable.message
    else -> throwable.message ?: "The request could not be completed"
}
