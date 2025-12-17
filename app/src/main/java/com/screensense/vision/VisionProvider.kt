package com.screensense.vision

import android.graphics.Bitmap

/**
 * Abstract interface for vision analysis providers.
 * Implementations can use ML Kit, cloud APIs, or local LLMs.
 */
interface VisionProvider {
    /**
     * Analyze an image and extract text/understanding from it.
     * @param bitmap The screen capture to analyze
     * @return VisionResult containing extracted information
     */
    suspend fun analyze(bitmap: Bitmap): VisionResult
    
    /**
     * Get the display name of this provider.
     */
    fun getProviderName(): String
    
    /**
     * Release any resources held by this provider.
     */
    fun release()
}

/**
 * Result of vision analysis.
 */
data class VisionResult(
    /** Extracted or generated text content */
    val text: String,
    
    /** Optional AI-generated summary of the screen */
    val summary: String? = null,
    
    /** Detected objects/labels in the image */
    val labels: List<String> = emptyList(),
    
    /** Confidence score (0.0 to 1.0) */
    val confidence: Float = 1.0f,
    
    /** Whether any content was found */
    val hasContent: Boolean = text.isNotBlank() || labels.isNotEmpty()
)
