package com.screensense.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.screensense.MainActivity
import com.screensense.R
import com.screensense.ScreenSenseApp
import com.screensense.capture.ScreenCapture
import com.screensense.tts.PiperTTSProvider
import com.screensense.tts.TTSProvider
import com.screensense.vision.MLKitVisionProvider
import com.screensense.vision.VisionProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Main foreground service that orchestrates screen capture, vision analysis, and TTS.
 */
class ScreenSenseService : Service() {
    
    companion object {
        private const val TAG = "ScreenSenseService"
        private const val NOTIFICATION_ID = 1
        
        const val ACTION_START = "com.screensense.START"
        const val ACTION_STOP = "com.screensense.STOP"
        const val ACTION_TRIGGER = "com.screensense.TRIGGER"
        const val ACTION_INIT_PROJECTION = "com.screensense.INIT_PROJECTION"
        
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        
        fun createTriggerIntent(context: Context): Intent {
            return Intent(context, ScreenSenseService::class.java).apply {
                action = ACTION_TRIGGER
            }
        }
    }
    
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    
    private lateinit var screenCapture: ScreenCapture
    private lateinit var visionProvider: VisionProvider
    private lateinit var ttsProvider: TTSProvider
    
    private var isProcessing = false
    
    // Binder for local binding
    private val binder = LocalBinder()
    
    inner class LocalBinder : Binder() {
        fun getService(): ScreenSenseService = this@ScreenSenseService
    }
    
    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Service created")
        
        // Initialize providers
        screenCapture = ScreenCapture(this)
        visionProvider = MLKitVisionProvider(this)
        ttsProvider = AndroidTTSProvider(this)
    }
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "onStartCommand: ${intent?.action}")
        
        when (intent?.action) {
            ACTION_START -> {
                startForeground(NOTIFICATION_ID, createNotification())
            }
            
            ACTION_INIT_PROJECTION -> {
                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
                val resultData = intent.getParcelableExtra<Intent>(EXTRA_RESULT_DATA)
                if (resultData != null) {
                    screenCapture.initialize(resultCode, resultData)
                    Log.d(TAG, "MediaProjection initialized")
                }
            }
            
            ACTION_TRIGGER -> {
                triggerScreenRead()
            }
            
            ACTION_STOP -> {
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
     * Trigger the screen reading process.
     * Called from Quick Settings tile, Accessibility Service, or other triggers.
     */
    fun triggerScreenRead() {
        if (isProcessing) {
            Log.d(TAG, "Already processing, ignoring trigger")
            return
        }
        
        if (!screenCapture.isReady()) {
            Log.e(TAG, "Screen capture not initialized")
            serviceScope.launch {
                ttsProvider.speak("Screen capture permission not granted. Please open ScreenSense app to set up.")
            }
            return
        }
        
        isProcessing = true
        
        serviceScope.launch {
            try {
                Log.d(TAG, "Starting screen read...")
                
                // Brief announcement
                ttsProvider.speak(getString(R.string.reading_screen))
                
                // Capture screen
                val bitmap = screenCapture.capture()
                
                if (bitmap == null) {
                    Log.e(TAG, "Failed to capture screen")
                    ttsProvider.speak("Failed to capture screen")
                    return@launch
                }
                
                Log.d(TAG, "Screen captured: ${bitmap.width}x${bitmap.height}")
                
                // Analyze with vision
                val result = visionProvider.analyze(bitmap)
                
                Log.d(TAG, "Vision result: ${result.text.take(100)}...")
                
                // Speak the result
                if (result.hasContent) {
                    ttsProvider.speak(result.text)
                } else {
                    ttsProvider.speak(getString(R.string.no_content))
                }
                
                // Clean up bitmap
                bitmap.recycle()
                
            } catch (e: Exception) {
                Log.e(TAG, "Error in screen read", e)
                ttsProvider.speak("Error reading screen")
            } finally {
                isProcessing = false
            }
        }
    }
    
    /**
     * Stop any ongoing speech.
     */
    fun stopSpeech() {
        ttsProvider.stop()
    }
    
    /**
     * Check if screen capture is ready.
     */
    fun isScreenCaptureReady(): Boolean = screenCapture.isReady()
    
    /**
     * Set TTS speech speed.
     */
    fun setSpeechSpeed(speed: Float) {
        ttsProvider.setSpeed(speed)
    }
    
    private fun createNotification(): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        
        return NotificationCompat.Builder(this, ScreenSenseApp.NOTIFICATION_CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.notification_text))
            .setSmallIcon(R.drawable.ic_screen_reader)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }
    
    override fun onDestroy() {
        Log.d(TAG, "Service destroyed")
        serviceScope.cancel()
        screenCapture.release()
        visionProvider.release()
        ttsProvider.release()
        super.onDestroy()
    }
}
