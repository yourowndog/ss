package com.screensense.service

import android.content.ClipboardManager
import android.content.Context
import android.graphics.drawable.Icon
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.util.Log
import com.screensense.R
import com.screensense.tts.OpenAITTSProvider
import com.screensense.tts.TTSProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Quick Settings Tile that reads clipboard contents aloud.
 * 
 * Pull down notification shade → tap tile → speaks clipboard.
 * Tap while speaking → stops speech.
 */
class ReadClipboardTile : TileService() {
    
    companion object {
        private const val TAG = "ReadClipboardTile"
    }
    
    private var ttsProvider: TTSProvider? = null
    private var isSpeaking = false
    private var speakJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.Main)
    
    override fun onStartListening() {
        super.onStartListening()
        updateTile()
    }
    
    override fun onTileAdded() {
        super.onTileAdded()
        Log.d(TAG, "Tile added to Quick Settings")
    }
    
    override fun onClick() {
        super.onClick()
        
        if (isSpeaking) {
            // Stop speaking
            stopSpeaking()
        } else {
            // Read clipboard
            readClipboard()
        }
    }
    
    private fun readClipboard() {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clipData = clipboard.primaryClip
        
        if (clipData == null || clipData.itemCount == 0) {
            Log.d(TAG, "Clipboard is empty")
            return
        }
        
        val text = clipData.getItemAt(0).coerceToText(this).toString()
        if (text.isBlank()) {
            Log.d(TAG, "Clipboard text is blank")
            return
        }
        
        Log.d(TAG, "Reading ${text.length} clipboard characters")
        
        // Initialize TTS if needed
        if (ttsProvider == null) {
            ttsProvider = OpenAITTSProvider(applicationContext)
        }
        
        if (ttsProvider?.isReady() != true) {
            Log.e(TAG, "TTS not ready")
            return
        }
        
        isSpeaking = true
        updateTile()
        
        speakJob = scope.launch {
            try {
                ttsProvider?.speak(text)
            } finally {
                isSpeaking = false
                updateTile()
            }
        }
    }
    
    private fun stopSpeaking() {
        speakJob?.cancel()
        ttsProvider?.stop()
        isSpeaking = false
        updateTile()
    }
    
    private fun updateTile() {
        qsTile?.let { tile ->
            if (isSpeaking) {
                tile.state = Tile.STATE_ACTIVE
                tile.label = "Stop Reading"
                tile.contentDescription = "Tap to stop speaking"
            } else {
                tile.state = Tile.STATE_INACTIVE
                tile.label = "Read Clipboard"
                tile.contentDescription = "Tap to read clipboard aloud"
            }
            tile.updateTile()
        }
    }
    
    override fun onDestroy() {
        stopSpeaking()
        ttsProvider?.release()
        ttsProvider = null
        super.onDestroy()
    }
}
