package com.screensense.vision

import android.content.Context
import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

/**
 * Vision provider implementation using Google ML Kit.
 * Uses Text Recognition for OCR - 100% on-device, 100% free.
 */
class MLKitVisionProvider(context: Context) : VisionProvider {
    
    private val textRecognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    
    override suspend fun analyze(bitmap: Bitmap): VisionResult = withContext(Dispatchers.IO) {
        val inputImage = InputImage.fromBitmap(bitmap, 0)
        
        val extractedText = runTextRecognition(inputImage)
        
        VisionResult(
            text = extractedText,
            confidence = if (extractedText.isNotBlank()) 0.9f else 0.5f
        )
    }
    
    private suspend fun runTextRecognition(image: InputImage): String = suspendCoroutine { cont ->
        textRecognizer.process(image)
            .addOnSuccessListener { result ->
                cont.resume(result.text)
            }
            .addOnFailureListener {
                cont.resume("")
            }
    }
    
    override fun getProviderName(): String = "Google ML Kit (On-Device OCR)"
    
    override fun release() {
        textRecognizer.close()
    }
}
