package com.example.ui.screens

import android.graphics.Bitmap
import android.util.Size
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.example.R
import com.example.data.local.ChannelMode
import com.example.data.repository.ActiveCallSession
import com.example.data.repository.CallPhase
import com.example.data.repository.MeshChatRepository
import com.example.ui.components.PeerAvatar
import com.example.ui.theme.CoralRed
import com.example.ui.theme.ElectricCyan
import com.example.ui.theme.ObsidianBackground
import com.example.ui.theme.SignalEmerald
import kotlinx.coroutines.delay
import org.webrtc.EglBase
import org.webrtc.RendererCommon
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoTrack
import java.util.concurrent.Executors

@Composable
fun CallScreen(
    call: ActiveCallSession,
    remoteVideoFrame: Bitmap?,
    localWebRtcTrack: VideoTrack?,
    remoteWebRtcTrack: VideoTrack?,
    webrtcEglContext: EglBase.Context,
    onAccept: () -> Unit,
    onDecline: () -> Unit,
    onEndCall: () -> Unit,
    onToggleMute: () -> Unit,
    onToggleSpeaker: () -> Unit,
    onToggleCamera: () -> Unit,
    onFlipCamera: () -> Unit,
    onMinimize: () -> Unit,
    onPermissionsGrantedInCall: () -> Unit = {},
    onCameraFrame: (ImageProxy, Boolean) -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var pendingAcceptAfterPerm by remember { mutableStateOf(false) }
    var permissionTick by remember { mutableIntStateOf(0) }
    val callPermLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions()
    ) {
        permissionTick++
        if (pendingAcceptAfterPerm) {
            pendingAcceptAfterPerm = false
            onAccept()
        } else {
            onPermissionsGrantedInCall()
        }
    }

    LaunchedEffect(call.phase, call.isVideo) {
        if (call.phase == CallPhase.ACTIVE) {
            val needed = if (call.isVideo) {
                arrayOf(android.Manifest.permission.RECORD_AUDIO, android.Manifest.permission.CAMERA)
            } else {
                arrayOf(android.Manifest.permission.RECORD_AUDIO)
            }
            val missing = needed.filter {
                !com.example.permissions.PermissionHelper.isPermissionGranted(context, it)
            }
            if (missing.isNotEmpty()) {
                pendingAcceptAfterPerm = false
                callPermLauncher.launch(missing.toTypedArray())
            }
        }
    }

    val isOnlineCall = call.channelMode == ChannelMode.ONLINE
    val hasRemoteVideo = if (isOnlineCall) {
        call.isVideo && call.phase == CallPhase.ACTIVE && remoteWebRtcTrack != null
    } else {
        call.isVideo && call.phase == CallPhase.ACTIVE && remoteVideoFrame != null
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(ObsidianBackground, Color(0xFF10242C), ObsidianBackground)
                )
            )
            .windowInsetsPadding(WindowInsets.safeDrawing)
    ) {
        // Remote Video Stream Surface (WebRTC for Online, Bitmap for Offline Mesh)
        if (isOnlineCall && call.isVideo && call.phase == CallPhase.ACTIVE && remoteWebRtcTrack != null) {
            WebRtcVideoSurface(
                videoTrack = remoteWebRtcTrack,
                eglContext = webrtcEglContext,
                mirror = false,
                isMediaOverlay = false,
                modifier = Modifier.fillMaxSize()
            )
        } else if (!isOnlineCall && call.isVideo && call.phase == CallPhase.ACTIVE && remoteVideoFrame != null) {
            Image(
                bitmap = remoteVideoFrame.asImageBitmap(),
                contentDescription = call.peerName,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        }

        // Top Bar: Minimize + Encrypted/Channel Badge + Status
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp)
                .align(Alignment.TopCenter),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (call.phase == CallPhase.ACTIVE) {
                IconButton(
                    onClick = onMinimize,
                    modifier = Modifier.testTag("call_minimize_button")
                ) {
                    Icon(
                        Icons.Rounded.KeyboardArrowDown,
                        contentDescription = stringResource(R.string.close),
                        tint = Color.White
                    )
                }
            } else {
                Spacer(Modifier.size(48.dp))
            }

            Surface(
                shape = RoundedCornerShape(20.dp),
                color = Color.Black.copy(alpha = 0.45f)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = if (isOnlineCall) Icons.Rounded.Public else Icons.Rounded.Lock,
                        contentDescription = null,
                        tint = if (isOnlineCall) ElectricCyan else SignalEmerald,
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    if (hasRemoteVideo) {
                        CallStatusText(call = call)
                    } else {
                        Text(
                            text = when {
                                isOnlineCall && call.isVideo -> stringResource(R.string.call_online_video_type)
                                isOnlineCall -> stringResource(R.string.call_online_audio_type)
                                call.isVideo -> stringResource(R.string.call_video_type)
                                else -> stringResource(R.string.call_audio_type)
                            },
                            style = MaterialTheme.typography.labelMedium,
                            color = Color.White
                        )
                    }
                }
            }

            Spacer(Modifier.size(48.dp))
        }

        // Center Caller Info when Audio or Ringing or waiting for remote video
        if (!hasRemoteVideo) {
            Column(
                modifier = Modifier.align(Alignment.Center),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                PeerAvatar(name = call.peerName, colorIndex = call.peerId.hashCode(), size = 104.dp)
                Spacer(Modifier.height(18.dp))
                Text(
                    text = call.peerName,
                    style = MaterialTheme.typography.headlineMedium,
                    color = Color.White,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(8.dp))
                CallStatusText(call = call)
            }
        }

        // Local Camera PiP Preview
        val hasCameraPerm = remember(permissionTick, call.phase, call.isVideo) {
            com.example.permissions.PermissionHelper.isPermissionGranted(context, android.Manifest.permission.CAMERA)
        }
        val showLocalCamera = call.isVideo && call.isCameraOn &&
            (call.phase == CallPhase.ACTIVE || call.phase == CallPhase.OUTGOING_RINGING) &&
            hasCameraPerm

        if (showLocalCamera) {
            if (isOnlineCall && localWebRtcTrack != null) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(top = 74.dp, end = 16.dp)
                        .size(width = 112.dp, height = 156.dp)
                        .clip(RoundedCornerShape(18.dp))
                        .background(Color.Black)
                ) {
                    WebRtcVideoSurface(
                        videoTrack = localWebRtcTrack,
                        eglContext = webrtcEglContext,
                        mirror = call.isFrontCamera,
                        isMediaOverlay = true,
                        modifier = Modifier.fillMaxSize()
                    )
                }
            } else if (!isOnlineCall) {
                val executor = remember { Executors.newSingleThreadExecutor() }
                val isDisposed = remember { java.util.concurrent.atomic.AtomicBoolean(false) }
                var boundProvider by remember { mutableStateOf<ProcessCameraProvider?>(null) }
                val previewView = remember {
                    PreviewView(context).apply {
                        implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                        scaleType = PreviewView.ScaleType.FILL_CENTER
                    }
                }

                LaunchedEffect(call.isFrontCamera, call.isCameraOn) {
                    val providerFuture = ProcessCameraProvider.getInstance(context)
                    providerFuture.addListener({
                        if (isDisposed.get() || executor.isShutdown) return@addListener
                        val provider = runCatching { providerFuture.get() }.getOrNull() ?: return@addListener
                        boundProvider = provider
                        val preferred = if (call.isFrontCamera) {
                            CameraSelector.DEFAULT_FRONT_CAMERA
                        } else {
                            CameraSelector.DEFAULT_BACK_CAMERA
                        }
                        val fallback = if (call.isFrontCamera) {
                            CameraSelector.DEFAULT_BACK_CAMERA
                        } else {
                            CameraSelector.DEFAULT_FRONT_CAMERA
                        }
                        val selector = when {
                            runCatching { provider.hasCamera(preferred) }.getOrDefault(false) -> preferred
                            runCatching { provider.hasCamera(fallback) }.getOrDefault(false) -> fallback
                            else -> return@addListener
                        }
                        val preview = Preview.Builder().build().also {
                            it.surfaceProvider = previewView.surfaceProvider
                        }
                        val resSelector = ResolutionSelector.Builder()
                            .setResolutionStrategy(
                                ResolutionStrategy(
                                    Size(640, 480),
                                    ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER
                                )
                            )
                            .build()
                        val analysis = ImageAnalysis.Builder()
                            .setResolutionSelector(resSelector)
                            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                            .build()
                            .also { imgAnalysis ->
                                imgAnalysis.setAnalyzer(executor) { proxy ->
                                    if (isDisposed.get() || executor.isShutdown) {
                                        proxy.close()
                                    } else {
                                        onCameraFrame(proxy, selector == CameraSelector.DEFAULT_FRONT_CAMERA)
                                    }
                                }
                            }
                        runCatching {
                            provider.unbindAll()
                            provider.bindToLifecycle(lifecycleOwner, selector, preview, analysis)
                        }
                    }, ContextCompat.getMainExecutor(context))
                }

                DisposableEffect(Unit) {
                    onDispose {
                        isDisposed.set(true)
                        runCatching { boundProvider?.unbindAll() }
                        executor.shutdownNow()
                    }
                }

                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(top = 74.dp, end = 16.dp)
                        .size(width = 112.dp, height = 156.dp)
                        .clip(RoundedCornerShape(18.dp))
                        .background(Color.Black)
                ) {
                    AndroidView(
                        factory = { previewView },
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
        }

        // Bottom Controls
        Surface(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(18.dp),
            shape = RoundedCornerShape(28.dp),
            color = Color.Black.copy(alpha = 0.55f)
        ) {
            if (call.phase == CallPhase.INCOMING_RINGING) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 20.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        FilledIconButton(
                            onClick = onDecline,
                            colors = IconButtonDefaults.filledIconButtonColors(
                                containerColor = CoralRed,
                                contentColor = Color.White
                            ),
                            modifier = Modifier
                                .size(64.dp)
                                .testTag("decline_call_button")
                        ) {
                            Icon(Icons.Rounded.CallEnd, contentDescription = stringResource(R.string.decline_call))
                        }
                        Spacer(Modifier.height(6.dp))
                        Text(stringResource(R.string.decline_call), color = Color.White, style = MaterialTheme.typography.labelMedium)
                    }
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        FilledIconButton(
                            onClick = {
                                val needed = if (call.isVideo) {
                                    arrayOf(android.Manifest.permission.RECORD_AUDIO, android.Manifest.permission.CAMERA)
                                } else {
                                    arrayOf(android.Manifest.permission.RECORD_AUDIO)
                                }
                                val missing = needed.filter {
                                    !com.example.permissions.PermissionHelper.isPermissionGranted(context, it)
                                }
                                if (missing.isEmpty()) {
                                    onAccept()
                                } else {
                                    pendingAcceptAfterPerm = true
                                    callPermLauncher.launch(missing.toTypedArray())
                                }
                            },
                            colors = IconButtonDefaults.filledIconButtonColors(
                                containerColor = SignalEmerald,
                                contentColor = Color.White
                            ),
                            modifier = Modifier
                                .size(64.dp)
                                .testTag("answer_call_button")
                        ) {
                            Icon(Icons.Rounded.Call, contentDescription = stringResource(R.string.answer_call))
                        }
                        Spacer(Modifier.height(6.dp))
                        Text(stringResource(R.string.answer_call), color = Color.White, style = MaterialTheme.typography.labelMedium)
                    }
                }
            } else {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 16.dp, horizontal = 8.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CallControlButton(
                        icon = if (call.isMuted) Icons.Rounded.MicOff else Icons.Rounded.Mic,
                        label = if (call.isMuted) stringResource(R.string.unmute_mic) else stringResource(R.string.mute_mic),
                        active = call.isMuted,
                        onClick = onToggleMute
                    )
                    CallControlButton(
                        icon = if (call.isSpeakerOn) Icons.Rounded.VolumeUp else Icons.Rounded.Hearing,
                        label = if (call.isSpeakerOn) stringResource(R.string.speaker_on) else stringResource(R.string.speaker_off),
                        active = call.isSpeakerOn,
                        onClick = onToggleSpeaker
                    )
                    if (call.isVideo) {
                        CallControlButton(
                            icon = if (call.isCameraOn) Icons.Rounded.Videocam else Icons.Rounded.VideocamOff,
                            label = if (call.isCameraOn) stringResource(R.string.camera_on) else stringResource(R.string.camera_off),
                            active = !call.isCameraOn,
                            onClick = onToggleCamera
                        )
                        CallControlButton(
                            icon = Icons.Rounded.Cameraswitch,
                            label = stringResource(R.string.switch_camera),
                            active = false,
                            onClick = onFlipCamera
                        )
                    }
                    FilledIconButton(
                        onClick = onEndCall,
                        colors = IconButtonDefaults.filledIconButtonColors(
                            containerColor = CoralRed,
                            contentColor = Color.White
                        ),
                        modifier = Modifier
                            .size(58.dp)
                            .testTag("end_call_button")
                    ) {
                        Icon(Icons.Rounded.CallEnd, contentDescription = stringResource(R.string.end_call))
                    }
                }
            }
        }
    }
}

