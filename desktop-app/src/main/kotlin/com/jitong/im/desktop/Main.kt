package com.jitong.im.desktop

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
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
import com.jitong.im.desktop.conversation.DesktopAiConsent
import com.jitong.im.desktop.conversation.DesktopAiDraft
import com.jitong.im.desktop.conversation.DesktopContactRequestSummary
import com.jitong.im.desktop.conversation.DesktopContactSearchResult
import com.jitong.im.desktop.conversation.DesktopGroupInvite
import com.jitong.im.desktop.conversation.DesktopGroupMember
import com.jitong.im.desktop.conversation.DesktopGroupSearchPage
import com.jitong.im.desktop.conversation.DesktopGroupSummary
import com.jitong.im.desktop.conversation.DesktopMyGroupJoinRequest
import com.jitong.im.desktop.conversation.DesktopRealtimeClient
import com.jitong.im.desktop.conversation.DesktopUserProfile
import com.jitong.im.desktop.conversation.RealtimeCommandException
import com.jitong.im.desktop.conversation.SyncGapException
import com.jitong.im.desktop.local.LocalConversation
import com.jitong.im.desktop.local.LocalAiActionItem
import com.jitong.im.desktop.local.LocalAiArtifact
import com.jitong.im.desktop.local.LocalDatabaseManager
import com.jitong.im.desktop.local.LocalMessage
import com.jitong.im.desktop.local.MacOsKeychain
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Path
import java.util.UUID
import java.io.ByteArrayInputStream
import java.io.File
import javax.imageio.ImageIO
import javax.swing.JFileChooser

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
    val searchResults: List<LocalMessage> = emptyList(),
    val requests: List<DesktopContactRequestSummary> = emptyList(),
    val groups: List<DesktopGroupSummary> = emptyList(),
    val groupSearch: DesktopGroupSearchPage? = null,
    val groupJoinRequests: List<DesktopMyGroupJoinRequest> = emptyList(),
    val groupMembers: List<DesktopGroupMember> = emptyList(),
    val groupInvite: DesktopGroupInvite? = null,
    val aiArtifacts: List<LocalAiArtifact> = emptyList(),
    val aiActionItems: List<LocalAiActionItem> = emptyList(),
)

