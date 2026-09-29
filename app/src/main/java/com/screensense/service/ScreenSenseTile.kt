package com.screensense.service

import android.annotation.SuppressLint
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.util.Log

/**
 * Quick Settings tile for triggering screen reading with one tap.
 * Users can add this tile to their Quick Settings panel.
 */
class ScreenSenseTile : TileService() {
    
    companion object {
        private const val TAG = "ScreenSenseTile"
    }
    
    override fun onStartListening() {
        super.onStartListening()
        updateTile()
    }
    
    override fun onTileAdded() {
        super.onTileAdded()
        Log.d(TAG, "Tile added to Quick Settings")
        updateTile()
    }
    
    override fun onTileRemoved() {
        super.onTileRemoved()
        Log.d(TAG, "Tile removed from Quick Settings")
    }
    
    override fun onClick() {
        super.onClick()
        Log.d(TAG, "Tile clicked - triggering screen read")
        
        // Collapse the Quick Settings panel
        try {
            @Suppress("DEPRECATION")
            collapseQuickSettingsPanel()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to collapse panel", e)
        }
        
        // Trigger the screen read
        val triggerIntent = android.content.Intent(this, ScreenSenseService::class.java).apply {
            action = ScreenSenseService.ACTION_CAPTURE_FRAME
        }
        startService(triggerIntent)
    }
    
    @Suppress("DEPRECATION")
    @SuppressLint("WrongConstant", "PrivateApi")
    private fun collapseQuickSettingsPanel() {
        // Use reflection to collapse the panel on newer Android versions
        try {
            val statusBarService = getSystemService("statusbar")
            val statusBarManager = Class.forName("android.app.StatusBarManager")
            val collapse = statusBarManager.getMethod("collapsePanels")
            collapse.invoke(statusBarService)
        } catch (e: Exception) {
            // Fallback: just proceed without collapsing
        }
    }
    
    private fun updateTile() {
        qsTile?.let { tile ->
            tile.state = Tile.STATE_INACTIVE
            tile.label = getString(com.screensense.R.string.tile_label)
            tile.updateTile()
        }
    }
}
