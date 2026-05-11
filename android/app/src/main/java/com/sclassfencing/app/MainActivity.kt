package com.sclassfencing.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.sclassfencing.app.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val handler = Handler(Looper.getMainLooper())
    private val statusPoller = object : Runnable {
        override fun run() {
            updateSDKStatus()
            if (!DJIApplication.isSDKRegistered && DJIApplication.sdkInitError == null && !DJIApplication.isDeviceConnected) {
                handler.postDelayed(this, 1500)
            }
        }
    }

    private val requiredPermissions = arrayOf(
        Manifest.permission.BLUETOOTH_SCAN,
        Manifest.permission.BLUETOOTH_CONNECT,
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.CAMERA,
        Manifest.permission.RECORD_AUDIO
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupUI()
        checkAndRequestPermissions()
    }

    override fun onResume() {
        super.onResume()
        updateSDKStatus()
        handler.post(statusPoller)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(statusPoller)
    }

    private fun setupUI() {
        binding.btnGimbalTracking.setOnClickListener {
            if (!DJIApplication.isSDKRegistered) {
                Toast.makeText(this, "DJI SDK not registered yet. Please wait.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            startActivity(Intent(this, GimbalTrackingActivity::class.java))
        }

        binding.btnTournament.setOnClickListener {
            startActivity(Intent(this, TournamentActivity::class.java))
        }
    }

    private fun updateSDKStatus() {
        val error = DJIApplication.sdkInitError
        when {
            DJIApplication.isSDKRegistered && DJIApplication.isDeviceConnected -> {
                binding.tvSdkStatus.text = "Connected: ${DJIApplication.connectedModelName}"
                binding.tvSdkStatus.setTextColor(getColor(R.color.green))
                binding.btnGimbalTracking.isEnabled = true
            }
            DJIApplication.isSDKRegistered -> {
                binding.tvSdkStatus.text = "SDK Ready – No device paired"
                binding.tvSdkStatus.setTextColor(getColor(R.color.yellow_dark))
                binding.btnGimbalTracking.isEnabled = true
            }
            error != null -> {
                binding.tvSdkStatus.text = "SDK Error: $error"
                binding.tvSdkStatus.setTextColor(getColor(R.color.red))
                binding.btnGimbalTracking.isEnabled = false
            }
            else -> {
                binding.tvSdkStatus.text = "Registering DJI SDK…"
                binding.tvSdkStatus.setTextColor(getColor(R.color.yellow_dark))
                binding.btnGimbalTracking.isEnabled = false
            }
        }
    }

    private fun checkAndRequestPermissions() {
        val missing = requiredPermissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, missing.toTypedArray(), REQUEST_PERMISSIONS)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_PERMISSIONS) {
            val denied = permissions.zip(grantResults.toList())
                .filter { it.second != PackageManager.PERMISSION_GRANTED }
                .map { it.first }
            if (denied.isNotEmpty()) {
                Toast.makeText(
                    this,
                    "Some permissions denied. Bluetooth/location required for DJI Osmo Mobile.",
                    Toast.LENGTH_LONG
                ).show()
            }
            updateSDKStatus()
        }
    }

    companion object {
        private const val REQUEST_PERMISSIONS = 100
    }
}
