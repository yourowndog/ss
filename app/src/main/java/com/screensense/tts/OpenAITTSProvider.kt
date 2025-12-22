package com.screensense.tts

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Cloud TTS provider using OpenAI's API.
 * 
 * Supports voices: alloy, echo, fable, onyx, nova, shimmer.
 * Requires an API key. Uses 16-bit PCM at 24kHz.
 */
class OpenAITTSProvider(private val context: Context) : TTSProvider {

    companion object {
        private const val TAG = "OpenAITTSProvider"
        private const val API_URL = "https://api.openai.com/v1/audio/speech"
        
        val VOICES = listOf("Alloy", "Echo", "Fable", "Onyx", "Nova", "Shimmer")
    }

    // Use key from BuildConfig (injected from local.properties)
    private var apiKey: String = com.screensense.BuildConfig.OPENAI_API_KEY
    private var voice: String = "alloy"
    private var speed: Float = 1.0f
    private var audioTrack: AudioTrack? = null
    
    @Volatile
    private var isSpeakingFlag = false

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .build()

    fun setApiKey(key: String) {
        apiKey = key
    }

    fun setVoice(voiceName: String) {
        // OpenAI API expects lowercase voice names
        val lowerName = voiceName.lowercase()
        if (VOICES.any { it.lowercase() == lowerName }) {
            voice = lowerName
        }
    }

    private var currentCall: Call? = null

    override suspend fun speak(text: String) {
        if (apiKey.isBlank()) {
            Log.e(TAG, "API key is missing")
            return
        }

        if (text.isBlank()) return

        stop() // Ensure previous playback/request is stopped
        isSpeakingFlag = true
        
        val mediaType = "application/json".toMediaType()
        val json = """
            {
                "model": "tts-1",
                "voice": "$voice",
                "input": ${escapeJson(text)},
                "speed": $speed,
                "response_format": "pcm"
            }
        """.trimIndent()

        val request = Request.Builder()
            .url(API_URL)
            .addHeader("Authorization", "Bearer $apiKey")
            .post(json.toRequestBody(mediaType))
            .build()
            
        val call = client.newCall(request)
        currentCall = call

        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (call.isCanceled()) {
                    Log.d(TAG, "Request cancelled")
                    return
                }
                Log.e(TAG, "Request failed", e)
                isSpeakingFlag = false
            }

            override fun onResponse(call: Call, response: Response) {
                if (call.isCanceled()) return
                
                if (!response.isSuccessful) {
                    val error = response.body?.string()
                    Log.e(TAG, "API Error: ${response.code} - $error")
                    isSpeakingFlag = false
                    return
                }

                val bytes = response.body?.bytes()
                if (bytes != null) {
                    // Check cancellation one last time before playing
                    if (!isSpeakingFlag) return
                    playPcm(bytes)
                } else {
                    isSpeakingFlag = false
                }
            }
        })
    }

    private fun playPcm(bytes: ByteArray) {
        // 24kHz, Mono, 16-bit PCM (as per OpenAI pcm response_format)
        val sampleRate = 24000
        val bufferSize = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )

        try {
            audioTrack?.release()
            audioTrack = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANT)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(sampleRate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .build()
                )
                .setBufferSizeInBytes(maxOf(bufferSize, bytes.size))
                .setTransferMode(AudioTrack.MODE_STATIC)
                .build()

            audioTrack?.setNotificationMarkerPosition(bytes.size / 2) // pcm_16bit is 2 bytes per sample
            audioTrack?.setPlaybackPositionUpdateListener(object : AudioTrack.OnPlaybackPositionUpdateListener {
                override fun onMarkerReached(track: AudioTrack?) {
                    isSpeakingFlag = false
                }
                override fun onPeriodicNotification(track: AudioTrack?) {}
            })

            audioTrack?.write(bytes, 0, bytes.size)
            audioTrack?.play()
        } catch (e: Exception) {
            Log.e(TAG, "Error playing PCM", e)
            isSpeakingFlag = false
        }
    }

    override fun stop() {
        isSpeakingFlag = false
        currentCall?.cancel()
        currentCall = null
        
        try {
            audioTrack?.pause() // Pause immediately
            audioTrack?.flush()
            audioTrack?.stop()
            audioTrack?.release()
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping audio", e)
        }
        audioTrack = null
    }

    override fun setSpeed(speed: Float) {
        this.speed = speed.coerceIn(0.25f, 4.0f)
    }

    override fun setPitch(pitch: Float) {
        // Not supported by OpenAI TTS API directly via simple param
    }

    override fun getProviderName(): String = "OpenAI Cloud TTS"

    override fun isReady(): Boolean = apiKey.isNotBlank()

    override fun release() {
        stop()
        client.dispatcher.executorService.shutdown()
    }

    fun isSpeaking(): Boolean = isSpeakingFlag

    private fun escapeJson(text: String): String {
        return text.replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")
            .let { "\"$it\"" }
    }
}
