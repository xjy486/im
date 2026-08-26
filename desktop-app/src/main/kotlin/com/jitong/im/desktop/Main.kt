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
import com.jitong.im.desktop.conversation.DesktopGroupAiPolicy
import com.jitong.im.desktop.conversation.DesktopContactRequestSummary
import com.jitong.im.desktop.conversation.DesktopContactSearchResult
import com.jitong.im.desktop.conversation.DesktopGroupInvite
import com.jitong.im.desktop.conversation.DesktopGroupMember
import com.jitong.im.desktop.conversation.DesktopGroupJoinRequestSummary
import com.jitong.im.desktop.conversation.DesktopGroupGovernancePolicy
import com.jitong.im.desktop.conversation.groupMessageText
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
import com.jitong.im.desktop.local.LocalAiJob
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
    val groupJoinRequestsForGroup: List<DesktopGroupJoinRequestSummary> = emptyList(),
    val groupInvite: DesktopGroupInvite? = null,
    val aiJobs: List<LocalAiJob> = emptyList(),
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

private data class DesktopAiSummary(
    val artifactId: String,
    val overview: String,
    val keyPoints: List<String>,
    val decisions: List<String>,
    val openQuestions: List<String>,
    val sourceMessageIds: List<String>,
)

private data class DesktopAiSelectionContext(
    val consent: DesktopAiConsent?,
    val groupPolicy: DesktopGroupAiPolicy?,
    val summaryRange: DesktopAiSummaryRange,
)

