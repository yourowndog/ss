package com.screensense.tts

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.getOfflineTtsConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * TTS provider using Kokoro via sherpa-onnx.
 * High-quality neural TTS with 11 named English voices.
 * 
 * Voices (0-10):
 *   0: af (American Female default)
 *   1: af_bella, 2: af_nicole, 3: af_sarah, 4: af_sky
 *   5: am_adam, 6: am_michael
 *   7: bf_emma, 8: bf_isabella
 *   9: bm_george, 10: bm_lewis
 */
class KokoroTTSProvider(private val context: Context) : TTSProvider {
    
    companion object {
        private const val TAG = "KokoroTTSProvider"
        private const val DEFAULT_SPEAKER_ID = 1 // af_bella
        
        // Named voice mappings - human-friendly names
        val VOICE_NAMES = listOf(
            "Default",
            "Bella",
            "Nicole", 
            "Sarah",
            "Sky",
            "Adam",
            "Michael",
            "Emma",
            "Isabella",
            "George",
            "Lewis"
        )
    }
    
    private var tts: OfflineTts? = null
    private var audioTrack: AudioTrack? = null
    private var speakerId = DEFAULT_SPEAKER_ID
    private var speechSpeed = 1.0f
    private var isInitialized = false
    
    @Volatile
    private var isSpeakingFlag = false
    
    @Synchronized
    private fun initializeTts(): Boolean {
        if (isInitialized) return true

        try {
            val modelDir = getModelDirectory()
            if (modelDir == null) {
                Log.e(TAG, "Model directory not found. Please ADB push kokoro-en-v0_19 to /sdcard/ScreenSense/")
                return false
            }
            
            val modelPath = File(modelDir, "model.onnx")
            val voicesPath = File(modelDir, "voices.bin")
            val tokensPath = File(modelDir, "tokens.txt")
            val dataDir = File(modelDir, "espeak-ng-data")

            val missing = missingRequiredPaths(modelDir)
            if (missing.isNotEmpty()) {
                // sherpa-onnx terminates the process on an invalid Kokoro
                // config, so never cross the JNI boundary until preflight passes.
                Log.e(TAG, "Local Kokoro model is incomplete; missing ${missing.joinToString { it.name }}")
                return false
            }
            
            Log.d(TAG, "All Kokoro files found. Initializing model from: ${modelDir.absolutePath}")
            
            // Kokoro uses different config parameters than Piper
            val config = getOfflineTtsConfig(
                modelDir = modelDir.absolutePath,
                modelName = "model.onnx",
                acousticModelName = "",
                vocoder = "",
                voices = "voices.bin",           // Kokoro uses voices.bin
                lexicon = "",
                dataDir = dataDir.absolutePath,
                dictDir = "",
                ruleFsts = "",
                ruleFars = "",
                numThreads = 4
            )
            
            tts = OfflineTts(assetManager = null, config = config)
            isInitialized = true
            Log.d(TAG, "Kokoro TTS initialized successfully with ${tts?.numSpeakers() ?: 0} speakers")
            return true
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize Kokoro TTS", e)
            isInitialized = false
            return false
        }
    }
    
    private fun getModelDirectory(): File? {
        // Check for kokoro model in /sdcard/ScreenSense/kokoro-en-v0_19/
        val kokoroDir = File("/sdcard/ScreenSense/kokoro-en-v0_19")
        if (File(kokoroDir, "model.onnx").exists()) {
            Log.d(TAG, "Found Kokoro model at: ${kokoroDir.absolutePath}")
            return kokoroDir
        }
        
        // Check app's files directory
        val filesDir = File(context.filesDir, "models/kokoro-en-v0_19")
        if (filesDir.exists()) {
            Log.d(TAG, "Found Kokoro model at: ${filesDir.absolutePath}")
            return filesDir
        }
        
        Log.w(TAG, "Kokoro model not found. Checked: ${kokoroDir.absolutePath}, ${filesDir.absolutePath}")
        return null
    }

    private fun missingRequiredPaths(modelDir: File): List<File> {
        val dataDir = File(modelDir, "espeak-ng-data")
        return listOf(
            File(modelDir, "model.onnx"),
            File(modelDir, "voices.bin"),
            File(modelDir, "tokens.txt"),
            dataDir,
            File(dataDir, "intonations"),
            File(dataDir, "phondata"),
            File(dataDir, "phonindex"),
            File(dataDir, "phontab"),
            File(dataDir, "en_dict")
        ).filterNot { it.exists() }
    }
    
