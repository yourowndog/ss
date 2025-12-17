package com.screensense.capture

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Wrapper for Android's MediaProjection API to capture screen content.
 */
class ScreenCapture(private val context: Context) {
    
    companion object {
        private const val TAG = "ScreenCapture"
        const val REQUEST_CODE = 1001
    }
    
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    
    private var screenWidth: Int = 0
    private var screenHeight: Int = 0
    private var screenDensity: Int = 0
    
    init {
        val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getRealMetrics(metrics)
        screenWidth = metrics.widthPixels
        screenHeight = metrics.heightPixels
        screenDensity = metrics.densityDpi
    }
    
    /**
     * Initialize the media projection with the result from the permission activity.
     */
    fun initialize(resultCode: Int, data: Intent) {
        val projectionManager = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjection = projectionManager.getMediaProjection(resultCode, data)
        
        if (mediaProjection == null) {
            Log.e(TAG, "Failed to create media projection")
            return
        }
        
        Log.d(TAG, "MediaProjection initialized. Screen: ${screenWidth}x${screenHeight} @ ${screenDensity}dpi")
    }
    
    /**
     * Capture the current screen and return as a Bitmap.
     * @return The captured screen, or null if capture failed.
     */
    suspend fun capture(): Bitmap? = withContext(Dispatchers.IO) {
        if (mediaProjection == null) {
            Log.e(TAG, "MediaProjection not initialized")
            return@withContext null
        }
        
        try {
            // Create ImageReader
            imageReader = ImageReader.newInstance(
                screenWidth, 
                screenHeight, 
                PixelFormat.RGBA_8888, 
                2
            )
            
            // Create VirtualDisplay
            virtualDisplay = mediaProjection?.createVirtualDisplay(
                "ScreenSenseCapture",
                screenWidth,
                screenHeight,
                screenDensity,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader?.surface,
                null,
                null
            )
            
            // Wait a bit for the frame to be captured
            delay(150)
            
            // Get the latest image
            val image = imageReader?.acquireLatestImage()
            val bitmap = image?.let { imageToBitmap(it) }
            
            image?.close()
            
            // Clean up virtual display (but keep projection alive for next capture)
            virtualDisplay?.release()
            virtualDisplay = null
            imageReader?.close()
            imageReader = null
            
            return@withContext bitmap
            
        } catch (e: Exception) {
            Log.e(TAG, "Error capturing screen", e)
            return@withContext null
        }
    }
    
    private fun imageToBitmap(image: Image): Bitmap {
        val planes = image.planes
        val buffer = planes[0].buffer
        val pixelStride = planes[0].pixelStride
        val rowStride = planes[0].rowStride
        val rowPadding = rowStride - pixelStride * screenWidth
        
        val bitmap = Bitmap.createBitmap(
            screenWidth + rowPadding / pixelStride,
            screenHeight,
            Bitmap.Config.ARGB_8888
        )
        bitmap.copyPixelsFromBuffer(buffer)
        
        // Crop to actual screen size if there's padding
        return if (rowPadding > 0) {
            Bitmap.createBitmap(bitmap, 0, 0, screenWidth, screenHeight)
        } else {
            bitmap
        }
    }
    
    /**
     * Check if the screen capture is ready to use.
     */
    fun isReady(): Boolean = mediaProjection != null
    
    /**
     * Release all resources.
     */
    fun release() {
        virtualDisplay?.release()
        virtualDisplay = null
        imageReader?.close()
        imageReader = null
        mediaProjection?.stop()
        mediaProjection = null
    }
}
