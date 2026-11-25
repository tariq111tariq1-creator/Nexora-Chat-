package com.example

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.example.data.local.ChannelMode
import com.example.data.local.SUPPORTED_LANGUAGES
import com.example.data.repository.CallPhase
import com.example.notifications.InAppBannerEvent
import com.example.notifications.NotificationOrchestrator
import com.example.ui.OfflineChatViewModel
import com.example.ui.components.ActiveCallMiniBar
import com.example.ui.components.InAppNotificationBanner
import com.example.ui.navigation.*
import com.example.ui.screens.*
import com.example.ui.theme.OfflineChatTheme
import kotlinx.coroutines.delay

class MainActivity : AppCompatActivity() {

    private val viewModel: OfflineChatViewModel by viewModels()
    private var pendingConversationId by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handleIntent(intent)

        setContent {
            val settings by viewModel.userSettings.collectAsStateWithLifecycle()
            val authUser by viewModel.authUser.collectAsStateWithLifecycle()
            val authInProgress by viewModel.authInProgress.collectAsStateWithLifecycle()
            val authError by viewModel.authError.collectAsStateWithLifecycle()
            val permissionSnapshot by viewModel.permissionStatus.collectAsStateWithLifecycle()

            val isRtl = remember(settings.languageTag) {
                SUPPORTED_LANGUAGES.firstOrNull { it.tag == settings.languageTag }?.isRtl == true
            }

            LaunchedEffect(settings.onboardingCompleted) {
                if (settings.onboardingCompleted) {
                    viewModel.startMeshService()
                }
            }

            CompositionLocalProvider(
                LocalLayoutDirection provides if (isRtl) LayoutDirection.Rtl else LayoutDirection.Ltr
            ) {
                OfflineChatTheme(themePreference = settings.themePreference) {
                    when {
                        !settings.onboardingCompleted -> {
                            OnboardingScreen(
                                settings = settings,
                                permissionSnapshot = permissionSnapshot,
                                onLanguageSelected = { viewModel.setAppLanguage(it) },
                                onPermissionsChanged = { viewModel.refreshPermissions() },
                                onComplete = { name, lang -> viewModel.completeOnboarding(name, lang) }
                            )
                        }

                        authUser == null && !settings.offlineOnlyBypass -> {
                            LaunchedEffect(Unit) {
                                viewModel.attemptSilentSignIn(this@MainActivity)
                            }
                            AuthScreen(
                                isSigningIn = authInProgress,
                                errorMessage = authError,
                                onGoogleSignIn = { viewModel.signInWithGoogle(this@MainActivity) },
                                onContinueOfflineOnly = { viewModel.continueOfflineOnly() }
                            )
                        }

                        else -> {
                            key(authUser?.uid ?: "offline_local") {
                                OfflineChatRoot(
                                    viewModel = viewModel,
                                    activityContext = this@MainActivity,
                                    pendingConversationId = pendingConversationId,
                                    onConsumedPendingConversation = { pendingConversationId = null }
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        val convId = intent?.getStringExtra(NotificationOrchestrator.EXTRA_CONVERSATION_ID)
        if (!convId.isNullOrBlank()) {
            pendingConversationId = convId
        }
    }

    override fun onStart() {
        super.onStart()
        NotificationOrchestrator.isAppInForeground = true
    }

    override fun onResume() {
        super.onResume()
        viewModel.refreshPermissions()
        viewModel.onAppForegrounded()
    }

    override fun onStop() {
        NotificationOrchestrator.isAppInForeground = false
        super.onStop()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun OfflineChatRoot(
    viewModel: OfflineChatViewModel,
    activityContext: android.content.Context,
    pendingConversationId: String?,
    onConsumedPendingConversation: () -> Unit
) {
    val settings by viewModel.userSettings.collectAsStateWithLifecycle()
    val authUser by viewModel.authUser.collectAsStateWithLifecycle()
    val cloudProfile by viewModel.cloudProfile.collectAsStateWithLifecycle()
    val isInternetAvailable by viewModel.isInternetAvailable.collectAsStateWithLifecycle()
    val permissionSnapshot by viewModel.permissionStatus.collectAsStateWithLifecycle()
    val isScanning by viewModel.isScanning.collectAsStateWithLifecycle()
    val wifiDirectEnabled by viewModel.wifiDirectEnabled.collectAsStateWithLifecycle()
    val wifiDirectPeers by viewModel.wifiDirectPeers.collectAsStateWithLifecycle()
    val lanPeers by viewModel.lanPeers.collectAsStateWithLifecycle()
    val savedPeers by viewModel.savedPeers.collectAsStateWithLifecycle()
    val connectedPeerIds by viewModel.connectedPeerIds.collectAsStateWithLifecycle()
    val hotspotState by viewModel.hotspotState.collectAsStateWithLifecycle()
    val conversations by viewModel.conversations.collectAsStateWithLifecycle()
    val transfers by viewModel.transfers.collectAsStateWithLifecycle()
    val typingPeers by viewModel.typingPeers.collectAsStateWithLifecycle()
    val activeCall by viewModel.activeCall.collectAsStateWithLifecycle()
    val remoteVideoFrame by viewModel.remoteVideoFrame.collectAsStateWithLifecycle()
    val localWebRtcTrack by viewModel.localWebRtcTrack.collectAsStateWithLifecycle()
    val remoteWebRtcTrack by viewModel.remoteWebRtcTrack.collectAsStateWithLifecycle()
    val recordingState by viewModel.recordingState.collectAsStateWithLifecycle()
    val playbackState by viewModel.playbackState.collectAsStateWithLifecycle()
    val statusMessage by viewModel.statusBannerMessage.collectAsStateWithLifecycle()

    val isSignedIn = authUser != null
    val myShareCode = cloudProfile?.shareCode?.ifBlank { settings.cloudShareCode } ?: settings.cloudShareCode
    val myDisplayName = cloudProfile?.displayName?.ifBlank { settings.displayName } ?: settings.displayName

    var currentBanner by remember { mutableStateOf<InAppBannerEvent?>(null) }
    var isCallMinimized by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        viewModel.inAppBanners.collect { event ->
            currentBanner = event
        }
    }

    LaunchedEffect(currentBanner) {
        if (currentBanner != null) {
            delay(4500L)
            currentBanner = null
        }
    }

    val context = LocalContext.current
    LaunchedEffect(activeCall.phase) {
        if (activeCall.phase == CallPhase.IDLE || activeCall.phase == CallPhase.INCOMING_RINGING) {
            isCallMinimized = false
        }
        val activity = context as? AppCompatActivity ?: return@LaunchedEffect
        val isCallActive = activeCall.phase != CallPhase.IDLE
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            activity.setShowWhenLocked(activeCall.phase == CallPhase.INCOMING_RINGING)
            activity.setTurnScreenOn(activeCall.phase == CallPhase.INCOMING_RINGING)
        }
        if (isCallActive) {
            activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentDest = backStackEntry?.destination
    val isInChatRoom = currentDest?.hasRoute<ChatRoomRoute>() == true
    val totalUnread = remember(conversations) { conversations.sumOf { it.unreadCount } }

    LaunchedEffect(pendingConversationId) {
        if (!pendingConversationId.isNullOrBlank()) {
            navController.navigate(ChatRoomRoute(pendingConversationId)) {
                launchSingleTop = true
            }
            onConsumedPendingConversation()
        }
    }

    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(statusMessage) {
        if (!statusMessage.isNullOrBlank()) {
            snackbarHostState.showSnackbar(statusMessage!!)
            viewModel.clearStatusBanner()
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val isExpandedScreen = maxWidth >= 600.dp

            Scaffold(
                contentWindowInsets = WindowInsets.safeDrawing,
                snackbarHost = { SnackbarHost(snackbarHostState) },
                topBar = {
                    if (!isInChatRoom) {
                        Column {
                            CenterAlignedTopAppBar(
                                title = {
                                    Text(
                                        text = when {
                                            currentDest?.hasRoute<RadarRoute>() == true -> stringResource(R.string.connections_hub_title)
                                            currentDest?.hasRoute<TransfersRoute>() == true -> stringResource(R.string.transfers_title)
                                            currentDest?.hasRoute<SettingsRoute>() == true -> stringResource(R.string.settings_title)
                                            else -> stringResource(R.string.chats_title)
                                        },
                                        style = MaterialTheme.typography.titleLarge
                                    )
                                }
                            )
                            if (activeCall.phase != CallPhase.IDLE && isCallMinimized) {
                                ActiveCallMiniBar(
                                    call = activeCall,
                                    onReturnToCall = { isCallMinimized = false },
                                    onEndCall = { viewModel.endCall() }
                                )
                            }
                        }
                    } else if (activeCall.phase != CallPhase.IDLE && isCallMinimized) {
                        Box(modifier = Modifier.windowInsetsPadding(WindowInsets.statusBars)) {
                            ActiveCallMiniBar(
                                call = activeCall,
                                onReturnToCall = { isCallMinimized = false },
                                onEndCall = { viewModel.endCall() }
                            )
                        }
                    }
                },
                bottomBar = {
                    if (!isExpandedScreen && !isInChatRoom) {
                        OfflineChatBottomBar(
                            currentDest = currentDest,
                            unreadCount = totalUnread,
                            onNavigate = { route ->
                                navController.navigate(route) {
                                    popUpTo(ChatsRoute) { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            }
                        )
                    }
                }
            ) { innerPadding ->
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding)
                ) {
                    if (isExpandedScreen && !isInChatRoom) {
                        OfflineChatNavRail(
                            currentDest = currentDest,
                            unreadCount = totalUnread,
                            onNavigate = { route ->
                                navController.navigate(route) {
                                    popUpTo(ChatsRoute) { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            }
                        )
                    }

                    NavHost(
                        navController = navController,
                        startDestination = ChatsRoute,
                        modifier = Modifier.weight(1f)
                    ) {
                        composable<ChatsRoute> {
                            var tabletSelectedConvId by remember { mutableStateOf<String?>(null) }

                            if (isExpandedScreen) {
                                Row(modifier = Modifier.fillMaxSize()) {
                                    Box(modifier = Modifier.weight(0.42f)) {
                                        ChatsListScreen(
                                            conversations = conversations,
                                            connectedPeerIds = connectedPeerIds,
                                            savedPeers = savedPeers,
                                            isSignedIn = isSignedIn,
                                            isInternetAvailable = isInternetAvailable,
                                            myShareCode = myShareCode,
                                            myDisplayName = myDisplayName,
                                            selectedConversationId = tabletSelectedConvId,
                                            onOpenConversation = { tabletSelectedConvId = it },
                                            onAddOnlineFriend = { codeOrQr ->
                                                viewModel.addOnlineFriendByCode(codeOrQr) { convId ->
                                                    tabletSelectedConvId = convId
                                                }
                                            },
                                            onCreateOnlineGroup = { title, ids ->
                                                viewModel.createOnlineGroup(title, ids) { tabletSelectedConvId = it }
                                            },
                                            onCreateOfflineGroup = { title, ids ->
                                                viewModel.createGroupChat(title, ids) { tabletSelectedConvId = it }
                                            },
                                            onGoogleSignInClick = { viewModel.signInWithGoogle(activityContext) },
                                            onOpenConnectionsTab = { navController.navigate(RadarRoute) },
                                            onDeleteConversation = { viewModel.deleteConversation(it) }
                                        )
                                    }
                                    VerticalDivider()
                                    Box(modifier = Modifier.weight(0.58f)) {
                                        val selectedId = tabletSelectedConvId ?: conversations.firstOrNull()?.conversationId
                                        if (selectedId != null) {
                                            ChatRoomContainer(
                                                conversationId = selectedId,
                                                viewModel = viewModel,
                                                connectedPeerIds = connectedPeerIds,
                                                isInternetAvailable = isInternetAvailable,
                                                transfers = transfers,
                                                typingPeers = typingPeers,
                                                recordingState = recordingState,
                                                playbackState = playbackState,
                                                showBackButton = false,
                                                onBack = {}
                                            )
                                        }
                                    }
                                }
                            } else {
                                ChatsListScreen(
                                    conversations = conversations,
                                    connectedPeerIds = connectedPeerIds,
                                    savedPeers = savedPeers,
                                    isSignedIn = isSignedIn,
                                    isInternetAvailable = isInternetAvailable,
                                    myShareCode = myShareCode,
                                    myDisplayName = myDisplayName,
                                    onOpenConversation = { convId ->
                                        navController.navigate(ChatRoomRoute(convId))
                                    },
                                    onAddOnlineFriend = { codeOrQr ->
                                        viewModel.addOnlineFriendByCode(codeOrQr) { convId ->
                                            navController.navigate(ChatRoomRoute(convId))
                                        }
                                    },
                                    onCreateOnlineGroup = { title, ids ->
                                        viewModel.createOnlineGroup(title, ids) { convId ->
                                            navController.navigate(ChatRoomRoute(convId))
                                        }
                                    },
                                    onCreateOfflineGroup = { title, ids ->
                                        viewModel.createGroupChat(title, ids) { convId ->
                                            navController.navigate(ChatRoomRoute(convId))
                                        }
                                    },
                                    onGoogleSignInClick = { viewModel.signInWithGoogle(activityContext) },
                                    onOpenConnectionsTab = { navController.navigate(RadarRoute) },
                                    onDeleteConversation = { viewModel.deleteConversation(it) }
                                )
                            }
                        }

                        composable<RadarRoute> {
                            RadarScreen(
                                isScanning = isScanning,
                                wifiDirectEnabled = wifiDirectEnabled,
                                permissionSnapshot = permissionSnapshot,
                                wifiDirectPeers = wifiDirectPeers,
                                lanPeers = lanPeers,
                                savedPeers = savedPeers,
                                connectedPeerIds = connectedPeerIds,
                                hotspotState = hotspotState,
                                isSignedIn = isSignedIn,
                                isInternetAvailable = isInternetAvailable,
                                myShareCode = myShareCode,
                                myDisplayName = myDisplayName,
                                signedInEmail = authUser?.email,
                                onAddOnlineFriend = { codeOrQr ->
                                    viewModel.addOnlineFriendByCode(codeOrQr) { convId ->
                                        navController.navigate(ChatRoomRoute(convId))
                                    }
                                },
                                onGoogleSignInClick = { viewModel.signInWithGoogle(activityContext) },
                                onStartScan = { viewModel.startNearbyScan() },
                                onStopScan = { viewModel.stopNearbyScan() },
                                onConnectWifiDirect = { addr -> viewModel.connectWifiDirectPeer(addr) },
                                onConnectIp = { ip, port -> viewModel.connectDirectIp(ip, port) },
                                onQrScanned = { raw -> viewModel.connectFromQr(raw) },
                                onStartHotspot = { viewModel.startLocalHotspot() },
                                onStopHotspot = { viewModel.stopLocalHotspot() },
                                onOpenChatForPeer = { peer ->
                                    viewModel.openPeerChat(peer) { convId ->
                                        navController.navigate(ChatRoomRoute(convId))
                                    }
                                },
                                onPermissionsChanged = { viewModel.refreshPermissions() },
                                onOpenSettingsTab = { navController.navigate(SettingsRoute) }
                            )
                        }

                        composable<ChatRoomRoute> { entry ->
                            val route = entry.toRoute<ChatRoomRoute>()
                            ChatRoomContainer(
                                conversationId = route.conversationId,
                                viewModel = viewModel,
                                connectedPeerIds = connectedPeerIds,
                                isInternetAvailable = isInternetAvailable,
                                transfers = transfers,
                                typingPeers = typingPeers,
                                recordingState = recordingState,
                                playbackState = playbackState,
                                showBackButton = true,
                                onBack = { navController.popBackStack() }
                            )
                        }

                        composable<TransfersRoute> {
                            TransfersScreen(
                                transfers = transfers,
                                onPauseTransfer = { viewModel.pauseTransfer(it) },
                                onResumeTransfer = { viewModel.resumeTransfer(it) },
                                onResumeAllTransfers = { viewModel.resumeAllTransfers() },
                                onCancelTransfer = { viewModel.cancelTransfer(it) },
                                onOpenFile = { path, mime -> viewModel.openFile(path, mime) },
                                onSaveToDownloads = { path, name, mime ->
                                    viewModel.saveToDownloads(path, name, mime)
                                }
                            )
                        }

                        composable<SettingsRoute> {
                            SettingsScreen(
                                settings = settings,
                                permissionSnapshot = permissionSnapshot,
                                isSignedIn = isSignedIn,
                                signedInEmail = authUser?.email,
                                myShareCode = myShareCode,
                                onGoogleSignInClick = { viewModel.signInWithGoogle(activityContext) },
                                onSignOutClick = { viewModel.signOut() },
                                onSelectPreferredMode = { viewModel.setPreferredMode(it) },
                                onUpdateName = { viewModel.updateDisplayName(it) },
                                onUpdateAvatarColor = { viewModel.updateAvatarColorIndex(it) },
                                onSelectLanguage = { viewModel.setAppLanguage(it) },
                                onSelectTheme = { viewModel.updateThemePreference(it) },
                                onUpdateSecurity = { enc, pin -> viewModel.updateSecuritySettings(enc, pin) },
                                onPermissionsChanged = { viewModel.refreshPermissions() }
                            )
                        }
                    }
                }
            }
        }

        // In-app Floating Notification Banner
        InAppNotificationBanner(
            banner = currentBanner,
            onOpenConversation = { convId ->
                currentBanner = null
                navController.navigate(ChatRoomRoute(convId)) {
                    launchSingleTop = true
                }
            },
            onDismiss = { currentBanner = null },
            modifier = Modifier
                .align(Alignment.TopCenter)
                .windowInsetsPadding(WindowInsets.statusBars)
        )

        // Full-Screen Active or Incoming Call Overlay
        if (activeCall.phase != CallPhase.IDLE && !isCallMinimized) {
            CallScreen(
                call = activeCall,
                remoteVideoFrame = remoteVideoFrame,
                localWebRtcTrack = localWebRtcTrack,
                remoteWebRtcTrack = remoteWebRtcTrack,
                webrtcEglContext = viewModel.webrtcEglContext,
                onAccept = { viewModel.acceptIncomingCall() },
                onDecline = { viewModel.declineIncomingCall() },
                onEndCall = { viewModel.endCall() },
                onToggleMute = { viewModel.toggleCallMute() },
                onToggleSpeaker = { viewModel.toggleCallSpeaker() },
                                onToggleCamera = { viewModel.toggleCallCamera() },
                onFlipCamera = { viewModel.flipCallCamera() },
                onMinimize = { isCallMinimized = true },
                onPermissionsGrantedInCall = { viewModel.restartCallMediaIfPermitted() },
                onCameraFrame = { proxy, front -> viewModel.forwardCameraFrame(proxy, front) }
            )
        }
    }
}

@Composable
private fun ChatRoomContainer(
    conversationId: String,
    viewModel: OfflineChatViewModel,
    connectedPeerIds: Set<String>,
    isInternetAvailable: Boolean,
    transfers: List<com.example.data.local.FileTransferEntity>,
    typingPeers: Map<String, Boolean>,
    recordingState: com.example.data.repository.VoiceRecordingState,
    playbackState: com.example.data.repository.VoicePlaybackState,
    showBackButton: Boolean,
    onBack: () -> Unit
) {
    var searchQuery by remember { mutableStateOf("") }
    val conversation by viewModel.observeConversation(conversationId)
        .collectAsStateWithLifecycle(initialValue = null)
    val messages by viewModel.observeMessages(conversationId, searchQuery)
        .collectAsStateWithLifecycle(initialValue = emptyList())

    val latestIncomingId = remember(messages) {
        messages.lastOrNull { !it.isOutgoing }?.messageId
    }
    LaunchedEffect(conversationId, latestIncomingId) {
        viewModel.clearUnread(conversationId)
    }

    val isOnline = remember(conversation, connectedPeerIds, isInternetAvailable) {
        val conv = conversation ?: return@remember false
        if (conv.channelMode == ChannelMode.ONLINE) {
            isInternetAvailable && conv.onlinePresence
        } else if (conv.isGroup) {
            conv.participantIdsCsv.split(",").any { connectedPeerIds.contains(it.trim()) }
        } else {
            conv.peerId != null && connectedPeerIds.contains(conv.peerId)
        }
    }

    ChatRoomScreen(
        conversation = conversation,
        messages = messages,
        transfers = transfers,
        isPeerOnline = isOnline,
        isPeerTyping = typingPeers[conversationId] == true,
        recordingState = recordingState,
        playbackState = playbackState,
        showBackButton = showBackButton,
        onBack = onBack,
        onEnterRoom = { viewModel.enterChatRoom(conversationId) },
        onExitRoom = { viewModel.exitChatRoom() },
        onSearchQueryChanged = { searchQuery = it },
        onSendMessage = { text, reply -> viewModel.sendTextMessage(conversationId, text, reply) },
        onTypingChanged = { typing -> viewModel.sendTypingState(conversationId, typing) },
        onReactMessage = { msgId, emoji -> viewModel.reactToMessage(conversationId, msgId, emoji) },
        onRetryMessage = { msgId -> viewModel.retryMessage(msgId) },
        onDeleteMessage = { msgId -> viewModel.deleteMessage(msgId) },
        onStartVoiceRecord = { viewModel.startVoiceNoteRecording() },
        onStopSendVoiceNote = { viewModel.stopAndSendVoiceNote(conversationId) },
        onCancelVoiceRecord = { viewModel.cancelVoiceNoteRecording() },
        onToggleVoicePlay = { msgId, path -> viewModel.toggleVoicePlayback(msgId, path) },
        onSendFileUri = { uri -> viewModel.sendFileUri(conversationId, uri) },
        onPauseTransfer = { id -> viewModel.pauseTransfer(id) },
        onResumeTransfer = { id -> viewModel.resumeTransfer(id) },
        onOpenFile = { path, mime -> viewModel.openFile(path, mime) },
        onSaveToDownloads = { path, name, mime -> viewModel.saveToDownloads(path, name, mime) },
        onStartAudioCall = { viewModel.startCall(conversationId, isVideo = false) },
        onStartVideoCall = { viewModel.startCall(conversationId, isVideo = true) }
    )
}

@Composable
private fun OfflineChatBottomBar(
    currentDest: androidx.navigation.NavDestination?,
    unreadCount: Int,
    onNavigate: (Any) -> Unit
) {
    NavigationBar(windowInsets = WindowInsets.navigationBars) {
        val isChats = currentDest?.hasRoute<ChatsRoute>() == true
        val isTransfers = currentDest?.hasRoute<TransfersRoute>() == true
        val isSettings = currentDest?.hasRoute<SettingsRoute>() == true

        NavigationBarItem(
            selected = isChats,
            onClick = { onNavigate(ChatsRoute) },
            icon = {
                BadgedBox(
                    badge = {
                        if (unreadCount > 0) {
                            Badge { Text(unreadCount.toString()) }
                        }
                    }
                ) {
                    Icon(
                        if (isChats) Icons.Rounded.Chat else Icons.Outlined.Chat,
                        contentDescription = stringResource(R.string.nav_chats)
                    )
                }
            },
            label = { Text(stringResource(R.string.nav_chats)) },
            modifier = Modifier.testTag("nav_chats_item")
        )
        NavigationBarItem(
            selected = isTransfers,
            onClick = { onNavigate(TransfersRoute) },
            icon = {
                Icon(
                    if (isTransfers) Icons.Rounded.FolderCopy else Icons.Outlined.FolderCopy,
                    contentDescription = stringResource(R.string.nav_transfers)
                )
            },
            label = { Text(stringResource(R.string.nav_transfers)) },
            modifier = Modifier.testTag("nav_transfers_item")
        )
        NavigationBarItem(
            selected = isSettings,
            onClick = { onNavigate(SettingsRoute) },
            icon = {
                Icon(
                    if (isSettings) Icons.Rounded.Settings else Icons.Outlined.Settings,
                    contentDescription = stringResource(R.string.nav_settings)
                )
            },
            label = { Text(stringResource(R.string.nav_settings)) },
            modifier = Modifier.testTag("nav_settings_item")
        )
    }
}

@Composable
private fun OfflineChatNavRail(
    currentDest: androidx.navigation.NavDestination?,
    unreadCount: Int,
    onNavigate: (Any) -> Unit
) {
    NavigationRail {
        val isChats = currentDest?.hasRoute<ChatsRoute>() == true
        val isTransfers = currentDest?.hasRoute<TransfersRoute>() == true
        val isSettings = currentDest?.hasRoute<SettingsRoute>() == true

        NavigationRailItem(
            selected = isChats,
            onClick = { onNavigate(ChatsRoute) },
            icon = {
                BadgedBox(badge = { if (unreadCount > 0) Badge { Text(unreadCount.toString()) } }) {
                    Icon(Icons.Rounded.Chat, contentDescription = stringResource(R.string.nav_chats))
                }
            },
            label = { Text(stringResource(R.string.nav_chats)) }
        )
        NavigationRailItem(
            selected = isTransfers,
            onClick = { onNavigate(TransfersRoute) },
            icon = { Icon(Icons.Rounded.FolderCopy, contentDescription = stringResource(R.string.nav_transfers)) },
            label = { Text(stringResource(R.string.nav_transfers)) }
        )
        NavigationRailItem(
            selected = isSettings,
            onClick = { onNavigate(SettingsRoute) },
            icon = { Icon(Icons.Rounded.Settings, contentDescription = stringResource(R.string.nav_settings)) },
            label = { Text(stringResource(R.string.nav_settings)) }
        )
    }
}