@Composable
private fun DesktopApp(
    authStore: DesktopAuthStore,
    conversationClient: ConversationClient,
) {
    var accountNo by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var challenge by remember { mutableStateOf<String?>(null) }
    var passwordChangeRequired by remember { mutableStateOf(false) }
    var passwordChangeError by remember { mutableStateOf<String?>(null) }
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
    var aiGroupPolicy by remember { mutableStateOf<DesktopGroupAiPolicy?>(null) }
    var aiDrafts by remember { mutableStateOf<List<DesktopAiDraft>>(emptyList()) }
    var aiDraftArtifactId by remember { mutableStateOf<String?>(null) }
    var aiSummaryRange by remember { mutableStateOf<DesktopAiSummaryRange?>(null) }
    var selectedAiMessageIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var aiLoading by remember { mutableStateOf(false) }
    var online by remember { mutableStateOf(false) }
    var avatarBytes by remember { mutableStateOf<Map<String, ByteArray>>(emptyMap()) }
    var selfProfile by remember { mutableStateOf<DesktopUserProfile?>(null) }
    var selfAvatarBytes by remember { mutableStateOf<ByteArray?>(null) }
    var selfAvatarLoading by remember { mutableStateOf(false) }
    var deleteAccountPassword by remember { mutableStateOf("") }
    var mediaBytes by remember { mutableStateOf<Map<String, ByteArray>>(emptyMap()) }
    var mediaLoadGeneration by remember { mutableStateOf(0L) }
    val uiScope = androidx.compose.runtime.rememberCoroutineScope()
    val syncMutex = remember { Mutex() }
    var groupSearchQuery by remember { mutableStateOf("") }
    var groupJoinGroupNo by remember { mutableStateOf("") }
    var groupJoinToken by remember { mutableStateOf("") }
    var groupManagementAccountNo by remember { mutableStateOf("") }
    var groupNameDraft by remember { mutableStateOf("") }
    var groupDescriptionDraft by remember { mutableStateOf("") }
    var groupVisibilityDraft by remember { mutableStateOf("PUBLIC") }

    fun refreshLocal() {
        val local = authStore.localDatabase() ?: return
        val generation = ++mediaLoadGeneration
        val conversations = local.listConversations()
        val messages = selectedConversationId?.let(local::listMessages).orEmpty()
        val aiJobs = local.listAiJobs()
        val aiArtifacts = local.listAiArtifacts()
        val aiActionItems = local.listAiActionItems()
        (authStore.session ?: session)?.let { current ->
            aiSummaryRange = aiSummaryRange?.includeNewMessages(messages, current.userId)
        }
        data = data.copy(
            conversations = conversations,
            messages = messages,
            aiJobs = aiJobs,
            aiArtifacts = aiArtifacts,
            aiActionItems = aiActionItems)
        val latestReplies = aiArtifacts.firstOrNull {
            it.conversationId == selectedConversationId && it.artifactType == "SMART_REPLY"
        }
        if (latestReplies == null) {
            aiDraftArtifactId = null
            aiDrafts = emptyList()
        } else if (latestReplies.artifactId != aiDraftArtifactId) {
            aiDraftArtifactId = latestReplies.artifactId
            aiDrafts = parseDesktopAiDrafts(latestReplies.contentJson)
        }
        val activeMediaKeys = messages
            .filter { it.type == "IMAGE" && it.state == "ACTIVE" && it.mediaId != null }
            .map { "message-media-${it.mediaId}-thumb" }
            .toSet()
        mediaBytes = mediaBytes.filterKeys { it in activeMediaKeys }
        val token = authStore.session?.accessToken ?: session?.accessToken
        val selectedAiConversation = selectedConversationId
            ?.let { conversationId ->
                conversations.firstOrNull { it.conversationId == conversationId }
            }
        if (token != null && selectedAiConversation?.kind == "C2C") {
            val conversationId = selectedAiConversation.conversationId
            uiScope.launch(Dispatchers.IO) {
                val policy = runCatching {
                    conversationClient.aiConsent(token, conversationId)
                }.getOrNull()
                if (policy != null) {
                    withContext(Dispatchers.Main.immediate) {
                        if (selectedConversationId == conversationId) {
                            aiConsent = policy
                        }
                    }
                }
            }
        }
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
                groups.firstOrNull { it.conversationId == selectedConversationId }
                    ?.let { group ->
                        aiGroupPolicy = DesktopGroupAiPolicy(
                            conversationId = group.conversationId,
                            enabled = group.aiEnabled,
                            policyVersion = group.aiPolicyVersion)
                    }
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
            passwordChangeRequired = it?.passwordMustChange == true
        }.onFailure {
            error = messageFor(it)
        }
        restoring = false
    }

    LaunchedEffect(session?.deviceId, passwordChangeRequired) {
        val current = session ?: return@LaunchedEffect
        if (passwordChangeRequired) return@LaunchedEffect
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
                                    val aiPolicy = conversationClient.applyRealtimeAuthoritatively(
                                        (authStore.session ?: current).accessToken,
                                        local,
                                        envelope,
                                        (authStore.session ?: current).userId)
                                    if (aiPolicy != null
                                        && selectedConversationId == aiPolicy.conversationId) {
                                        aiConsent = aiPolicy
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
                        if (shouldRefreshContactRequests(envelope.operation)) {
                            refreshRequests()
                        }
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
                            is LoginOutcome.Authenticated -> {
                                session = result.session
                                passwordChangeRequired = result.session.passwordMustChange
                                passwordChangeError = null
                            }
                            is LoginOutcome.ReplacementRequired -> challenge = result.challenge
                        }
                    }
                    .onFailure { error = messageFor(it) }
            },
            onConfirmReplacement = {
                runCatching { authStore.confirmReplacement(challenge.orEmpty()) }
                    .onSuccess {
                        session = it
                        passwordChangeRequired = it.passwordMustChange
                        challenge = null
                    }
                    .onFailure { error = messageFor(it) }
            },
            onCancelReplacement = { challenge = null })
    } else if (passwordChangeRequired) {
        PasswordChangeScreen(
            temporaryPasswordRequired = session!!.passwordMustChange,
            error = passwordChangeError,
            onChange = { currentPassword, newPassword ->
                passwordChangeError = null
                runCatching {
                    authStore.changePassword(currentPassword, newPassword)
                }.onSuccess {
                    session = it
                    passwordChangeRequired = false
                }.onFailure { passwordChangeError = messageFor(it) }
            },
            onLogout = {
                authStore.logout()
                session = null
                passwordChangeRequired = false
            })
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
            groupJoinRequestsForGroup = data.groupJoinRequestsForGroup,
            groupManagementAccountNo = groupManagementAccountNo,
            groupInvite = data.groupInvite,
            draft = draft,
            aiConsent = aiConsent,
            aiGroupPolicy = aiGroupPolicy,
            aiDrafts = aiDrafts,
            canRequestAiSummary = aiSummaryRange?.canRequest == true,
            selectedAiMessageIds = selectedAiMessageIds,
            aiLoading = aiLoading,
            error = error,
            onRequestPasswordChange = { passwordChangeRequired = true },
            deleteAccountPassword = deleteAccountPassword,
            onDeleteAccountPasswordChange = { deleteAccountPassword = it },
            onDeleteAccount = {
                runCatching {
                    authStore.deleteAccount(deleteAccountPassword)
                }.onSuccess {
                    deleteAccountPassword = ""
                    session = null
                    data = DesktopData()
                }.onFailure { error = messageFor(it) }
            },
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
            onRefreshGroupJoinRequestsForGroup = { conversationId ->
                val current = authStore.session ?: session ?: return@MainScreen
                uiScope.launch {
                    runCatching {
                        withContext(Dispatchers.IO) {
                            conversationClient.listGroupJoinRequests(
                                current.accessToken,
                                conversationId)
                        }
                    }.onSuccess { data = data.copy(groupJoinRequestsForGroup = it) }
                        .onFailure { error = messageFor(it) }
                }
            },
            onBeginManageGroup = { group ->
                groupNameDraft = group.name
                groupDescriptionDraft = group.description
                groupVisibilityDraft = group.visibility
                refreshGroupMembers(group.conversationId)
                val current = authStore.session ?: session ?: return@MainScreen
                uiScope.launch {
                    runCatching {
                        withContext(Dispatchers.IO) {
                            conversationClient.listGroupJoinRequests(
                                current.accessToken,
                                group.conversationId)
                        }
                    }.onSuccess { data = data.copy(groupJoinRequestsForGroup = it) }
                        .onFailure { error = messageFor(it) }
                }
            },
            onApproveGroupJoinRequest = { conversationId, requestId ->
                val current = authStore.session ?: session ?: return@MainScreen
                uiScope.launch {
                    runCatching {
                        withContext(Dispatchers.IO) {
                            conversationClient.approveGroupJoinRequest(
                                current.accessToken,
                                conversationId,
                                requestId)
                        }
                        refreshGroups()
                        refreshGroupMembers(conversationId)
                        data = data.copy(
                            groupJoinRequestsForGroup = withContext(Dispatchers.IO) {
                                conversationClient.listGroupJoinRequests(
                                    current.accessToken,
                                    conversationId)
                            })
                    }.onFailure { error = messageFor(it) }
                }
            },
            onRejectGroupJoinRequest = { conversationId, requestId ->
                val current = authStore.session ?: session ?: return@MainScreen
                uiScope.launch {
                    runCatching {
                        withContext(Dispatchers.IO) {
                            conversationClient.rejectGroupJoinRequest(
                                current.accessToken,
                                conversationId,
                                requestId)
                        }
                        data = data.copy(
                            groupJoinRequestsForGroup = withContext(Dispatchers.IO) {
                                conversationClient.listGroupJoinRequests(
                                    current.accessToken,
                                    conversationId)
                            })
                    }.onFailure { error = messageFor(it) }
                }
            },
            groupNameDraft = groupNameDraft,
            onGroupNameDraftChange = { groupNameDraft = it.take(128) },
            groupDescriptionDraft = groupDescriptionDraft,
            onGroupDescriptionDraftChange = { groupDescriptionDraft = it.take(1000) },
            groupVisibilityDraft = groupVisibilityDraft,
            onGroupVisibilityDraftChange = { groupVisibilityDraft = it },
            onUpdateGroupProfile = { conversationId ->
                val current = authStore.session ?: session ?: return@MainScreen
                uiScope.launch {
                    runCatching {
                        withContext(Dispatchers.IO) {
                            conversationClient.updateGroupProfile(
                                current.accessToken,
                                conversationId,
                                groupNameDraft.trim(),
                                groupDescriptionDraft.trim(),
                                groupVisibilityDraft)
                        }
                        refreshGroups()
                        refreshGroupMembers(conversationId)
                    }.onFailure { error = messageFor(it) }
                }
            },
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
                aiDraftArtifactId = null
                aiSummaryRange = null
                aiConsent = null
                aiGroupPolicy = null
                uiScope.launch {
                    runCatching {
                        val policy = withContext(Dispatchers.IO) {
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
                            val accessToken = authStore.session?.accessToken
                                ?: session!!.accessToken
                            val conversation = local.listConversations()
                                .first { it.conversationId == conversationId }
                            val consent: DesktopAiConsent?
                            val groupPolicy: DesktopGroupAiPolicy?
                            if (conversation.kind == "GROUP") {
                                consent = null
                                groupPolicy = conversationClient.groupAiPolicy(
                                    accessToken, conversationId)
                            } else {
                                consent = conversationClient.aiConsent(accessToken, conversationId)
                                groupPolicy = null
                            }
                            DesktopAiSelectionContext(
                                consent = consent,
                                groupPolicy = groupPolicy,
                                summaryRange = DesktopAiSummaryRange(
                                    afterSeq = conversation.readSeq,
                                    untilSeq = local.lastConversationSeq(conversationId)))
                        }
                        refreshLocal()
                        policy
                    }.onSuccess { context ->
                        if (selectedConversationId != conversationId) return@onSuccess
                        aiConsent = context.consent
                        aiGroupPolicy = context.groupPolicy
                        aiSummaryRange = context.summaryRange.includeNewMessages(
                            data.messages,
                            (authStore.session ?: session!!).userId)
                    }
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
                            val conversation = authStore.localDatabase()
                                ?.listConversations()
                                ?.firstOrNull { it.conversationId == conversationId }
                                ?: error("PC local conversation is not available")
                            if (conversation.kind == "GROUP") {
                                null to conversationClient.updateGroupAiPolicy(
                                    current.accessToken,
                                    conversationId,
                                    !(aiGroupPolicy?.enabled ?: false))
                            } else {
                                conversationClient.updateAiConsent(
                                    current.accessToken,
                                    conversationId,
                                    !(aiConsent?.enabled ?: false)) to null
                            }
                        }
                    }.onSuccess { (consent, groupPolicy) ->
                        aiConsent = consent
                        aiGroupPolicy = groupPolicy
                        if (groupPolicy != null) refreshGroups()
                    }
                        .onFailure { error = messageFor(it) }
                    aiLoading = false
                }
            },
            onToggleAiMessage = { messageId ->
                selectedAiMessageIds = selectedAiMessageIds.toMutableSet().also {
                    if (!it.add(messageId)) it.remove(messageId)
                }
            },
            onRequestAiSummary = {
                val conversationId = selectedConversationId ?: return@MainScreen
                val current = authStore.session ?: session ?: return@MainScreen
                val summaryRange = aiSummaryRange ?: return@MainScreen
                if (!summaryRange.canRequest) return@MainScreen
                uiScope.launch {
                    aiLoading = true
                    runCatching {
                        withContext(Dispatchers.IO) {
                            val local = authStore.localDatabase()
                                ?: error("PC local database is not open")
                            conversationClient.requestSummary(
                                current.accessToken,
                                conversationId,
                                afterSeq = summaryRange.afterSeq,
                                untilSeq = summaryRange.untilSeq,
                                onJobUpdate = { conversationClient.applyAiJob(local, it) })
                            conversationClient.refreshAiData(current.accessToken, local)
                        }
                    }.onSuccess {
                        if (selectedConversationId == conversationId) {
                            aiSummaryRange = aiSummaryRange
                                ?.summarizedThrough(summaryRange.untilSeq)
                        }
                        refreshLocal()
                    }
                        .onFailure { error = messageFor(it) }
                    aiLoading = false
                }
            },
            onRequestSmartReplies = {
                val conversationId = selectedConversationId ?: return@MainScreen
                val current = authStore.session ?: session ?: return@MainScreen
                uiScope.launch {
                    aiLoading = true
                    runCatching {
                        withContext(Dispatchers.IO) {
                            val local = authStore.localDatabase()
                                ?: error("PC local database is not open")
                            val replies = conversationClient.requestSmartReplies(
                                current.accessToken,
                                conversationId,
                                onJobUpdate = { conversationClient.applyAiJob(local, it) })
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
                            val local = authStore.localDatabase()
                                ?: error("PC local database is not open")
                            conversationClient.extractInformation(
                                current.accessToken,
                                conversationId,
                                selectedAiMessageIds.toList(),
                                onJobUpdate = { conversationClient.applyAiJob(local, it) })
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
            onDeleteAiJob = { jobId ->
                val current = authStore.session ?: session ?: return@MainScreen
                uiScope.launch {
                    runCatching {
                        withContext(Dispatchers.IO) {
                            conversationClient.deleteAiJob(current.accessToken, jobId)
                            val local = authStore.localDatabase()
                                ?: error("PC local database is not open")
                            local.deleteAiJob(jobId)
                            conversationClient.refreshAiData(current.accessToken, local)
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
private fun PasswordChangeScreen(
    temporaryPasswordRequired: Boolean,
    error: String?,
    onChange: (String, String) -> Unit,
    onLogout: () -> Unit,
) {
    var currentPassword by remember { mutableStateOf("") }
    var newPassword by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize().padding(56.dp)) {
        Text(
            if (temporaryPasswordRequired) "Password change required" else "Change password",
            style = MaterialTheme.typography.displaySmall)
        Spacer(Modifier.height(8.dp))
        Text(
            if (temporaryPasswordRequired) {
                "Sign in with the temporary password, then choose a permanent password before using Jitong."
            } else {
                "Enter your current password. Other devices will be signed out immediately."
            })
        Spacer(Modifier.height(24.dp))
        OutlinedTextField(
            modifier = Modifier.fillMaxWidth(),
            value = currentPassword,
            onValueChange = { currentPassword = it },
            label = { Text(if (temporaryPasswordRequired) "Temporary password" else "Current password") },
            visualTransformation = PasswordVisualTransformation(),
            singleLine = true)
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            modifier = Modifier.fillMaxWidth(),
            value = newPassword,
            onValueChange = { newPassword = it },
            label = { Text("New password") },
            visualTransformation = PasswordVisualTransformation(),
            singleLine = true)
        Spacer(Modifier.height(20.dp))
        Button(
            modifier = Modifier.fillMaxWidth(),
            enabled = currentPassword.isNotBlank() && newPassword.length >= 8,
            onClick = { onChange(currentPassword, newPassword) }) {
            Text("Save password")
        }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(
            modifier = Modifier.fillMaxWidth(),
            onClick = onLogout) {
            Text("Log out")
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
    groupJoinRequestsForGroup: List<DesktopGroupJoinRequestSummary>,
    groupManagementAccountNo: String,
    groupNameDraft: String,
    groupDescriptionDraft: String,
    groupVisibilityDraft: String,
    groupInvite: DesktopGroupInvite?,
    draft: String,
    aiConsent: DesktopAiConsent?,
    aiGroupPolicy: DesktopGroupAiPolicy?,
    aiDrafts: List<DesktopAiDraft>,
    canRequestAiSummary: Boolean,
    selectedAiMessageIds: Set<String>,
    aiLoading: Boolean,
    error: String?,
    onRequestPasswordChange: () -> Unit,
    deleteAccountPassword: String,
    onDeleteAccountPasswordChange: (String) -> Unit,
    onDeleteAccount: () -> Unit,
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
    onRefreshGroupJoinRequestsForGroup: (String) -> Unit,
    onBeginManageGroup: (DesktopGroupSummary) -> Unit,
    onApproveGroupJoinRequest: (String, String) -> Unit,
    onRejectGroupJoinRequest: (String, String) -> Unit,
    onGroupNameDraftChange: (String) -> Unit,
    onGroupDescriptionDraftChange: (String) -> Unit,
    onGroupVisibilityDraftChange: (String) -> Unit,
    onUpdateGroupProfile: (String) -> Unit,
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
    onRequestAiSummary: () -> Unit,
    onRequestSmartReplies: () -> Unit,
    onEditAiDraft: (Int, String) -> Unit,
    onUseAiDraft: (String) -> Unit,
    onExtractAiInformation: () -> Unit,
    onDeleteAiArtifact: (String) -> Unit,
    onDeleteAiJob: (String) -> Unit,
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
                        OutlinedButton(onClick = onRequestPasswordChange) {
                            Text("Change password")
                        }
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
                    OutlinedTextField(
                        value = deleteAccountPassword,
                        onValueChange = onDeleteAccountPasswordChange,
                        label = { Text("Current password to delete account") },
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Button(
                        onClick = onDeleteAccount,
                        enabled = deleteAccountPassword.isNotBlank(),
                    ) {
                        Text("Permanently delete account")
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
                    groupJoinRequestsForGroup = groupJoinRequestsForGroup,
                    groupManagementAccountNo = groupManagementAccountNo,
                    groupNameDraft = groupNameDraft,
                    groupDescriptionDraft = groupDescriptionDraft,
                    groupVisibilityDraft = groupVisibilityDraft,
                    groupInvite = groupInvite,
                    onGroupSearchQueryChange = onGroupSearchQueryChange,
                    onGroupSearch = onGroupSearch,
                    onJoinGroup = onJoinGroup,
                    onGroupJoinGroupNoChange = onGroupJoinGroupNoChange,
                    onGroupJoinTokenChange = onGroupJoinTokenChange,
                    onGroupManagementAccountNoChange = onGroupManagementAccountNoChange,
                    onRefreshGroupJoinRequests = onRefreshGroupJoinRequests,
                    onRefreshGroupMembers = onRefreshGroupMembers,
                    onRefreshGroupJoinRequestsForGroup = onRefreshGroupJoinRequestsForGroup,
                    onBeginManageGroup = onBeginManageGroup,
                    onApproveGroupJoinRequest = onApproveGroupJoinRequest,
                    onRejectGroupJoinRequest = onRejectGroupJoinRequest,
                    onGroupNameDraftChange = onGroupNameDraftChange,
                    onGroupDescriptionDraftChange = onGroupDescriptionDraftChange,
                    onGroupVisibilityDraftChange = onGroupVisibilityDraftChange,
                    onUpdateGroupProfile = onUpdateGroupProfile,
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
                                Text(
                                    buildString {
                                        append(conversation.peerDisplayName)
                                        conversation.peerAccountNo?.let {
                                            append(" · ")
                                            append(it)
                                        }
                                    })
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
                aiGroupPolicy = aiGroupPolicy,
                aiDrafts = aiDrafts,
                canRequestAiSummary = canRequestAiSummary,
                aiJobs = data.aiJobs.filter {
                    it.conversationId == selectedConversationId
                },
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
                onRequestAiSummary = onRequestAiSummary,
                onRequestSmartReplies = onRequestSmartReplies,
                onEditAiDraft = onEditAiDraft,
                onUseAiDraft = onUseAiDraft,
                onExtractAiInformation = onExtractAiInformation,
                onDeleteAiArtifact = onDeleteAiArtifact,
                onDeleteAiJob = onDeleteAiJob,
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
    groupJoinRequestsForGroup: List<DesktopGroupJoinRequestSummary>,
    groupManagementAccountNo: String,
    groupNameDraft: String,
    groupDescriptionDraft: String,
    groupVisibilityDraft: String,
    groupInvite: DesktopGroupInvite?,
    onGroupSearchQueryChange: (String) -> Unit,
    onGroupSearch: () -> Unit,
    onJoinGroup: () -> Unit,
    onGroupJoinGroupNoChange: (String) -> Unit,
    onGroupJoinTokenChange: (String) -> Unit,
    onGroupManagementAccountNoChange: (String) -> Unit,
    onRefreshGroupJoinRequests: () -> Unit,
    onRefreshGroupMembers: (String) -> Unit,
    onRefreshGroupJoinRequestsForGroup: (String) -> Unit,
    onBeginManageGroup: (DesktopGroupSummary) -> Unit,
    onApproveGroupJoinRequest: (String, String) -> Unit,
    onRejectGroupJoinRequest: (String, String) -> Unit,
    onGroupNameDraftChange: (String) -> Unit,
    onGroupDescriptionDraftChange: (String) -> Unit,
    onGroupVisibilityDraftChange: (String) -> Unit,
    onUpdateGroupProfile: (String) -> Unit,
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
                        if (DesktopGroupGovernancePolicy.canEditProfile(group.role)) {
                            OutlinedButton(onClick = {
                                managedGroupId = group.conversationId
                                onBeginManageGroup(group)
                            }) { Text("Manage") }
                            OutlinedButton(onClick = {
                                onCreateGroupInvite(group.conversationId)
                            }) { Text("Invite") }
                        }
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
                    if (managedGroupId == group.conversationId &&
                        DesktopGroupGovernancePolicy.canEditProfile(group.role)) {
                        OutlinedTextField(
                            modifier = Modifier.fillMaxWidth(),
                            value = groupNameDraft,
                            onValueChange = onGroupNameDraftChange,
                            label = { Text("Group name") },
                            singleLine = true)
                        OutlinedTextField(
                            modifier = Modifier.fillMaxWidth(),
                            value = groupDescriptionDraft,
                            onValueChange = onGroupDescriptionDraftChange,
                            label = { Text("Group description") })
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            listOf("PUBLIC", "UNLISTED", "PRIVATE").forEach { visibility ->
                                OutlinedButton(
                                    onClick = { onGroupVisibilityDraftChange(visibility) }) {
                                    Text(
                                        if (groupVisibilityDraft == visibility) {
                                            "✓ $visibility"
                                        } else {
                                            visibility
                                        })
                                }
                            }
                        }
                        Button(onClick = { onUpdateGroupProfile(group.conversationId) }) {
                            Text("Save group profile")
                        }
                        if (DesktopGroupGovernancePolicy.canApproveJoinRequests(group.role)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                                Text("Pending join requests")
                                OutlinedButton(onClick = {
                                    onRefreshGroupJoinRequestsForGroup(group.conversationId)
                                }) { Text("Refresh") }
                            }
                            groupJoinRequestsForGroup
                                .filter {
                                    it.conversationId == group.conversationId &&
                                        it.status == "PENDING"
                                }
                                .forEach { request ->
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                                        Text("${request.displayName} · ${request.accountNo}")
                                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                            Button(onClick = {
                                                onApproveGroupJoinRequest(
                                                    request.conversationId,
                                                    request.requestId)
                                            }) { Text("Approve") }
                                            OutlinedButton(onClick = {
                                                onRejectGroupJoinRequest(
                                                    request.conversationId,
                                                    request.requestId)
                                            }) { Text("Reject") }
                                        }
                                    }
                                }
                        }
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
                                if (member.role == "MEMBER" && group.role == "OWNER") {
                                    OutlinedButton(onClick = {
                                        onChangeGroupRole(
                                            group.conversationId,
                                            member.userId,
                                            "ADMIN")
                                    }) { Text("Admin") }
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
                                if (DesktopGroupGovernancePolicy.canRemoveMember(
                                        group.role,
                                        member.role)) {
                                    OutlinedButton(onClick = {
                                        onRemoveGroupMember(
                                            group.conversationId,
                                            member.userId)
                                    }) { Text("Remove") }
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
    aiGroupPolicy: DesktopGroupAiPolicy?,
    aiDrafts: List<DesktopAiDraft>,
    canRequestAiSummary: Boolean,
    aiJobs: List<LocalAiJob>,
    aiArtifacts: List<LocalAiArtifact>,
    aiActionItems: List<LocalAiActionItem>,
    selectedAiMessageIds: Set<String>,
    aiLoading: Boolean,
    error: String?,
    onDraftChange: (String) -> Unit,
    onToggleAiConsent: () -> Unit,
    onToggleAiMessage: (String) -> Unit,
    onRequestAiSummary: () -> Unit,
    onRequestSmartReplies: () -> Unit,
    onEditAiDraft: (Int, String) -> Unit,
    onUseAiDraft: (String) -> Unit,
    onExtractAiInformation: () -> Unit,
    onDeleteAiArtifact: (String) -> Unit,
    onDeleteAiJob: (String) -> Unit,
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
            buildString {
                append(selectedConversation.peerDisplayName)
                selectedConversation.peerAccountNo?.let {
                    append(" · ")
                    append(it)
                }
            },
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
                        if (message.senderDisplayName.isNotBlank()) {
                            Text(
                                message.senderDisplayName,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        Text(if (message.localState == "SENDING") "Sending…" else message.localState)
                        when {
                            message.type == "SYSTEM" -> Text(message.groupMessageText())
                            message.state == "RECALLED" -> Text(message.groupMessageText())
                            message.state == "MODERATED" -> Text(message.groupMessageText())
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
                        if (message.isEligibleForAiEvidence()) {
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
            conversationRole = selectedConversation.relationship,
            online = online,
            consent = aiConsent,
            groupPolicy = aiGroupPolicy,
            drafts = aiDrafts,
            canRequestSummary = canRequestAiSummary,
            jobs = aiJobs,
            artifacts = aiArtifacts,
            actionItems = aiActionItems,
            selectedMessageCount = selectedAiMessageIds.size,
            loading = aiLoading,
            onToggleConsent = onToggleAiConsent,
            onRequestSummary = onRequestAiSummary,
            onRequestSmartReplies = onRequestSmartReplies,
            onEditDraft = onEditAiDraft,
            onUseDraft = onUseAiDraft,
            onExtractInformation = onExtractAiInformation,
            onDeleteArtifact = onDeleteAiArtifact,
            onDeleteJob = onDeleteAiJob,
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
    conversationRole: String,
    online: Boolean,
    consent: DesktopAiConsent?,
    groupPolicy: DesktopGroupAiPolicy?,
    drafts: List<DesktopAiDraft>,
    canRequestSummary: Boolean,
    jobs: List<LocalAiJob>,
    artifacts: List<LocalAiArtifact>,
    actionItems: List<LocalAiActionItem>,
    selectedMessageCount: Int,
    loading: Boolean,
    onToggleConsent: () -> Unit,
    onRequestSummary: () -> Unit,
    onRequestSmartReplies: () -> Unit,
    onEditDraft: (Int, String) -> Unit,
    onUseDraft: (String) -> Unit,
    onExtractInformation: () -> Unit,
    onDeleteArtifact: (String) -> Unit,
    onDeleteJob: (String) -> Unit,
    onSetActionItemStatus: (String, String) -> Unit,
    onDeleteActionItem: (String) -> Unit,
) {
    if (conversationKind != "C2C" && conversationKind != "GROUP") return
    val aiEnabled = if (conversationKind == "GROUP") {
        groupPolicy?.enabled == true
    } else {
        consent?.enabledForBoth == true
    }
    val policyLoaded = if (conversationKind == "GROUP") groupPolicy != null else consent != null
    val canManagePolicy = conversationKind == "C2C" || conversationRole == "OWNER"
    val summaries = artifacts.mapNotNull(::parseDesktopAiSummary)
    val facts = artifacts.flatMap(::parseDesktopAiFacts)
    val draftArtifactId = artifacts.firstOrNull { it.artifactType == "SMART_REPLY" }?.artifactId
    Card(Modifier.fillMaxWidth().padding(top = 8.dp)) {
        LazyColumn(
            modifier = Modifier.fillMaxWidth().heightIn(max = 440.dp).padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                Text("Private AI assistant", style = MaterialTheme.typography.titleMedium)
                Text(
                    if (conversationKind == "GROUP") {
                        when {
                            groupPolicy == null -> "Loading group AI policy…"
                            groupPolicy.enabled -> "Enabled for this group by its owner"
                            else -> "Disabled for this group"
                        }
                    } else {
                        when {
                            consent == null -> "Loading conversation consent…"
                            consent.enabledForBoth -> "Enabled by both participants"
                            consent.enabled -> "You enabled AI; waiting for the other participant"
                            else -> "Disabled for you"
                        }
                    })
                Text(
                    "Results stay private. Images are included only when the server and model " +
                        "allow it; a reply is copied to the composer and is never sent automatically.")
                if (canManagePolicy) {
                    OutlinedButton(
                        enabled = online && policyLoaded && !loading,
                        onClick = onToggleConsent,
                    ) {
                        val enabledByActor = if (conversationKind == "GROUP") {
                            groupPolicy?.enabled == true
                        } else {
                            consent?.enabled == true
                        }
                        Text(if (enabledByActor) "Disable AI" else "Enable AI")
                    }
                }
            }
            if (loading) {
                item { Text("AI task queued or running…") }
            }
            if (aiEnabled) {
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            enabled = online && !loading && canRequestSummary,
                            onClick = onRequestSummary,
                        ) { Text("Summarize unread") }
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
                    Button(onClick = { onUseDraft(reply.text) }) { Text("Copy to composer") }
                }
            }
            if (drafts.isNotEmpty() && draftArtifactId != null) {
                item {
                    OutlinedButton(onClick = { onDeleteArtifact(draftArtifactId) }) {
                        Text("Delete reply drafts")
                    }
                }
            }
            if (jobs.isNotEmpty()) {
                item { Text("AI tasks", style = MaterialTheme.typography.titleSmall) }
                items(jobs, key = { it.jobId }) { job ->
                    Card(Modifier.fillMaxWidth()) {
                        Row(
                            Modifier.fillMaxWidth().padding(8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text("${job.kind} · ${job.status}")
                                job.errorCode?.let { Text("Error: $it") }
                            }
                            OutlinedButton(onClick = { onDeleteJob(job.jobId) }) {
                                Text("Delete task")
                            }
                        }
                    }
                }
            }
            if (summaries.isNotEmpty()) {
                item { Text("Summaries", style = MaterialTheme.typography.titleSmall) }
                items(summaries, key = { it.artifactId }) { summary ->
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(8.dp)) {
                            Text(summary.overview)
                            if (summary.keyPoints.isNotEmpty()) {
                                Text("Key points: ${summary.keyPoints.joinToString()}")
                            }
                            if (summary.decisions.isNotEmpty()) {
                                Text("Decisions: ${summary.decisions.joinToString()}")
                            }
                            if (summary.openQuestions.isNotEmpty()) {
                                Text("Open questions: ${summary.openQuestions.joinToString()}")
                            }
                            Text("Evidence: ${summary.sourceMessageIds.joinToString()}")
                            OutlinedButton(onClick = { onDeleteArtifact(summary.artifactId) }) {
                                Text("Delete summary")
                            }
                        }
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

private fun parseDesktopAiSummary(artifact: LocalAiArtifact): DesktopAiSummary? {
    if (artifact.artifactType != "SUMMARY") return null
    return runCatching {
        val summary = Json.parseToJsonElement(artifact.contentJson).jsonObject
        DesktopAiSummary(
            artifactId = artifact.artifactId,
            overview = summary.getValue("overview").jsonPrimitive.content,
            keyPoints = summary.getValue("keyPoints").jsonArray.map { it.jsonPrimitive.content },
            decisions = summary.getValue("decisions").jsonArray.map { it.jsonPrimitive.content },
            openQuestions = summary.getValue("openQuestions").jsonArray.map {
                it.jsonPrimitive.content
            },
            sourceMessageIds = summary.getValue("sourceMessageIds").jsonArray.map {
                it.jsonPrimitive.content
            })
    }.getOrNull()
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
