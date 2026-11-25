package com.example.ui.components

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import com.example.R
import com.example.data.local.ChannelMode
import com.example.data.remote.FriendQrPayload
import com.example.data.repository.ActiveCallSession
import com.example.data.repository.MeshChatRepository
import com.example.network.QrCodeHelper
import com.example.notifications.InAppBannerEvent
import com.example.permissions.PermissionHelper
import com.example.permissions.PermissionStatusSnapshot
import com.example.ui.theme.ElectricCyan
import com.example.ui.theme.SignalEmerald
import com.example.ui.theme.WarmAmber
import java.util.concurrent.Executors

private val AvatarColors = listOf(
    Color(0xFF10B981),
    Color(0xFF06B6D4),
    Color(0xFF8B5CF6),
    Color(0xFFF59E0B),
    Color(0xFFEC4899),
    Color(0xFF3B82F6)
)

@Composable
fun PeerAvatar(
    name: String,
    colorIndex: Int,
    isOnline: Boolean = false,
    isGroup: Boolean = false,
    size: Dp = 46.dp
) {
    val baseColor = AvatarColors[(colorIndex and 0x7FFFFFFF) % AvatarColors.size]
    val initials = remember(name) {
        name.trim().split(" ").filter { it.isNotEmpty() }.take(2)
            .joinToString("") { it.first().uppercaseChar().toString() }
            .ifEmpty { "OC" }
    }

    Box(modifier = Modifier.size(size), contentAlignment = Alignment.Center) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clip(CircleShape)
                .background(
                    Brush.linearGradient(
                        listOf(baseColor.copy(alpha = 0.85f), baseColor.copy(alpha = 0.45f))
                    )
                ),
            contentAlignment = Alignment.Center
        ) {
            if (isGroup) {
                Icon(
                    imageVector = Icons.Rounded.Groups,
                    contentDescription = name,
                    tint = Color.White,
                    modifier = Modifier.size(size * 0.52f)
                )
            } else {
                Text(
                    text = initials,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
            }
        }
        if (isOnline) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .size(13.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(2.dp)
                    .clip(CircleShape)
                    .background(SignalEmerald)
            )
        }
    }
}

@Composable
fun InAppNotificationBanner(
    banner: InAppBannerEvent?,
    onOpenConversation: (String) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    AnimatedVisibility(
        visible = banner != null,
        enter = slideInVertically { -it },
        exit = slideOutVertically { -it },
        modifier = modifier
    ) {
        if (banner != null) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
                    .testTag("inapp_notification_banner")
                    .clickable { onOpenConversation(banner.conversationId) },
                shape = RoundedCornerShape(18.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
                tonalElevation = 6.dp,
                shadowElevation = 8.dp,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.5f))
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primaryContainer),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = if (banner.isFile) Icons.Rounded.AttachFile else Icons.Rounded.NotificationsActive,
                            contentDescription = banner.senderName,
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.inapp_new_message, banner.senderName),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = banner.previewText,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier.minimumInteractiveComponentSize()
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Close,
                            contentDescription = stringResource(R.string.close),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun ActiveCallMiniBar(
    call: ActiveCallSession,
    onReturnToCall: () -> Unit,
    onEndCall: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onReturnToCall() }
            .testTag("active_call_minibar"),
        color = SignalEmerald.copy(alpha = 0.18f),
        border = BorderStroke(1.dp, SignalEmerald.copy(alpha = 0.5f))
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = if (call.isVideo) Icons.Rounded.Videocam else Icons.Rounded.Call,
                contentDescription = call.peerName,
                tint = SignalEmerald
            )
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = call.peerName,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = stringResource(R.string.return_to_call),
                    style = MaterialTheme.typography.bodySmall,
                    color = SignalEmerald
                )
            }
            FilledIconButton(
                onClick = onEndCall,
                colors = IconButtonDefaults.filledIconButtonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = Color.White
                ),
                modifier = Modifier.size(40.dp)
            ) {
                Icon(Icons.Rounded.CallEnd, contentDescription = stringResource(R.string.end_call))
            }
        }
    }
}

@Composable
fun QrCodeCard(
    payload: String,
    ssid: String,
    password: String,
    ip: String,
    port: Int
) {
    val qrBitmap = remember(payload) { QrCodeHelper.generateQrBitmap(payload, 512) }

    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.secondary.copy(alpha = 0.45f)),
        tonalElevation = 4.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = stringResource(R.string.hotspot_active_title),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.secondary
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = stringResource(R.string.hotspot_qr_instruction),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(14.dp))
            if (qrBitmap != null) {
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = Color.White,
                    modifier = Modifier.padding(4.dp)
                ) {
                    Image(
                        bitmap = qrBitmap.asImageBitmap(),
                        contentDescription = stringResource(R.string.hotspot_active_title),
                        modifier = Modifier
                            .size(190.dp)
                            .padding(10.dp)
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            CredentialRow(stringResource(R.string.ssid_label), ssid)
            if (password.isNotBlank()) {
                CredentialRow(stringResource(R.string.password_label), password)
            }
            CredentialRow(stringResource(R.string.ip_address_label), "$ip:$port")
        }
    }
}

