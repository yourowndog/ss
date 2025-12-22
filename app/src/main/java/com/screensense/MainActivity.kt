package com.screensense

import android.Manifest
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.provider.Settings
import android.util.Log
import android.view.View
import android.widget.Toast
import android.view.accessibility.AccessibilityManager
import android.os.Environment
import android.net.Uri
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.screensense.databinding.ActivityMainBinding
import com.screensense.service.ScreenSenseService
import com.screensense.tts.PiperTTSProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Main activity for setting up ScreenSense.
 * 
 * IMPORTANT: Android 14+ MediaProjection rules:
 * - Permission grant -> IMMEDIATELY start capture session
 * - Single action that does foreground + projection + VD creation
 * - Test button captures from the running stream
 */
class MainActivity : AppCompatActivity() {
    
    companion object {
        private const val TAG = "MainActivity"
        private const val PREFS_NAME = "screensense_prefs"
        private const val PREF_SPEAKER_ID = "speaker_id"
        private const val PREF_SPEECH_SPEED = "speech_speed"
        private const val PREF_USE_CLOUD = "use_cloud"
    }
    
    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: SharedPreferences
    private var screenSenseService: ScreenSenseService? = null
    private var isServiceBound = false
    private var currentSpeakerId = 0
    private var currentSpeed = 1.0f
    private var useCloud = false
    
    // Single launcher that does everything on permission grant
    private val mediaProjectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            Log.d(TAG, "MediaProjection permission granted - starting capture IMMEDIATELY")
            
            // Single intent that does everything: foreground + projection + VD creation
            val intent = Intent(this, ScreenSenseService::class.java).apply {
                action = ScreenSenseService.ACTION_START_PROJECTION
                putExtra(ScreenSenseService.EXTRA_RESULT_CODE, result.resultCode)
                putExtra(ScreenSenseService.EXTRA_RESULT_DATA, result.data)
            }
            
            // Start as foreground service
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
            
            // Bind to get reference
            bindToService()
            
