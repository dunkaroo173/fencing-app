package com.sclassfencing.app

import android.app.Application
import android.content.Context
import android.util.Log
import androidx.multidex.MultiDex
import dji.v5.common.error.IDJIError
import dji.v5.common.register.DJISDKInitEvent
import dji.v5.manager.SDKManager
import dji.v5.manager.interfaces.SDKManagerCallback

class DJIApplication : Application() {

    companion object {
        private const val TAG = "DJIApplication"

        @Volatile
        var isSDKRegistered = false
            private set

        // Raw int passed by SDK; -1 = disconnected.
        @Volatile
        var connectedProductTypeId: Int = -1
            private set

        val isDeviceConnected get() = connectedProductTypeId >= 0
    }

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base)
        MultiDex.install(this)
        // com.secneo.sdk.Helper is only in the runtime artifact (dji-sdk-v5-aircraft),
        // not in the compileOnly provided artifact, so we call it via reflection to
        // avoid an "Unresolved reference" compile error if the symbol is absent.
        try {
            val cls = Class.forName("com.secneo.sdk.Helper")
            cls.getMethod("install", Application::class.java).invoke(null, this)
        } catch (e: Exception) {
            Log.e(TAG, "DJI Helper install skipped: ${e.message}")
        }
    }

    override fun onCreate() {
        super.onCreate()
        initDJISDK()
    }

    private fun initDJISDK() {
        SDKManager.getInstance().init(this, object : SDKManagerCallback {

            override fun onRegisterSuccess() {
                isSDKRegistered = true
                Log.i(TAG, "DJI SDK registered OK")
            }

            override fun onRegisterFailure(error: IDJIError?) {
                isSDKRegistered = false
                Log.e(TAG, "DJI SDK registration failed: $error")
            }

            // NOTE: the SDK passes an int product-type ID, not a ProductType enum.
            override fun onProductDisconnect(productTypeId: Int) {
                connectedProductTypeId = -1
                Log.i(TAG, "Product disconnected (typeId=$productTypeId)")
            }

            override fun onProductConnect(productTypeId: Int) {
                connectedProductTypeId = productTypeId
                Log.i(TAG, "Product connected (typeId=$productTypeId)")
            }

            override fun onProductChanged(productTypeId: Int) {
                connectedProductTypeId = productTypeId
                Log.i(TAG, "Product changed (typeId=$productTypeId)")
            }

            // DJISDKInitEvent is an enum: START_TO_INITIALIZE | INITIALIZE_COMPLETE
            override fun onInitProcess(event: DJISDKInitEvent, totalProgress: Int) {
                Log.d(TAG, "SDK init: $event  progress=$totalProgress%")
            }

            override fun onDatabaseDownloadProgress(current: Long, total: Long) {
                Log.d(TAG, "DB download $current/$total")
            }
        })
    }
}
