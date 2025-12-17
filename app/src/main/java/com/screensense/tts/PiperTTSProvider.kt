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
 * TTS provider using Piper via sherpa-onnx.
 * High-quality neural TTS running fully offline.
 */
class PiperTTSProvider(private val context: Context) : TTSProvider {
    
    companion object {
        private const val TAG = "PiperTTSProvider"
        
        // Default speaker ID for libritts_r model (904 speakers available: 0-903)
        private const val DEFAULT_SPEAKER_ID = 0
    }
    
    private var tts: OfflineTts? = null
    private var audioTrack: AudioTrack? = null
    private var speakerId = DEFAULT_SPEAKER_ID
    private var speechSpeed = 1.0f
    private var isInitialized = false
    
    init {
        initializeTts()
    }
    
    private fun initializeTts() {
        try {
            val modelDir = getModelDirectory()
            if (modelDir == null) {
                Log.e(TAG, "Model directory not found. Please ADB push models to /sdcard/ScreenSense/")
                return
            }
            
            val modelPath = File(modelDir, "en_US-libritts_r-medium.onnx")
            val tokensPath = File(modelDir, "tokens.txt")
            val dataDir = File(modelDir, "espeak-ng-data")
            
            // Verify files exist
            if (!modelPath.exists()) {
                Log.e(TAG, "Model file not found: ${modelPath.absolutePath}")
                return
            }
            if (!tokensPath.exists()) {
                Log.e(TAG, "Tokens file not found: ${tokensPath.absolutePath}")
                return
            }
            if (!dataDir.exists()) {
                Log.e(TAG, "espeak-ng-data not found: ${dataDir.absolutePath}")
                return
            }
            
            Log.d(TAG, "Loading model from: ${modelDir.absolutePath}")
            
            // getOfflineTtsConfig parameter order (from decompiled bytecode):
            // 1. modelDir, 2. modelName, 3. acousticModelName, 4. vocoder, 5. voices,
            // 6. lexicon, 7. dataDir, 8. dictDir, 9. ruleFsts, 10. ruleFars, 
            // 11. numThreads (Integer?), 12. debug (boolean, positional only)
            val config = getOfflineTtsConfig(
                modelDir = modelDir.absolutePath,
                modelName = "en_US-libritts_r-medium.onnx",
                acousticModelName = "",          // Not needed for VITS
                vocoder = "",                    // Built into VITS
                voices = "",                     // Not needed
                lexicon = "",                    // Not needed for Piper
                dataDir = dataDir.absolutePath,  // espeak-ng-data path
                dictDir = "",                    // Not needed
                ruleFsts = "",                   // Not needed
                ruleFars = "",                   // Not needed
                numThreads = 4                   // Last named param, debug is positional
            )
            
            // Create TTS instance with null AssetManager (using file paths, not assets)
            tts = OfflineTts(assetManager = null, config = config)
            isInitialized = true
            Log.d(TAG, "Piper TTS initialized successfully with ${tts?.numSpeakers() ?: 0} speakers")
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize Piper TTS", e)
            isInitialized = false
        }
    }
    
    private fun getModelDirectory(): File? {
        // Check external storage first (for ADB pushed models)
        val externalDir = File("/sdcard/ScreenSense/vits-piper-en_US-libritts_r-medium")
        if (externalDir.exists()) {
            Log.d(TAG, "Found model at: ${externalDir.absolutePath}")
            return externalDir
        }
        
        // Check app's files directory
        val filesDir = File(context.filesDir, "models/vits-piper-en_US-libritts_r-medium")
        if (filesDir.exists()) {
            Log.d(TAG, "Found model at: ${filesDir.absolutePath}")
            return filesDir
        }
        
        Log.w(TAG, "Model not found. Checked: ${externalDir.absolutePath}, ${filesDir.absolutePath}")
        return null
    }
    
    override suspend fun speak(text: String) {
        if (!isInitialized || text.isBlank()) {
            Log.w(TAG, "Cannot speak: initialized=$isInitialized, text blank=${text.isBlank()}")
            return
        }
        
        withContext(Dispatchers.IO) {
            try {
                Log.d(TAG, "Generating speech for: ${text.take(50)}...")
                
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
        
        audioTrack?.write(pcmData, 0, pcmData.size)
        audioTrack?.play()
    }
    
    override fun stop() {
        audioTrack?.stop()
    }
    
    override fun setSpeed(speed: Float) {
        speechSpeed = speed.coerceIn(0.5f, 2.0f)
    }
    
    override fun setPitch(pitch: Float) {
        // Piper doesn't support pitch adjustment, ignore
    }
    
    /**
     * Set the speaker ID (0-903 for libritts_r model).
     */
    fun setSpeaker(id: Int) {
        val maxSpeakers = tts?.numSpeakers() ?: 1
        speakerId = id.coerceIn(0, maxSpeakers - 1)
        Log.d(TAG, "Speaker set to: $speakerId")
    }
    
    override fun getProviderName(): String = "Piper (libritts_r-medium)"
    
    override fun isReady(): Boolean = isInitialized
    
    override fun release() {
        audioTrack?.release()
        audioTrack = null
        tts = null
        isInitialized = false
    }
}