@Composable
private fun CredentialRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurface,
            fontWeight = FontWeight.SemiBold
        )
    }
}

@Composable
fun CameraQrScannerDialog(
    onQrScanned: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var hasCameraPermission by remember {
        mutableStateOf(PermissionHelper.isPermissionGranted(context, Manifest.permission.CAMERA))
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> hasCameraPermission = granted }

    LaunchedEffect(Unit) {
        if (!hasCameraPermission) {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surface
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(R.string.qr_scanner_title),
                        style = MaterialTheme.typography.titleLarge
                    )
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.close))
                    }
                }
                Spacer(Modifier.height(12.dp))
                if (hasCameraPermission) {
                    val executor = remember { Executors.newSingleThreadExecutor() }
                    var decodedOnce by remember { mutableStateOf(false) }

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(300.dp)
                            .clip(RoundedCornerShape(20.dp))
                    ) {
                        AndroidView(
                            factory = { ctx ->
                                val previewView = PreviewView(ctx)
                                val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)
                                cameraProviderFuture.addListener({
                                    val cameraProvider = cameraProviderFuture.get()
                                    val preview = Preview.Builder().build().also {
                                        it.surfaceProvider = previewView.surfaceProvider
                                    }
                                    val analysis = ImageAnalysis.Builder()
                                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                                        .build()
                                        .also { imgAnalysis ->
                                            imgAnalysis.setAnalyzer(executor) { imageProxy ->
                                                if (!decodedOnce) {
                                                    val buffer = imageProxy.planes[0].buffer
                                                    val data = ByteArray(buffer.remaining())
                                                    buffer.get(data)
                                                    val text = QrCodeHelper.decodeFromYuv(
                                                        data,
                                                        imageProxy.width,
                                                        imageProxy.height
                                                    )
                                                    if (!text.isNullOrBlank()) {
                                                        decodedOnce = true
                                                        ContextCompat.getMainExecutor(ctx).execute {
                                                            onQrScanned(text)
                                                        }
                                                    }
                                                }
                                                imageProxy.close()
                                            }
                                        }

                                    runCatching {
                                        cameraProvider.unbindAll()
                                        cameraProvider.bindToLifecycle(
                                            lifecycleOwner,
                                            CameraSelector.DEFAULT_BACK_CAMERA,
                                            preview,
                                            analysis
                                        )
                                    }
                                }, ContextCompat.getMainExecutor(ctx))
                                previewView
                            },
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = stringResource(R.string.qr_scanner_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    Text(
                        text = stringResource(R.string.perm_camera_desc),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = { permissionLauncher.launch(Manifest.permission.CAMERA) }) {
                        Text(stringResource(R.string.grant_permission))
                    }
                }
            }
        }
    }
}

@Composable
fun PermissionsChecklistCard(
    snapshot: PermissionStatusSnapshot,
    onRequestPermissions: (Array<String>) -> Unit,
    onOpenAppSettings: () -> Unit,
    onOpenLocationSettings: () -> Unit,
    onOpenWifiSettings: () -> Unit
) {
    val context = LocalContext.current

    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.permissions_title),
                        style = MaterialTheme.typography.titleMedium
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = stringResource(R.string.permissions_subtitle),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(Modifier.height(12.dp))

            if (!snapshot.wifiEnabled) {
                WarningActionRow(
                    text = stringResource(R.string.wifi_off_warning),
                    buttonLabel = stringResource(R.string.enable_wifi),
                    onClick = onOpenWifiSettings
                )
            }

            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU && !snapshot.locationServicesEnabled) {
                WarningActionRow(
                    text = stringResource(R.string.location_services_off),
                    buttonLabel = stringResource(R.string.enable_location_services),
                    onClick = onOpenLocationSettings
                )
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                PermissionRowItem(
                    icon = Icons.Rounded.WifiFind,
                    title = stringResource(R.string.perm_nearby_wifi_title),
                    description = stringResource(R.string.perm_nearby_wifi_desc),
                    granted = snapshot.nearbyWifiGranted,
                    onGrant = { onRequestPermissions(arrayOf(Manifest.permission.NEARBY_WIFI_DEVICES)) }
                )
            } else {
                PermissionRowItem(
                    icon = Icons.Rounded.LocationOn,
                    title = stringResource(R.string.perm_location_title),
                    description = stringResource(R.string.perm_location_desc),
                    granted = snapshot.locationGranted,
                    onGrant = {
                        onRequestPermissions(
                            arrayOf(
                                Manifest.permission.ACCESS_FINE_LOCATION,
                                Manifest.permission.ACCESS_COARSE_LOCATION
                            )
                        )
                    }
                )
            }

            PermissionRowItem(
                icon = Icons.Rounded.Videocam,
                title = stringResource(R.string.perm_camera_title),
                description = stringResource(R.string.perm_camera_desc),
                granted = snapshot.cameraGranted,
                onGrant = { onRequestPermissions(arrayOf(Manifest.permission.CAMERA)) }
            )

            PermissionRowItem(
                icon = Icons.Rounded.Mic,
                title = stringResource(R.string.perm_mic_title),
                description = stringResource(R.string.perm_mic_desc),
                granted = snapshot.microphoneGranted,
                onGrant = { onRequestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO)) }
            )

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                PermissionRowItem(
                    icon = Icons.Rounded.Notifications,
                    title = stringResource(R.string.perm_notifications_title),
                    description = stringResource(R.string.perm_notifications_desc),
                    granted = snapshot.notificationsGranted,
                    onGrant = { onRequestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS)) }
                )
            }

            Spacer(Modifier.height(10.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                if (!snapshot.allRecommendedGranted) {
                    Button(
                        onClick = { onRequestPermissions(PermissionHelper.allRuntimePermissionsForCurrentApi()) },
                        modifier = Modifier
                            .weight(1f)
                            .testTag("grant_all_permissions_button")
                    ) {
                        Text(stringResource(R.string.grant_all_permissions))
                    }
                }
                OutlinedButton(
                    onClick = onOpenAppSettings,
                    modifier = Modifier.testTag("open_app_settings_button")
                ) {
                    Text(stringResource(R.string.open_app_settings))
                }
            }
        }
    }
}

