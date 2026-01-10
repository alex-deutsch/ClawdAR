package com.clawdbot.ar.audio

import android.util.Base64
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * ElevenLabs Speech-to-Text client.
 * Records audio and sends to ElevenLabs API for transcription.
 */
class ElevenLabsSTT(private val apiKey: String) {

    companion object {
        private const val TAG = "ElevenLabsSTT"
        private const val STT_URL = "https://api.elevenlabs.io/v1/speech-to-text"
    }

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Transcribe audio data using ElevenLabs API.
     * @param audioData Raw PCM audio data (16-bit, 16kHz, mono)
     * @return Transcribed text or null on error
     */
    suspend fun transcribe(audioData: ByteArray): String? = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "Transcribing ${audioData.size} bytes of audio...")

            // Convert PCM to WAV format (ElevenLabs expects WAV)
            val wavData = pcmToWav(audioData, 16000, 1, 16)
            Log.d(TAG, "WAV data size: ${wavData.size} bytes")

            // Create multipart form data
            val boundary = "----FormBoundary${System.currentTimeMillis()}"
            val lineEnd = "\r\n"
            val twoHyphens = "--"

            val url = URL(STT_URL)
            val connection = url.openConnection() as HttpURLConnection

            connection.apply {
                requestMethod = "POST"
                doInput = true
                doOutput = true
                useCaches = false
                setRequestProperty("xi-api-key", apiKey)
                setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
                setRequestProperty("Accept", "application/json")
                connectTimeout = 30000
                readTimeout = 60000
            }

            // Build multipart body
            val outputStream = DataOutputStream(connection.outputStream)

            // Add file part
            outputStream.writeBytes(twoHyphens + boundary + lineEnd)
            outputStream.writeBytes("Content-Disposition: form-data; name=\"file\"; filename=\"audio.wav\"$lineEnd")
            outputStream.writeBytes("Content-Type: audio/wav$lineEnd")
            outputStream.writeBytes(lineEnd)
            outputStream.write(wavData)
            outputStream.writeBytes(lineEnd)

            // Add model_id part (required)
            outputStream.writeBytes(twoHyphens + boundary + lineEnd)
            outputStream.writeBytes("Content-Disposition: form-data; name=\"model_id\"$lineEnd")
            outputStream.writeBytes(lineEnd)
            outputStream.writeBytes("scribe_v1$lineEnd")

            // End boundary
            outputStream.writeBytes(twoHyphens + boundary + twoHyphens + lineEnd)
            outputStream.flush()
            outputStream.close()

            val responseCode = connection.responseCode
            Log.d(TAG, "ElevenLabs response code: $responseCode")

            if (responseCode == HttpURLConnection.HTTP_OK) {
                val response = connection.inputStream.bufferedReader().readText()
                Log.d(TAG, "ElevenLabs response: $response")

                val result = json.decodeFromString<ElevenLabsResponse>(response)
                result.text
            } else {
                val error = connection.errorStream?.bufferedReader()?.readText() ?: "Unknown error"
                Log.e(TAG, "ElevenLabs error ($responseCode): $error")
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Transcription failed: ${e.message}", e)
            null
        }
    }

    /**
     * Convert raw PCM audio to WAV format.
     */
    private fun pcmToWav(
        pcmData: ByteArray,
        sampleRate: Int,
        channels: Int,
        bitsPerSample: Int
    ): ByteArray {
        val byteRate = sampleRate * channels * bitsPerSample / 8
        val blockAlign = channels * bitsPerSample / 8
        val dataSize = pcmData.size
        val fileSize = 36 + dataSize

        val output = ByteArrayOutputStream()
        val writer = DataOutputStream(output)

        // RIFF header
        writer.writeBytes("RIFF")
        writer.writeIntLE(fileSize)
        writer.writeBytes("WAVE")

        // fmt subchunk
        writer.writeBytes("fmt ")
        writer.writeIntLE(16) // Subchunk1Size for PCM
        writer.writeShortLE(1) // AudioFormat: PCM = 1
        writer.writeShortLE(channels)
        writer.writeIntLE(sampleRate)
        writer.writeIntLE(byteRate)
        writer.writeShortLE(blockAlign)
        writer.writeShortLE(bitsPerSample)

        // data subchunk
        writer.writeBytes("data")
        writer.writeIntLE(dataSize)
        writer.write(pcmData)

        writer.flush()
        return output.toByteArray()
    }

    // Helper extensions for little-endian writing
    private fun DataOutputStream.writeIntLE(value: Int) {
        write(value and 0xFF)
        write((value shr 8) and 0xFF)
        write((value shr 16) and 0xFF)
        write((value shr 24) and 0xFF)
    }

    private fun DataOutputStream.writeShortLE(value: Int) {
        write(value and 0xFF)
        write((value shr 8) and 0xFF)
    }
}

@Serializable
data class ElevenLabsResponse(
    val text: String? = null,
    val language_code: String? = null,
    val language_probability: Double? = null
)
