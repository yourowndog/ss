package com.screensense.service

import android.accessibilityservice.AccessibilityButtonController
import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.graphics.Bitmap
import android.view.Display
import androidx.core.content.ContextCompat
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume
import android.os.Build
import android.util.Log
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent

/**
 * Accessibility Service that can trigger screen reading via:
 * - Accessibility button (floating button or nav bar button)
 * - Volume button combos (Volume Down then Volume Up)
 * 
 * This service can also directly read screen content via the accessibility tree,
 * which is faster than screenshot-based OCR for text-heavy screens.
 */
class ScreenSenseAccessibilityService : AccessibilityService() {
    
    companion object {
        private const val TAG = "ScreenSenseA11y"
        
        // Volume key combo detection
        private const val VOLUME_COMBO_TIMEOUT_MS = 500L
        
        @Volatile
        var instance: ScreenSenseAccessibilityService? = null
            private set
    }
    
    private var lastVolumeDownTime = 0L
    private var lastVolumeUpTime = 0L
    
    // Accessibility button callback for API 26+
    private var accessibilityButtonCallback: AccessibilityButtonController.AccessibilityButtonCallback? = null
    
    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        
        Log.d(TAG, "Accessibility Service connected")
        
        // Configure the service
        serviceInfo = serviceInfo.apply {
            flags = flags or AccessibilityServiceInfo.FLAG_REQUEST_ACCESSIBILITY_BUTTON or
                AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS
        }
        
        // Android rebinds enabled accessibility services after process death/reboot.
        try {
            ContextCompat.startForegroundService(this, android.content.Intent(this, ScreenSenseService::class.java).apply {
                action = ScreenSenseService.ACTION_START_TTS_HELPER
            })
        } catch (e: Exception) {
            Log.e(TAG, "Reader startup failed", e)
        }