@Composable
private fun WebRtcVideoSurface(
    videoTrack: VideoTrack,
    eglContext: EglBase.Context,
    mirror: Boolean,
    isMediaOverlay: Boolean,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val renderer = remember {
        SurfaceViewRenderer(context).apply {
            init(eglContext, null)
            setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FILL)
            setEnableHardwareScaler(true)
            setZOrderMediaOverlay(isMediaOverlay)
        }
    }

    LaunchedEffect(mirror) {
        renderer.setMirror(mirror)
    }

    DisposableEffect(videoTrack) {
        runCatching { videoTrack.addSink(renderer) }
        onDispose {
            runCatching { videoTrack.removeSink(renderer) }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            runCatching { renderer.release() }
        }
    }

    AndroidView(
        factory = { renderer },
        modifier = modifier
    )
}

@Composable
private fun CallControlButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    active: Boolean,
    onClick: () -> Unit
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        FilledIconButton(
            onClick = onClick,
            colors = IconButtonDefaults.filledIconButtonColors(
                containerColor = if (active) Color.White else Color.White.copy(alpha = 0.16f),
                contentColor = if (active) Color.Black else Color.White
            ),
            modifier = Modifier.size(48.dp)
        ) {
            Icon(icon, contentDescription = label)
        }
        Spacer(Modifier.height(4.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = Color.White
        )
    }
}

@Composable
private fun CallStatusText(call: ActiveCallSession) {
    var elapsedMs by remember(call.callId, call.phase) { mutableLongStateOf(0L) }
    LaunchedEffect(call.phase, call.connectedAtMs) {
        if (call.phase == CallPhase.ACTIVE && call.connectedAtMs > 0L) {
            while (true) {
                elapsedMs = System.currentTimeMillis() - call.connectedAtMs
                delay(1000L)
            }
        }
    }
    Text(
        text = when (call.phase) {
            CallPhase.INCOMING_RINGING -> stringResource(R.string.incoming_call_title)
            CallPhase.OUTGOING_RINGING -> stringResource(R.string.call_ringing)
            CallPhase.CONNECTING -> stringResource(R.string.call_connecting)
            CallPhase.ACTIVE -> stringResource(
                R.string.call_connected,
                MeshChatRepository.formatDuration(elapsedMs)
            )
            else -> stringResource(R.string.call_ended)
        },
        style = MaterialTheme.typography.bodyLarge,
        color = SignalEmerald
    )
}
