package com.example.ui.screens

import android.Manifest
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.example.R
import com.example.data.local.ChannelMode
import com.example.data.local.ConversationEntity
import com.example.data.local.FileTransferEntity
import com.example.data.local.MessageEntity
import com.example.data.local.MessageStatus
import com.example.data.local.MessageType
import com.example.data.local.TransferStatus
import com.example.data.repository.MeshChatRepository
import com.example.data.repository.VoicePlaybackState
import com.example.data.repository.VoiceRecordingState
import com.example.notifications.NotificationOrchestrator
import com.example.permissions.PermissionHelper
import com.example.ui.components.ChannelModeBadge
import com.example.ui.components.PeerAvatar
import com.example.ui.theme.ElectricCyan
import com.example.ui.theme.SignalEmerald
import com.example.ui.theme.WarmAmber
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ChatRoomScreen(
    conversation: ConversationEntity?,
    messages: List<MessageEntity>,
    transfers: List<FileTransferEntity> = emptyList(),
    isPeerOnline: Boolean,
    isPeerTyping: Boolean,
    recordingState: VoiceRecordingState,
    playbackState: VoicePlaybackState,
    showBackButton: Boolean,
    onBack: () -> Unit,
    onEnterRoom: () -> Unit = {},
    onExitRoom: () -> Unit = {},
    onSearchQueryChanged: (String) -> Unit,
    onSendMessage: (text: String, replyTo: MessageEntity?) -> Unit,
    onTypingChanged: (Boolean) -> Unit,
    onReactMessage: (messageId: String, emoji: String) -> Unit,
    onRetryMessage: (messageId: String) -> Unit,
    onDeleteMessage: (messageId: String) -> Unit,
    onStartVoiceRecord: () -> Boolean,
    onStopSendVoiceNote: () -> Unit,
    onCancelVoiceRecord: () -> Unit,
    onToggleVoicePlay: (messageId: String, path: String) -> Unit,
    onSendFileUri: (Uri) -> Unit,
    onPauseTransfer: (String) -> Unit = {},
    onResumeTransfer: (String) -> Unit = {},
    onOpenFile: (pathOrUri: String, mimeType: String?) -> Unit,
    onSaveToDownloads: (localPath: String, fileName: String, mimeType: String) -> Boolean,
    onStartAudioCall: () -> Unit,
    onStartVideoCall: () -> Unit
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val listState = rememberLazyListState()

    var messageInput by remember { mutableStateOf("") }
    var replyTarget by remember { mutableStateOf<MessageEntity?>(null) }
    var selectedMessageForMenu by remember { mutableStateOf<MessageEntity?>(null) }
    var showAttachmentSheet by remember { mutableStateOf(false) }
    var showSearchBar by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }

    if (showBackButton) {
        BackHandler { onBack() }
    }

    DisposableEffect(conversation?.conversationId) {
        NotificationOrchestrator.activeForegroundConversationId = conversation?.conversationId
        if (conversation != null) {
            onEnterRoom()
        }
        onDispose {
            NotificationOrchestrator.activeForegroundConversationId = null
            onExitRoom()
        }
    }

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.lastIndex)
        }
    }

    val photoPickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        uri?.let { onSendFileUri(it) }
    }

    val anyFilePickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let { onSendFileUri(it) }
    }

    var pendingActionAfterPerm by remember { mutableStateOf<(() -> Unit)?>(null) }
    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        if (results.values.all { it }) {
            pendingActionAfterPerm?.invoke()
        }
        pendingActionAfterPerm = null
    }

    if (showAttachmentSheet) {
        ModalBottomSheet(onDismissRequest = { showAttachmentSheet = false }) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.attach_photo_video)) },
                    leadingContent = {
                        Icon(Icons.Rounded.PermMedia, contentDescription = null, tint = SignalEmerald)
                    },
                    modifier = Modifier
                        .clip(MaterialTheme.shapes.medium)
                        .testTag("attach_photo_video_option")
                        .let { mod ->
                            mod.combinedClickable(onClick = {
                                showAttachmentSheet = false
                                photoPickerLauncher.launch(
                                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)
                                )
                            })
                        }
                )
                ListItem(
                    headlineContent = { Text(stringResource(R.string.attach_any_file)) },
                    leadingContent = {
                        Icon(Icons.Rounded.InsertDriveFile, contentDescription = null, tint = ElectricCyan)
                    },
                    modifier = Modifier
                        .clip(MaterialTheme.shapes.medium)
                        .testTag("attach_any_file_option")
                        .combinedClickable(onClick = {
                            showAttachmentSheet = false
                            anyFilePickerLauncher.launch(arrayOf("*/*"))
                        })
                )
                Spacer(Modifier.height(16.dp))
            }
        }
    }

    selectedMessageForMenu?.let { target ->
        AlertDialog(
            onDismissRequest = { selectedMessageForMenu = null },
            title = { Text(target.senderName) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        listOf("👍", "❤️", "😂", "🔥", "👏").forEach { emoji ->
                            TextButton(
                                onClick = {
                                    onReactMessage(target.messageId, emoji)
                                    selectedMessageForMenu = null
                                }
                            ) {
                                Text(emoji, style = MaterialTheme.typography.titleLarge)
                            }
                        }
                    }
                    HorizontalDivider()
                    TextButton(
                        onClick = {
                            replyTarget = target
                            selectedMessageForMenu = null
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Rounded.Reply, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.replying_to, target.senderName))
                    }
                    if (target.textContent.isNotBlank()) {
                        val copiedLabel = stringResource(R.string.message_copied)
                        TextButton(
                            onClick = {
                                clipboard.setText(AnnotatedString(target.textContent))
                                Toast.makeText(context, copiedLabel, Toast.LENGTH_SHORT).show()
                                selectedMessageForMenu = null
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Rounded.ContentCopy, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.copy_message))
                        }
                    }
                    TextButton(
                        onClick = {
                            onDeleteMessage(target.messageId)
                            selectedMessageForMenu = null
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Rounded.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.delete_message), color = MaterialTheme.colorScheme.error)
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { selectedMessageForMenu = null }) {
                    Text(stringResource(R.string.close))
                }
            }
        )
    }

    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            Column {
                TopAppBar(
                    navigationIcon = {
                        if (showBackButton) {
                            IconButton(
                                onClick = onBack,
                                modifier = Modifier.testTag("chat_back_button")
                            ) {
                                Icon(
                                    Icons.AutoMirrored.Rounded.ArrowBack,
                                    contentDescription = stringResource(R.string.close)
                                )
                            }
                        }
                    },
                    title = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            PeerAvatar(
                                name = conversation?.title ?: "Chat",
                                colorIndex = conversation?.avatarColorIndex ?: 0,
                                isOnline = isPeerOnline,
                                isGroup = conversation?.isGroup == true,
                                size = 38.dp
                            )
                            Spacer(Modifier.width(10.dp))
                            Column {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Text(
                                        text = conversation?.title ?: "",
                                        style = MaterialTheme.typography.titleMedium,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f, fill = false)
                                    )
                                    conversation?.let {
                                        ChannelModeBadge(mode = it.channelMode)
                                    }
                                }
                                Text(
                                    text = when {
                                        isPeerTyping -> stringResource(R.string.typing_indicator)
                                        conversation?.channelMode == ChannelMode.ONLINE && isPeerOnline -> stringResource(R.string.online_cloud_active)
                                        conversation?.channelMode == ChannelMode.ONLINE -> stringResource(R.string.online_reconnecting_banner)
                                        isPeerOnline -> stringResource(R.string.online_now)
                                        else -> stringResource(R.string.offline_status)
                                    },
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (isPeerOnline || isPeerTyping) SignalEmerald
                                    else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    },
                    actions = {
                        IconButton(
                            onClick = { showSearchBar = !showSearchBar },
                            modifier = Modifier.testTag("chat_search_toggle")
                        ) {
                            Icon(Icons.Rounded.Search, contentDescription = stringResource(R.string.search_chats_hint))
                        }
                        IconButton(
                            onClick = {
                                if (PermissionHelper.isPermissionGranted(context, Manifest.permission.RECORD_AUDIO)) {
                                    onStartAudioCall()
                                } else {
                                    pendingActionAfterPerm = onStartAudioCall
                                    permLauncher.launch(arrayOf(Manifest.permission.RECORD_AUDIO))
                                }
                            },
                            modifier = Modifier.testTag("start_voice_call_button")
                        ) {
                            Icon(Icons.Rounded.Call, contentDescription = stringResource(R.string.voice_call))
                        }
                        IconButton(
                            onClick = {
                                val needed = arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
                                val hasBoth = needed.all { PermissionHelper.isPermissionGranted(context, it) }
                                if (hasBoth) {
                                    onStartVideoCall()
                                } else {
                                    pendingActionAfterPerm = onStartVideoCall
                                    permLauncher.launch(needed)
                                }
                            },
                            modifier = Modifier.testTag("start_video_call_button")
                        ) {
                            Icon(Icons.Rounded.Videocam, contentDescription = stringResource(R.string.video_call))
                        }
                    }
                )
                if (showSearchBar) {
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = {
                            searchQuery = it
                            onSearchQueryChanged(it)
                        },
                        placeholder = { Text(stringResource(R.string.search_chats_hint)) },
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 6.dp)
                    )
                }
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            val transfersById = remember(transfers) { transfers.associateBy { it.transferId } }
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp),
                contentPadding = PaddingValues(vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(messages, key = { it.messageId }) { msg ->
                    val linkedTransfer = msg.transferId?.let { transfersById[it] }
                    MessageBubbleRow(
                        msg = msg,
                        transfer = linkedTransfer,
                        playbackState = playbackState,
                        onLongPress = { selectedMessageForMenu = msg },
                        onRetry = { onRetryMessage(msg.messageId) },
                        onPauseTransfer = { msg.transferId?.let(onPauseTransfer) },
                        onResumeTransfer = { msg.transferId?.let(onResumeTransfer) },
                        onToggleVoice = {
                            msg.mediaLocalPath?.let { onToggleVoicePlay(msg.messageId, it) }
                        },
                        onOpenFile = {
                            msg.mediaLocalPath?.let { onOpenFile(it, msg.mimeType) }
                        },
                        onSaveFile = {
                            val path = msg.mediaLocalPath
                            val name = msg.fileName ?: "file"
                            val mime = msg.mimeType ?: "*/*"
                            if (path != null && onSaveToDownloads(path, name, mime)) {
                                Toast.makeText(
                                    context,
                                    context.getString(R.string.saved_to_downloads),
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        }
                    )
                }
            }

            // Reply preview bar
            if (replyTarget != null) {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Rounded.Reply, contentDescription = null, tint = SignalEmerald)
                        Spacer(Modifier.width(8.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(R.string.replying_to, replyTarget!!.senderName),
                                style = MaterialTheme.typography.labelMedium,
                                color = SignalEmerald
                            )
                            Text(
                                text = replyTarget!!.textContent.ifBlank { replyTarget!!.fileName ?: "" },
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        IconButton(onClick = { replyTarget = null }) {
                            Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.cancel_reply))
                        }
                    }
                }
            }

            // Composer Bar (Voice Recording OR Text + Attachments)
            Surface(
                tonalElevation = 4.dp,
                color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
            ) {
                if (recordingState.isRecording) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Rounded.GraphicEq, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                        Spacer(Modifier.width(10.dp))
                        Text(
                            text = stringResource(
                                R.string.recording_audio,
                                MeshChatRepository.formatDuration(recordingState.elapsedMs)
                            ),
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(onClick = onCancelVoiceRecord) {
                            Text(stringResource(R.string.cancel))
                        }
                        FilledIconButton(
                            onClick = onStopSendVoiceNote,
                            colors = IconButtonDefaults.filledIconButtonColors(containerColor = SignalEmerald)
                        ) {
                            Icon(Icons.AutoMirrored.Rounded.Send, contentDescription = stringResource(R.string.send_message))
                        }
                    }
                } else {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.Bottom
                    ) {
                        IconButton(
                            onClick = { showAttachmentSheet = true },
                            modifier = Modifier
                                .minimumInteractiveComponentSize()
                                .testTag("chat_attach_button")
                        ) {
                            Icon(Icons.Rounded.AttachFile, contentDescription = stringResource(R.string.attach_any_file))
                        }
                        OutlinedTextField(
                            value = messageInput,
                            onValueChange = {
                                messageInput = it
                                onTypingChanged(it.isNotBlank())
                            },
                            placeholder = { Text(stringResource(R.string.message_hint)) },
                            maxLines = 4,
                            shape = RoundedCornerShape(22.dp),
                            modifier = Modifier
                                .weight(1f)
                                .testTag("chat_message_input")
                        )
                        Spacer(Modifier.width(6.dp))
                        if (messageInput.isBlank()) {
                            FilledIconButton(
                                onClick = {
                                    val triggerRecord = {
                                        if (!onStartVoiceRecord()) {
                                            Toast.makeText(
                                                context,
                                                context.getString(R.string.voice_record_failed),
                                                Toast.LENGTH_SHORT
                                            ).show()
                                        }
                                    }
                                    if (PermissionHelper.isPermissionGranted(context, Manifest.permission.RECORD_AUDIO)) {
                                        triggerRecord()
                                    } else {
                                        pendingActionAfterPerm = triggerRecord
                                        permLauncher.launch(arrayOf(Manifest.permission.RECORD_AUDIO))
                                    }
                                },
                                modifier = Modifier
                                    .minimumInteractiveComponentSize()
                                    .testTag("chat_voice_record_button")
                            ) {
                                Icon(Icons.Rounded.Mic, contentDescription = stringResource(R.string.hold_to_record))
                            }
                        } else {
                            FilledIconButton(
                                onClick = {
                                    onSendMessage(messageInput, replyTarget)
                                    messageInput = ""
                                    replyTarget = null
                                    onTypingChanged(false)
                                },
                                modifier = Modifier
                                    .minimumInteractiveComponentSize()
                                    .testTag("chat_send_button")
                            ) {
                                Icon(Icons.AutoMirrored.Rounded.Send, contentDescription = stringResource(R.string.send_message))
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MessageBubbleRow(
    msg: MessageEntity,
    transfer: FileTransferEntity?,
    playbackState: VoicePlaybackState,
    onLongPress: () -> Unit,
    onRetry: () -> Unit,
    onPauseTransfer: () -> Unit,
    onResumeTransfer: () -> Unit,
    onToggleVoice: () -> Unit,
    onOpenFile: () -> Unit,
    onSaveFile: () -> Unit
) {
    val alignment = if (msg.isOutgoing) Alignment.CenterEnd else Alignment.CenterStart
    val bubbleColor = if (msg.isOutgoing) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceVariant
    }

    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = alignment) {
        Surface(
            shape = RoundedCornerShape(
                topStart = 18.dp,
                topEnd = 18.dp,
                bottomStart = if (msg.isOutgoing) 18.dp else 4.dp,
                bottomEnd = if (msg.isOutgoing) 4.dp else 18.dp
            ),
            color = bubbleColor,
            modifier = Modifier
                .widthIn(max = 310.dp)
                .combinedClickable(
                    onClick = { if (msg.isOutgoing && msg.status == MessageStatus.FAILED) onRetry() },
                    onLongClick = onLongPress
                )
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                if (!msg.isOutgoing) {
                    Text(
                        text = msg.senderName,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.secondary,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(Modifier.height(2.dp))
                }

                if (!msg.replyToPreview.isNullOrBlank()) {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = Color.Black.copy(alpha = 0.14f),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 6.dp)
                    ) {
                        Text(
                            text = msg.replyToPreview,
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                }

                when (msg.type) {
                    MessageType.TEXT -> {
                        Text(
                            text = msg.textContent,
                            style = MaterialTheme.typography.bodyLarge
                        )
                    }

                    MessageType.VOICE_NOTE -> {
                        val isPlayingThis = playbackState.playingMessageId == msg.messageId && playbackState.isPlaying
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.widthIn(min = 190.dp)
                        ) {
                            IconButton(onClick = onToggleVoice) {
                                Icon(
                                    imageVector = if (isPlayingThis) Icons.Rounded.PauseCircle else Icons.Rounded.PlayCircle,
                                    contentDescription = stringResource(R.string.voice_note_label, ""),
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(34.dp)
                                )
                            }
                            Column(modifier = Modifier.weight(1f)) {
                                LinearProgressIndicator(
                                    progress = { if (isPlayingThis) playbackState.progressFraction else 0f },
                                    modifier = Modifier.fillMaxWidth()
                                )
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    text = MeshChatRepository.formatDuration(msg.durationMs),
                                    style = MaterialTheme.typography.labelSmall
                                )
                            }
                        }
                    }

                    MessageType.IMAGE -> {
                        val model = msg.mediaLocalPath?.let { path ->
                            if (path.startsWith("content://")) Uri.parse(path) else File(path)
                        }
                        if (model != null) {
                            AsyncImage(
                                model = model,
                                contentDescription = msg.fileName ?: "Image",
                                contentScale = ContentScale.Crop,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(170.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .combinedClickable(onClick = onOpenFile, onLongClick = onLongPress)
                            )
                            Spacer(Modifier.height(6.dp))
                        }
                        FileMetaFooter(msg, transfer, onPauseTransfer, onResumeTransfer, onOpenFile, onSaveFile)
                    }

                    MessageType.VIDEO, MessageType.FILE -> {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(40.dp)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.secondaryContainer),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = if (msg.type == MessageType.VIDEO) Icons.Rounded.VideoFile else Icons.Rounded.InsertDriveFile,
                                    contentDescription = msg.fileName,
                                    tint = MaterialTheme.colorScheme.secondary
                                )
                            }
                            Spacer(Modifier.width(10.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = msg.fileName ?: msg.textContent,
                                    style = MaterialTheme.typography.titleSmall,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = MeshChatRepository.formatBytes(msg.fileSizeBytes),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        Spacer(Modifier.height(6.dp))
                        FileMetaFooter(msg, transfer, onPauseTransfer, onResumeTransfer, onOpenFile, onSaveFile)
                    }

                    else -> {
                        Text(text = msg.textContent, style = MaterialTheme.typography.bodyMedium)
                    }
                }

                Spacer(Modifier.height(4.dp))
                Row(
                    modifier = Modifier.align(Alignment.End),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    if (!msg.reactionEmoji.isNullOrBlank()) {
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.6f)
                        ) {
                            Text(
                                text = msg.reactionEmoji,
                                style = MaterialTheme.typography.labelMedium,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }
                    Text(
                        text = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(msg.timestamp)),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (msg.isOutgoing) {
                        val icon = when (msg.status) {
                            MessageStatus.PENDING -> Icons.Rounded.Schedule
                            MessageStatus.SENT -> Icons.Rounded.Done
                            MessageStatus.DELIVERED, MessageStatus.READ -> Icons.Rounded.DoneAll
                            MessageStatus.FAILED -> Icons.Rounded.ErrorOutline
                        }
                        val tint = when (msg.status) {
                            MessageStatus.READ -> MaterialTheme.colorScheme.secondary
                            MessageStatus.FAILED -> MaterialTheme.colorScheme.error
                            else -> MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.65f)
                        }
                        val statusDesc = when (msg.status) {
                            MessageStatus.PENDING -> stringResource(R.string.status_pending)
                            MessageStatus.SENT -> stringResource(R.string.status_sent)
                            MessageStatus.DELIVERED -> stringResource(R.string.status_delivered)
                            MessageStatus.READ -> stringResource(R.string.status_read)
                            MessageStatus.FAILED -> stringResource(R.string.status_failed)
                        }
                        Icon(
                            imageVector = icon,
                            contentDescription = statusDesc,
                            tint = tint,
                            modifier = Modifier.size(16.dp)
                        )
                        if (msg.status == MessageStatus.FAILED) {
                            Text(
                                text = stringResource(R.string.tap_to_retry),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FileMetaFooter(
    msg: MessageEntity,
    transfer: FileTransferEntity?,
    onPauseTransfer: () -> Unit,
    onResumeTransfer: () -> Unit,
    onOpenFile: () -> Unit,
    onSaveFile: () -> Unit
) {
    if (transfer != null &&
        (transfer.status == TransferStatus.TRANSFERRING ||
            transfer.status == TransferStatus.PAUSED ||
            transfer.status == TransferStatus.FAILED ||
            transfer.status == TransferStatus.QUEUED)
    ) {
        val progress = if (transfer.totalBytes > 0L) {
            (transfer.transferredBytes.toFloat() / transfer.totalBytes).coerceIn(0f, 1f)
        } else 0f
        val pct = (progress * 100).toInt()
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier.fillMaxWidth()
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = if (transfer.status == TransferStatus.PAUSED || transfer.status == TransferStatus.FAILED) {
                        stringResource(R.string.transfer_paused_progress, pct)
                    } else {
                        stringResource(
                            R.string.transfer_in_progress,
                            pct,
                            MeshChatRepository.formatBytesPerSec(transfer.bytesPerSecond)
                        )
                    },
                    style = MaterialTheme.typography.labelSmall
                )
                if (transfer.status == TransferStatus.TRANSFERRING) {
                    TextButton(
                        onClick = onPauseTransfer,
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                    ) {
                        Text(stringResource(R.string.pause_transfer), style = MaterialTheme.typography.labelMedium)
                    }
                } else {
                    TextButton(
                        onClick = onResumeTransfer,
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                    ) {
                        Text(stringResource(R.string.resume_transfer), style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
        }
        return
    }

    if (!msg.mediaLocalPath.isNullOrBlank()) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = onOpenFile, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)) {
                Text(stringResource(R.string.open_file), style = MaterialTheme.typography.labelMedium)
            }
            if (!msg.isOutgoing) {
                TextButton(onClick = onSaveFile, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)) {
                    Text(stringResource(R.string.save_to_downloads), style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }
}
