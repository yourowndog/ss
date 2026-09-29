package com.screensense.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.media.AudioManager
import android.media.ToneGenerator
import android.util.Log
import androidx.core.app.NotificationCompat
import com.screensense.MainActivity
import com.screensense.R
import com.screensense.ScreenSenseApp
import com.screensense.capture.ScreenCapture
import com.screensense.tts.KokoroTTSProvider
import com.screensense.tts.OpenAITTSProvider
import com.screensense.tts.TTSProvider
import com.screensense.vision.MLKitVisionProvider
import com.screensense.vision.VisionProvider
import kotlinx.coroutines.*

/**
 * Main foreground service that orchestrates screen capture, vision analysis, and TTS.
 * 
 * Android 14+ MediaProjection lifecycle:
 * - Permission grant -> IMMEDIATELY start capture session
 * - VirtualDisplay created right away, not on-demand
 * - capture() reads from the running stream
 */
class ScreenSenseService : Service() {
    
    companion object {
        private const val TAG = "ScreenSenseService"
        private const val NOTIFICATION_ID = 1
        
        // Actions
        const val ACTION_START_PROJECTION = "com.screensense.START_PROJECTION"
        const val ACTION_CAPTURE_FRAME = "com.screensense.CAPTURE_FRAME"
        const val ACTION_TOGGLE_READ = "com.screensense.TOGGLE_READ"
        const val ACTION_STOP = "com.screensense.STOP"
        
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        
        @Volatile
        var instance: ScreenSenseService? = null
            private set
    }
    
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    
    private lateinit var screenCapture: ScreenCapture
    private lateinit var visionProvider: VisionProvider
    private lateinit var ttsProvider: TTSProvider
    
    private var localTtsProvider: KokoroTTSProvider? = null
    private lateinit var cloudTtsProvider: OpenAITTSProvider
    private var useCloud = true
    
    private var toneGenerator: ToneGenerator? = null
    
    private var readingJob: Job? = null
    
    /**
     * Check if currently reading (either processing or speaking).
     */
    val isReading: Boolean get() {
        val jobActive = readingJob?.isActive == true
        val speaking = when (val provider = ttsProvider) {
            is KokoroTTSProvider -> provider.isSpeaking()
            is OpenAITTSProvider -> provider.isSpeaking()
            else -> false
        }
        return jobActive || speaking
    }
    
    // Binder for local binding
    private val binder = LocalBinder()
    
    inner class LocalBinder : Binder() {
        fun getService(): ScreenSenseService = this@ScreenSenseService
    }
    
