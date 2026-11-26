package com.example.service

import android.content.Context
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.os.Build
import android.util.Base64
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.util.UUID

class VoiceRecorderPlayer(private val context: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var recorder: MediaRecorder? = null
    private var currentFile: File? = null
    private var recordStartMs: Long = 0L
    private var recordingTickerJob: Job? = null
    private var playbackTickerJob: Job? = null

    private var player: MediaPlayer? = null
    private val _playingMessageId = MutableStateFlow<String?>(null)
    val playingMessageId: StateFlow<String?> = _playingMessageId.asStateFlow()

    private val _isRecording = MutableStateFlow(false)
    val isRecording: StateFlow<Boolean> = _isRecording.asStateFlow()

    private val _recordingElapsedSeconds = MutableStateFlow(0L)
    val recordingElapsedSeconds: StateFlow<Long> = _recordingElapsedSeconds.asStateFlow()

    private val _recordingAmplitudes = MutableStateFlow<List<Float>>(emptyList())
    val recordingAmplitudes: StateFlow<List<Float>> = _recordingAmplitudes.asStateFlow()

    private val _playbackSpeed = MutableStateFlow(1.0f)
    val playbackSpeed: StateFlow<Float> = _playbackSpeed.asStateFlow()

    private val _playbackProgress = MutableStateFlow(0f)
    val playbackProgress: StateFlow<Float> = _playbackProgress.asStateFlow()

    fun startRecording(): Boolean {
        if (_isRecording.value) return false
        val dir = File(context.cacheDir, "voice_notes").apply { mkdirs() }
        val file = File(dir, "vn_${UUID.randomUUID()}.m4a")
        return try {
            val rec = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(context)
            } else {
                @Suppress("DEPRECATION")
                MediaRecorder()
            }
            rec.apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioSamplingRate(16000)
                setAudioEncodingBitRate(20000)
                setMaxDuration(45_000)
                setOnInfoListener { _, what, _ ->
                    if (what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED) {
                        runCatching { rec.stop() }
                        releaseRecorder()
                        _isRecording.value = false
                        recordingTickerJob?.cancel()
                    }
                }
                setOutputFile(file.absolutePath)
                prepare()
                start()
            }
            recorder = rec
            currentFile = file
            recordStartMs = System.currentTimeMillis()
            _recordingElapsedSeconds.value = 0L
            _recordingAmplitudes.value = emptyList()
            _isRecording.value = true
            startRecordingTicker()
            true
        } catch (_: Exception) {
            releaseRecorder()
            false
        }
    }

    private fun startRecordingTicker() {
        recordingTickerJob?.cancel()
        recordingTickerJob = scope.launch {
            while (_isRecording.value) {
                _recordingElapsedSeconds.value = (System.currentTimeMillis() - recordStartMs) / 1000L
                val amp = runCatching { recorder?.maxAmplitude ?: 0 }.getOrDefault(0)
                val normalized = (amp / 18_000f).coerceIn(0.12f, 1.0f)
                _recordingAmplitudes.value = (_recordingAmplitudes.value + normalized).takeLast(28)
                delay(120L)
            }
        }
    }

    fun stopRecording(): Pair<File, Long>? {
        recordingTickerJob?.cancel()
        val file = currentFile
        if (!_isRecording.value && file == null) return null
        val durationMs = (System.currentTimeMillis() - recordStartMs).coerceIn(500L, 45_000L)
        return try {
            if (recorder != null) {
                runCatching { recorder?.stop() }
                releaseRecorder()
            }
            _isRecording.value = false
            _recordingElapsedSeconds.value = 0L
            _recordingAmplitudes.value = emptyList()
            currentFile = null
            if (file != null && file.exists() && file.length() > 0) file to durationMs else null
        } catch (_: Exception) {
            releaseRecorder()
            _isRecording.value = false
            _recordingElapsedSeconds.value = 0L
            _recordingAmplitudes.value = emptyList()
            currentFile = null
            if (file != null && file.exists() && file.length() > 0) file to durationMs else null
        }
    }

    fun cancelRecording() {
        recordingTickerJob?.cancel()
        runCatching { recorder?.stop() }
        releaseRecorder()
        currentFile?.delete()
        currentFile = null
        _isRecording.value = false
        _recordingElapsedSeconds.value = 0L
        _recordingAmplitudes.value = emptyList()
    }

    private fun releaseRecorder() {
        runCatching { recorder?.reset() }
        runCatching { recorder?.release() }
        recorder = null
    }

    fun cyclePlaybackSpeed(): Float {
        val next = when (_playbackSpeed.value) {
            1.0f -> 1.5f
            1.5f -> 2.0f
            else -> 1.0f
        }
        _playbackSpeed.value = next
        player?.let { mp ->
            runCatching {
                if (mp.isPlaying) {
                    mp.playbackParams = mp.playbackParams.setSpeed(next)
                }
            }
        }
        return next
    }

    fun seekToFraction(fraction: Float) {
        val mp = player ?: return
        runCatching {
            val dur = mp.duration
            if (dur > 0) {
                mp.seekTo((dur * fraction.coerceIn(0f, 1f)).toInt())
                _playbackProgress.value = fraction.coerceIn(0f, 1f)
            }
        }
    }

    fun togglePlayback(messageId: String, audioBase64: String) {
        if (_playingMessageId.value == messageId) {
            stopPlayback()
            return
        }
        stopPlayback()
        runCatching {
            val bytes = Base64.decode(audioBase64, Base64.DEFAULT)
            val tempFile = File(context.cacheDir, "play_${messageId.take(32)}.m4a")
            tempFile.writeBytes(bytes)
            val mp = MediaPlayer().apply {
                setDataSource(tempFile.absolutePath)
                setOnCompletionListener {
                    _playingMessageId.value = null
                    stopPlayback()
                    runCatching { tempFile.delete() }
                }
                setOnErrorListener { _, _, _ ->
                    _playingMessageId.value = null
                    stopPlayback()
                    runCatching { tempFile.delete() }
                    true
                }
                prepare()
                runCatching {
                    playbackParams = playbackParams.setSpeed(_playbackSpeed.value)
                }
                start()
            }
            player = mp
            _playingMessageId.value = messageId
            _playbackProgress.value = 0f
            startPlaybackTicker()
        }.onFailure {
            _playingMessageId.value = null
            stopPlayback()
        }
    }

    private fun startPlaybackTicker() {
        playbackTickerJob?.cancel()
        playbackTickerJob = scope.launch {
            while (_playingMessageId.value != null) {
                val mp = player ?: break
                runCatching {
                    val dur = mp.duration.coerceAtLeast(1)
                    val pos = mp.currentPosition
                    _playbackProgress.value = (pos.toFloat() / dur.toFloat()).coerceIn(0f, 1f)
                }
                delay(150L)
            }
        }
    }

    fun stopPlayback() {
        playbackTickerJob?.cancel()
        runCatching { player?.stop() }
        runCatching { player?.release() }
        player = null
        _playingMessageId.value = null
        _playbackProgress.value = 0f
    }

    fun release() {
        cancelRecording()
        stopPlayback()
    }
}
