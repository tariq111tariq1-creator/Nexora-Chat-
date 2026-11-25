package com.example.data.repository

import android.content.Context
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.os.Build
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.util.UUID

data class VoiceRecordingState(
    val isRecording: Boolean = false,
    val elapsedMs: Long = 0L,
    val amplitudeLevel: Float = 0f
)

data class VoicePlaybackState(
    val playingMessageId: String? = null,
    val progressFraction: Float = 0f,
    val isPlaying: Boolean = false
)

class VoiceNoteManager(private val context: Context) {

    private val scope = CoroutineScope(Dispatchers.Main)
    private var recorder: MediaRecorder? = null
    private var currentOutputFile: File? = null
    private var recordingStartTime: Long = 0L
    private var recordTickerJob: Job? = null

    private var player: MediaPlayer? = null
    private var playTickerJob: Job? = null

    private val _recordingState = MutableStateFlow(VoiceRecordingState())
    val recordingState: StateFlow<VoiceRecordingState> = _recordingState.asStateFlow()

    private val _playbackState = MutableStateFlow(VoicePlaybackState())
    val playbackState: StateFlow<VoicePlaybackState> = _playbackState.asStateFlow()

    fun startRecording(): Boolean {
        stopRecording(discard = true)
        val dir = File(context.filesDir, "voice_notes").apply { mkdirs() }
        val file = File(dir, "vn_${UUID.randomUUID().toString().take(10)}.m4a")
        currentOutputFile = file

        val prepared = tryPrepareRecorder(file, highQuality = true)
            ?: tryPrepareRecorder(file, highQuality = false)
            ?: return false

        return runCatching {
            prepared.start()
            recorder = prepared
            recordingStartTime = System.currentTimeMillis()
            _recordingState.value = VoiceRecordingState(isRecording = true, elapsedMs = 0L)

            recordTickerJob = scope.launch {
                while (isActive) {
                    val amp = runCatching { (recorder?.maxAmplitude ?: 0) / 32767f }.getOrDefault(0f)
                    _recordingState.value = VoiceRecordingState(
                        isRecording = true,
                        elapsedMs = System.currentTimeMillis() - recordingStartTime,
                        amplitudeLevel = amp.coerceIn(0.05f, 1f)
                    )
                    delay(100L)
                }
            }
            true
        }.getOrElse {
            Log.w("VoiceNoteManager", "startRecording failed: ${it.message}")
            runCatching { prepared.release() }
            currentOutputFile = null
            false
        }
    }

    private fun tryPrepareRecorder(file: File, highQuality: Boolean): MediaRecorder? {
        val mediaRecorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(context)
        } else {
            @Suppress("DEPRECATION")
            MediaRecorder()
        }
        return runCatching {
            mediaRecorder.apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                if (highQuality) {
                    setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                    setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                    setAudioEncodingBitRate(96000)
                    setAudioSamplingRate(44100)
                } else {
                    setOutputFormat(MediaRecorder.OutputFormat.THREE_GPP)
                    setAudioEncoder(MediaRecorder.AudioEncoder.AMR_NB)
                }
                setOutputFile(file.absolutePath)
                prepare()
            }
            mediaRecorder
        }.getOrElse {
            runCatching { mediaRecorder.release() }
            null
        }
    }

    fun stopRecording(discard: Boolean = false): Pair<File, Long>? {
        recordTickerJob?.cancel()
        val elapsed = System.currentTimeMillis() - recordingStartTime
        val file = currentOutputFile
        runCatching { recorder?.stop() }
        runCatching { recorder?.release() }
        recorder = null
        currentOutputFile = null
        _recordingState.value = VoiceRecordingState()

        if (discard || file == null || !file.exists() || file.length() == 0L || elapsed < 350L) {
            runCatching { file?.delete() }
            return null
        }
        return file to elapsed
    }

    fun togglePlayback(messageId: String, filePath: String) {
        val current = _playbackState.value
        if (current.playingMessageId == messageId && current.isPlaying) {
            stopPlayback()
            return
        }

        stopPlayback()
        val file = File(filePath)
        if (!file.exists()) return

        runCatching {
            val mp = MediaPlayer().apply {
                setDataSource(file.absolutePath)
                prepare()
                start()
                setOnCompletionListener {
                    stopPlayback()
                }
            }
            player = mp
            _playbackState.value = VoicePlaybackState(
                playingMessageId = messageId,
                progressFraction = 0f,
                isPlaying = true
            )
            playTickerJob = scope.launch {
                while (isActive && mp.isPlaying) {
                    val dur = mp.duration.coerceAtLeast(1)
                    val pos = mp.currentPosition
                    _playbackState.value = VoicePlaybackState(
                        playingMessageId = messageId,
                        progressFraction = (pos.toFloat() / dur).coerceIn(0f, 1f),
                        isPlaying = true
                    )
                    delay(100L)
                }
            }
        }.onFailure {
            stopPlayback()
        }
    }

    fun stopPlayback() {
        playTickerJob?.cancel()
        runCatching { player?.stop() }
        runCatching { player?.release() }
        player = null
        _playbackState.value = VoicePlaybackState()
    }
}
