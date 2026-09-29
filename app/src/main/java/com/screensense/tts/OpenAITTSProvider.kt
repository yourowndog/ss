package com.screensense.tts

import android.content.ContentValues
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.io.InputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * OpenAI-compatible TTS provider for Sam's voice service on Sisyphus.
 */
class OpenAITTSProvider(private val context: Context) : TTSProvider {

    companion object {
        private const val TAG = "OpenAITTSProvider"
        private const val MAX_RESPONSE_BYTES = 64L * 1024L * 1024L
        private const val DOWNLOAD_FOLDER = "ScreenSense Voice Generations"
        private const val LAST_GENERATION_FILE = "last_voice_generation.wav"

        data class Voice(val apiName: String, val displayName: String)

        val VOICES = listOf(
            Voice("sam", "Sam"),
            Voice("sam-bright", "Sam Bright")
        )
    }

    private val apiUrl =
        "${com.screensense.BuildConfig.SAM_TTS_BASE_URL.trimEnd('/')}/audio/speech"
    private val streamApiUrl =
        "${com.screensense.BuildConfig.SAM_TTS_BASE_URL.trimEnd('/')}/audio/speech/stream"
    private val apiKey: String = com.screensense.BuildConfig.SAM_TTS_API_KEY
    private var voice: String = VOICES.first().apiName
    private var speed: Float = 1.0f
    private var audioTrack: AudioTrack? = null
    private val generationLock = Any()
    
    @Volatile
    private var isSpeakingFlag = false

    private var audioFocusRequested = false
    private val audioManager: AudioManager? by lazy {
        context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    }

    private val audioFocusChangeListener = AudioManager.OnAudioFocusChangeListener { focusChange ->
        when (focusChange) {
            AudioManager.AUDIOFOCUS_LOSS,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                Log.d(TAG, "Audio focus lost, stopping speech")
                abandonAudioFocus()
                stop()
            }
        }
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        // A cold VoxCPM load takes about 170 seconds; warm calls take ~14.
        .readTimeout(5, TimeUnit.MINUTES)
        .writeTimeout(10, TimeUnit.SECONDS)
        .build()

