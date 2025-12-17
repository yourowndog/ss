package com.screensense.tts

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.Locale
import java.util.UUID
import kotlin.coroutines.resume

/**
 * TTS provider implementation using Android's built-in TextToSpeech engine.
 * Uses whatever TTS engine is installed on the device (Google, Samsung, etc.)
 */
class AndroidTTSProvider(context: Context) : TTSProvider {
    
    companion object {
        private const val TAG = "AndroidTTSProvider"
    }
    
    private var tts: TextToSpeech? = null
    private var isInitialized = false
    private var speechSpeed = 1.0f
    private var speechPitch = 1.0f
    
    init {
        tts = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val result = tts?.setLanguage(Locale.US)
                isInitialized = result != TextToSpeech.LANG_MISSING_DATA 
                    && result != TextToSpeech.LANG_NOT_SUPPORTED
                
                if (isInitialized) {
                    tts?.setSpeechRate(speechSpeed)
                    tts?.setPitch(speechPitch)
                    Log.d(TAG, "TTS initialized successfully")
                } else {
                    Log.e(TAG, "TTS language not supported")
                }
            } else {
                Log.e(TAG, "TTS initialization failed with status: $status")
            }
        }
    }
    
    override suspend fun speak(text: String) {
        if (!isInitialized || text.isBlank()) return
        
        suspendCancellableCoroutine { continuation ->
            val utteranceId = UUID.randomUUID().toString()
            
            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(id: String?) {}
                
                override fun onDone(id: String?) {
                    if (id == utteranceId && continuation.isActive) {
                        continuation.resume(Unit)
                    }
                }
                
                @Deprecated("Deprecated in Java")
                override fun onError(id: String?) {
                    if (id == utteranceId && continuation.isActive) {
                        continuation.resume(Unit)
                    }
                }
                
                override fun onError(id: String?, errorCode: Int) {
                    if (id == utteranceId && continuation.isActive) {
                        Log.e(TAG, "TTS error: $errorCode")
                        continuation.resume(Unit)
                    }
                }
            })
            
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
            
            continuation.invokeOnCancellation {
                tts?.stop()
            }
        }
    }
    
    override fun stop() {
        tts?.stop()
    }
    
    override fun setSpeed(speed: Float) {
        speechSpeed = speed.coerceIn(0.1f, 4.0f)
        tts?.setSpeechRate(speechSpeed)
    }
    
    override fun setPitch(pitch: Float) {
        speechPitch = pitch.coerceIn(0.1f, 2.0f)
        tts?.setPitch(speechPitch)
    }
    
    override fun getProviderName(): String = "Android TextToSpeech"
    
    override fun isReady(): Boolean = isInitialized
    
    override fun release() {
        tts?.stop()
        tts?.shutdown()
        tts = null
        isInitialized = false
    }
}