private data class DesktopAiKeyFact(
    val artifactId: String,
    val category: String,
    val content: String,
    val confidence: Double,
    val sourceMessageIds: List<String>,
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
    var selectedSearchMessageId by remember { mutableStateOf<String?>(null) }
    var searchAccountNo by remember { mutableStateOf("") }
    var searchResult by remember { mutableStateOf<DesktopContactSearchResult?>(null) }
    var localSearchQuery by remember { mutableStateOf("") }
    var draft by remember { mutableStateOf("") }
    var aiConsent by remember { mutableStateOf<DesktopAiConsent?>(null) }
    var aiDrafts by remember { mutableStateOf<List<DesktopAiDraft>>(emptyList()) }
    var selectedAiMessageIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var aiLoading by remember { mutableStateOf(false) }
    var online by remember { mutableStateOf(false) }
    var avatarBytes by remember { mutableStateOf<Map<String, ByteArray>>(emptyMap()) }
    var selfProfile by remember { mutableStateOf<DesktopUserProfile?>(null) }
    var selfAvatarBytes by remember { mutableStateOf<ByteArray?>(null) }
    var selfAvatarLoading by remember { mutableStateOf(false) }
    var mediaBytes by remember { mutableStateOf<Map<String, ByteArray>>(emptyMap()) }
    var mediaLoadGeneration by remember { mutableStateOf(0L) }
    val uiScope = androidx.compose.runtime.rememberCoroutineScope()
    val syncMutex = remember { Mutex() }
    var groupSearchQuery by remember { mutableStateOf("") }
    var groupJoinGroupNo by remember { mutableStateOf("") }
    var groupJoinToken by remember { mutableStateOf("") }
    var groupManagementAccountNo by remember { mutableStateOf("") }

    fun refreshLocal() {
        val local = authStore.localDatabase() ?: return
        val generation = ++mediaLoadGeneration
        val conversations = local.listConversations()
        val messages = selectedConversationId?.let(local::listMessages).orEmpty()
        val aiArtifacts = local.listAiArtifacts()
        val aiActionItems = local.listAiActionItems()
        data = data.copy(
            conversations = conversations,
            messages = messages,
            aiArtifacts = aiArtifacts,
            aiActionItems = aiActionItems)
        if (aiDrafts.isEmpty()) {
            val latestReplies = aiArtifacts.firstOrNull {
                it.conversationId == selectedConversationId && it.artifactType == "SMART_REPLY"
            }
            if (latestReplies != null) {
                aiDrafts = parseDesktopAiDrafts(latestReplies.contentJson)
            }
        }
        val activeMediaKeys = messages
            .filter { it.type == "IMAGE" && it.state == "ACTIVE" && it.mediaId != null }
            .map { "message-media-${it.mediaId}-thumb" }
            .toSet()
        mediaBytes = mediaBytes.filterKeys { it in activeMediaKeys }
        val token = authStore.session?.accessToken ?: session?.accessToken
        val activeAvatarKeys = conversations
            .filter { it.kind == "C2C" && it.peerAvatarVersion > 0 && it.peerUserId.isNotBlank() }
            .map { "${it.peerUserId}-v${it.peerAvatarVersion}" }
            .toSet()
        val activeGroupAvatarKeys = conversations
            .filter { it.kind == "GROUP" && it.peerAvatarVersion > 0 }
            .map { "group-avatar-${it.conversationId}-v${it.peerAvatarVersion}" }
            .toSet()
        avatarBytes = avatarBytes.filterKeys { it in activeAvatarKeys }
        val query = localSearchQuery
        if (query.isBlank()) {
            data = data.copy(searchResults = emptyList())
        } else {
            uiScope.launch(Dispatchers.IO) {
                val results = runCatching { local.searchMessages(query) }
                    .getOrElse {
                        withContext(Dispatchers.Main.immediate) {
                            if (localSearchQuery == query) {
                                error = messageFor(it)
                            }
                        }
                        return@launch
                    }
                withContext(Dispatchers.Main.immediate) {
                    if (localSearchQuery == query) {
                        data = data.copy(searchResults = results)
                    }
                }
            }
        }
        if (token == null) return
        uiScope.launch(Dispatchers.IO) {
            val loaded = buildMap {
                conversations
                    .filter { it.kind == "C2C" && it.peerAvatarVersion > 0 && it.peerUserId.isNotBlank() }
                    .forEach { conversation ->
                        conversationClient.loadUserAvatar(
                            token,
                            local,
                            conversation.peerUserId,
                            conversation.peerAvatarVersion)
                            ?.let { bytes ->
                                put(
                                    "${conversation.peerUserId}-v${conversation.peerAvatarVersion}",
                                    bytes)
                            }
                    }
                conversations
                    .filter { it.kind == "GROUP" && it.peerAvatarVersion > 0 }
                    .forEach { conversation ->
                        conversationClient.loadGroupAvatar(
                            token,
                            local,
                            conversation.conversationId,
                            conversation.peerAvatarVersion)
                            ?.let { bytes ->
                                put(
                                    "group-avatar-${conversation.conversationId}-v${conversation.peerAvatarVersion}",
                                    bytes)
                            }
                    }
                messages
                    .filter {
                        it.type == "IMAGE"
                            && it.state == "ACTIVE"
                            && it.mediaId != null
                            && !it.mediaId.startsWith("pending-image-")
                    }
                    .forEach { message ->
                        conversationClient.loadMedia(
                            token,
                            local,
                            message,
                            thumbnail = true)
                            ?.let { bytes ->
                                put(
                                    "message-media-${message.mediaId}-thumb",
                                    bytes)
                            }
                    }
            }
            withContext(Dispatchers.Main.immediate) {
                if (generation != mediaLoadGeneration) return@withContext
                val loadedAvatars = loaded.filterKeys { key ->
                    key in activeAvatarKeys || key in activeGroupAvatarKeys
                }
                val loadedMedia = loaded.filterKeys { key ->
                    key.startsWith("message-media-")
                }
                avatarBytes = avatarBytes + loadedAvatars
                mediaBytes = mediaBytes + loadedMedia
            }
        }
    }

    fun searchLocalHistory() {
        val local = authStore.localDatabase() ?: return
        uiScope.launch(Dispatchers.IO) {
            runCatching {
                local.searchMessages(localSearchQuery)
            }.onSuccess { results ->
                withContext(Dispatchers.Main.immediate) {
                    data = data.copy(searchResults = results)
                }
            }.onFailure {
                withContext(Dispatchers.Main.immediate) {
                    error = messageFor(it)
                }
            }
        }
    }

    fun refreshSelfProfile() {
        val current = authStore.session ?: session ?: return
        val local = authStore.localDatabase() ?: return
        selfAvatarLoading = true
        uiScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    val profile = conversationClient.userProfile(current.accessToken, current.userId)
                    val bytes = conversationClient.loadUserAvatar(
                        current.accessToken,
                        local,
                        profile.userId,
                        profile.avatarVersion)
                    profile to bytes
                }
            }.onSuccess { (profile, bytes) ->
                selfProfile = profile
                selfAvatarBytes = bytes
            }.onFailure { error = messageFor(it) }
            selfAvatarLoading = false
        }
    }

    fun chooseAvatar() {
        val current = authStore.session ?: session ?: return
        val chooser = JFileChooser()
        if (chooser.showOpenDialog(null) != JFileChooser.APPROVE_OPTION) return
        val file: File = chooser.selectedFile ?: return
        selfAvatarLoading = true
        uiScope.launch {
            runCatching {
                val bytes = withContext(Dispatchers.IO) { file.readBytes() }
                withContext(Dispatchers.IO) {
                    conversationClient.replaceUserAvatar(
                        current.accessToken,
                        file.name,
                        bytes,
                        conversationClient.deriveSquareCrop(bytes))
                }
            }.onSuccess { refreshSelfProfile() }
                .onFailure { error = messageFor(it) }
            selfAvatarLoading = false
        }
    }

    fun removeAvatar() {
        val current = authStore.session ?: session ?: return
        selfAvatarLoading = true
        uiScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    conversationClient.removeUserAvatar(current.accessToken)
                }
            }.onSuccess { refreshSelfProfile() }
                .onFailure { error = messageFor(it) }
            selfAvatarLoading = false
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

    fun refreshGroups() {
        val current = authStore.session ?: session ?: return
        uiScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    conversationClient.listGroups(current.accessToken)
                }
            }.onSuccess { groups ->
                data = data.copy(groups = groups)
                withContext(Dispatchers.IO) {
                    authStore.localDatabase()?.let { local ->
                        val existingGroupIds = local.listConversations()
                            .filter { it.kind == "GROUP" }
                            .map { it.conversationId }
                            .toSet()
                        val incomingGroupIds = groups
                            .map { it.conversationId }
                            .toSet()
                        (existingGroupIds - incomingGroupIds)
                            .forEach(local::clearGroupData)
                        conversationClient.replaceGroups(local, groups)
                        val currentSession = authStore.session ?: session
                        if (currentSession != null) {
                            groups.forEach { group ->
                                conversationClient.restoreConversation(
                                    currentSession.accessToken,
                                    local,
                                    group.conversationId,
                                    currentSession.userId)
                                conversationClient.restoreReadStates(
                                    currentSession.accessToken,
                                    local,
                                    group.conversationId)
                            }
                        }
                    }
                }
                refreshLocal()
            }.onFailure { error = messageFor(it) }
        }
    }

    fun refreshGroupMembers(conversationId: String) {
        val current = authStore.session ?: session ?: return
        uiScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    conversationClient.listGroupMembers(
                        current.accessToken,
                        conversationId)
                }
            }.onSuccess {
                data = data.copy(groupMembers = it)
            }.onFailure { error = messageFor(it) }
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
        refreshSelfProfile()
        refreshRequests()
        refreshGroups()
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
                                    if (envelope.operation.startsWith("ai.")) {
                                        conversationClient.refreshAiData(
                                            (authStore.session ?: current).accessToken,
                                            local)
                                    }
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
                        if (envelope.operation == "membership.granted"
                            || (envelope.operation == "message.created"
                                && envelope.body?.type == "SYSTEM")) {
                            refreshGroups()
                        }
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
                    refreshGroups()
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
            selectedSearchMessageId = selectedSearchMessageId,
            searchAccountNo = searchAccountNo,
            searchResult = searchResult,
            avatarBytes = avatarBytes,
            selfProfile = selfProfile,
            selfAvatarBytes = selfAvatarBytes,
            selfAvatarLoading = selfAvatarLoading,
            mediaBytes = mediaBytes,
            groups = data.groups,
            groupSearchQuery = groupSearchQuery,
            groupSearch = data.groupSearch,
            groupJoinGroupNo = groupJoinGroupNo,
            groupJoinToken = groupJoinToken,
            groupJoinRequests = data.groupJoinRequests,
            groupMembers = data.groupMembers,
            groupManagementAccountNo = groupManagementAccountNo,
            groupInvite = data.groupInvite,
            draft = draft,
            aiConsent = aiConsent,
            aiDrafts = aiDrafts,
            selectedAiMessageIds = selectedAiMessageIds,
            aiLoading = aiLoading,
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
            localSearchQuery = localSearchQuery,
            localSearchResults = data.searchResults,
            onLocalSearchQueryChange = {
                localSearchQuery = it.take(200)
                data = data.copy(searchResults = emptyList())
            },
            onLocalSearch = ::searchLocalHistory,
            onGroupSearchQueryChange = {
                groupSearchQuery = it.take(128)
                data = data.copy(groupSearch = null)
            },
            onGroupSearch = {
                val current = authStore.session ?: session ?: return@MainScreen
                uiScope.launch {
                    runCatching {
                        withContext(Dispatchers.IO) {
                            conversationClient.searchGroups(
                                current.accessToken,
                                groupSearchQuery)
                        }
                    }.onSuccess { data = data.copy(groupSearch = it) }
                        .onFailure { error = messageFor(it) }
                }
            },
            onJoinGroup = {
                val current = authStore.session ?: session ?: return@MainScreen
                uiScope.launch {
                    runCatching {
                        withContext(Dispatchers.IO) {
                            conversationClient.requestToJoinGroup(
                                current.accessToken,
                                groupJoinGroupNo,
                                groupJoinToken.takeIf(String::isNotBlank))
                        }
                    }.onSuccess {
                        data = data.copy(
                            groupJoinRequests = data.groupJoinRequests + DesktopMyGroupJoinRequest(
                                requestId = it.requestId,
                                conversationId = it.conversationId,
                                groupNo = groupJoinGroupNo,
                                groupName = "Group $groupJoinGroupNo",
                                status = it.status,
                                createdAt = it.createdAt,
                                resolvedAt = it.resolvedAt))
                    }.onFailure { error = messageFor(it) }
                }
            },
            onGroupJoinGroupNoChange = { groupJoinGroupNo = it.filter(Char::isDigit).take(11) },
            onGroupJoinTokenChange = { groupJoinToken = it.take(128) },
            onGroupManagementAccountNoChange = {
                groupManagementAccountNo = it.filter(Char::isDigit).take(11)
            },
            onRefreshGroupJoinRequests = {
                val current = authStore.session ?: session ?: return@MainScreen
                uiScope.launch {
                    runCatching {
                        withContext(Dispatchers.IO) {
                            conversationClient.listMyGroupJoinRequests(current.accessToken)
                        }
                    }.onSuccess { data = data.copy(groupJoinRequests = it) }
                        .onFailure { error = messageFor(it) }
                }
            },
            onRefreshGroupMembers = ::refreshGroupMembers,
            onAddGroupMember = { conversationId ->
                val current = authStore.session ?: session ?: return@MainScreen
                uiScope.launch {
                    runCatching {
                        withContext(Dispatchers.IO) {
                            conversationClient.addGroupMember(
                                current.accessToken,
                                conversationId,
                                groupManagementAccountNo)
                        }
                        refreshGroups()
                        refreshGroupMembers(conversationId)
                    }.onFailure { error = messageFor(it) }
                }
            },
            onChangeGroupRole = { conversationId, userId, role ->
                val current = authStore.session ?: session ?: return@MainScreen
                uiScope.launch {
                    runCatching {
                        withContext(Dispatchers.IO) {
                            conversationClient.changeGroupRole(
                                current.accessToken,
                                conversationId,
                                userId,
                                role)
                        }
                        refreshGroups()
                        refreshGroupMembers(conversationId)
                    }.onFailure { error = messageFor(it) }
                }
            },
            onTransferGroupOwner = { conversationId, userId ->
                val current = authStore.session ?: session ?: return@MainScreen
                uiScope.launch {
                    runCatching {
                        withContext(Dispatchers.IO) {
                            conversationClient.transferGroupOwner(
                                current.accessToken,
                                conversationId,
                                userId)
                        }
                        refreshGroups()
                        refreshGroupMembers(conversationId)
                    }.onFailure { error = messageFor(it) }
                }
            },
            onRemoveGroupMember = { conversationId, userId ->
                val current = authStore.session ?: session ?: return@MainScreen
                uiScope.launch {
                    runCatching {
                        withContext(Dispatchers.IO) {
                            conversationClient.removeGroupMember(
                                current.accessToken,
                                conversationId,
                                userId)
                        }
                        refreshGroups()
                        refreshGroupMembers(conversationId)
                    }.onFailure { error = messageFor(it) }
                }
            },
            onLeaveGroup = { conversationId ->
                val current = authStore.session ?: session ?: return@MainScreen
                uiScope.launch {
                    runCatching {
                        withContext(Dispatchers.IO) {
                            conversationClient.leaveGroup(
                                current.accessToken,
                                conversationId)
                        }
                        selectedConversationId = null
                        refreshGroups()
                        refreshLocal()
                    }.onFailure { error = messageFor(it) }
                }
            },
            onDissolveGroup = { conversationId ->
                val current = authStore.session ?: session ?: return@MainScreen
                uiScope.launch {
                    runCatching {
                        withContext(Dispatchers.IO) {
                            conversationClient.dissolveGroup(
                                current.accessToken,
                                conversationId)
                        }
                        selectedConversationId = null
                        refreshGroups()
                        refreshLocal()
                    }.onFailure { error = messageFor(it) }
                }
            },
            onCreateGroupInvite = { conversationId ->
                val current = authStore.session ?: session ?: return@MainScreen
                uiScope.launch {
                    runCatching {
                        withContext(Dispatchers.IO) {
                            conversationClient.createGroupInvite(
                                current.accessToken,
                                conversationId)
                        }
                    }.onSuccess { data = data.copy(groupInvite = it) }
                        .onFailure { error = messageFor(it) }
                }
            },
            onOpenSearchResult = { conversationId, messageId ->
                selectedConversationId = conversationId
                selectedSearchMessageId = messageId
                refreshLocal()
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
                selectedSearchMessageId = null
                selectedAiMessageIds = emptySet()
                aiDrafts = emptyList()
                aiConsent = null
                uiScope.launch {
                    runCatching {
                        val consent = withContext(Dispatchers.IO) {
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
                            conversationClient.aiConsent(
                                authStore.session?.accessToken ?: session!!.accessToken,
                                conversationId)
                        }
                        refreshLocal()
                        consent
                    }.onSuccess { aiConsent = it }
                        .onFailure { error = messageFor(it) }
                }
            },
            onDraftChange = { draft = it.take(4000) },
            onToggleAiConsent = {
                val conversationId = selectedConversationId ?: return@MainScreen
                val current = authStore.session ?: session ?: return@MainScreen
                uiScope.launch {
                    aiLoading = true
                    runCatching {
                        withContext(Dispatchers.IO) {
                            conversationClient.updateAiConsent(
                                current.accessToken,
                                conversationId,
                                !(aiConsent?.enabled ?: false))
                        }
                    }.onSuccess { aiConsent = it }
                        .onFailure { error = messageFor(it) }
                    aiLoading = false
                }
            },
            onToggleAiMessage = { messageId ->
                selectedAiMessageIds = selectedAiMessageIds.toMutableSet().also {
                    if (!it.add(messageId)) it.remove(messageId)
                }
            },
            onRequestSmartReplies = {
                val conversationId = selectedConversationId ?: return@MainScreen
                val current = authStore.session ?: session ?: return@MainScreen
                uiScope.launch {
                    aiLoading = true
                    runCatching {
                        withContext(Dispatchers.IO) {
                            val replies = conversationClient.requestSmartReplies(
                                current.accessToken,
                                conversationId)
                            val local = authStore.localDatabase()
                                ?: error("PC local database is not open")
                            conversationClient.refreshAiData(current.accessToken, local)
                            replies
                        }
                    }.onSuccess {
                        aiDrafts = it
                        refreshLocal()
                    }.onFailure { error = messageFor(it) }
                    aiLoading = false
                }
            },
            onEditAiDraft = { index, value ->
                aiDrafts = aiDrafts.mapIndexed { itemIndex, item ->
                    if (itemIndex == index) item.copy(text = value.take(4000)) else item
                }
            },
            onUseAiDraft = { value -> draft = value.take(4000) },
            onExtractAiInformation = {
                val conversationId = selectedConversationId ?: return@MainScreen
                val current = authStore.session ?: session ?: return@MainScreen
                if (selectedAiMessageIds.isEmpty()) return@MainScreen
                uiScope.launch {
                    aiLoading = true
                    runCatching {
                        withContext(Dispatchers.IO) {
                            conversationClient.extractInformation(
                                current.accessToken,
                                conversationId,
                                selectedAiMessageIds.toList())
                            val local = authStore.localDatabase()
                                ?: error("PC local database is not open")
                            conversationClient.refreshAiData(current.accessToken, local)
                        }
                    }.onSuccess {
                        selectedAiMessageIds = emptySet()
                        refreshLocal()
                    }.onFailure { error = messageFor(it) }
                    aiLoading = false
                }
            },
            onDeleteAiArtifact = { artifactId ->
                val current = authStore.session ?: session ?: return@MainScreen
                uiScope.launch {
                    runCatching {
                        withContext(Dispatchers.IO) {
                            conversationClient.deleteAiArtifact(current.accessToken, artifactId)
                            conversationClient.refreshAiData(
                                current.accessToken,
                                authStore.localDatabase()
                                    ?: error("PC local database is not open"))
                        }
                        refreshLocal()
                    }.onFailure { error = messageFor(it) }
                }
            },
            onSetAiActionItemStatus = { actionItemId, status ->
                val current = authStore.session ?: session ?: return@MainScreen
                uiScope.launch {
                    runCatching {
                        withContext(Dispatchers.IO) {
                            conversationClient.updateAiActionItem(
                                current.accessToken,
                                actionItemId,
                                status)
                            conversationClient.refreshAiData(
                                current.accessToken,
                                authStore.localDatabase()
                                    ?: error("PC local database is not open"))
                        }
                        refreshLocal()
                    }.onFailure { error = messageFor(it) }
                }
            },
            onDeleteAiActionItem = { actionItemId ->
                val current = authStore.session ?: session ?: return@MainScreen
                uiScope.launch {
                    runCatching {
                        withContext(Dispatchers.IO) {
                            conversationClient.deleteAiActionItem(current.accessToken, actionItemId)
                            conversationClient.refreshAiData(
                                current.accessToken,
                                authStore.localDatabase()
                                    ?: error("PC local database is not open"))
                        }
                        refreshLocal()
                    }.onFailure { error = messageFor(it) }
                }
            },
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
                            val canSend = activeConversation?.let {
                                if (it.kind == "GROUP") {
                                    it.status == "ACTIVE" &&
                                        it.relationship in setOf("OWNER", "ADMIN", "MEMBER")
                                } else {
                                    it.status == "ACTIVE" &&
                                        it.relationship == "ACTIVE" &&
                                        !it.blockedByMe
                                }
                            } == true
                            if (!canSend) {
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
            },
            onChooseAvatar = ::chooseAvatar,
            onRemoveAvatar = ::removeAvatar,
            onChooseImage = {
                val conversationId = selectedConversationId ?: return@MainScreen
                val current = authStore.session ?: session ?: return@MainScreen
                val chooser = JFileChooser()
                if (chooser.showOpenDialog(null) != JFileChooser.APPROVE_OPTION) return@MainScreen
                val file = chooser.selectedFile ?: return@MainScreen
                if (file.length() > com.jitong.im.desktop.media.ImageNormalizer.MAX_INPUT_BYTES) {
                    error = "Image is too large."
                    return@MainScreen
                }
                val clientMsgId = UUID.randomUUID().toString()
                val pendingCacheName = "pending-image-$clientMsgId"
                uiScope.launch {
                    runCatching {
                        val bytes = withContext(Dispatchers.IO) { file.readBytes() }
                        val normalized = withContext(Dispatchers.IO) {
                            com.jitong.im.desktop.media.ImageNormalizer.normalize(bytes)
                        }
                        val local = authStore.localDatabase()
                            ?: error("PC local database is not open")
                        withContext(Dispatchers.IO) {
                            local.mediaCache().put(pendingCacheName, normalized)
                            conversationClient.newPendingImage(
                                local,
                                conversationId,
                                current.userId,
                                clientMsgId,
                                pendingCacheName)
                        }
                        val upload = withContext(Dispatchers.IO) {
                            conversationClient.uploadImage(
                                current.accessToken,
                                file.name,
                                normalized)
                        }
                        withContext(Dispatchers.IO) {
                            local.replaceMessageByClientId(
                                local.findMessageByClientId(conversationId, clientMsgId)!!
                                    .copy(mediaId = upload.mediaId))
                            val sent = conversationClient.sendImage(
                                current.accessToken,
                                conversationId,
                                clientMsgId,
                                upload.mediaId)
                            conversationClient.applyMessage(local, sent, current.userId)
                            local.mediaCache().deleteMatching(pendingCacheName)
                        }
                        refreshLocal()
                    }.onFailure {
                        withContext(Dispatchers.IO) {
                            authStore.localDatabase()?.markMessageFailedAndDeleteCache(
                                conversationId,
                                clientMsgId,
                                pendingCacheName)
                        }
                        error = messageFor(it)
                    }
                }
            },
            onRecall = { message ->
                val current = authStore.session ?: session ?: return@MainScreen
                if (message.senderId != current.userId || message.state != "ACTIVE") return@MainScreen
                uiScope.launch {
                    runCatching {
                        withContext(Dispatchers.IO) {
                            val local = authStore.localDatabase()
                                ?: error("PC local database is not open")
                            val recalled = conversationClient.recallMessage(
                                current.accessToken,
                                message.messageId)
                            conversationClient.applyRecalledMessage(
                                local,
                                recalled,
                                current.userId)
                        }
                        refreshLocal()
                    }.onFailure { error = messageFor(it) }
                }
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
    selectedSearchMessageId: String?,
    searchAccountNo: String,
    searchResult: DesktopContactSearchResult?,
    localSearchQuery: String,
    localSearchResults: List<LocalMessage>,
    avatarBytes: Map<String, ByteArray>,
    selfProfile: DesktopUserProfile?,
    selfAvatarBytes: ByteArray?,
    selfAvatarLoading: Boolean,
    mediaBytes: Map<String, ByteArray>,
    groups: List<DesktopGroupSummary>,
    groupSearchQuery: String,
    groupSearch: DesktopGroupSearchPage?,
    groupJoinGroupNo: String,
    groupJoinToken: String,
    groupJoinRequests: List<DesktopMyGroupJoinRequest>,
    groupMembers: List<DesktopGroupMember>,
    groupManagementAccountNo: String,
    groupInvite: DesktopGroupInvite?,
    draft: String,
    aiConsent: DesktopAiConsent?,
    aiDrafts: List<DesktopAiDraft>,
    selectedAiMessageIds: Set<String>,
    aiLoading: Boolean,
    error: String?,
    onSearchAccountNoChange: (String) -> Unit,
    onSearch: () -> Unit,
    onLocalSearchQueryChange: (String) -> Unit,
    onLocalSearch: () -> Unit,
    onGroupSearchQueryChange: (String) -> Unit,
    onGroupSearch: () -> Unit,
    onJoinGroup: () -> Unit,
    onGroupJoinGroupNoChange: (String) -> Unit,
    onGroupJoinTokenChange: (String) -> Unit,
    onGroupManagementAccountNoChange: (String) -> Unit,
    onRefreshGroupJoinRequests: () -> Unit,
    onRefreshGroupMembers: (String) -> Unit,
    onAddGroupMember: (String) -> Unit,
    onChangeGroupRole: (String, String, String) -> Unit,
    onTransferGroupOwner: (String, String) -> Unit,
    onRemoveGroupMember: (String, String) -> Unit,
    onLeaveGroup: (String) -> Unit,
    onDissolveGroup: (String) -> Unit,
    onCreateGroupInvite: (String) -> Unit,
    onOpenSearchResult: (String, String) -> Unit,
    onAddContact: () -> Unit,
    onAcceptRequest: (String) -> Unit,
    onRejectRequest: (String) -> Unit,
    onCancelRequest: (String) -> Unit,
    onRemoveContact: (String) -> Unit,
    onBlockContact: (String) -> Unit,
    onUnblockContact: (String) -> Unit,
    onSelectConversation: (String) -> Unit,
    onDraftChange: (String) -> Unit,
    onToggleAiConsent: () -> Unit,
    onToggleAiMessage: (String) -> Unit,
    onRequestSmartReplies: () -> Unit,
    onEditAiDraft: (Int, String) -> Unit,
    onUseAiDraft: (String) -> Unit,
    onExtractAiInformation: () -> Unit,
    onDeleteAiArtifact: (String) -> Unit,
    onSetAiActionItemStatus: (String, String) -> Unit,
    onDeleteAiActionItem: (String) -> Unit,
    onSend: () -> Unit,
    onMarkRead: (Long) -> Unit,
    onLogout: () -> Unit,
    onClearLocalData: () -> Unit,
    onChooseAvatar: () -> Unit,
    onRemoveAvatar: () -> Unit,
    onChooseImage: () -> Unit,
    onRecall: (LocalMessage) -> Unit,
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
        Card(Modifier.fillMaxWidth()) {
            Row(
                Modifier.padding(12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                DesktopAvatar(
                    bytes = selfAvatarBytes,
                    fallback = selfProfile?.avatarFallback ?: "?",
                    size = 56.dp)
                Column(Modifier.weight(1f)) {
                    Text(
                        selfProfile?.displayName ?: "Your profile",
                        style = MaterialTheme.typography.titleMedium)
                    Text("Your avatar is private and versioned.")
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = onChooseAvatar,
                            enabled = !selfAvatarLoading) {
                            Text("Choose avatar")
                        }
                        OutlinedButton(
                            onClick = onRemoveAvatar,
                            enabled = !selfAvatarLoading &&
                                (selfProfile?.avatarVersion ?: 0) > 0) {
                            Text("Remove")
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(12.dp))
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
                Text("Local history", style = MaterialTheme.typography.titleMedium)
                OutlinedTextField(
                    modifier = Modifier.fillMaxWidth(),
                    value = localSearchQuery,
                    onValueChange = onLocalSearchQueryChange,
                    label = { Text("Search messages") },
                    singleLine = true)
                Spacer(Modifier.height(8.dp))
                Button(
                    modifier = Modifier.fillMaxWidth(),
                    enabled = localSearchQuery.isNotBlank(),
                    onClick = onLocalSearch) { Text("Search history") }
                LazyColumn(
                    modifier = Modifier.heightIn(max = 360.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(localSearchResults, key = { it.messageId }) { result ->
                        Card(
                            Modifier
                                .fillMaxWidth()
                                .clickable {
                                    onOpenSearchResult(
                                        result.conversationId,
                                        result.messageId)
                                },
                        ) {
                            Column(Modifier.padding(10.dp)) {
                                Text(result.text)
                                Text(
                                    "${result.conversationId} · ${result.conversationSeq ?: "pending"}",
                                    style = MaterialTheme.typography.bodySmall,
                                )
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
                GroupSidebarPanel(
                    groups = groups,
                    groupSearchQuery = groupSearchQuery,
                    groupSearch = groupSearch,
                    groupJoinGroupNo = groupJoinGroupNo,
                    groupJoinToken = groupJoinToken,
                    groupJoinRequests = groupJoinRequests,
                    groupMembers = groupMembers,
                    groupManagementAccountNo = groupManagementAccountNo,
                    groupInvite = groupInvite,
                    onGroupSearchQueryChange = onGroupSearchQueryChange,
                    onGroupSearch = onGroupSearch,
                    onJoinGroup = onJoinGroup,
                    onGroupJoinGroupNoChange = onGroupJoinGroupNoChange,
                    onGroupJoinTokenChange = onGroupJoinTokenChange,
                    onGroupManagementAccountNoChange = onGroupManagementAccountNoChange,
                    onRefreshGroupJoinRequests = onRefreshGroupJoinRequests,
                    onRefreshGroupMembers = onRefreshGroupMembers,
                    onAddGroupMember = onAddGroupMember,
                    onChangeGroupRole = onChangeGroupRole,
                    onTransferGroupOwner = onTransferGroupOwner,
                    onRemoveGroupMember = onRemoveGroupMember,
                    onLeaveGroup = onLeaveGroup,
                    onDissolveGroup = onDissolveGroup,
                    onCreateGroupInvite = onCreateGroupInvite,
                    onSelectGroup = onSelectConversation)
                Spacer(Modifier.height(16.dp))
                Text("Conversations", style = MaterialTheme.typography.titleMedium)
                data.conversations.filter { it.kind == "C2C" }.forEach { conversation ->
                    Card(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                        Column(Modifier.padding(8.dp)) {
                            DesktopAvatar(
                                bytes = avatarBytes[
                                    "${conversation.peerUserId}-v${conversation.peerAvatarVersion}"],
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
                currentUserId = session.userId,
                messages = data.messages,
                initialMessageId = selectedSearchMessageId,
                avatarBytes = avatarBytes,
                mediaBytes = mediaBytes,
                draft = draft,
                online = online,
                aiConsent = aiConsent,
                aiDrafts = aiDrafts,
                aiArtifacts = data.aiArtifacts.filter {
                    it.conversationId == selectedConversationId
                },
                aiActionItems = data.aiActionItems.filter {
                    it.conversationId == selectedConversationId
                },
                selectedAiMessageIds = selectedAiMessageIds,
                aiLoading = aiLoading,
                error = error,
                onDraftChange = onDraftChange,
                onToggleAiConsent = onToggleAiConsent,
                onToggleAiMessage = onToggleAiMessage,
                onRequestSmartReplies = onRequestSmartReplies,
                onEditAiDraft = onEditAiDraft,
                onUseAiDraft = onUseAiDraft,
                onExtractAiInformation = onExtractAiInformation,
                onDeleteAiArtifact = onDeleteAiArtifact,
                onSetAiActionItemStatus = onSetAiActionItemStatus,
                onDeleteAiActionItem = onDeleteAiActionItem,
                onSend = onSend,
                onMarkRead = onMarkRead,
                onChooseImage = onChooseImage,
                onRecall = onRecall)
        }
    }
}

@Composable
private fun GroupSidebarPanel(
    groups: List<DesktopGroupSummary>,
    groupSearchQuery: String,
    groupSearch: DesktopGroupSearchPage?,
    groupJoinGroupNo: String,
    groupJoinToken: String,
    groupJoinRequests: List<DesktopMyGroupJoinRequest>,
    groupMembers: List<DesktopGroupMember>,
    groupManagementAccountNo: String,
    groupInvite: DesktopGroupInvite?,
    onGroupSearchQueryChange: (String) -> Unit,
    onGroupSearch: () -> Unit,
    onJoinGroup: () -> Unit,
    onGroupJoinGroupNoChange: (String) -> Unit,
    onGroupJoinTokenChange: (String) -> Unit,
    onGroupManagementAccountNoChange: (String) -> Unit,
    onRefreshGroupJoinRequests: () -> Unit,
    onRefreshGroupMembers: (String) -> Unit,
    onAddGroupMember: (String) -> Unit,
    onChangeGroupRole: (String, String, String) -> Unit,
    onTransferGroupOwner: (String, String) -> Unit,
    onRemoveGroupMember: (String, String) -> Unit,
    onLeaveGroup: (String) -> Unit,
    onDissolveGroup: (String) -> Unit,
    onCreateGroupInvite: (String) -> Unit,
    onSelectGroup: (String) -> Unit,
) {
    var managedGroupId by remember { mutableStateOf<String?>(null) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Groups", style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(
            modifier = Modifier.fillMaxWidth(),
            value = groupSearchQuery,
            onValueChange = onGroupSearchQueryChange,
            label = { Text("Search group number or PUBLIC name") },
            singleLine = true)
        Button(
            modifier = Modifier.fillMaxWidth(),
            enabled = groupSearchQuery.isNotBlank(),
            onClick = onGroupSearch) { Text("Search groups") }
        groupSearch?.groups?.forEach { result ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(8.dp)) {
                    Text(result.name, style = MaterialTheme.typography.titleSmall)
                    Text(result.description.ifBlank { "No description" })
                    Text("${result.memberCount} members")
                }
            }
        }
        OutlinedTextField(
            modifier = Modifier.fillMaxWidth(),
            value = groupJoinGroupNo,
            onValueChange = onGroupJoinGroupNoChange,
            label = { Text("Join by exact group number") },
            singleLine = true)
        OutlinedTextField(
            modifier = Modifier.fillMaxWidth(),
            value = groupJoinToken,
            onValueChange = onGroupJoinTokenChange,
            label = { Text("Invite token (optional)") },
            singleLine = true)
        Button(
            modifier = Modifier.fillMaxWidth(),
            enabled = groupJoinGroupNo.length == 11,
            onClick = onJoinGroup) { Text("Submit join request") }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Text("My join requests")
            OutlinedButton(onClick = onRefreshGroupJoinRequests) { Text("Refresh") }
        }
        groupJoinRequests.take(5).forEach { request ->
            Text("${request.groupName} · ${request.status}")
        }
        groups.forEach { group ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("${group.name} · ${group.groupNo}", style = MaterialTheme.typography.titleSmall)
                    Text("${group.visibility} · ${group.role} · ${group.memberCount} members")
                    Text(group.description.ifBlank { "No description" })
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Button(onClick = {
                            onRefreshGroupMembers(group.conversationId)
                            onSelectGroup(group.conversationId)
                        }) { Text("Open") }
                        OutlinedButton(onClick = {
                            managedGroupId = group.conversationId
                            onRefreshGroupMembers(group.conversationId)
                        }) { Text("Manage") }
                        OutlinedButton(onClick = {
                            onCreateGroupInvite(group.conversationId)
                        }) { Text("Invite") }
                    }
                    if (group.role != "OWNER") {
                        OutlinedButton(onClick = { onLeaveGroup(group.conversationId) }) {
                            Text("Leave")
                        }
                    } else {
                        OutlinedButton(onClick = { onDissolveGroup(group.conversationId) }) {
                            Text("Dissolve")
                        }
                    }
                    if (groupInvite?.conversationId == group.conversationId) {
                        Text("Invite link: ${groupInvite.deepLink}")
                    }
                    if (managedGroupId == group.conversationId) {
                        OutlinedTextField(
                            modifier = Modifier.fillMaxWidth(),
                            value = groupManagementAccountNo,
                            onValueChange = onGroupManagementAccountNoChange,
                            label = { Text("Member account number") },
                            singleLine = true)
                        Button(
                            modifier = Modifier.fillMaxWidth(),
                            enabled = groupManagementAccountNo.length == 11,
                            onClick = { onAddGroupMember(group.conversationId) }) {
                            Text("Invite member directly")
                        }
                        groupMembers.forEach { member ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                                Text(
                                    "${member.displayName} · ${member.role}",
                                    modifier = Modifier.weight(1f))
                                if (member.role == "MEMBER" &&
                                    (group.role == "OWNER" || group.role == "ADMIN")) {
                                    OutlinedButton(onClick = {
                                        onChangeGroupRole(
                                            group.conversationId,
                                            member.userId,
                                            "ADMIN")
                                    }) { Text("Admin") }
                                    OutlinedButton(onClick = {
                                        onRemoveGroupMember(
                                            group.conversationId,
                                            member.userId)
                                    }) { Text("Remove") }
                                } else if (member.role == "ADMIN" && group.role == "OWNER") {
                                    OutlinedButton(onClick = {
                                        onChangeGroupRole(
                                            group.conversationId,
                                            member.userId,
                                            "MEMBER")
                                    }) { Text("Demote") }
                                    OutlinedButton(onClick = {
                                        onTransferGroupOwner(
                                            group.conversationId,
                                            member.userId)
                                    }) { Text("Transfer") }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ConversationPane(
    modifier: Modifier,
    selectedConversation: LocalConversation?,
    currentUserId: String,
    messages: List<LocalMessage>,
    initialMessageId: String?,
    avatarBytes: Map<String, ByteArray>,
    mediaBytes: Map<String, ByteArray>,
    draft: String,
    online: Boolean,
    aiConsent: DesktopAiConsent?,
    aiDrafts: List<DesktopAiDraft>,
    aiArtifacts: List<LocalAiArtifact>,
    aiActionItems: List<LocalAiActionItem>,
    selectedAiMessageIds: Set<String>,
    aiLoading: Boolean,
    error: String?,
    onDraftChange: (String) -> Unit,
    onToggleAiConsent: () -> Unit,
    onToggleAiMessage: (String) -> Unit,
    onRequestSmartReplies: () -> Unit,
    onEditAiDraft: (Int, String) -> Unit,
    onUseAiDraft: (String) -> Unit,
    onExtractAiInformation: () -> Unit,
    onDeleteAiArtifact: (String) -> Unit,
    onSetAiActionItemStatus: (String, String) -> Unit,
    onDeleteAiActionItem: (String) -> Unit,
    onSend: () -> Unit,
    onMarkRead: (Long) -> Unit,
    onChooseImage: () -> Unit,
    onRecall: (LocalMessage) -> Unit,
) {
    if (selectedConversation == null) {
        Column(modifier.padding(28.dp)) {
            Text("Select a conversation to view local history.")
        }
        return
    }
    val listState = rememberLazyListState()
    LaunchedEffect(messages, initialMessageId) {
        val messageId = initialMessageId ?: return@LaunchedEffect
        val index = messages.indexOfFirst { it.messageId == messageId }
        if (index >= 0) listState.animateScrollToItem(index)
    }
    Column(modifier) {
        DesktopAvatar(
            bytes = if (selectedConversation.kind == "GROUP") {
                avatarBytes[
                    "group-avatar-${selectedConversation.conversationId}-v${selectedConversation.peerAvatarVersion}"]
            } else {
                avatarBytes[
                    "${selectedConversation.peerUserId}-v${selectedConversation.peerAvatarVersion}"]
            },
            fallback = selectedConversation.peerAvatarFallback,
            size = 64.dp)
        Text(
            "${selectedConversation.peerDisplayName} · ${selectedConversation.peerAccountNo}",
            style = MaterialTheme.typography.titleLarge)
        Text(
            if (selectedConversation.kind == "GROUP") {
                "${selectedConversation.groupVisibility} · " +
                    "${selectedConversation.relationship} · " +
                    "${selectedConversation.groupMemberCount} members"
            } else {
                "${selectedConversation.status} · ${selectedConversation.relationship}"
            })
        Spacer(Modifier.height(8.dp))
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth(),
        ) {
            items(messages, key = { it.messageId }) { message ->
                Card(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Column(Modifier.padding(12.dp)) {
                        Text(if (message.localState == "SENDING") "Sending…" else message.localState)
                        when {
                            message.type == "SYSTEM" -> {
                                val target = message.systemTargetUserId
                                    ?.let { " · target $it" }
                                    .orEmpty()
                                val role = message.systemRole
                                    ?.let { " · role $it" }
                                    .orEmpty()
                                Text(
                                    "Group event: " +
                                        (message.systemEventType ?: "UNKNOWN") +
                                        target +
                                        role)
                            }
                            message.state == "RECALLED" -> Text("Message recalled")
                            message.state == "MODERATED" -> Text(
                                if (message.moderatedReason.isNullOrBlank()) {
                                    "Message moderated"
                                } else {
                                    "Message moderated: ${message.moderatedReason}"
                                })
                            message.type == "IMAGE" -> {
                                val image = mediaBytes[
                                    "message-media-${message.mediaId}-thumb"]
                                if (image == null) {
                                    Text("Image loading…")
                                } else {
                                    Image(
                                        painter = ImageIO.read(ByteArrayInputStream(image)).toPainter(),
                                        contentDescription = "Message image",
                                        modifier = Modifier.size(240.dp))
                                }
                            }
                            else -> Text(message.text)
                        }
                        if (message.senderId == currentUserId
                            && message.state == "ACTIVE") {
                            OutlinedButton(onClick = { onRecall(message) }) {
                                Text("Recall")
                            }
                        }
                        message.conversationSeq?.let {
                            Text("Message #$it")
                        }
                        if (message.state == "ACTIVE" &&
                            message.localState == "SENT" &&
                            runCatching { UUID.fromString(message.messageId) }.isSuccess) {
                            OutlinedButton(onClick = {
                                onToggleAiMessage(message.messageId)
                            }) {
                                Text(
                                    if (message.messageId in selectedAiMessageIds) {
                                        "Remove from AI evidence"
                                    } else {
                                        "Select as AI evidence"
                                    })
                            }
                        }
                    }
                }
            }
        }
        AiAssistantPanel(
            conversationKind = selectedConversation.kind,
            online = online,
            consent = aiConsent,
            drafts = aiDrafts,
            artifacts = aiArtifacts,
            actionItems = aiActionItems,
            selectedMessageCount = selectedAiMessageIds.size,
            loading = aiLoading,
            onToggleConsent = onToggleAiConsent,
            onRequestSmartReplies = onRequestSmartReplies,
            onEditDraft = onEditAiDraft,
            onUseDraft = onUseAiDraft,
            onExtractInformation = onExtractAiInformation,
            onDeleteArtifact = onDeleteAiArtifact,
            onSetActionItemStatus = onSetAiActionItemStatus,
            onDeleteActionItem = onDeleteAiActionItem)
        if (!online) {
            Text(
                "Offline: you can browse history, but sending is disabled.",
                color = MaterialTheme.colorScheme.error)
        }
        Row(Modifier.fillMaxWidth().padding(top = 8.dp)) {
            OutlinedButton(enabled = online, onClick = onChooseImage) {
                Text("Image")
            }
            Spacer(Modifier.width(8.dp))
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
private fun AiAssistantPanel(
    conversationKind: String,
    online: Boolean,
    consent: DesktopAiConsent?,
    drafts: List<DesktopAiDraft>,
    artifacts: List<LocalAiArtifact>,
    actionItems: List<LocalAiActionItem>,
    selectedMessageCount: Int,
    loading: Boolean,
    onToggleConsent: () -> Unit,
    onRequestSmartReplies: () -> Unit,
    onEditDraft: (Int, String) -> Unit,
    onUseDraft: (String) -> Unit,
    onExtractInformation: () -> Unit,
    onDeleteArtifact: (String) -> Unit,
    onSetActionItemStatus: (String, String) -> Unit,
    onDeleteActionItem: (String) -> Unit,
) {
    if (conversationKind != "C2C") return
    val enabledForBoth = consent?.enabledForBoth == true
    val facts = artifacts.flatMap(::parseDesktopAiFacts)
    Card(Modifier.fillMaxWidth().padding(top = 8.dp)) {
        LazyColumn(
            modifier = Modifier.fillMaxWidth().heightIn(max = 320.dp).padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                Text("Private AI assistant", style = MaterialTheme.typography.titleMedium)
                Text(
                    when {
                        consent == null -> "Loading consent…"
                        enabledForBoth -> "Enabled by both participants"
                        consent.enabled -> "You enabled AI; waiting for the other participant"
                        else -> "Disabled for you"
                    })
                OutlinedButton(
                    enabled = online && consent != null && !loading,
                    onClick = onToggleConsent,
                ) {
                    Text(if (consent?.enabled == true) "Disable AI" else "Enable AI")
                }
            }
            if (enabledForBoth) {
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            enabled = online && !loading,
                            onClick = onRequestSmartReplies,
                        ) { Text("Generate 3 replies") }
                        Button(
                            enabled = online && !loading && selectedMessageCount > 0,
                            onClick = onExtractInformation,
                        ) { Text("Extract selected ($selectedMessageCount)") }
                    }
                }
                items(drafts.size) { index ->
                    val reply = drafts[index]
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            modifier = Modifier.weight(1f),
                            value = reply.text,
                            onValueChange = { onEditDraft(index, it) },
                            label = { Text("Reply ${index + 1} · ${reply.tone}") },
                            singleLine = true)
                        Button(onClick = { onUseDraft(reply.text) }) { Text("Use") }
                    }
                }
            }
            if (facts.isNotEmpty()) {
                item { Text("Extracted facts", style = MaterialTheme.typography.titleSmall) }
                items(facts, key = { "${it.artifactId}-${it.category}-${it.content}" }) { fact ->
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(8.dp)) {
                            Text("${fact.category} · ${"%.0f".format(fact.confidence * 100)}%")
                            Text(fact.content)
                            Text("Evidence: ${fact.sourceMessageIds.joinToString()}")
                            OutlinedButton(onClick = { onDeleteArtifact(fact.artifactId) }) {
                                Text("Delete result")
                            }
                        }
                    }
                }
            }
            if (actionItems.isNotEmpty()) {
                item { Text("Action items", style = MaterialTheme.typography.titleSmall) }
                items(actionItems, key = { it.actionItemId }) { action ->
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(8.dp)) {
                            Text("${action.title} · ${action.priority} · ${action.status}")
                            if (action.details.isNotBlank()) Text(action.details)
                            action.dueAt?.let { Text("Due: $it") }
                            action.assigneeUserId?.let { Text("Recognized assignee: $it") }
                            Text("Evidence: ${parseDesktopAiEvidence(action.sourceMessageIdsJson).joinToString()}")
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(onClick = {
                                    onSetActionItemStatus(
                                        action.actionItemId,
                                        if (action.status == "OPEN") "COMPLETED" else "OPEN")
                                }) {
                                    Text(if (action.status == "OPEN") "Complete" else "Reopen")
                                }
                                OutlinedButton(onClick = {
                                    onDeleteActionItem(action.actionItemId)
                                }) { Text("Delete") }
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun parseDesktopAiDrafts(contentJson: String): List<DesktopAiDraft> = runCatching {
    Json.parseToJsonElement(contentJson).jsonObject["replies"]?.jsonArray?.map { value ->
        val reply = value.jsonObject
        DesktopAiDraft(
            text = reply.getValue("text").jsonPrimitive.content,
            tone = reply.getValue("tone").jsonPrimitive.content)
    }.orEmpty()
}.getOrDefault(emptyList())

private fun parseDesktopAiFacts(artifact: LocalAiArtifact): List<DesktopAiKeyFact> = runCatching {
    Json.parseToJsonElement(artifact.contentJson).jsonObject["keyFacts"]?.jsonArray?.map { value ->
        val fact = value.jsonObject
        DesktopAiKeyFact(
            artifactId = artifact.artifactId,
            category = fact.getValue("category").jsonPrimitive.content,
            content = fact.getValue("content").jsonPrimitive.content,
            confidence = fact.getValue("confidence").jsonPrimitive.content.toDouble(),
            sourceMessageIds = fact.getValue("sourceMessageIds").jsonArray.map {
                it.jsonPrimitive.content
            })
    }.orEmpty()
}.getOrDefault(emptyList())

private fun parseDesktopAiEvidence(contentJson: String): List<String> = runCatching {
    Json.parseToJsonElement(contentJson).jsonArray.map { it.jsonPrimitive.content }
}.getOrDefault(emptyList())

@Composable
private fun DefaultAvatar(fallback: String) {
    DefaultAvatar(fallback, 48.dp)
}

@Composable
private fun DefaultAvatar(
    fallback: String,
    size: androidx.compose.ui.unit.Dp,
) {
    Card {
        Text(
            fallback.take(2),
            modifier = Modifier
                .size(size)
                .padding(horizontal = 12.dp, vertical = 8.dp),
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
        DefaultAvatar(fallback, size)
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
            .also { client.applyAiSyncEvents(current.accessToken, local, it) }
            .also { events ->
                if (events.any {
                        it.eventType == "MESSAGE_RECALLED" ||
                            it.eventType == "MESSAGE_MODERATED"
                    }) {
                    client.fullRestore(
                        current.accessToken,
                        local,
                        current.userId,
                        page.highWatermark)
                }
            }
            .also { events ->
                events
                    .filter {
                        (it.eventType == "GROUP_ACCESS_REVOKED"
                            || it.eventType == "GROUP_DISSOLVED")
                            && it.conversationId != null
                    }
                    .map { it.conversationId!! }
                    .distinct()
                    .forEach(local::clearGroupData)
            }
            .mapNotNull { it.conversationId }
            .filter { conversationId ->
                page.events.none {
                    it.conversationId == conversationId
                        && (it.eventType == "GROUP_ACCESS_REVOKED"
                            || it.eventType == "GROUP_DISSOLVED")
                }
            }
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
    client.restoreGroupProfiles(
        current.accessToken,
        local,
        conversations.map { it.conversationId })
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
