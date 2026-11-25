package com.example.network

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import org.webrtc.AudioSource
import org.webrtc.AudioTrack
import org.webrtc.Camera1Enumerator
import org.webrtc.Camera2Enumerator
import org.webrtc.CameraEnumerator
import org.webrtc.CameraVideoCapturer
import org.webrtc.DataChannel
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpReceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoSource
import org.webrtc.VideoTrack
import org.webrtc.audio.JavaAudioDeviceModule
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class WebRtcCallManager(private val context: Context) {

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val eglBase: EglBase = EglBase.create()
    val eglContext: EglBase.Context get() = eglBase.eglBaseContext

    @Volatile
    private var factory: PeerConnectionFactory? = null
    private var peerConnection: PeerConnection? = null
    private var audioSource: AudioSource? = null
    private var localAudioTrack: AudioTrack? = null
    private var videoSource: VideoSource? = null
    private var videoCapturer: CameraVideoCapturer? = null
    private var surfaceTextureHelper: SurfaceTextureHelper? = null

    private val _localVideoTrack = MutableStateFlow<VideoTrack?>(null)
    val localVideoTrack: StateFlow<VideoTrack?> = _localVideoTrack.asStateFlow()

    private val _remoteVideoTrack = MutableStateFlow<VideoTrack?>(null)
    val remoteVideoTrack: StateFlow<VideoTrack?> = _remoteVideoTrack.asStateFlow()

    private val pendingRemoteCandidates = CopyOnWriteArrayList<IceCandidate>()

    @Volatile
    private var remoteDescriptionApplied = false

    private var onLocalIceCandidateCallback: ((IceCandidate) -> Unit)? = null
    private var onPeerStateCallback: ((PeerConnection.PeerConnectionState) -> Unit)? = null

    private fun ensureFactory(): PeerConnectionFactory {
        factory?.let { return it }
        synchronized(this) {
            factory?.let { return it }
            PeerConnectionFactory.initialize(
                PeerConnectionFactory.InitializationOptions.builder(context.applicationContext)
                    .setEnableInternalTracer(false)
                    .createInitializationOptions()
            )
            val adm = JavaAudioDeviceModule.builder(context.applicationContext)
                .setUseHardwareAcousticEchoCanceler(true)
                .setUseHardwareNoiseSuppressor(true)
                .createAudioDeviceModule()
            val built = PeerConnectionFactory.builder()
                .setAudioDeviceModule(adm)
                .setVideoEncoderFactory(DefaultVideoEncoderFactory(eglBase.eglBaseContext, true, true))
                .setVideoDecoderFactory(DefaultVideoDecoderFactory(eglBase.eglBaseContext))
                .createPeerConnectionFactory()
            adm.release()
            factory = built
            return built
        }
    }

    fun startSession(
        isVideo: Boolean,
        isFrontCamera: Boolean = true,
        onIceCandidate: (IceCandidate) -> Unit,
        onConnectionState: (PeerConnection.PeerConnectionState) -> Unit
    ) {
        disposeCall()
        onLocalIceCandidateCallback = onIceCandidate
        onPeerStateCallback = onConnectionState
        remoteDescriptionApplied = false
        pendingRemoteCandidates.clear()

        val pcFactory = ensureFactory()

        runCatching {
            audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
            setSpeakerphone(isVideo)
        }

        val iceServers = listOf(
            PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer(),
            PeerConnection.IceServer.builder("stun:stun1.l.google.com:19302").createIceServer(),
            PeerConnection.IceServer.builder("stun:stun2.l.google.com:19302").createIceServer()
        )
        val rtcConfig = PeerConnection.RTCConfiguration(iceServers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
        }

        val pc = pcFactory.createPeerConnection(
            rtcConfig,
            object : PeerConnection.Observer {
                override fun onSignalingChange(state: PeerConnection.SignalingState?) {}
                override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) {}
                override fun onIceConnectionReceivingChange(receiving: Boolean) {}
                override fun onIceGatheringChange(state: PeerConnection.IceGatheringState?) {}
                override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) {}
                override fun onAddStream(stream: MediaStream?) {
                    val vTrack = stream?.videoTracks?.firstOrNull()
                    if (vTrack != null) _remoteVideoTrack.value = vTrack
                }
                override fun onRemoveStream(stream: MediaStream?) {}
                override fun onDataChannel(dc: DataChannel?) {}
                override fun onRenegotiationNeeded() {}
                override fun onIceCandidate(candidate: IceCandidate?) {
                    candidate?.let { onLocalIceCandidateCallback?.invoke(it) }
                }
                override fun onAddTrack(receiver: RtpReceiver?, streams: Array<out MediaStream>?) {
                    val track = receiver?.track()
                    if (track is VideoTrack) {
                        track.setEnabled(true)
                        _remoteVideoTrack.value = track
                    }
                }
                override fun onConnectionChange(newState: PeerConnection.PeerConnectionState?) {
                    if (newState != null) {
                        onPeerStateCallback?.invoke(newState)
                    }
                }
            }
        ) ?: throw IllegalStateException("Failed to create WebRTC PeerConnection")
        peerConnection = pc

        val audioConstraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("googEchoCancellation", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("googNoiseSuppression", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("googAutoGainControl", "true"))
        }
        val aSrc = pcFactory.createAudioSource(audioConstraints)
        audioSource = aSrc
        val aTrack = pcFactory.createAudioTrack("OC_AUDIO_0", aSrc).apply { setEnabled(true) }
        localAudioTrack = aTrack
        pc.addTrack(aTrack, listOf("OC_STREAM"))

        if (isVideo) {
            setupLocalVideo(pcFactory, pc, isFrontCamera)
        }
    }

    private fun setupLocalVideo(
        pcFactory: PeerConnectionFactory,
        pc: PeerConnection,
        isFrontCamera: Boolean
    ) {
        runCatching {
            val enumerator: CameraEnumerator = if (Camera2Enumerator.isSupported(context)) {
                Camera2Enumerator(context)
            } else {
                Camera1Enumerator(false)
            }
            val names = enumerator.deviceNames
            val chosenName = names.firstOrNull {
                if (isFrontCamera) enumerator.isFrontFacing(it) else enumerator.isBackFacing(it)
            } ?: names.firstOrNull() ?: return

            val capturer = enumerator.createCapturer(chosenName, null) ?: return
            videoCapturer = capturer
            val texHelper = SurfaceTextureHelper.create("OC_WebRtcCapture", eglBase.eglBaseContext)
            surfaceTextureHelper = texHelper
            val vSrc = pcFactory.createVideoSource(capturer.isScreencast)
            videoSource = vSrc
            capturer.initialize(texHelper, context.applicationContext, vSrc.capturerObserver)
            capturer.startCapture(640, 480, 24)

            val vTrack = pcFactory.createVideoTrack("OC_VIDEO_0", vSrc).apply { setEnabled(true) }
            _localVideoTrack.value = vTrack
            pc.addTrack(vTrack, listOf("OC_STREAM"))
        }.onFailure {
            Log.w("WebRtcCallManager", "Camera capture unavailable: ${it.message}")
        }
    }

    suspend fun createOffer(isVideo: Boolean): String {
        val pc = peerConnection ?: throw IllegalStateException("PeerConnection not initialized")
        val constraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", if (isVideo) "true" else "false"))
        }
        val sdp = suspendCancellableCoroutine { cont ->
            pc.createOffer(object : SdpObserver {
                override fun onCreateSuccess(desc: SessionDescription?) {
                    if (desc != null) cont.resume(desc)
                    else cont.resumeWithException(IllegalStateException("Null SDP offer"))
                }
                override fun onCreateFailure(err: String?) {
                    cont.resumeWithException(IllegalStateException(err ?: "createOffer failed"))
                }
                override fun onSetSuccess() {}
                override fun onSetFailure(err: String?) {}
            }, constraints)
        }
        setLocalDescription(pc, sdp)
        return sdp.description
    }

    suspend fun acceptOfferAndCreateAnswer(offerSdp: String, isVideo: Boolean): String {
        val pc = peerConnection ?: throw IllegalStateException("PeerConnection not initialized")
        setRemoteDescription(pc, SessionDescription(SessionDescription.Type.OFFER, offerSdp))
        remoteDescriptionApplied = true
        flushPendingIceCandidates(pc)

        val constraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", if (isVideo) "true" else "false"))
        }
        val answer = suspendCancellableCoroutine { cont ->
            pc.createAnswer(object : SdpObserver {
                override fun onCreateSuccess(desc: SessionDescription?) {
                    if (desc != null) cont.resume(desc)
                    else cont.resumeWithException(IllegalStateException("Null SDP answer"))
                }
                override fun onCreateFailure(err: String?) {
                    cont.resumeWithException(IllegalStateException(err ?: "createAnswer failed"))
                }
                override fun onSetSuccess() {}
                override fun onSetFailure(err: String?) {}
            }, constraints)
        }
        setLocalDescription(pc, answer)
        return answer.description
    }

    suspend fun applyRemoteAnswer(answerSdp: String) {
        val pc = peerConnection ?: return
        if (remoteDescriptionApplied) return
        setRemoteDescription(pc, SessionDescription(SessionDescription.Type.ANSWER, answerSdp))
        remoteDescriptionApplied = true
        flushPendingIceCandidates(pc)
    }

    fun addRemoteIceCandidate(sdpMid: String, sdpMLineIndex: Int, candidateSdp: String) {
        val ice = IceCandidate(sdpMid, sdpMLineIndex, candidateSdp)
        val pc = peerConnection
        if (pc != null && remoteDescriptionApplied) {
            runCatching { pc.addIceCandidate(ice) }
        } else {
            pendingRemoteCandidates.add(ice)
        }
    }

    private fun flushPendingIceCandidates(pc: PeerConnection) {
        val toAdd = pendingRemoteCandidates.toList()
        pendingRemoteCandidates.clear()
        toAdd.forEach { runCatching { pc.addIceCandidate(it) } }
    }

    private suspend fun setLocalDescription(pc: PeerConnection, desc: SessionDescription) {
        suspendCancellableCoroutine<Unit> { cont ->
            pc.setLocalDescription(object : SdpObserver {
                override fun onSetSuccess() = cont.resume(Unit)
                override fun onSetFailure(err: String?) {
                    cont.resumeWithException(IllegalStateException(err ?: "setLocalDescription failed"))
                }
                override fun onCreateSuccess(p0: SessionDescription?) {}
                override fun onCreateFailure(p0: String?) {}
            }, desc)
        }
    }

    private suspend fun setRemoteDescription(pc: PeerConnection, desc: SessionDescription) {
        suspendCancellableCoroutine<Unit> { cont ->
            pc.setRemoteDescription(object : SdpObserver {
                override fun onSetSuccess() = cont.resume(Unit)
                override fun onSetFailure(err: String?) {
                    cont.resumeWithException(IllegalStateException(err ?: "setRemoteDescription failed"))
                }
                override fun onCreateSuccess(p0: SessionDescription?) {}
                override fun onCreateFailure(p0: String?) {}
            }, desc)
        }
    }

    fun setMuted(muted: Boolean) {
        runCatching { localAudioTrack?.setEnabled(!muted) }
    }

    fun setCameraEnabled(enabled: Boolean) {
        runCatching { _localVideoTrack.value?.setEnabled(enabled) }
    }

    fun switchCamera() {
        runCatching { videoCapturer?.switchCamera(null) }
    }

    @Suppress("DEPRECATION")
    fun setSpeakerphone(enabled: Boolean) {
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val targetType = if (enabled) {
                    AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
                } else {
                    AudioDeviceInfo.TYPE_BUILTIN_EARPIECE
                }
                val device = audioManager.availableCommunicationDevices.firstOrNull { it.type == targetType }
                if (device != null) {
                    audioManager.setCommunicationDevice(device)
                } else {
                    audioManager.isSpeakerphoneOn = enabled
                }
            } else {
                audioManager.isSpeakerphoneOn = enabled
            }
        }
    }

    fun disposeCall() {
        onLocalIceCandidateCallback = null
        onPeerStateCallback = null
        remoteDescriptionApplied = false
        pendingRemoteCandidates.clear()

        _localVideoTrack.value = null
        _remoteVideoTrack.value = null

        runCatching { videoCapturer?.stopCapture() }
        runCatching { videoCapturer?.dispose() }
        videoCapturer = null

        runCatching { videoSource?.dispose() }
        videoSource = null

        runCatching { surfaceTextureHelper?.dispose() }
        surfaceTextureHelper = null

        runCatching { localAudioTrack?.dispose() }
        localAudioTrack = null
        runCatching { audioSource?.dispose() }
        audioSource = null

        runCatching { peerConnection?.close() }
        runCatching { peerConnection?.dispose() }
        peerConnection = null

        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                audioManager.clearCommunicationDevice()
            }
            audioManager.mode = AudioManager.MODE_NORMAL
            audioManager.isSpeakerphoneOn = false
        }
    }
}