    override fun onCreate() {
        super.onCreate()
        instance = this
        Log.d(TAG, "Service created")
        
        screenCapture = ScreenCapture(this)
        visionProvider = MLKitVisionProvider(this)
        
        cloudTtsProvider = OpenAITTSProvider(this)
        ttsProvider = cloudTtsProvider
        
        try {
            toneGenerator = ToneGenerator(AudioManager.STREAM_MUSIC, 100)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create ToneGenerator", e)
        }
    }
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "onStartCommand: ${intent?.action}")
        
        when (intent?.action) {
            ACTION_START_PROJECTION -> {
                // Step 1: Start foreground FIRST with correct type
                Log.d(TAG, "Starting foreground service with MEDIA_PROJECTION type")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    startForeground(
                        NOTIFICATION_ID, 
                        createNotification(),
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                    )
                } else {
                    startForeground(NOTIFICATION_ID, createNotification())
                }
                
                // Step 2: Get projection data and IMMEDIATELY start capture
                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
                val resultData = intent.getParcelableExtra<Intent>(EXTRA_RESULT_DATA)
                
                if (resultData != null) {
                    Log.d(TAG, "Initializing and starting capture session IMMEDIATELY")
                    val success = screenCapture.initializeAndStart(resultCode, resultData)
                    if (success) {
                        Log.d(TAG, "Capture session started successfully!")
                        // Announce that we're ready
                        serviceScope.launch {
                            ttsProvider.speak("Screen capture ready")
                        }
                    } else {
                        Log.e(TAG, "Failed to start capture session")
                        serviceScope.launch {
                            ttsProvider.speak("Failed to start screen capture")
                        }
                    }
                } else {
                    Log.e(TAG, "No result data in intent")
                }
            }
            
            ACTION_CAPTURE_FRAME -> {
                triggerScreenRead()
            }
            
            ACTION_TOGGLE_READ -> {
                if (isReading) {
                    Log.d(TAG, "Toggle: Cancelling current read")
                    cancelScreenRead()
                } else {
                    Log.d(TAG, "Toggle: Starting screen read")
                    triggerScreenRead()
                }
            }
            
            ACTION_STOP -> {
                screenCapture.release()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        
        return START_STICKY
    }
    
    override fun onBind(intent: Intent?): IBinder {
        return binder
    }
    
    /**
     * Cancel any ongoing screen read.
     */
    fun cancelScreenRead() {
        readingJob?.cancel()
        readingJob = null
        ttsProvider.stop()
        Log.d(TAG, "Screen read cancelled")
    }
    
    /**
     * Capture a frame and read it aloud.
     */
    fun triggerScreenRead() {
        if (isReading) {
            Log.d(TAG, "Already reading, ignoring trigger")
            return
        }
        
        readingJob = serviceScope.launch {
            try {
                Log.d(TAG, "Starting screen read (Hybrid Mode)...")
                toneGenerator?.startTone(ToneGenerator.TONE_PROP_BEEP)
                
                // STEP 1: Fast Accessibility Read
                var textToSpeak = ScreenSenseAccessibilityService.instance?.readScreenContentFromAccessibilityTree() ?: ""
                var usedFallback = false
                
                // STEP 2: Smart Validation & Fallback
                // Check if text is empty OR just generic navigation noise
                if (shouldUseFallback(textToSpeak)) {
                    Log.d(TAG, "Accessibility text poor/empty. Attempting Visual OCR fallback...")
                    
                    if (isScreenCaptureReady()) {
                        toneGenerator?.startTone(ToneGenerator.TONE_PROP_BEEP) // Double beep for fallback start
                        
                        val bitmap = screenCapture.captureFrame()
                        if (bitmap != null) {
                            val ocrResult = visionProvider.analyze(bitmap)
                            if (ocrResult.text.isNotBlank()) {
                                textToSpeak = ocrResult.text
                                usedFallback = true
                                Log.d(TAG, "Visual OCR successful: ${textToSpeak.length} chars")
                            } else {
                                Log.w(TAG, "Visual OCR found no text")
                            }
                        } else {
                            Log.e(TAG, "Failed to capture frame for fallback")
                        }
                    } else {
                        Log.w(TAG, "Fallback needed but Screen Capture NOT ready")
                        // If we have NO text at all, warn the user
                        if (textToSpeak.isBlank()) {
                            textToSpeak = "Screen capture permission required for this content."
                        }
                    }
                } else {
                    Log.d(TAG, "Accessibility read successful: ${textToSpeak.length} chars")
                }
                
                // STEP 3: Speak
                if (textToSpeak.isNotBlank()) {
                    if (textToSpeak.length > 3000) {
                        textToSpeak = textToSpeak.take(3000)
                    }
                    
                    // Announce mode if debugging preferred, but for now just speak content
                     val cleanText = processText(textToSpeak)
                    ttsProvider.speak(cleanText)
                } else {
                    toneGenerator?.startTone(ToneGenerator.TONE_PROP_NACK)
                }
                
            } catch (e: kotlinx.coroutines.CancellationException) {
                Log.d(TAG, "Screen read was cancelled")
            } catch (e: Exception) {
                Log.e(TAG, "Error during screen read", e)
                toneGenerator?.startTone(ToneGenerator.TONE_PROP_NACK)
            }
        }
    }
    
    /**
     * Heuristic to determine if we should fall back to OCR.
     * Returns true if A11y text is empty, too short, or looks like "button soup" (nav noise).
     */
    private fun shouldUseFallback(text: String): Boolean {
        if (text.isBlank()) return true
        
        // 1. Length Threshold
        // < 80 chars is usually just a header/footer or simple dialog. 
        // Real content (article, email, chat) is usually longer.
        if (text.length < 80) {
            Log.d(TAG, "Fallback: Text too short (${text.length} chars)")
            return true
        }
        
        // 2. "Button Soup" Detection
        // If the text is just a list of short items separated by newlines/spaces, it's likely a menu.
        // We check average word length or line density.
        val lines = text.split('\n')
        if (lines.size > 5) {
            val avgLineLength = text.length / lines.size
            // If average line is very short (e.g. "Home\nSearch\nProfile\nSettings"), it's likely UI noise.
            if (avgLineLength < 15) {
                Log.d(TAG, "Fallback: Detected 'Button Soup' (Avg line len: $avgLineLength)")
                return true
            }
        }
        
        return false
    }
    
    /**
     * Stop any ongoing speech.
     */
    fun stopSpeech() {
        ttsProvider.stop()
    }
    
    fun setUseCloud(cloud: Boolean) {
        if (useCloud == cloud && ::ttsProvider.isInitialized) return
        ttsProvider.stop()
        useCloud = cloud
        ttsProvider = if (useCloud) {
            cloudTtsProvider
        } else {
            localTtsProvider ?: KokoroTTSProvider(this).also { localTtsProvider = it }
        }
        Log.d(TAG, "Switched to ${ttsProvider.getProviderName()}")
    }
    
    /**
     * Check if screen capture is ready.
     * Returns false if screenCapture is not initialized (Accessibility-only mode).
     */
    fun isScreenCaptureReady(): Boolean = try { screenCapture.isReady() } catch (e: UninitializedPropertyAccessException) { false }
    
    /**
     * Set TTS speech speed.
     */
    fun setSpeechSpeed(speed: Float) {
        ttsProvider.setSpeed(speed)
    }
    
    /**
     * Set TTS speaker ID.
     */
    fun setSpeaker(id: Int) {
        when (val provider = ttsProvider) {
            is KokoroTTSProvider -> provider.setSpeaker(id)
            is OpenAITTSProvider -> provider.setVoice(
                OpenAITTSProvider.VOICES.getOrNull(id)?.apiName ?: OpenAITTSProvider.VOICES.first().apiName
            )
        }
    }
    
    /**
     * Get number of available speakers.
     */
    fun getNumSpeakers(): Int {
        return when (val provider = ttsProvider) {
            is KokoroTTSProvider -> provider.getNumSpeakers()
            is OpenAITTSProvider -> OpenAITTSProvider.VOICES.size
            else -> 1
        }
    }
    
    /**
     * Get voice name for speaker ID.
     */
    fun getVoiceName(id: Int): String {
        return when (val provider = ttsProvider) {
            is KokoroTTSProvider -> provider.getVoiceName(id)
            is OpenAITTSProvider -> OpenAITTSProvider.VOICES.getOrNull(id)?.displayName ?: "Voice $id"
            else -> "Voice $id"
        }
    }
    
    /**
     * Speak arbitrary text (for test voice).
     */
    fun speakText(text: String) {
        serviceScope.launch {
            ttsProvider.speak(text)
        }
    }

    /**
     * Copy the exact last WAV returned by the Titan voice service into the
     * user's shared Downloads/ScreenSense Voice Generations folder.
     */
    suspend fun downloadLastVoiceGeneration(): String? =
        cloudTtsProvider.saveLastGenerationToDownloads()
    
    private fun createNotification(): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        
        return NotificationCompat.Builder(this, ScreenSenseApp.NOTIFICATION_CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText("Screen capture active - tap to open")
            .setSmallIcon(R.drawable.ic_screen_reader)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }
    
    private fun processText(text: String): String {
        // Fix common OCR errors
        return text
            .replace(Regex("\\bAL\\b"), "AI") // Word "AL" -> "AI"
            .replace(Regex("Generative AL"), "Generative AI")
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
        screenCapture.release()
        visionProvider.release()
        localTtsProvider?.release()
        cloudTtsProvider.release()
        toneGenerator?.release()
    }
}
