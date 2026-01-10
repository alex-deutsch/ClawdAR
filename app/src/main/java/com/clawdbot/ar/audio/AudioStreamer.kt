package com.clawdbot.ar.audio

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Base64
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

/**
 * Captures audio from the microphone and streams it to the gateway.
 * Used as a fallback when Google Speech Services isn't available on the device.
 */
class AudioStreamer(private val context: Context) {

    companion object {
        private const val TAG = "AudioStreamer"

        // Audio recording settings - optimal for speech recognition
        private const val SAMPLE_RATE = 16000
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT

        // Chunk size for streaming (~100ms of audio at 16kHz)
        private const val CHUNK_DURATION_MS = 100
        private const val SAMPLES_PER_CHUNK = SAMPLE_RATE * CHUNK_DURATION_MS / 1000
        private const val BYTES_PER_SAMPLE = 2 // 16-bit = 2 bytes
        private const val CHUNK_SIZE_BYTES = SAMPLES_PER_CHUNK * BYTES_PER_SAMPLE
    }

    private var audioRecord: AudioRecord? = null
    private var recordingJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val _isRecording = MutableStateFlow(false)
    val isRecording: StateFlow<Boolean> = _isRecording.asStateFlow()

    private val _audioChunks = MutableSharedFlow<AudioChunk>()
    val audioChunks: SharedFlow<AudioChunk> = _audioChunks.asSharedFlow()

    private val _status = MutableStateFlow("Ready")
    val status: StateFlow<String> = _status.asStateFlow()

    // Buffer for collecting audio data for batch transcription
    private val audioBuffer = mutableListOf<ByteArray>()

    /**
     * Check if we have microphone permission.
     */
    fun hasPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * Start capturing audio and emitting chunks.
     */
    fun startRecording(): Boolean {
        if (_isRecording.value) {
            Log.w(TAG, "Already recording")
            return true
        }

        if (!hasPermission()) {
            Log.e(TAG, "No microphone permission")
            _status.value = "No mic permission"
            return false
        }

        // Clear audio buffer for new recording
        synchronized(audioBuffer) {
            audioBuffer.clear()
        }

        val minBufferSize = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            CHANNEL_CONFIG,
            AUDIO_FORMAT
        )

        if (minBufferSize == AudioRecord.ERROR || minBufferSize == AudioRecord.ERROR_BAD_VALUE) {
            Log.e(TAG, "Invalid buffer size: $minBufferSize")
            _status.value = "Audio init failed"
            return false
        }

        // Use at least 2x the minimum buffer size for stability
        val bufferSize = maxOf(minBufferSize * 2, CHUNK_SIZE_BYTES * 4)

        try {
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                CHANNEL_CONFIG,
                AUDIO_FORMAT,
                bufferSize
            )

            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "AudioRecord failed to initialize")
                _status.value = "Mic unavailable"
                audioRecord?.release()
                audioRecord = null
                return false
            }

            audioRecord?.startRecording()
            _isRecording.value = true
            _status.value = "Listening..."

            recordingJob = scope.launch {
                recordLoop()
            }

            Log.d(TAG, "Started recording: sampleRate=$SAMPLE_RATE, bufferSize=$bufferSize")
            return true

        } catch (e: SecurityException) {
            Log.e(TAG, "Security exception starting recording: ${e.message}")
            _status.value = "Mic access denied"
            return false
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start recording: ${e.message}", e)
            _status.value = "Recording failed"
            return false
        }
    }

    private suspend fun recordLoop() {
        val buffer = ByteArray(CHUNK_SIZE_BYTES)
        var chunkIndex = 0L
        val startTime = System.currentTimeMillis()

        try {
            while (_isRecording.value && currentCoroutineContext().isActive) {
                val bytesRead = audioRecord?.read(buffer, 0, CHUNK_SIZE_BYTES) ?: -1

                when {
                    bytesRead > 0 -> {
                        // Calculate RMS for voice activity detection
                        val rms = calculateRMS(buffer, bytesRead)
                        val hasVoice = rms > 500 // Threshold for voice detection

                        // Buffer audio data for transcription
                        synchronized(audioBuffer) {
                            audioBuffer.add(buffer.copyOf(bytesRead))
                        }

                        val chunk = AudioChunk(
                            index = chunkIndex++,
                            timestamp = System.currentTimeMillis() - startTime,
                            data = buffer.copyOf(bytesRead),
                            sampleRate = SAMPLE_RATE,
                            channels = 1,
                            bitsPerSample = 16,
                            rms = rms,
                            hasVoiceActivity = hasVoice
                        )

                        _audioChunks.emit(chunk)

                        if (hasVoice) {
                            _status.value = "Hearing you..."
                        } else {
                            _status.value = "Listening..."
                        }
                    }
                    bytesRead == AudioRecord.ERROR_INVALID_OPERATION -> {
                        Log.e(TAG, "Invalid operation in audio read")
                        break
                    }
                    bytesRead == AudioRecord.ERROR_BAD_VALUE -> {
                        Log.e(TAG, "Bad value in audio read")
                        break
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Recording loop error: ${e.message}", e)
        } finally {
            Log.d(TAG, "Recording loop ended, chunks emitted: $chunkIndex")
        }
    }

    private fun calculateRMS(buffer: ByteArray, bytesRead: Int): Int {
        var sum = 0L
        val samples = bytesRead / 2
        for (i in 0 until samples) {
            val sample = (buffer[i * 2].toInt() and 0xFF) or
                        (buffer[i * 2 + 1].toInt() shl 8)
            sum += sample.toLong() * sample.toLong()
        }
        return kotlin.math.sqrt(sum.toDouble() / samples).toInt()
    }

    /**
     * Stop recording audio.
     */
    fun stopRecording() {
        Log.d(TAG, "Stopping recording")
        _isRecording.value = false
        _status.value = "Stopped"

        recordingJob?.cancel()
        recordingJob = null

        try {
            audioRecord?.stop()
            audioRecord?.release()
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping audio record: ${e.message}")
        }
        audioRecord = null
    }

    /**
     * Get all buffered audio data as a single byte array.
     * Call this after stopRecording() to get the complete audio.
     */
    fun getBufferedAudio(): ByteArray {
        synchronized(audioBuffer) {
            if (audioBuffer.isEmpty()) {
                return ByteArray(0)
            }

            val totalSize = audioBuffer.sumOf { it.size }
            val result = ByteArray(totalSize)
            var offset = 0

            for (chunk in audioBuffer) {
                System.arraycopy(chunk, 0, result, offset, chunk.size)
                offset += chunk.size
            }

            Log.d(TAG, "Buffered audio: ${audioBuffer.size} chunks, $totalSize bytes total")
            return result
        }
    }

    /**
     * Clear the audio buffer.
     */
    fun clearBuffer() {
        synchronized(audioBuffer) {
            audioBuffer.clear()
        }
    }

    /**
     * Cleanup resources.
     */
    fun release() {
        stopRecording()
        scope.cancel()
    }
}

/**
 * Represents a chunk of captured audio data.
 */
data class AudioChunk(
    val index: Long,
    val timestamp: Long,
    val data: ByteArray,
    val sampleRate: Int,
    val channels: Int,
    val bitsPerSample: Int,
    val rms: Int,
    val hasVoiceActivity: Boolean
) {
    /**
     * Convert audio data to base64 for transmission.
     */
    fun toBase64(): String = Base64.encodeToString(data, Base64.NO_WRAP)

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as AudioChunk
        return index == other.index && timestamp == other.timestamp
    }

    override fun hashCode(): Int {
        return 31 * index.hashCode() + timestamp.hashCode()
    }
}