@Composable
private fun WarningActionRow(text: String, buttonLabel: String, onClick: () -> Unit) {
    Surface(
        shape = MaterialTheme.shapes.small,
        color = WarmAmber.copy(alpha = 0.14f),
        border = BorderStroke(1.dp, WarmAmber.copy(alpha = 0.45f)),
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Rounded.WarningAmber, contentDescription = text, tint = WarmAmber)
            Spacer(Modifier.width(8.dp))
            Text(
                text = text,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = onClick) {
                Text(buttonLabel, color = WarmAmber)
            }
        }
    }
}

@Composable
private fun PermissionRowItem(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    description: String,
    granted: Boolean,
    onGrant: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(38.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(
                    if (granted) MaterialTheme.colorScheme.primaryContainer
                    else MaterialTheme.colorScheme.surfaceVariant
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = title,
                tint = if (granted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp)
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.titleSmall)
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.width(8.dp))
        if (granted) {
            AssistChip(
                onClick = {},
                enabled = false,
                label = {
                    Text(
                        stringResource(R.string.perm_granted),
                        color = SignalEmerald,
                        style = MaterialTheme.typography.labelSmall
                    )
                },
                leadingIcon = {
                    Icon(
                        Icons.Rounded.CheckCircle,
                        contentDescription = stringResource(R.string.perm_granted),
                        tint = SignalEmerald,
                        modifier = Modifier.size(16.dp)
                    )
                }
            )
        } else {
            FilledTonalButton(
                onClick = onGrant,
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
            ) {
                Text(stringResource(R.string.grant_permission), style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

@Composable
fun ChannelModeBadge(
    mode: ChannelMode,
    modifier: Modifier = Modifier
) {
    val isOnline = mode == ChannelMode.ONLINE
    val tint = if (isOnline) ElectricCyan else SignalEmerald
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = tint.copy(alpha = 0.16f),
        border = BorderStroke(0.5.dp, tint.copy(alpha = 0.45f)),
        modifier = modifier
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Icon(
                imageVector = if (isOnline) Icons.Rounded.Public else Icons.Rounded.WifiTethering,
                contentDescription = null,
                tint = tint,
                modifier = Modifier.size(12.dp)
            )
            Text(
                text = if (isOnline) stringResource(R.string.mode_badge_online)
                else stringResource(R.string.mode_badge_offline),
                style = MaterialTheme.typography.labelSmall,
                color = tint,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

@Composable
fun FriendQrShareDialog(
    shareCode: String,
    displayName: String,
    onCopyCode: () -> Unit,
    onDismiss: () -> Unit
) {
    val payload = remember(shareCode, displayName) {
        FriendQrPayload(shareCode = shareCode, displayName = displayName).toQrString()
    }
    val qrBitmap = remember(payload) { QrCodeHelper.generateQrBitmap(payload, 512) }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp
        ) {
            Column(
                modifier = Modifier.padding(22.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = stringResource(R.string.my_share_code_title),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = stringResource(R.string.my_share_code_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (qrBitmap != null) {
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = Color.White
                    ) {
                        Image(
                            bitmap = qrBitmap.asImageBitmap(),
                            contentDescription = shareCode,
                            modifier = Modifier
                                .size(190.dp)
                                .padding(10.dp)
                        )
                    }
                }
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = ElectricCyan.copy(alpha = 0.14f),
                    border = BorderStroke(1.dp, ElectricCyan.copy(alpha = 0.45f))
                ) {
                    Row(
                        modifier = Modifier
                            .clickable { onCopyCode() }
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = shareCode,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = ElectricCyan
                        )
                        Icon(
                            Icons.Rounded.ContentCopy,
                            contentDescription = stringResource(R.string.copy_message),
                            tint = ElectricCyan,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
                TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End)) {
                    Text(stringResource(R.string.close))
                }
            }
        }
    }
}