    override suspend fun speak(text: String) {
        if (text.isBlank()) return
        if (!initializeTts()) {
            Log.w(TAG, "Cannot speak: initialized=$isInitialized, text blank=${text.isBlank()}")
            return
        }
        
        withContext(Dispatchers.IO) {
            try {
                Log.d(TAG, "Generating speech with voice ${VOICE_NAMES.getOrNull(speakerId) ?: speakerId}: ${text.take(50)}...")
                
                // Generate audio samples
                val audio = tts?.generate(
                    text = text,
                    sid = speakerId,
                    speed = speechSpeed
                )
                
                if (audio != null && audio.samples.isNotEmpty()) {
                    Log.d(TAG, "Generated ${audio.samples.size} samples at ${audio.sampleRate}Hz")
                    playAudio(audio.samples, audio.sampleRate)
                } else {
                    Log.w(TAG, "No audio generated")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error generating speech", e)
                isSpeakingFlag = false
            }
        }
    }
    
    private fun playAudio(samples: FloatArray, sampleRate: Int) {
        // Convert float samples to 16-bit PCM
        val pcmData = ShortArray(samples.size)
        for (i in samples.indices) {
            val sample = (samples[i] * 32767).toInt().coerceIn(-32768, 32767)
            pcmData[i] = sample.toShort()
        }
        
        val bufferSize = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        
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
            .setBufferSizeInBytes(maxOf(bufferSize, pcmData.size * 2))
            .setTransferMode(AudioTrack.MODE_STATIC)
            .build()
        
        // Set up notification when playback completes
        audioTrack?.setNotificationMarkerPosition(pcmData.size)
        audioTrack?.setPlaybackPositionUpdateListener(object : AudioTrack.OnPlaybackPositionUpdateListener {
            override fun onMarkerReached(track: AudioTrack?) {
                Log.d(TAG, "Playback completed")
                isSpeakingFlag = false
            }
            override fun onPeriodicNotification(track: AudioTrack?) {}
        })
        
        isSpeakingFlag = true
        audioTrack?.write(pcmData, 0, pcmData.size)
        audioTrack?.play()
    }
    
    override fun stop() {
        isSpeakingFlag = false
        audioTrack?.apply {
            try {
                stop()
                flush()
                release()
            } catch (e: Exception) {
                Log.w(TAG, "Error stopping audio", e)
            }
        }
        audioTrack = null
    }
    
    /**
     * Check if audio is currently playing.
     */
    fun isSpeaking(): Boolean = isSpeakingFlag
    
    override fun setSpeed(speed: Float) {
        speechSpeed = speed.coerceIn(0.5f, 2.0f)
    }
    
    override fun setPitch(pitch: Float) {
        // Kokoro doesn't support pitch adjustment
    }
    
    /**
     * Set the speaker ID (0-10 for kokoro-en-v0_19).
     */
    fun setSpeaker(id: Int) {
        val maxSpeakers = tts?.numSpeakers() ?: 11
        speakerId = id.coerceIn(0, maxSpeakers - 1)
        Log.d(TAG, "Speaker set to: ${VOICE_NAMES.getOrNull(speakerId) ?: speakerId}")
    }
    
    /**
     * Get the current speaker ID.
     */
    fun getSpeakerId(): Int = speakerId
    
    /**
     * Get the total number of available speakers.
     */
    fun getNumSpeakers(): Int = tts?.numSpeakers() ?: 11
    
    /**
     * Get the current speech speed.
     */
    fun getSpeed(): Float = speechSpeed
    
    /**
     * Get voice name for speaker ID.
     */
    fun getVoiceName(id: Int): String = VOICE_NAMES.getOrNull(id) ?: "Voice $id"
    
    override fun getProviderName(): String = "Kokoro (kokoro-en-v0_19)"
    
    override fun isReady(): Boolean = isInitialized ||
        getModelDirectory()?.let { missingRequiredPaths(it).isEmpty() } == true
    
    override fun release() {
        audioTrack?.release()
        audioTrack = null
        tts = null
        isInitialized = false
    }
}
