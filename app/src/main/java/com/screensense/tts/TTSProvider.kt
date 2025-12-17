package com.screensense.tts

/**
 * Abstract interface for text-to-speech providers.
 * Implementations can use Android TTS, Piper, or cloud services.
 */
interface TTSProvider {
    /**
     * Speak the given text aloud.
     * @param text The text to speak
     */
    suspend fun speak(text: String)
    
    /**
     * Stop any ongoing speech.
     */
    fun stop()
    
    /**
     * Set the speech rate.
     * @param speed Speech rate multiplier (1.0 = normal, 0.5 = half speed, 2.0 = double speed)
     */
    fun setSpeed(speed: Float)
    
    /**
     * Set the speech pitch.
     * @param pitch Pitch multiplier (1.0 = normal)
     */
    fun setPitch(pitch: Float)
    
    /**
     * Get the display name of this provider.
     */
    fun getProviderName(): String
    
    /**
     * Check if the provider is ready to speak.
     */
    fun isReady(): Boolean
    
    /**
     * Release any resources held by this provider.
     */
    fun release()
}
