package com.screensense.capture

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
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager

/**
 * Screen capture using MediaProjection.
 * 
 * IMPORTANT: On Android 14+, the virtual display MUST be created immediately
 * after getting the MediaProjection. The token cannot be stored for later use.
 * 
 * Flow:
 * 1. User grants permission -> get MediaProjection
 * 2. IMMEDIATELY create VirtualDisplay and start capturing
 * 3. capture() just reads from the already-running stream
 */
class ScreenCapture(private val context: Context) {
    
    companion object {
        private const val TAG = "ScreenCapture"
        const val REQUEST_CODE = 1001
    }
    
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    
    private var screenWidth: Int = 0
    private var screenHeight: Int = 0
    private var screenDensity: Int = 0
    
    @Volatile
    private var isSessionActive = false
    
    // Callback required for Android 14+
    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            Log.d(TAG, "MediaProjection stopped by system - session ended")
            isSessionActive = false
            cleanupCapture()
        }
    }
    
    init {
        val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getRealMetrics(metrics)
        screenWidth = metrics.widthPixels
        screenHeight = metrics.heightPixels
        screenDensity = metrics.densityDpi
        Log.d(TAG, "Screen: ${screenWidth}x${screenHeight} @ ${screenDensity}dpi")
    }
    
    /**
     * Initialize AND START the capture session immediately.
     * On Android 14+, we MUST create the VirtualDisplay right away.
     * 
     * @return true if session started successfully
     */
    fun initializeAndStart(resultCode: Int, data: Intent): Boolean {
        Log.d(TAG, "initializeAndStart called")
        
        // Clean up any existing session
        release()
        
        val projectionManager = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjection = projectionManager.getMediaProjection(resultCode, data)
        
        if (mediaProjection == null) {
            Log.e(TAG, "Failed to get MediaProjection")
            return false
        }
        
        Log.d(TAG, "Got MediaProjection, registering callback...")
        
        // Register callback (required for Android 14+) - BEFORE createVirtualDisplay
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            mediaProjection?.registerCallback(projectionCallback, mainHandler)
        }
        
        // IMMEDIATELY create the ImageReader and VirtualDisplay
        Log.d(TAG, "Creating ImageReader and VirtualDisplay IMMEDIATELY...")
        
        try {
            imageReader = ImageReader.newInstance(
                screenWidth,
                screenHeight,
                PixelFormat.RGBA_8888,
                2
            )
            
            virtualDisplay = mediaProjection?.createVirtualDisplay(
                "ScreenSenseCapture",
                screenWidth,
                screenHeight,
                screenDensity,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader?.surface,
                object : VirtualDisplay.Callback() {
                    override fun onStopped() {
                        Log.d(TAG, "VirtualDisplay stopped")
                    }
                },
                mainHandler
            )
            
            if (virtualDisplay == null) {
                Log.e(TAG, "Failed to create VirtualDisplay")
                release()
                return false
            }
            
            isSessionActive = true
            Log.d(TAG, "Capture session started successfully!")
            return true
            
        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException creating VirtualDisplay", e)
            release()
            return false
        } catch (e: Exception) {
            Log.e(TAG, "Exception creating VirtualDisplay", e)
            release()
            return false
        }
    }
    
    /**
     * Capture a frame from the already-running stream.
     * The VirtualDisplay was created in initializeAndStart().
     */
    fun captureFrame(): Bitmap? {
        if (!isSessionActive) {
            Log.e(TAG, "No active capture session")
            return null
        }
        
        if (imageReader == null) {
            Log.e(TAG, "ImageReader is null")
            return null
        }
        
        try {
            // Give a moment for a fresh frame
            Thread.sleep(100)
            
            val image = imageReader?.acquireLatestImage()
            if (image == null) {
                Log.w(TAG, "No image available, retrying...")
                Thread.sleep(100)
                val retryImage = imageReader?.acquireLatestImage()
                if (retryImage == null) {
                    Log.e(TAG, "Still no image available")
                    return null
                }
                val bitmap = imageToBitmap(retryImage)
                retryImage.close()
                return bitmap
            }
            
            val bitmap = imageToBitmap(image)
            image.close()
            
            Log.d(TAG, "Captured frame: ${bitmap.width}x${bitmap.height}")
            return bitmap
            
        } catch (e: Exception) {
            Log.e(TAG, "Error capturing frame", e)
            return null
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
     * Check if there's an active capture session ready for frame capture.
     */
    fun isReady(): Boolean = isSessionActive && mediaProjection != null && virtualDisplay != null
    
    private fun cleanupCapture() {
        virtualDisplay?.release()
        virtualDisplay = null
        imageReader?.close()
        imageReader = null
    }
    
    /**
     * Release all resources and end the capture session.
     */
    fun release() {
        Log.d(TAG, "Releasing all resources")
        isSessionActive = false
        
        cleanupCapture()
        
        val projection = mediaProjection
        if (projection != null) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                try {
                    projection.unregisterCallback(projectionCallback)
                } catch (e: Exception) {
                    // Ignore
                }
            }
            try {
                projection.stop()
            } catch (e: Exception) {
                // Ignore
            }
        }
        mediaProjection = null
    }
}