        // Register accessibility button callback for API 26+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val controller = accessibilityButtonController
            accessibilityButtonCallback = object : AccessibilityButtonController.AccessibilityButtonCallback() {
                override fun onClicked(controller: AccessibilityButtonController) {
                    Log.d(TAG, "Accessibility button clicked!")
                    triggerScreenRead()
                }

                override fun onAvailabilityChanged(controller: AccessibilityButtonController, available: Boolean) {
                    Log.d(TAG, "Accessibility button availability changed: $available")
                }
            }
            controller.registerAccessibilityButtonCallback(accessibilityButtonCallback!!)
            Log.d(TAG, "Registered accessibility button callback")
        }
    }
    
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // We don't need to process accessibility events for our use case
        // This could be extended to auto-read specific apps or notifications
    }
    
    override fun onInterrupt() {
        Log.d(TAG, "Accessibility Service interrupted")
    }
    
    override fun onDestroy() {
        super.onDestroy()
        instance = null
        
        // Unregister callback
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && accessibilityButtonCallback != null) {
            accessibilityButtonController.unregisterAccessibilityButtonCallback(accessibilityButtonCallback!!)
        }
        
        Log.d(TAG, "Accessibility Service destroyed")
    }
    
    /**
     * Detect volume button combos (Volume Down then Volume Up quickly).
     */
    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (event.action != KeyEvent.ACTION_DOWN || event.repeatCount != 0) {
            return super.onKeyEvent(event)
        }
        
        val now = System.currentTimeMillis()
        
        when (event.keyCode) {
            KeyEvent.KEYCODE_VOLUME_DOWN -> {
                lastVolumeDownTime = now
                
                // Check if Volume Up was pressed recently
                if (now - lastVolumeUpTime < VOLUME_COMBO_TIMEOUT_MS) {
                    Log.d(TAG, "Volume combo detected (Up then Down)")
                    triggerScreenRead()
                    return true // Consume the event
                }
            }
            
            KeyEvent.KEYCODE_VOLUME_UP -> {
                lastVolumeUpTime = now
                
                // Check if Volume Down was pressed recently
                if (now - lastVolumeDownTime < VOLUME_COMBO_TIMEOUT_MS) {
                    Log.d(TAG, "Volume combo detected (Down then Up)")
                    triggerScreenRead()
                    return true // Consume the event
                }
            }
        }
        
        return super.onKeyEvent(event)
    }
    
    /** One on-demand screenshot using accessibility, not a screen-sharing session. */
    suspend fun captureScreenshot(): Bitmap? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        return withTimeoutOrNull(5000L) {
            suspendCancellableCoroutine { continuation ->
                takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor, object : TakeScreenshotCallback {
                    override fun onSuccess(result: ScreenshotResult) {
                        val buffer = result.hardwareBuffer
                        val bitmap = try {
                            val hardware = Bitmap.wrapHardwareBuffer(buffer, result.colorSpace)
                            try { hardware?.copy(Bitmap.Config.ARGB_8888, false) }
                            finally { hardware?.recycle() }
                        } finally { buffer.close() }
                        if (continuation.isActive) continuation.resume(bitmap) else bitmap?.recycle()
                    }
                    override fun onFailure(errorCode: Int) {
                        Log.w(TAG, "Accessibility screenshot failed: $errorCode")
                        if (continuation.isActive) continuation.resume(null)
                    }
                })
            }
        }
    }

    private fun resetVolumeCombo() {
        lastVolumeDownTime = 0L
        lastVolumeUpTime = 0L
    }
    
    /**
     * Trigger screen reading via the main service.
     */
    private fun triggerScreenRead() {
        Log.d(TAG, "Triggering screen read toggle...")
        
        val intent = android.content.Intent(this, ScreenSenseService::class.java).apply {
            action = ScreenSenseService.ACTION_TOGGLE_READ
        }
        
        try {
            ContextCompat.startForegroundService(this, intent)
            Log.d(TAG, "Sent ACTION_TOGGLE_READ to service")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start service", e)
        }
        
        resetVolumeCombo()
    }
    
    /**
     * Read screen content directly from the accessibility tree.
     * PRIORITY: Real text content (paragraphs, articles) over UI labels.
     * 
     * @return All text content visible on screen
     */
    fun readScreenContentFromAccessibilityTree(): String {
        val rootNode = rootInActiveWindow ?: return ""
        
        val textNodes = mutableListOf<Pair<String, android.graphics.Rect>>()
        collectTextNodes(rootNode, textNodes, 0)
        rootNode.recycle()
        
        // Sort by visual position: Top-to-Bottom, then Left-to-Right
        textNodes.sortWith(Comparator { a, b ->
            val rectA = a.second
            val rectB = b.second
            
            if (kotlin.math.abs(rectA.top - rectB.top) > 40) {
                rectA.top - rectB.top
            } else {
                rectA.left - rectB.left
            }
        })
        
        val result = textNodes.joinToString(" ") { it.first }
        Log.d(TAG, "Collected ${textNodes.size} text nodes, total ${result.length} chars")
        return result
    }
    
    // Short labels that are almost always UI noise, not content
    private val IGNORED_LABELS = setOf(
        "back", "navigate up", "close", "search", "send", "attach", "emoji", 
        "voice message", "camera", "settings", "profile", "menu", "more options",
        "share", "copy", "paste", "cut", "select all", "home", "tab", "notifications",
        "double tap to activate", "double-tap to activate", "unlabeled"
    )

    private fun collectTextNodes(
        node: android.view.accessibility.AccessibilityNodeInfo,
        nodes: MutableList<Pair<String, android.graphics.Rect>>,
        depth: Int
    ) {
        if (!node.isVisibleToUser || node.isPassword || depth > 80) return
        
        val nodeText = node.text?.toString()?.trim()
        val nodeDesc = node.contentDescription?.toString()?.trim()
        val isClickable = node.isClickable
        
        // PRIORITY 1: node.text - This is the actual displayed text (paragraphs, articles, etc.)
        // This is what we WANT for reading content.
        if (!nodeText.isNullOrBlank()) {
            // Skip very short text on clickable elements (buttons like "OK", "Cancel")
            val isButton = isClickable && nodeText.length < 30
            
            if (!isButton && !isSymbolNoise(nodeText)) {
                val rect = android.graphics.Rect()
                node.getBoundsInScreen(rect)
                nodes.add(nodeText to rect)
            }
        }
        // PRIORITY 2: contentDescription - ONLY for non-clickable, long descriptions
        // Short contentDescriptions are almost always button labels ("Back", "Search")
        else if (!nodeDesc.isNullOrBlank()) {
            val lowerDesc = nodeDesc.lowercase()
            val isIgnoredLabel = IGNORED_LABELS.any { lowerDesc == it || lowerDesc.contains(it) }
            
            // Only use contentDescription if it's long enough to be actual content
            // AND it's not a known UI label AND the node isn't clickable (buttons)
            if (!isIgnoredLabel && !isClickable && nodeDesc.length > 50) {
                val rect = android.graphics.Rect()
                node.getBoundsInScreen(rect)
                nodes.add(nodeDesc to rect)
            }
        }
        
        // Recurse into children
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            collectTextNodes(child, nodes, depth + 1)
            child.recycle()
        }
    }
    
    /**
     * Check if text is purely presentational symbols (bullets, ellipsis, etc.)
     */
    private fun isSymbolNoise(text: String): Boolean {
        return text.matches(Regex("^[•·|…\\-–—]+$")) || text == "..."
    }
}
