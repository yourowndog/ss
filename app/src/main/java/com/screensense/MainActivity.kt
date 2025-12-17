package com.screensense

import android.Manifest
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.provider.Settings
import android.util.Log
import android.view.accessibility.AccessibilityManager
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.screensense.capture.ScreenCapture
import com.screensense.databinding.ActivityMainBinding
import com.screensense.service.ScreenSenseAccessibilityService
import com.screensense.service.ScreenSenseService

/**
 * Main activity for setting up ScreenSense.
 * Handles permission requests and provides a test trigger.
 */
class MainActivity : AppCompatActivity() {
    
    companion object {
        private const val TAG = "MainActivity"
    }
    
    private lateinit var binding: ActivityMainBinding
    private var screenSenseService: ScreenSenseService? = null
    private var isServiceBound = false
    
    // MediaProjection permission result
    private var mediaProjectionResultCode: Int = Activity.RESULT_CANCELED
    private var mediaProjectionResultData: Intent? = null
    
    private val mediaProjectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            Log.d(TAG, "MediaProjection permission granted")
            mediaProjectionResultCode = result.resultCode
            mediaProjectionResultData = result.data
            
            // Start and initialize the service with projection
            startScreenSenseService()
            initializeMediaProjection()
            
            updateUI()
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
            
            // Initialize projection if we have it
            if (mediaProjectionResultData != null) {
                initializeMediaProjection()
            }
            
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
        
        setupClickListeners()
        requestNotificationPermission()
    }
    
    override fun onResume() {
        super.onResume()
        updateUI()
        
        // Try to bind to existing service
        bindToService()
    }
    
    override fun onPause() {
        super.onPause()
        if (isServiceBound) {
            unbindService(serviceConnection)
            isServiceBound = false
        }
    }
    
    private fun setupClickListeners() {
        binding.btnGrantScreenCapture.setOnClickListener {
            requestMediaProjectionPermission()
        }
        
        binding.btnEnableAccessibility.setOnClickListener {
            openAccessibilitySettings()
        }
        
        binding.btnTestRead.setOnClickListener {
            triggerTestRead()
        }
    }
    
    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) 
                != PackageManager.PERMISSION_GRANTED) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }
    
    private fun requestMediaProjectionPermission() {
        val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjectionLauncher.launch(projectionManager.createScreenCaptureIntent())
    }
    
    private fun startScreenSenseService() {
        val intent = Intent(this, ScreenSenseService::class.java).apply {
            action = ScreenSenseService.ACTION_START
        }
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        
        bindToService()
    }
    
    private fun bindToService() {
        val intent = Intent(this, ScreenSenseService::class.java)
        bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
    }
    
    private fun initializeMediaProjection() {
        mediaProjectionResultData?.let { data ->
            val intent = Intent(this, ScreenSenseService::class.java).apply {
                action = ScreenSenseService.ACTION_INIT_PROJECTION
                putExtra(ScreenSenseService.EXTRA_RESULT_CODE, mediaProjectionResultCode)
                putExtra(ScreenSenseService.EXTRA_RESULT_DATA, data)
            }
            startService(intent)
        }
    }
    
    private fun openAccessibilitySettings() {
        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
        startActivity(intent)
    }
    
    private fun triggerTestRead() {
        if (screenSenseService?.isScreenCaptureReady() == true) {
            screenSenseService?.triggerScreenRead()
        } else {
            // Need to grant permission first
            requestMediaProjectionPermission()
        }
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
    
    private fun updateUI() {
        // Update screen capture status
        val isScreenCaptureReady = screenSenseService?.isScreenCaptureReady() == true
        binding.screenCaptureStatus.text = if (isScreenCaptureReady) {
            getString(R.string.status_granted)
        } else {
            getString(R.string.status_not_granted)
        }
        binding.screenCaptureStatus.setTextColor(
            ContextCompat.getColor(this, if (isScreenCaptureReady) R.color.success else R.color.warning)
        )
        binding.btnGrantScreenCapture.isEnabled = !isScreenCaptureReady
        
        // Update accessibility status
        val isAccessibilityEnabled = isAccessibilityServiceEnabled()
        binding.accessibilityStatus.text = if (isAccessibilityEnabled) {
            getString(R.string.status_enabled)
        } else {
            getString(R.string.status_disabled)
        }
        binding.accessibilityStatus.setTextColor(
            ContextCompat.getColor(this, if (isAccessibilityEnabled) R.color.success else R.color.warning)
        )
        
        // Update test button
        binding.btnTestRead.isEnabled = isScreenCaptureReady
    }
}
