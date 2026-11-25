package com.example.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import android.widget.Toast
import com.example.R
import com.example.data.local.PeerEntity
import com.example.network.ActiveHotspotState
import com.example.network.DiscoveredLanPeer
import com.example.network.DiscoveredWifiDirectPeer
import com.example.permissions.PermissionHelper
import com.example.permissions.PermissionStatusSnapshot
import com.example.ui.components.CameraQrScannerDialog
import com.example.ui.components.FriendQrShareDialog
import com.example.ui.components.PeerAvatar
import com.example.ui.components.QrCodeCard
import com.example.ui.theme.ElectricCyan
import com.example.ui.theme.SignalEmerald
import com.example.ui.theme.WarmAmber

private enum class RadarConnectionTab {
    ONLINE_FRIEND,
    WIFI_DIRECT,
    HOTSPOT_QR,
    JOIN_IP_QR
}

@Composable
fun RadarScreen(
    isScanning: Boolean,
    wifiDirectEnabled: Boolean,
    permissionSnapshot: PermissionStatusSnapshot,
    wifiDirectPeers: List<DiscoveredWifiDirectPeer>,
    lanPeers: List<DiscoveredLanPeer>,
    savedPeers: List<PeerEntity>,
    connectedPeerIds: Set<String>,
    hotspotState: ActiveHotspotState,
    isSignedIn: Boolean,
    isInternetAvailable: Boolean,
    myShareCode: String,
    myDisplayName: String,
    signedInEmail: String?,
    onAddOnlineFriend: (String) -> Unit,
    onGoogleSignInClick: () -> Unit,
    onStartScan: () -> Unit,
    onStopScan: () -> Unit,
    onConnectWifiDirect: (String) -> Unit,
    onConnectIp: (String, Int) -> Unit,
    onQrScanned: (String) -> Unit,
    onStartHotspot: () -> Unit,
    onStopHotspot: () -> Unit,
    onOpenChatForPeer: (PeerEntity) -> Unit,
    onPermissionsChanged: () -> Unit,
    onOpenSettingsTab: () -> Unit
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    var selectedTab by remember { mutableStateOf(RadarConnectionTab.ONLINE_FRIEND) }
    var showQrScanner by remember { mutableStateOf(false) }
    var showFriendQrScanner by remember { mutableStateOf(false) }
    var showMyFriendQr by remember { mutableStateOf(false) }
    var friendCodeInput by remember { mutableStateOf("") }
    var manualIp by remember { mutableStateOf("192.168.49.1") }
    var manualPort by remember { mutableStateOf("8988") }

    val discoveryPermLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        onPermissionsChanged()
        onStartScan()
    }

    if (showQrScanner) {
        CameraQrScannerDialog(
            onQrScanned = { raw ->
                showQrScanner = false
                onQrScanned(raw)
            },
            onDismiss = { showQrScanner = false }
        )
    }

    if (showFriendQrScanner) {
        CameraQrScannerDialog(
            onQrScanned = { raw ->
                showFriendQrScanner = false
                onAddOnlineFriend(raw)
            },
            onDismiss = { showFriendQrScanner = false }
        )
    }

    if (showMyFriendQr && myShareCode.isNotBlank()) {
        FriendQrShareDialog(
            shareCode = myShareCode,
            displayName = myDisplayName,
            onCopyCode = {
                clipboard.setText(AnnotatedString(myShareCode))
                Toast.makeText(context, context.getString(R.string.message_copied), Toast.LENGTH_SHORT).show()
            },
            onDismiss = { showMyFriendQr = false }
        )
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        contentPadding = PaddingValues(top = 12.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // Permission or Wi-Fi/Location Readiness Warning Banner
        if (!permissionSnapshot.discoveryReady || !permissionSnapshot.wifiEnabled) {
            item {
                Surface(
                    shape = MaterialTheme.shapes.large,
                    color = WarmAmber.copy(alpha = 0.14f),
                    border = BorderStroke(1.dp, WarmAmber.copy(alpha = 0.5f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Rounded.WarningAmber, contentDescription = null, tint = WarmAmber)
                        Spacer(Modifier.width(10.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = if (!permissionSnapshot.wifiEnabled) {
                                    stringResource(R.string.wifi_off_warning)
                                } else {
                                    stringResource(R.string.permission_required_banner)
                                },
                                style = MaterialTheme.typography.titleSmall
                            )
                        }
                        TextButton(
                            onClick = {
                                if (!permissionSnapshot.wifiEnabled) {
                                    PermissionHelper.openWifiSettings(context)
                                } else if (!permissionSnapshot.discoveryReady) {
                                    discoveryPermLauncher.launch(PermissionHelper.requiredDiscoveryPermissions())
                                } else {
                                    onOpenSettingsTab()
                                }
                            }
                        ) {
                            Text(stringResource(R.string.grant_permission), color = WarmAmber)
                        }
                    }
                }
            }
        }

        // Animated Mesh Radar Card
        item {
            RadarHeroPanel(
                isScanning = isScanning,
                wifiDirectEnabled = wifiDirectEnabled,
                connectedCount = connectedPeerIds.size,
                onToggleScan = {
                    if (isScanning) {
                        onStopScan()
                    } else if (permissionSnapshot.discoveryReady) {
                        onStartScan()
                    } else {
                        discoveryPermLauncher.launch(PermissionHelper.requiredDiscoveryPermissions())
                    }
                }
            )
        }

        // Connection Mode Selector (Online | Wi-Fi Direct | Hotspot + QR | Join IP/QR)
        item {
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                SegmentedButton(
                    selected = selectedTab == RadarConnectionTab.ONLINE_FRIEND,
                    onClick = { selectedTab = RadarConnectionTab.ONLINE_FRIEND },
                    shape = SegmentedButtonDefaults.itemShape(0, 4),
                    modifier = Modifier.testTag("mode_online_tab")
                ) {
                    Text(stringResource(R.string.mode_badge_online), maxLines = 1)
                }
                SegmentedButton(
                    selected = selectedTab == RadarConnectionTab.WIFI_DIRECT,
                    onClick = { selectedTab = RadarConnectionTab.WIFI_DIRECT },
                    shape = SegmentedButtonDefaults.itemShape(1, 4),
                    modifier = Modifier.testTag("mode_wifi_direct_tab")
                ) {
                    Text(stringResource(R.string.mode_wifi_direct_short), maxLines = 1)
                }
                SegmentedButton(
                    selected = selectedTab == RadarConnectionTab.HOTSPOT_QR,
                    onClick = { selectedTab = RadarConnectionTab.HOTSPOT_QR },
                    shape = SegmentedButtonDefaults.itemShape(2, 4),
                    modifier = Modifier.testTag("mode_hotspot_tab")
                ) {
                    Text(stringResource(R.string.mode_hotspot_short), maxLines = 1)
                }
                SegmentedButton(
                    selected = selectedTab == RadarConnectionTab.JOIN_IP_QR,
                    onClick = { selectedTab = RadarConnectionTab.JOIN_IP_QR },
                    shape = SegmentedButtonDefaults.itemShape(3, 4),
                    modifier = Modifier.testTag("mode_join_qr_tab")
                ) {
                    Text(stringResource(R.string.mode_lan_qr_short), maxLines = 1)
                }
            }
        }

        // Mode-Specific Controls
        when (selectedTab) {
            RadarConnectionTab.ONLINE_FRIEND -> {
                item {
                    Surface(
                        shape = MaterialTheme.shapes.large,
                        color = MaterialTheme.colorScheme.surface,
                        border = BorderStroke(1.dp, ElectricCyan.copy(alpha = 0.45f))
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Rounded.Public, contentDescription = null, tint = ElectricCyan)
                                Spacer(Modifier.width(8.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = stringResource(R.string.online_connection_title),
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    Text(
                                        text = if (isSignedIn) {
                                            (signedInEmail?.takeIf { it.isNotBlank() } ?: myDisplayName) +
                                                " • " + if (isInternetAvailable) stringResource(R.string.online_ready_banner) else stringResource(R.string.online_reconnecting_banner)
                                        } else {
                                            stringResource(R.string.cloud_sign_in_required)
                                        },
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }

                            if (!isSignedIn) {
                                Button(
                                    onClick = onGoogleSignInClick,
                                    modifier = Modifier.fillMaxWidth().testTag("radar_google_sign_in_button")
                                ) {
                                    Icon(Icons.Rounded.AccountCircle, contentDescription = null)
                                    Spacer(Modifier.width(8.dp))
                                    Text(stringResource(R.string.sign_in_with_google))
                                }
                            } else {
                                if (myShareCode.isNotBlank()) {
                                    Surface(
                                        shape = RoundedCornerShape(12.dp),
                                        color = ElectricCyan.copy(alpha = 0.12f),
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.SpaceBetween
                                        ) {
                                            Column {
                                                Text(
                                                    text = stringResource(R.string.my_share_code_title),
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                                Text(
                                                    text = myShareCode,
                                                    style = MaterialTheme.typography.titleMedium,
                                                    fontWeight = FontWeight.Bold,
                                                    color = ElectricCyan
                                                )
                                            }
                                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                                IconButton(onClick = {
                                                    clipboard.setText(AnnotatedString(myShareCode))
                                                    Toast.makeText(context, context.getString(R.string.message_copied), Toast.LENGTH_SHORT).show()
                                                }) {
                                                    Icon(Icons.Rounded.ContentCopy, contentDescription = stringResource(R.string.copy_message))
                                                }
                                                FilledTonalIconButton(onClick = { showMyFriendQr = true }) {
                                                    Icon(Icons.Rounded.QrCode2, contentDescription = stringResource(R.string.my_qr_short))
                                                }
                                            }
                                        }
                                    }
                                }

                                OutlinedTextField(
                                    value = friendCodeInput,
                                    onValueChange = { friendCodeInput = it.uppercase().take(32) },
                                    label = { Text(stringResource(R.string.friend_code_label)) },
                                    placeholder = { Text("OC-1A2B3C4D") },
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth()
                                )
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Button(
                                        onClick = {
                                            onAddOnlineFriend(friendCodeInput)
                                            friendCodeInput = ""
                                        },
                                        enabled = friendCodeInput.trim().length >= 4,
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Icon(Icons.Rounded.PersonAdd, contentDescription = null, modifier = Modifier.size(18.dp))
                                        Spacer(Modifier.width(6.dp))
                                        Text(stringResource(R.string.add_online_friend))
                                    }
                                    OutlinedButton(onClick = { showFriendQrScanner = true }) {
                                        Icon(Icons.Rounded.QrCodeScanner, contentDescription = null, modifier = Modifier.size(18.dp))
                                        Spacer(Modifier.width(6.dp))
                                        Text(stringResource(R.string.scan_qr_short))
                                    }
                                }
                            }
                        }
                    }
                }
            }

            RadarConnectionTab.HOTSPOT_QR -> {
                item {
                    if (hotspotState.isActive) {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            QrCodeCard(
                                payload = hotspotState.qrPayload,
                                ssid = hotspotState.ssid,
                                password = hotspotState.passphrase,
                                ip = hotspotState.hostIp,
                                port = hotspotState.port
                            )
                            OutlinedButton(
                                onClick = onStopHotspot,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("stop_hotspot_button")
                            ) {
                                Icon(Icons.Rounded.WifiOff, contentDescription = null)
                                Spacer(Modifier.width(8.dp))
                                Text(stringResource(R.string.stop_hotspot))
                            }
                        }
                    } else {
                        Surface(
                            shape = MaterialTheme.shapes.large,
                            color = MaterialTheme.colorScheme.surface,
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
                        ) {
                            Column(
                                modifier = Modifier.padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Text(
                                    text = stringResource(R.string.host_hotspot),
                                    style = MaterialTheme.typography.titleMedium
                                )
                                Text(
                                    text = stringResource(R.string.hotspot_qr_instruction),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Button(
                                    onClick = {
                                        if (permissionSnapshot.discoveryReady) {
                                            onStartHotspot()
                                        } else {
                                            discoveryPermLauncher.launch(PermissionHelper.requiredDiscoveryPermissions())
                                        }
                                    },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .testTag("start_hotspot_button")
                                ) {
                                    Icon(Icons.Rounded.WifiTethering, contentDescription = null)
                                    Spacer(Modifier.width(8.dp))
                                    Text(stringResource(R.string.host_hotspot))
                                }
                            }
                        }
                    }
                }
            }

            RadarConnectionTab.JOIN_IP_QR -> {
                item {
                    Surface(
                        shape = MaterialTheme.shapes.large,
                        color = MaterialTheme.colorScheme.surface,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Button(
                                onClick = { showQrScanner = true },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("scan_qr_button")
                            ) {
                                Icon(Icons.Rounded.QrCodeScanner, contentDescription = null)
                                Spacer(Modifier.width(8.dp))
                                Text(stringResource(R.string.scan_qr_join))
                            }

                            HorizontalDivider()

                            Text(
                                text = stringResource(R.string.connect_manual_ip),
                                style = MaterialTheme.typography.titleSmall
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedTextField(
                                    value = manualIp,
                                    onValueChange = { manualIp = it },
                                    label = { Text(stringResource(R.string.ip_address_label)) },
                                    placeholder = { Text(stringResource(R.string.ip_input_hint)) },
                                    singleLine = true,
                                    modifier = Modifier.weight(2f)
                                )
                                OutlinedTextField(
                                    value = manualPort,
                                    onValueChange = { manualPort = it.filter { ch -> ch.isDigit() } },
                                    label = { Text(stringResource(R.string.port_label)) },
                                    singleLine = true,
                                    modifier = Modifier.weight(1f)
                                )
                            }
                            FilledTonalButton(
                                onClick = {
                                    val port = manualPort.toIntOrNull() ?: 8988
                                    onConnectIp(manualIp, port)
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("connect_ip_button")
                            ) {
                                Text(stringResource(R.string.join_now))
                            }
                        }
                    }
                }
            }

            RadarConnectionTab.WIFI_DIRECT -> Unit
        }

        // Connected Peers Section
        val activePeers = savedPeers.filter { connectedPeerIds.contains(it.peerId) || it.isConnected }
        if (activePeers.isNotEmpty()) {
            item {
                Text(
                    text = "${stringResource(R.string.connected)} (${activePeers.size})",
                    style = MaterialTheme.typography.titleSmall,
                    color = SignalEmerald
                )
            }
            items(activePeers, key = { "connected_${it.peerId}" }) { peer ->
                ConnectedPeerCard(peer = peer, onOpenChat = { onOpenChatForPeer(peer) })
            }
        }

        // Discovered Peers Header (Wi-Fi Direct + LAN/Hotspot)
        val totalDiscovered = wifiDirectPeers.size + lanPeers.size
        item {
            Text(
                text = stringResource(R.string.nearby_devices_header, totalDiscovered),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        if (totalDiscovered == 0 && activePeers.isEmpty()) {
            item {
                Surface(
                    shape = MaterialTheme.shapes.large,
                    color = MaterialTheme.colorScheme.surface,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Radar,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.secondary,
                            modifier = Modifier.size(44.dp)
                        )
                        Spacer(Modifier.height(10.dp))
                        Text(
                            text = stringResource(R.string.no_devices_found),
                            style = MaterialTheme.typography.titleMedium
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = stringResource(R.string.no_devices_tip),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        items(wifiDirectPeers, key = { "wfd_${it.deviceAddress}" }) { p2pPeer ->
            Surface(
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    PeerAvatar(name = p2pPeer.deviceName, colorIndex = p2pPeer.deviceAddress.hashCode(), isOnline = p2pPeer.isConnected)
                    Spacer(Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(text = p2pPeer.deviceName, style = MaterialTheme.typography.titleMedium)
                        Text(
                            text = "${stringResource(R.string.mode_wifi_direct)} • ${p2pPeer.deviceAddress}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Button(
                        onClick = { onConnectWifiDirect(p2pPeer.deviceAddress) },
                        enabled = !p2pPeer.isConnected && !p2pPeer.isInvited,
                        modifier = Modifier.testTag("connect_wfd_${p2pPeer.deviceAddress}")
                    ) {
                        Text(
                            when {
                                p2pPeer.isConnected -> stringResource(R.string.connected)
                                p2pPeer.isInvited -> stringResource(R.string.connecting)
                                else -> stringResource(R.string.connect)
                            }
                        )
                    }
                }
            }
        }

        items(lanPeers, key = { "lan_${it.peerId}" }) { lanPeer ->
            val alreadyConnected = connectedPeerIds.contains(lanPeer.peerId)
            Surface(
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    PeerAvatar(name = lanPeer.displayName, colorIndex = lanPeer.peerId.hashCode(), isOnline = alreadyConnected)
                    Spacer(Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(text = lanPeer.displayName, style = MaterialTheme.typography.titleMedium)
                        Text(
                            text = "${lanPeer.ipAddress}:${lanPeer.port}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    FilledTonalButton(
                        onClick = { onConnectIp(lanPeer.ipAddress, lanPeer.port) },
                        enabled = !alreadyConnected
                    ) {
                        Text(
                            if (alreadyConnected) stringResource(R.string.connected)
                            else stringResource(R.string.connect)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun RadarHeroPanel(
    isScanning: Boolean,
    wifiDirectEnabled: Boolean,
    connectedCount: Int,
    onToggleScan: () -> Unit
) {
    val infiniteTransition = rememberInfiniteTransition(label = "radar_pulse")
    val pulse by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(2200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "radar_ring"
    )

    Surface(
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    Brush.radialGradient(
                        colors = listOf(
                            SignalEmerald.copy(alpha = 0.14f),
                            Color.Transparent
                        )
                    )
                )
                .padding(18.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(modifier = Modifier.size(84.dp), contentAlignment = Alignment.Center) {
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        val center = Offset(size.width / 2f, size.height / 2f)
                        val maxR = size.minDimension / 2f
                        drawCircle(
                            color = SignalEmerald.copy(alpha = 0.22f),
                            radius = maxR * 0.65f,
                            center = center,
                            style = Stroke(width = 2f)
                        )
                        drawCircle(
                            color = ElectricCyan.copy(alpha = if (isScanning) (1f - pulse) * 0.7f else 0.18f),
                            radius = maxR * (if (isScanning) (0.35f + 0.65f * pulse) else 0.95f),
                            center = center,
                            style = Stroke(width = 3f)
                        )
                    }
                    Icon(
                        imageVector = Icons.Rounded.WifiTethering,
                        contentDescription = stringResource(R.string.radar_title),
                        tint = SignalEmerald,
                        modifier = Modifier.size(30.dp)
                    )
                }

                Spacer(Modifier.width(14.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.radar_title),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = when {
                            connectedCount > 0 -> stringResource(R.string.notif_service_peers_connected, connectedCount)
                            isScanning -> stringResource(R.string.radar_scanning)
                            wifiDirectEnabled -> stringResource(R.string.wifi_direct_status_ready)
                            else -> stringResource(R.string.radar_idle)
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(10.dp))
                    Button(
                        onClick = onToggleScan,
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                        modifier = Modifier.testTag("radar_scan_button")
                    ) {
                        Icon(
                            if (isScanning) Icons.Rounded.Stop else Icons.Rounded.Radar,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            if (isScanning) stringResource(R.string.stop_scan)
                            else stringResource(R.string.scan_nearby)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ConnectedPeerCard(
    peer: PeerEntity,
    onOpenChat: () -> Unit
) {
    Surface(
        shape = MaterialTheme.shapes.large,
        color = SignalEmerald.copy(alpha = 0.1f),
        border = BorderStroke(1.dp, SignalEmerald.copy(alpha = 0.45f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            PeerAvatar(name = peer.displayName, colorIndex = peer.avatarColorIndex, isOnline = true)
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = peer.displayName,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = stringResource(R.string.online_now),
                        style = MaterialTheme.typography.bodySmall,
                        color = SignalEmerald
                    )
                    if (peer.verificationPin.isNotBlank()) {
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant
                        ) {
                            Text(
                                text = stringResource(R.string.paired_pin_subtitle, peer.verificationPin),
                                style = MaterialTheme.typography.labelSmall,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }
                }
            }
            Button(
                onClick = onOpenChat,
                modifier = Modifier.testTag("open_peer_chat_${peer.peerId}")
            ) {
                Icon(Icons.Rounded.Chat, contentDescription = stringResource(R.string.nav_chats), modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.nav_chats))
            }
        }
    }
}