            // Update UI after service has time to initialize
            binding.root.postDelayed({ updateUI() }, 1000)
        } else {
            Log.w(TAG, "MediaProjection permission denied")
        }
    }
    
    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        Log.d(TAG, "Notification permission: $isGranted")
    }
    
    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val localBinder = binder as ScreenSenseService.LocalBinder
            screenSenseService = localBinder.getService()
            isServiceBound = true
            Log.d(TAG, "Service bound")
            updateUI()
        }
        
        override fun onServiceDisconnected(name: ComponentName?) {
            screenSenseService = null
            isServiceBound = false
            Log.d(TAG, "Service unbound")
        }
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        loadVoiceSettings()
        setupClickListeners()
        setupVoiceSettingsListeners()
        requestNotificationPermission()
    }
    
    override fun onResume() {
        super.onResume()
        updateUI()
        bindToService()
    }
    
    override fun onPause() {
        super.onPause()
        if (isServiceBound) {
            try {
                unbindService(serviceConnection)
            } catch (e: Exception) {
                // Ignore
            }
            isServiceBound = false
        }
    }
    
    private fun setupClickListeners() {
        binding.btnGrantScreenCapture.setOnClickListener {
             // Request screen capture for Visual OCR fallback (RustDesk, images, etc.)
             requestMediaProjectionPermission()
        }
        
        binding.btnEnableAccessibility.setOnClickListener {
            openAccessibilitySettings()
        }
        
        binding.btnTestRead.setOnClickListener {
            if (isAccessibilityServiceEnabled()) {
                // Flash the button
                it.animate().alpha(0.5f).setDuration(100).withEndAction {
                    it.animate().alpha(1.0f).duration = 100
                }
                
                // Trigger read via service (Accessibility Mode)
                val intent = Intent(this, ScreenSenseService::class.java).apply {
                    action = ScreenSenseService.ACTION_TOGGLE_READ
                }
                startService(intent)
            } else {
                Toast.makeText(this, "Service not running (Enable Accessibility)", Toast.LENGTH_SHORT).show()
                openAccessibilitySettings()
            }
        }
        
        binding.btnTestVoice.setOnClickListener {
            triggerTestVoice()
        }

        binding.rgProvider.setOnCheckedChangeListener { _, checkedId ->
            useCloud = checkedId == R.id.rbCloud
            updateVoiceSliderRange()
            screenSenseService?.setUseCloud(useCloud)
            saveVoiceSettings()
            updateSpeakerLabel()
        }
        
        binding.btnGrantStorage.setOnClickListener {
            openStoragePermissionSettings()
        }
    }
    
    private fun updateVoiceSliderRange() {
        val max = if (useCloud) 5f else 10f
        binding.sliderSpeaker.valueTo = max
        if (currentSpeakerId > max) {
            currentSpeakerId = max.toInt()
            binding.sliderSpeaker.value = max
            screenSenseService?.setSpeaker(currentSpeakerId)
        }
    }
    
    private fun setupVoiceSettingsListeners() {
        // Provider setup
        binding.rbCloud.isChecked = useCloud
        binding.rbLocal.isChecked = !useCloud
        updateVoiceSliderRange()

        // Speaker slider
        binding.sliderSpeaker.value = currentSpeakerId.toFloat()
        updateSpeakerLabel()
        
        binding.sliderSpeaker.addOnChangeListener { _, value, fromUser ->
            if (fromUser) {
                currentSpeakerId = value.toInt()
                screenSenseService?.setSpeaker(currentSpeakerId)
                saveVoiceSettings()
                updateSpeakerLabel()
            }
        }
        
        // Speed slider
        binding.sliderSpeed.value = currentSpeed
        updateSpeedLabel()
        
        binding.sliderSpeed.addOnChangeListener { _, value, fromUser ->
            if (fromUser) {
                currentSpeed = value
                screenSenseService?.setSpeechSpeed(currentSpeed)
                saveVoiceSettings()
                updateSpeedLabel()
            }
        }
    }
    
    private fun updateSpeakerLabel() {
        val voiceName = screenSenseService?.getVoiceName(currentSpeakerId) ?: "Voice ${currentSpeakerId + 1}"
        binding.labelSpeaker.text = "Voice: $voiceName"
    }
    
    private fun updateSpeedLabel() {
        binding.labelSpeed.text = getString(R.string.voice_speed_label, currentSpeed)
    }
    
    private fun loadVoiceSettings() {
        currentSpeakerId = prefs.getInt(PREF_SPEAKER_ID, 1)
        currentSpeed = prefs.getFloat(PREF_SPEECH_SPEED, 1.0f)
        useCloud = prefs.getBoolean(PREF_USE_CLOUD, false)
        
        // Cap speaker ID based on provider
        val max = if (useCloud) 5 else 10
        currentSpeakerId = currentSpeakerId.coerceIn(0, max)
    }
    
    private fun saveVoiceSettings() {
        prefs.edit()
            .putInt(PREF_SPEAKER_ID, currentSpeakerId)
            .putFloat(PREF_SPEECH_SPEED, currentSpeed)
            .putBoolean(PREF_USE_CLOUD, useCloud)
            .apply()
    }
    
    private fun applyVoiceSettingsToService() {
        screenSenseService?.setUseCloud(useCloud)
        screenSenseService?.setSpeaker(currentSpeakerId)
        screenSenseService?.setSpeechSpeed(currentSpeed)
    }
    
    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) 
                != PackageManager.PERMISSION_GRANTED) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }
    
    // MediaProjection permission for Visual OCR fallback (RustDesk, image viewers, etc.)
    private fun requestMediaProjectionPermission() {
        val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjectionLauncher.launch(projectionManager.createScreenCaptureIntent())
    }
    
    private fun bindToService() {
        val intent = Intent(this, ScreenSenseService::class.java)
        try {
            bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to bind service", e)
        }
    }
    
    private fun openAccessibilitySettings() {
        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
        startActivity(intent)
    }
    
    private fun triggerTestRead() {
        // This function's logic has been moved into setupClickListeners for btnTestRead
        // and now directly interacts with the service via ACTION_TOGGLE_READ.
        // The MediaProjection permission logic is removed.
    }
    
    private fun triggerTestVoice() {
        // Cancel any ongoing speech first
        screenSenseService?.cancelScreenRead()
        
        // Speak test sample with voice name
        val voiceName = screenSenseService?.getVoiceName(currentSpeakerId) ?: "Voice ${currentSpeakerId + 1}"
        val sampleText = getString(R.string.test_voice_sample, voiceName, currentSpeed)
        screenSenseService?.speakText(sampleText)
    }
    
    private fun isAccessibilityServiceEnabled(): Boolean {
        val accessibilityManager = getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
        val enabledServices = accessibilityManager.getEnabledAccessibilityServiceList(
            AccessibilityServiceInfo.FEEDBACK_ALL_MASK
        )
        
        return enabledServices.any { 
            it.resolveInfo.serviceInfo.packageName == packageName 
        }
    }
    
    private fun isStoragePermissionGranted(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            true // Not needed on older versions
        }
    }
    
    private fun openStoragePermissionSettings() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                intent.data = Uri.parse("package:$packageName")
                startActivity(intent)
            } catch (e: Exception) {
                // Fallback for devices that don't support direct intent
                val intent = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                startActivity(intent)
            }
        }
    }
    
    private fun updateUI() {
        val isAccessibilityEnabled = isAccessibilityServiceEnabled()
        val isScreenCaptureReady = screenSenseService?.isScreenCaptureReady() == true
        
        // Screen Capture status (optional, for Visual OCR fallback)
        binding.screenCaptureStatus.text = if (isScreenCaptureReady) {
            getString(R.string.status_granted)
        } else {
            "Not Granted (Optional)"
        }
        binding.screenCaptureStatus.setTextColor(
            ContextCompat.getColor(this, if (isScreenCaptureReady) R.color.success else R.color.warning)
        )
        binding.btnGrantScreenCapture.isEnabled = !isScreenCaptureReady
        binding.btnGrantScreenCapture.visibility = View.VISIBLE // Always show for hybrid mode
        
        // Accessibility status (required)
        binding.accessibilityStatus.text = if (isAccessibilityEnabled) {
            getString(R.string.status_enabled)
        } else {
            getString(R.string.status_disabled)
        }
        binding.accessibilityStatus.setTextColor(
            ContextCompat.getColor(this, if (isAccessibilityEnabled) R.color.success else R.color.warning)
        )
        
        // Storage/Files Access status (required for local TTS)
        val isStorageGranted = isStoragePermissionGranted()
        binding.storageStatus.text = if (isStorageGranted) "Granted" else "Not Granted"
        binding.storageStatus.setTextColor(
            ContextCompat.getColor(this, if (isStorageGranted) R.color.success else R.color.warning)
        )
        binding.btnGrantStorage.isEnabled = !isStorageGranted
        
        // Test button enabled if accessibility is on (screen capture is optional fallback)
        binding.btnTestRead.isEnabled = isAccessibilityEnabled
        
        // Update voice settings labels
        updateSpeakerLabel()
        updateSpeedLabel()
}
}