    suspend fun speakStreaming(text: String) {
        if (streamApiUrl.isBlank() || apiKey.isBlank()) {
            Log.e(TAG, "Sam streaming voice service is not configured")
            return
        }
        if (text.isBlank()) return

        stop()
        isSpeakingFlag = true

        val json = JSONObject()
            .put("model", "voxcpm2-sam-v3")
            .put("voice", voice)
            .put("input", text)
            .put("speed", 1.0)
            .put("response_format", "pcm")
            .toString()

        val request = Request.Builder()
            .url(streamApiUrl)
            .addHeader("Authorization", "Bearer $apiKey")
            .post(json.toRequestBody("application/json".toMediaType()))
            .build()

        val call = client.newCall(request)
        currentCall = call
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (call.isCanceled()) {
                    Log.d(TAG, "Streaming request cancelled")
                    return
                }
                Log.e(TAG, "Streaming request failed", e)
                finishCall(call)
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    if (call.isCanceled() || currentCall !== call) return
                    if (!it.isSuccessful) {
                        val error = it.body?.string()?.take(200)
                        Log.e(TAG, "Sam streaming service error: " + it.code + " - " + error)
                        finishCall(call)
                        return
                    }

                    val sampleRate = it.header("X-Sample-Rate")
                        ?.toIntOrNull()
                        ?: 48000
                    val body = it.body
                    if (body == null) {
                        Log.e(TAG, "Sam streaming response had no body")
                        finishCall(call)
                        return
                    }

                    try {
                        playPcmStream(body.byteStream(), sampleRate, call)
                    } catch (e: Exception) {
                        Log.e(TAG, "Error streaming Sam voice audio", e)
                        finishCall(call)
                    }
                }
            }
        })
    }

    fun setVoice(voiceName: String) {
        val requested = voiceName.lowercase()
        if (VOICES.any { it.apiName == requested }) {
            voice = requested
        }
    }

    @Volatile
    private var currentCall: Call? = null

    override suspend fun speak(text: String) {
        if (apiUrl.isBlank() || apiKey.isBlank()) {
            Log.e(TAG, "Sam voice service is not configured")
            return
        }

        if (text.isBlank()) return

        stop() // Ensure previous playback/request is stopped
        isSpeakingFlag = true
        
        val json = JSONObject()
            .put("model", "voxcpm2-sam-v3")
            .put("voice", voice)
            .put("input", text)
            .put("speed", speed)
            .put("response_format", "wav")
            .toString()

        val request = Request.Builder()
            .url(apiUrl)
            .addHeader("Authorization", "Bearer $apiKey")
            .post(json.toRequestBody("application/json".toMediaType()))
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
                finishCall(call)
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    if (call.isCanceled() || currentCall !== call) return

                    if (!it.isSuccessful) {
                        val error = it.body?.string()?.take(200)
                        Log.e(TAG, "Sam voice service error: ${it.code} - $error")
                        finishCall(call)
                        return
                    }

                    val body = it.body
                    val contentLength = body?.contentLength() ?: -1L
                    if (body == null || contentLength > MAX_RESPONSE_BYTES) {
                        Log.e(TAG, "Invalid or oversized audio response")
                        finishCall(call)
                        return
                    }

                    try {
                        val wavBytes = body.bytes()
                        val wav = decodePcmWav(wavBytes)
                        if (!isSpeakingFlag || currentCall !== call) return
                        cacheGeneratedWav(wavBytes)
                        playPcm(wav.pcm, wav.sampleRate, call)
                    } catch (e: Exception) {
                        Log.e(TAG, "Invalid WAV response", e)
                        finishCall(call)
                    }
                }
            }
        })
    }

    /**
     * Save the exact WAV returned by Sisyphus for the most recent successful
     * generation. MediaStore places it in shared Downloads without needing
     * broad storage permission on Android 10+.
     */
    suspend fun saveLastGenerationToDownloads(): String? = withContext(Dispatchers.IO) {
        val cachedWav = File(context.cacheDir, LAST_GENERATION_FILE)
        synchronized(generationLock) {
            if (!cachedWav.isFile || cachedWav.length() == 0L) return@withContext null
        }

        val timestamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date())
        val fileName = "ScreenSense_$timestamp.wav"
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, fileName)
            put(MediaStore.Downloads.MIME_TYPE, "audio/wav")
            put(
                MediaStore.Downloads.RELATIVE_PATH,
                "${Environment.DIRECTORY_DOWNLOADS}/$DOWNLOAD_FOLDER"
            )
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val destination = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: return@withContext null

        try {
            resolver.openOutputStream(destination)?.use { output ->
                synchronized(generationLock) {
                    if (!cachedWav.isFile || cachedWav.length() == 0L) {
                        throw IOException("Cached generation is unavailable")
                    }
                    cachedWav.inputStream().use { input -> input.copyTo(output) }
                }
            } ?: throw IOException("Unable to open shared Downloads destination")

            values.clear()
            values.put(MediaStore.Downloads.IS_PENDING, 0)
            resolver.update(destination, values, null, null)
            fileName
        } catch (e: Exception) {
            resolver.delete(destination, null, null)
            Log.e(TAG, "Failed to save generated WAV", e)
            null
        }
    }

    private fun playPcmStream(input: InputStream, sampleRate: Int, call: Call) {
        requestAudioFocus()

        val minBuffer = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        val streamBuffer = maxOf(minBuffer * 2, sampleRate)

        val track = AudioTrack.Builder()
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
            .setBufferSizeInBytes(streamBuffer)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()

        audioTrack?.release()
        audioTrack = track

        val readBuffer = ByteArray(32 * 1024)
        val writeBuffer = ByteArray(readBuffer.size + 1)
        var carry: Byte? = null
        var started = false
        var totalBytes = 0L

        while (!call.isCanceled() && currentCall === call) {
            val read = input.read(readBuffer)
            if (read < 0) break
            if (read == 0) continue

            var outLen = 0
            val saved = carry
            if (saved != null) {
                writeBuffer[0] = saved
                outLen = 1
                carry = null
            }
            System.arraycopy(readBuffer, 0, writeBuffer, outLen, read)
            outLen += read

            if ((outLen and 1) != 0) {
                carry = writeBuffer[outLen - 1]
                outLen -= 1
            }
            if (outLen == 0) continue

            var offset = 0
            while (offset < outLen) {
                val written = track.write(writeBuffer, offset, outLen - offset)
                if (written < 0) {
                    throw IOException("AudioTrack streaming write failed: " + written)
                }
                offset += written
                totalBytes += written.toLong()
            }

            if (!started) {
                track.play()
                started = true
                Log.d(TAG, "Watch Sam stream playback started @ " + sampleRate + " Hz")
            }
        }

        if (!started) {
            throw IOException("Sam stream ended before audio arrived")
        }

        val targetFrames = totalBytes / 2L
        val deadline = System.currentTimeMillis() + 30_000L
        while (
            !call.isCanceled() &&
            currentCall === call &&
            System.currentTimeMillis() < deadline
        ) {
            val played = track.playbackHeadPosition.toLong() and 0xffffffffL
            if (played >= targetFrames) break
            Thread.sleep(25)
        }

        if (audioTrack === track) {
            try {
                track.stop()
            } catch (_: Exception) {
            }
            track.release()
            audioTrack = null
        }
        Log.d(TAG, "Watch Sam stream complete: " + totalBytes + " PCM bytes")
        finishCall(call)
    }

    private fun requestAudioFocus(): Boolean {
        audioManager?.let { am ->
            val result = am.requestAudioFocus(
                audioFocusChangeListener,
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN_TRANSIENT
            )
            audioFocusRequested = result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
            if (!audioFocusRequested) {
                Log.w(TAG, "Audio focus request denied: $result")
            }
            return audioFocusRequested
        }
        return false
    }

    private fun abandonAudioFocus() {
        if (audioFocusRequested) {
            audioManager?.abandonAudioFocus(audioFocusChangeListener)
            audioFocusRequested = false
        }
    }

    private fun cacheGeneratedWav(bytes: ByteArray) {
        val cacheFile = File(context.cacheDir, LAST_GENERATION_FILE)
        val temporaryFile = File(context.cacheDir, "$LAST_GENERATION_FILE.tmp")

        try {
            temporaryFile.writeBytes(bytes)
            synchronized(generationLock) {
                if (cacheFile.exists() && !cacheFile.delete()) {
                    throw IOException("Unable to replace cached generation")
                }
                if (!temporaryFile.renameTo(cacheFile)) {
                    temporaryFile.copyTo(cacheFile, overwrite = true)
                    temporaryFile.delete()
                }
            }
        } catch (e: Exception) {
            temporaryFile.delete()
            Log.e(TAG, "Failed to cache generated WAV", e)
        }
    }

    private fun playPcm(bytes: ByteArray, sampleRate: Int, call: Call) {
        requestAudioFocus()

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
                    if (audioTrack === track) {
                        finishCall(call)
                        track?.release()
                        audioTrack = null
                    }
                }
                override fun onPeriodicNotification(track: AudioTrack?) {}
            })

            val written = audioTrack?.write(bytes, 0, bytes.size) ?: -1
            if (written < 0) throw IOException("AudioTrack write failed: $written")
            audioTrack?.play()
        } catch (e: Exception) {
            Log.e(TAG, "Error playing Sam voice audio", e)
            finishCall(call)
        }
    }

    private data class PcmWav(val sampleRate: Int, val pcm: ByteArray)

    private fun decodePcmWav(bytes: ByteArray): PcmWav {
        require(bytes.size >= 44 && ascii(bytes, 0) == "RIFF" && ascii(bytes, 8) == "WAVE") {
            "Not a RIFF/WAVE file"
        }

        var sampleRate = 0
        var channels = 0
        var bitsPerSample = 0
        var audioFormat = 0
        var pcm: ByteArray? = null
        var offset = 12

        while (offset + 8 <= bytes.size) {
            val chunkId = ascii(bytes, offset)
            val chunkSize = littleEndianInt(bytes, offset + 4)
            val dataOffset = offset + 8
            require(chunkSize >= 0 && dataOffset + chunkSize <= bytes.size) { "Invalid WAV chunk" }

            when (chunkId) {
                "fmt " -> {
                    require(chunkSize >= 16) { "Invalid WAV format chunk" }
                    val buffer = ByteBuffer.wrap(bytes, dataOffset, chunkSize)
                        .order(ByteOrder.LITTLE_ENDIAN)
                    audioFormat = buffer.short.toInt() and 0xffff
                    channels = buffer.short.toInt() and 0xffff
                    sampleRate = buffer.int
                    buffer.int // byte rate
                    buffer.short // block alignment
                    bitsPerSample = buffer.short.toInt() and 0xffff
                }
                "data" -> pcm = bytes.copyOfRange(dataOffset, dataOffset + chunkSize)
            }

            offset = dataOffset + chunkSize + (chunkSize and 1)
        }

        require(audioFormat == 1 && channels == 1 && bitsPerSample == 16) {
            "Expected mono 16-bit PCM WAV"
        }
        require(sampleRate in 8_000..192_000) { "Invalid WAV sample rate" }
        return PcmWav(sampleRate, requireNotNull(pcm) { "WAV data chunk missing" })
    }

    private fun ascii(bytes: ByteArray, offset: Int): String =
        String(bytes, offset, 4, Charsets.US_ASCII)

    private fun littleEndianInt(bytes: ByteArray, offset: Int): Int =
        ByteBuffer.wrap(bytes, offset, 4).order(ByteOrder.LITTLE_ENDIAN).int

    private fun finishCall(call: Call) {
        if (currentCall === call) {
            currentCall = null
            isSpeakingFlag = false
        }
        abandonAudioFocus()
    }

    override fun stop() {
        isSpeakingFlag = false
        currentCall?.cancel()
        currentCall = null
        
        try {
            audioTrack?.pause()
            audioTrack?.flush()
            audioTrack?.stop()
            audioTrack?.release()
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping audio", e)
        }
        audioTrack = null
        abandonAudioFocus()
    }

    override fun setSpeed(speed: Float) {
        this.speed = speed.coerceIn(0.25f, 4.0f)
    }

    override fun setPitch(pitch: Float) {
        // Not supported by OpenAI TTS API directly via simple param
    }

    override fun getProviderName(): String = "Sam Voice (Sisyphus)"

    override fun isReady(): Boolean = apiUrl.isNotBlank() && apiKey.isNotBlank()

    override fun release() {
        stop()
        client.dispatcher.executorService.shutdown()
    }

    fun isSpeaking(): Boolean = isSpeakingFlag

}
