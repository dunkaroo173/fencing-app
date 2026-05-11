package com.sclassfencing.app

import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.sclassfencing.app.databinding.ActivityGimbalTrackingBinding

/**
 * Controls DJI Osmo Mobile gimbal for tracking fencers during bouts.
 *
 * Landscape orientation – left half shows live gimbal status, right half
 * has tracking controls and the current match overlay.
 */
class GimbalTrackingActivity : AppCompatActivity() {

    private lateinit var binding: ActivityGimbalTrackingBinding

    private var isTracking = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityGimbalTrackingBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupControls()
        updateConnectionStatus()
    }

    private fun setupControls() {
        binding.btnStartTracking.setOnClickListener {
            if (isTracking) stopTracking() else startTracking()
        }

        binding.btnGimbalCenter.setOnClickListener {
            centerGimbal()
        }

        binding.btnGimbalUp.setOnClickListener { adjustGimbal(pitch = 10f) }
        binding.btnGimbalDown.setOnClickListener { adjustGimbal(pitch = -10f) }
        binding.btnGimbalLeft.setOnClickListener { adjustGimbal(yaw = -10f) }
        binding.btnGimbalRight.setOnClickListener { adjustGimbal(yaw = 10f) }

        binding.btnBack.setOnClickListener { finish() }
    }

    private fun updateConnectionStatus() {
        if (DJIApplication.isDeviceConnected) {
            binding.tvConnectionStatus.text = "Connected: ${DJIApplication.connectedModelName}"
            binding.layoutControls.visibility = View.VISIBLE
            binding.tvNoDevice.visibility = View.GONE
        } else {
            binding.tvConnectionStatus.text = "No DJI device connected"
            binding.layoutControls.visibility = View.GONE
            binding.tvNoDevice.visibility = View.VISIBLE
        }
    }

    /**
     * Starts ActiveTrack on the Osmo Mobile for fencer tracking.
     * DJI MSDK V5: use TrackingManager or the Perception/Vision APIs for
     * subject tracking on supported Osmo Mobile models.
     */
    private fun startTracking() {
        isTracking = true
        binding.btnStartTracking.text = "Stop Tracking"
        binding.btnStartTracking.setBackgroundColor(getColor(R.color.red))
        binding.tvTrackingStatus.text = "TRACKING ACTIVE"
        binding.tvTrackingStatus.setTextColor(getColor(R.color.green))
        Log.i(TAG, "Tracking started")
        // TODO: Invoke DJI ActiveTrack/IntelligentTracking API
        // Example for MSDK V5 (Osmo Mobile 6):
        // KeyManager.getInstance().performAction(
        //     KeyTools.createKey(IntelligentKey.KeyStartIntelligentTracking), null)
    }

    private fun stopTracking() {
        isTracking = false
        binding.btnStartTracking.text = "Start Tracking"
        binding.btnStartTracking.setBackgroundColor(getColor(R.color.blue_primary))
        binding.tvTrackingStatus.text = "STANDBY"
        binding.tvTrackingStatus.setTextColor(getColor(R.color.yellow_dark))
        Log.i(TAG, "Tracking stopped")
    }

    /**
     * Centers the gimbal to the neutral position.
     */
    private fun centerGimbal() {
        // MSDK V5: Reset gimbal attitude to 0,0,0
        // KeyManager.getInstance().performAction(
        //     KeyTools.createKey(GimbalKey.KeyResetGimbal, 0), null)
        Toast.makeText(this, "Centering gimbal…", Toast.LENGTH_SHORT).show()
        Log.i(TAG, "Centering gimbal")
    }

    /**
     * Adjusts gimbal pitch/yaw by the given delta (degrees).
     */
    private fun adjustGimbal(pitch: Float = 0f, yaw: Float = 0f) {
        // MSDK V5 gimbal rotation via KeyManager:
        // val rotation = GimbalAngleRotation(mode=RELATIVE, pitch=pitch, yaw=yaw, roll=0f)
        // KeyManager.getInstance().setValue(
        //     KeyTools.createKey(GimbalKey.KeyRotation, 0), rotation, null)
        Log.d(TAG, "Gimbal adjust pitch=$pitch yaw=$yaw")
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isTracking) stopTracking()
    }

    companion object {
        private const val TAG = "GimbalTrackingActivity"
    }
}
