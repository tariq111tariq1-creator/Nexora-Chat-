package com.example.network

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.Ringtone
import android.media.RingtoneManager
import android.media.ToneGenerator
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import androidx.camera.core.ImageProxy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicBoolean

class MediaCallEngine(private val context: Context) {

    companion object {
        private const val SAMPLE_RATE = 16000
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private var audioSendJob: Job? = null
    private var audioPlayJob: Job? = null
    private var videoDecodeJob: Job? = null
    private var ringbackJob: Job? = null

    private var echoCanceler: AcousticEchoCanceler? = null
    private var noiseSuppressor: NoiseSuppressor? = null
    private var incomingRingtone: Ringtone? = null
    private var toneGenerator: ToneGenerator? = null

    @Volatile
    private var incomingAudioChannel = Channel<ByteArray>(capacity = 32, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    @Volatile
    private var incomingVideoChannel = Channel<ByteArray>(capacity = Channel.CONFLATED)

    @Volatile
    private var mediaSender: ((isVideo: Boolean, bytes: ByteArray, length: Int) -> Boolean)? = null

    @Volatile
    private var isCallRunning: Boolean = false

    @Volatile
    private var isMicMuted: Boolean = false

    @Volatile
    private var isCameraEnabled: Boolean = true

    @Volatile
    private var lastVideoFrameSentAt: Long = 0L

    private val isEncodingVideo = AtomicBoolean(false)

    private val _remoteVideoFrame = MutableStateFlow<Bitmap?>(null)
    val remoteVideoFrame: StateFlow<Bitmap?> = _remoteVideoFrame.asStateFlow()

    fun startOutgoingRingback() {
        stopTones()
        ringbackJob = scope.launch {
            runCatching {
                val gen = runCatching {
                    ToneGenerator(AudioManager.STREAM_VOICE_CALL, 85)
                }.getOrElse {
                    ToneGenerator(AudioManager.STREAM_RING, 85)
                }
                toneGenerator = gen
                while (isActive) {
                    gen.startTone(ToneGenerator.TONE_SUP_RINGTONE, 1400)
                    delay(3200L)
                }
            }
        }
    }

    fun startIncomingRingtone() {
        stopTones()
        runCatching {
            val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            val ringtone = RingtoneManager.getRingtone(context, uri)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                ringtone.isLooping = true
            }
            ringtone.play()
            incomingRingtone = ringtone
        }
        runCatching {
            val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 500, 400), 0))
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(longArrayOf(0, 500, 400), 0)
            }
        }
    }

    fun stopTones() {
        ringbackJob?.cancel()
        ringbackJob = null
        runCatching { toneGenerator?.stopTone() }
        runCatching { toneGenerator?.release() }
        toneGenerator = null
        runCatching { incomingRingtone?.stop() }
        incomingRingtone = null
        runCatching {
            val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            }
            vibrator.cancel()
        }
    }

    @SuppressLint("MissingPermission")
    fun startCallMedia(
        isVideo: Boolean,
        sendMedia: (isVideo: Boolean, bytes: ByteArray, length: Int) -> Boolean
    ) {
        stopCallMedia()
        mediaSender = sendMedia
        isCameraEnabled = isVideo
        incomingAudioChannel = Channel(capacity = 32, onBufferOverflow = BufferOverflow.DROP_OLDEST)
        incomingVideoChannel = Channel(capacity = Channel.CONFLATED)
        isCallRunning = true

        runCatching {
            audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
            setSpeakerphone(isVideo)
        }

        startAudioCaptureLoop()
        startAudioPlaybackLoop()
        startVideoDecodeLoop()
    }

    fun ensureAudioCaptureRunning() {
        if (isCallRunning && mediaSender != null && audioSendJob?.isActive != true) {
            startAudioCaptureLoop()
        }
    }

    fun onIncomingAudioFrame(pcmBytes: ByteArray) {
        if (!isCallRunning) return
        incomingAudioChannel.trySend(pcmBytes)
    }

    fun onIncomingVideoFrame(jpegBytes: ByteArray) {
        if (!isCallRunning) return
        incomingVideoChannel.trySend(jpegBytes)
    }

    @SuppressLint("MissingPermission")
    private fun startAudioCaptureLoop() {
        if (audioSendJob?.isActive == true) return
        audioSendJob = scope.launch {
            val minBuf = AudioRecord.getMinBufferSize(
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            ).coerceAtLeast(1280)

            val recorder = createAudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION, minBuf * 2)
                ?: createAudioRecord(MediaRecorder.AudioSource.MIC, minBuf * 2)
                ?: return@launch

            if (AcousticEchoCanceler.isAvailable()) {
                echoCanceler = runCatching { AcousticEchoCanceler.create(recorder.audioSessionId) }
                    .getOrNull()?.apply { enabled = true }
            }
            if (NoiseSuppressor.isAvailable()) {
                noiseSuppressor = runCatching { NoiseSuppressor.create(recorder.audioSessionId) }
                    .getOrNull()?.apply { enabled = true }
            }

            val frameBuffer = ByteArray(640) // 20ms @ 16kHz 16-bit mono
            runCatching {
                recorder.startRecording()
                while (isActive && isCallRunning) {
                    val read = recorder.read(frameBuffer, 0, frameBuffer.size)
                    if (read > 0 && !isMicMuted) {
                        mediaSender?.invoke(false, frameBuffer, read)
                    }
                }
            }.onFailure {
                Log.w("MediaCallEngine", "Audio capture loop ended: ${it.message}")
            }

            runCatching { recorder.stop() }
            runCatching { recorder.release() }
        }
    }

    @SuppressLint("MissingPermission")
    private fun createAudioRecord(source: Int, bufferSize: Int): AudioRecord? {
        return runCatching {
            val rec = AudioRecord(
                source,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize
            )
            if (rec.state == AudioRecord.STATE_INITIALIZED) {
                rec
            } else {
                rec.release()
                null
            }
        }.getOrNull()
    }

    private fun startAudioPlaybackLoop() {
        audioPlayJob?.cancel()
        audioPlayJob = scope.launch {
            val minBuf = AudioTrack.getMinBufferSize(
                SAMPLE_RATE,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            ).coerceAtLeast(1280)

            val track = runCatching {
                AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build()
                    )
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(SAMPLE_RATE)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build()
                    )
                    .setBufferSizeInBytes(minBuf * 2)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build()
            }.getOrNull()?.takeIf { it.state == AudioTrack.STATE_INITIALIZED }

            runCatching { track?.play() }

            try {
                for (pcm in incomingAudioChannel) {
                    if (!isActive || !isCallRunning) break
                    track?.write(pcm, 0, pcm.size)
                }
            } finally {
                runCatching { track?.stop() }
                runCatching { track?.release() }
            }
        }
    }

    private fun startVideoDecodeLoop() {
        videoDecodeJob?.cancel()
        videoDecodeJob = scope.launch(Dispatchers.Default) {
            for (jpeg in incomingVideoChannel) {
                if (!isActive || !isCallRunning) break
                val bmp = runCatching { BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size) }.getOrNull()
                if (bmp != null) {
                    _remoteVideoFrame.value = bmp
                }
            }
        }
    }

    fun setMuted(muted: Boolean) {
        isMicMuted = muted
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

    fun setCameraEnabled(enabled: Boolean) {
        isCameraEnabled = enabled
    }

    fun sendCameraFrame(imageProxy: ImageProxy, isFrontCamera: Boolean) {
        if (!isCallRunning || !isCameraEnabled || mediaSender == null) {
            imageProxy.close()
            return
        }

        val now = System.currentTimeMillis()
        if (now - lastVideoFrameSentAt < 75L || !isEncodingVideo.compareAndSet(false, true)) {
            imageProxy.close()
            return
        }
        lastVideoFrameSentAt = now

        val rotation = imageProxy.imageInfo.rotationDegrees.toFloat()
        val rawBitmap = runCatching { imageProxy.toBitmap() }.getOrNull()
        imageProxy.close()

        if (rawBitmap == null) {
            isEncodingVideo.set(false)
            return
        }

        scope.launch {
            try {
                val matrix = Matrix().apply {
                    if (rotation != 0f) postRotate(rotation)
                    if (isFrontCamera) postScale(-1f, 1f)
                }
                val rotated = Bitmap.createBitmap(
                    rawBitmap,
                    0,
                    0,
                    rawBitmap.width,
                    rawBitmap.height,
                    matrix,
                    true
                )
                val targetWidth = 320
                val srcWidth = rotated.width.coerceAtLeast(1)
                val targetHeight = ((rotated.height.toFloat() / srcWidth) * targetWidth)
                    .toInt().coerceIn(180, 480)
                val scaled = Bitmap.createScaledBitmap(rotated, targetWidth, targetHeight, true)

                val outStream = ByteArrayOutputStream()
                scaled.compress(Bitmap.CompressFormat.JPEG, 58, outStream)
                val bytes = outStream.toByteArray()
                mediaSender?.invoke(true, bytes, bytes.size)

                if (rotated !== rawBitmap) rotated.recycle()
                if (scaled !== rotated) scaled.recycle()
                rawBitmap.recycle()
            } catch (e: Exception) {
                Log.d("MediaCallEngine", "Video frame encode skip: ${e.message}")
            } finally {
                isEncodingVideo.set(false)
            }
        }
    }

    fun stopCallMedia() {
        stopTones()
        isCallRunning = false
        mediaSender = null
        audioSendJob?.cancel()
        audioPlayJob?.cancel()
        videoDecodeJob?.cancel()
        runCatching { incomingAudioChannel.close() }
        runCatching { incomingVideoChannel.close() }
        runCatching { echoCanceler?.release() }
        runCatching { noiseSuppressor?.release() }
        echoCanceler = null
        noiseSuppressor = null
        _remoteVideoFrame.value = null
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                audioManager.clearCommunicationDevice()
            }
            audioManager.mode = AudioManager.MODE_NORMAL
            audioManager.isSpeakerphoneOn = false
        }
    }
}
