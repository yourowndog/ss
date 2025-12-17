package com.screensense.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.util.Log
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent

/**
 * Accessibility Service that can trigger screen reading via:
 * - Volume button combos
 * - Accessibility button in navigation bar
 * - System gestures
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
    
    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        
        Log.d(TAG, "Accessibility Service connected")
        
        // Configure the service
        serviceInfo = serviceInfo.apply {
            flags = flags or AccessibilityServiceInfo.FLAG_REQUEST_ACCESSIBILITY_BUTTON
        }
    }
    
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // We don't need to process accessibility events for our use case
        // This could be extended to auto-read specific apps or notifications
    }
    
    override fun onInterrupt() {
        Log.d(TAG, "Accessibility Service interrupted")
    }
    
    /**
     * Note: onAccessibilityButtonClicked is deprecated.
     * Use AccessibilityButtonController callback for API 26+.
     * For now, this method is removed - button click handled via service config.
     */
    private fun triggerFromAccessibilityButton() {
        Log.d(TAG, "Accessibility button triggered")
        triggerScreenRead()
    }
    
    /**
     * Detect volume button combos (Volume Down + Volume Up simultaneously).
     */
    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (event.action != KeyEvent.ACTION_DOWN) {
            return super.onKeyEvent(event)
        }
        
        val currentTime = System.currentTimeMillis()
        
        when (event.keyCode) {
            KeyEvent.KEYCODE_VOLUME_DOWN -> {
                lastVolumeDownTime = currentTime
                checkVolumeCombo(currentTime)
            }
            KeyEvent.KEYCODE_VOLUME_UP -> {
                lastVolumeUpTime = currentTime
                checkVolumeCombo(currentTime)
            }
        }
        
        return super.onKeyEvent(event)
    }
    
    private fun checkVolumeCombo(currentTime: Long) {
        // Check if both volume buttons were pressed within the timeout
        val downDelta = currentTime - lastVolumeDownTime
        val upDelta = currentTime - lastVolumeUpTime
        
        if (downDelta < VOLUME_COMBO_TIMEOUT_MS && upDelta < VOLUME_COMBO_TIMEOUT_MS) {
            Log.d(TAG, "Volume combo detected!")
            triggerScreenRead()
            
            // Reset to prevent repeated triggers
            lastVolumeDownTime = 0L
            lastVolumeUpTime = 0L
        }
    }
    
    /**
     * Trigger screen reading via the main service.
     */
    private fun triggerScreenRead() {
        val intent = ScreenSenseService.createTriggerIntent(this)
        startService(intent)
    }
    
    /**
     * Read screen content directly from the accessibility tree.
     * This is an alternative to screenshot-based OCR that's faster for text.
     * 
     * @return All text content visible on screen
     */
    fun readScreenContentFromAccessibilityTree(): String {
        val rootNode = rootInActiveWindow ?: return ""
        
        val textContent = StringBuilder()
        extractTextFromNode(rootNode, textContent)
        rootNode.recycle()
        
        return textContent.toString().trim()
    }
    
    private fun extractTextFromNode(
        node: android.view.accessibility.AccessibilityNodeInfo,
        builder: StringBuilder
    ) {
        // Get text from this node
        node.text?.let { text ->
            if (text.isNotBlank()) {
                builder.append(text).append(" ")
            }
        }
        
        // Get content description if no text
        if (node.text.isNullOrBlank()) {
            node.contentDescription?.let { desc ->
                if (desc.isNotBlank()) {
                    builder.append(desc).append(" ")
                }
            }
        }
        
        // Recurse into children
        for (i in 0 until node.childCount) {
            val child = node.getChild(i)
            if (child != null) {
                extractTextFromNode(child, builder)
                child.recycle()
            }
        }
    }
    
    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }
}
