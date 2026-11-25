package com.example.ui.screens

import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.data.local.ChannelMode
import com.example.data.local.ConversationEntity
import com.example.data.local.PeerEntity
import com.example.ui.components.CameraQrScannerDialog
import com.example.ui.components.ChannelModeBadge
import com.example.ui.components.FriendQrShareDialog
import com.example.ui.components.PeerAvatar
import com.example.ui.theme.ElectricCyan
import com.example.ui.theme.SignalEmerald
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private enum class ChatModeFilter {
    ALL,
    ONLINE,
    OFFLINE
}

@Composable
fun ChatsListScreen(
    conversations: List<ConversationEntity>,
    connectedPeerIds: Set<String>,
    savedPeers: List<PeerEntity>,
    isSignedIn: Boolean,
    isInternetAvailable: Boolean,
    myShareCode: String,
    myDisplayName: String,
    selectedConversationId: String? = null,
    onOpenConversation: (String) -> Unit,
    onAddOnlineFriend: (codeOrQr: String) -> Unit,
    onCreateOnlineGroup: (title: String, friendConvIds: List<String>) -> Unit,
    onCreateOfflineGroup: (title: String, peerIds: List<String>) -> Unit,
    onGoogleSignInClick: () -> Unit,
    onOpenConnectionsTab: () -> Unit,
    onDeleteConversation: (String) -> Unit
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    var searchQuery by remember { mutableStateOf("") }
    var modeFilter by remember { mutableStateOf(ChatModeFilter.ALL) }
    var showGroupDialog by remember { mutableStateOf(false) }
    var showAddFriendDialog by remember { mutableStateOf(false) }
    var showMyQrDialog by remember { mutableStateOf(false) }
    var showFriendQrScanner by remember { mutableStateOf(false) }

    val filtered = remember(conversations, searchQuery, modeFilter) {
        conversations.filter { conv ->
            val matchesMode = when (modeFilter) {
                ChatModeFilter.ALL -> true
                ChatModeFilter.ONLINE -> conv.channelMode == ChannelMode.ONLINE
                ChatModeFilter.OFFLINE -> conv.channelMode == ChannelMode.OFFLINE
            }
            val matchesQuery = searchQuery.isBlank() ||
                conv.title.contains(searchQuery, ignoreCase = true) ||
                conv.lastMessagePreview.contains(searchQuery, ignoreCase = true)
            matchesMode && matchesQuery
        }
    }

    if (showMyQrDialog && myShareCode.isNotBlank()) {
        FriendQrShareDialog(
            shareCode = myShareCode,
            displayName = myDisplayName,
            onCopyCode = {
                clipboard.setText(AnnotatedString(myShareCode))
                Toast.makeText(context, context.getString(R.string.message_copied), Toast.LENGTH_SHORT).show()
            },
            onDismiss = { showMyQrDialog = false }
        )
    }

    if (showFriendQrScanner) {
        CameraQrScannerDialog(
            onQrScanned = { raw ->
                showFriendQrScanner = false
                showAddFriendDialog = false
                onAddOnlineFriend(raw)
            },
            onDismiss = { showFriendQrScanner = false }
        )
    }

    if (showAddFriendDialog) {
        AddOnlineFriendDialog(
            myShareCode = myShareCode,
            onAddByCode = { code ->
                showAddFriendDialog = false
                onAddOnlineFriend(code)
            },
            onScanQr = { showFriendQrScanner = true },
            onShowMyQr = { showMyQrDialog = true },
            onDismiss = { showAddFriendDialog = false }
        )
    }

    if (showGroupDialog) {
        val onlineDirectConvs = remember(conversations) {
            conversations.filter { it.channelMode == ChannelMode.ONLINE && !it.isGroup }
        }
        CreateHybridGroupDialog(
            isSignedIn = isSignedIn,
            onlineFriends = onlineDirectConvs,
            offlinePeers = savedPeers,
            connectedPeerIds = connectedPeerIds,
            onCreateOnline = { title, convIds ->
                showGroupDialog = false
                onCreateOnlineGroup(title, convIds)
            },
            onCreateOffline = { title, peerIds ->
                showGroupDialog = false
                onCreateOfflineGroup(title, peerIds)
            },
            onDismiss = { showGroupDialog = false }
        )
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp)
        ) {
            Spacer(Modifier.height(8.dp))

            // Hybrid Connection Status & Share Code Strip
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Icon(
                        imageVector = if (isSignedIn && isInternetAvailable) Icons.Rounded.Public else Icons.Rounded.WifiTethering,
                        contentDescription = null,
                        tint = if (isSignedIn && isInternetAvailable) ElectricCyan else SignalEmerald,
                        modifier = Modifier.size(20.dp)
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = when {
                                isSignedIn && isInternetAvailable -> stringResource(R.string.online_ready_banner)
                                isSignedIn -> stringResource(R.string.online_reconnecting_banner)
                                else -> stringResource(R.string.offline_mode_banner)
                            },
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = if (isSignedIn && myShareCode.isNotBlank()) {
                                stringResource(R.string.your_share_code_inline, myShareCode)
                            } else {
                                stringResource(R.string.notif_service_peers_connected, connectedPeerIds.size)
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    if (isSignedIn && myShareCode.isNotBlank()) {
                        FilledTonalIconButton(
                            onClick = { showMyQrDialog = true },
                            modifier = Modifier.size(40.dp).testTag("show_my_friend_qr_button")
                        ) {
                            Icon(
                                Icons.Rounded.QrCode2,
                                contentDescription = stringResource(R.string.my_share_code_title)
                            )
                        }
                    } else {
                        TextButton(
                            onClick = onGoogleSignInClick,
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                        ) {
                            Text(stringResource(R.string.sign_in_short))
                        }
                    }
                }
            }

            Spacer(Modifier.height(10.dp))

            // Mode Filter Chips: All | Online | Offline
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterChip(
                    selected = modeFilter == ChatModeFilter.ALL,
                    onClick = { modeFilter = ChatModeFilter.ALL },
                    label = { Text(stringResource(R.string.filter_all)) },
                    modifier = Modifier.testTag("filter_chats_all")
                )
                FilterChip(
                    selected = modeFilter == ChatModeFilter.ONLINE,
                    onClick = { modeFilter = ChatModeFilter.ONLINE },
                    leadingIcon = {
                        Icon(Icons.Rounded.Public, contentDescription = null, modifier = Modifier.size(16.dp))
                    },
                    label = { Text(stringResource(R.string.mode_badge_online)) },
                    modifier = Modifier.testTag("filter_chats_online")
                )
                FilterChip(
                    selected = modeFilter == ChatModeFilter.OFFLINE,
                    onClick = { modeFilter = ChatModeFilter.OFFLINE },
                    leadingIcon = {
                        Icon(Icons.Rounded.WifiTethering, contentDescription = null, modifier = Modifier.size(16.dp))
                    },
                    label = { Text(stringResource(R.string.mode_badge_offline)) },
                    modifier = Modifier.testTag("filter_chats_offline")
                )
            }

            Spacer(Modifier.height(8.dp))

            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = { Text(stringResource(R.string.search_chats_hint)) },
                leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = { searchQuery = "" }) {
                            Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.close))
                        }
                    }
                },
                singleLine = true,
                shape = RoundedCornerShape(18.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("search_chats_input")
            )
            Spacer(Modifier.height(12.dp))

            if (filtered.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.padding(24.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Forum,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(52.dp)
                        )
                        Text(
                            text = stringResource(R.string.no_chats_title),
                            style = MaterialTheme.typography.titleLarge
                        )
                        Text(
                            text = stringResource(R.string.no_chats_hybrid_tip),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            if (isSignedIn) {
                                Button(onClick = { showAddFriendDialog = true }) {
                                    Icon(Icons.Rounded.PersonAdd, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text(stringResource(R.string.add_online_friend))
                                }
                            }
                            OutlinedButton(onClick = onOpenConnectionsTab) {
                                Icon(Icons.Rounded.Hub, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text(stringResource(R.string.nav_connections))
                            }
                        }
                    }
                }
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    contentPadding = PaddingValues(bottom = 130.dp)
                ) {
                    items(filtered, key = { it.conversationId }) { conv ->
                        val isPeerOnline = when (conv.channelMode) {
                            ChannelMode.ONLINE -> isInternetAvailable && conv.onlinePresence
                            ChannelMode.OFFLINE -> if (conv.isGroup) {
                                conv.participantIdsCsv.split(",").any { connectedPeerIds.contains(it.trim()) }
                            } else {
                                conv.peerId != null && connectedPeerIds.contains(conv.peerId)
                            }
                        }
                        val isSelected = conv.conversationId == selectedConversationId

                        Surface(
                            shape = MaterialTheme.shapes.large,
                            color = if (isSelected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
                            else MaterialTheme.colorScheme.surface,
                            border = BorderStroke(
                                1.dp,
                                if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("conversation_card_${conv.conversationId}")
                                .clickable { onOpenConversation(conv.conversationId) }
                        ) {
                            Row(
                                modifier = Modifier.padding(14.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                PeerAvatar(
                                    name = conv.title,
                                    colorIndex = conv.avatarColorIndex,
                                    isOnline = isPeerOnline,
                                    isGroup = conv.isGroup
                                )
                                Spacer(Modifier.width(12.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        Text(
                                            text = conv.title,
                                            style = MaterialTheme.typography.titleMedium,
                                            fontWeight = FontWeight.SemiBold,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.weight(1f)
                                        )
                                        ChannelModeBadge(mode = conv.channelMode)
                                        Text(
                                            text = formatChatTime(conv.lastMessageTimestamp),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    Spacer(Modifier.height(4.dp))
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = conv.lastMessagePreview.ifBlank {
                                                when {
                                                    conv.channelMode == ChannelMode.ONLINE -> stringResource(R.string.online_cloud_active)
                                                    isPeerOnline -> stringResource(R.string.online_now)
                                                    else -> stringResource(R.string.offline_status)
                                                }
                                            },
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.weight(1f)
                                        )
                                        if (conv.unreadCount > 0) {
                                            Spacer(Modifier.width(8.dp))
                                            Surface(
                                                shape = CircleShape,
                                                color = if (conv.channelMode == ChannelMode.ONLINE) ElectricCyan else SignalEmerald
                                            ) {
                                                Text(
                                                    text = conv.unreadCount.toString(),
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = MaterialTheme.colorScheme.onPrimary,
                                                    modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp)
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        Column(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(18.dp),
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            SmallFloatingActionButton(
                onClick = { showGroupDialog = true },
                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                modifier = Modifier.testTag("create_group_fab")
            ) {
                Icon(
                    imageVector = Icons.Rounded.GroupAdd,
                    contentDescription = stringResource(R.string.create_group_chat)
                )
            }
            ExtendedFloatingActionButton(
                onClick = {
                    if (isSignedIn) {
                        showAddFriendDialog = true
                    } else {
                        onGoogleSignInClick()
                    }
                },
                icon = {
                    Icon(
                        imageVector = Icons.Rounded.PersonAdd,
                        contentDescription = stringResource(R.string.add_online_friend)
                    )
                },
                text = { Text(stringResource(R.string.add_online_friend)) },
                containerColor = MaterialTheme.colorScheme.primary,
                modifier = Modifier.testTag("add_online_friend_fab")
            )
        }
    }
}

@Composable
private fun AddOnlineFriendDialog(
    myShareCode: String,
    onAddByCode: (String) -> Unit,
    onScanQr: () -> Unit,
    onShowMyQr: () -> Unit,
    onDismiss: () -> Unit
) {
    var codeInput by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.add_online_friend)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = stringResource(R.string.add_friend_code_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedTextField(
                    value = codeInput,
                    onValueChange = { codeInput = it.uppercase().take(32) },
                    label = { Text(stringResource(R.string.friend_code_label)) },
                    placeholder = { Text("OC-1A2B3C4D") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("friend_code_input")
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = onScanQr,
                        modifier = Modifier.weight(1f).testTag("scan_friend_qr_button")
                    ) {
                        Icon(Icons.Rounded.QrCodeScanner, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(stringResource(R.string.scan_qr_short), maxLines = 1)
                    }
                    if (myShareCode.isNotBlank()) {
                        OutlinedButton(
                            onClick = onShowMyQr,
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Rounded.QrCode2, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text(stringResource(R.string.my_qr_short), maxLines = 1)
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onAddByCode(codeInput) },
                enabled = codeInput.trim().length >= 4,
                modifier = Modifier.testTag("confirm_add_friend_button")
            ) {
                Text(stringResource(R.string.connect))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        }
    )
}

@Composable
private fun CreateHybridGroupDialog(
    isSignedIn: Boolean,
    onlineFriends: List<ConversationEntity>,
    offlinePeers: List<PeerEntity>,
    connectedPeerIds: Set<String>,
    onCreateOnline: (String, List<String>) -> Unit,
    onCreateOffline: (String, List<String>) -> Unit,
    onDismiss: () -> Unit
) {
    var groupName by remember { mutableStateOf("") }
    var isOnlineMode by remember { mutableStateOf(isSignedIn) }
    val selectedIds = remember { mutableStateListOf<String>() }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.create_group_chat)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    SegmentedButton(
                        selected = isOnlineMode,
                        enabled = isSignedIn,
                        onClick = {
                            isOnlineMode = true
                            selectedIds.clear()
                        },
                        shape = SegmentedButtonDefaults.itemShape(0, 2)
                    ) {
                        Text(stringResource(R.string.mode_badge_online), maxLines = 1)
                    }
                    SegmentedButton(
                        selected = !isOnlineMode,
                        onClick = {
                            isOnlineMode = false
                            selectedIds.clear()
                        },
                        shape = SegmentedButtonDefaults.itemShape(1, 2)
                    ) {
                        Text(stringResource(R.string.mode_badge_offline), maxLines = 1)
                    }
                }

                OutlinedTextField(
                    value = groupName,
                    onValueChange = { groupName = it },
                    label = {
                        Text(
                            if (isOnlineMode) stringResource(R.string.online_group_badge)
                            else stringResource(R.string.group_chat_badge)
                        )
                    },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                if (isOnlineMode) {
                    if (onlineFriends.isEmpty()) {
                        Text(
                            text = stringResource(R.string.select_online_friends_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    onlineFriends.forEach { friendConv ->
                        val checked = selectedIds.contains(friendConv.conversationId)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    if (checked) selectedIds.remove(friendConv.conversationId)
                                    else selectedIds.add(friendConv.conversationId)
                                }
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(
                                checked = checked,
                                onCheckedChange = {
                                    if (it) selectedIds.add(friendConv.conversationId)
                                    else selectedIds.remove(friendConv.conversationId)
                                }
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = friendConv.title,
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                } else {
                    offlinePeers.forEach { peer ->
                        val checked = selectedIds.contains(peer.peerId)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    if (checked) selectedIds.remove(peer.peerId) else selectedIds.add(peer.peerId)
                                }
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(
                                checked = checked,
                                onCheckedChange = {
                                    if (it) selectedIds.add(peer.peerId) else selectedIds.remove(peer.peerId)
                                }
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = peer.displayName,
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.weight(1f)
                            )
                            if (connectedPeerIds.contains(peer.peerId)) {
                                Icon(
                                    Icons.Rounded.Circle,
                                    contentDescription = null,
                                    tint = SignalEmerald,
                                    modifier = Modifier.size(10.dp)
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (isOnlineMode) {
                        onCreateOnline(groupName, selectedIds.toList())
                    } else {
                        onCreateOffline(groupName, selectedIds.toList())
                    }
                },
                enabled = groupName.isNotBlank() && (!isOnlineMode || selectedIds.isNotEmpty())
            ) {
                Text(stringResource(R.string.confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        }
    )
}

private fun formatChatTime(timestamp: Long): String {
    return SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(timestamp))
}
