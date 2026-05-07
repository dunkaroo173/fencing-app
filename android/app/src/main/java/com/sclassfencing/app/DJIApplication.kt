package com.sclassfencing.app

import android.app.Application
import android.content.Context
import android.util.Log
import androidx.multidex.MultiDex
import dji.v5.common.error.IDJIError
import dji.v5.et.product.ProductType
import dji.v5.manager.SDKManager
import dji.v5.manager.interfaces.SDKManagerCallback

class DJIApplication : Application() {

    companion object {
        private const val TAG = "DJIApplication"

        @Volatile
        var isSDKRegistered = false
            private set

        @Volatile
        var connectedProductType: ProductType? = null
            private set
    }

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base)
        MultiDex.install(this)
        // DJI SDK requires this for class-loading on Android < 5 and for
        // multidex splitting of the large SDK AAR.
        try {
            com.secneo.sdk.Helper.install(this)
        } catch (e: Exception) {
            Log.e(TAG, "DJI Helper install failed: ${e.message}")
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
                // IDJIError.errorDescription() or .description() depending on SDK version
                val msg = runCatching { error?.toString() }.getOrDefault("unknown")
                Log.e(TAG, "DJI SDK registration failed: $msg")
            }

            override fun onProductDisconnect(productType: ProductType) {
                connectedProductType = null
                Log.i(TAG, "Product disconnected: $productType")
            }

            override fun onProductConnect(productType: ProductType) {
                connectedProductType = productType
                Log.i(TAG, "Product connected: $productType")
            }

            override fun onProductChanged(productType: ProductType) {
                connectedProductType = productType
                Log.i(TAG, "Product changed: $productType")
            }

            override fun onInitProcess(
                event: SDKManagerCallback.InitializationEvent,
                totalProgress: Int
            ) {
                Log.d(TAG, "SDK init: $event  progress=$totalProgress%")
            }

            override fun onDatabaseDownloadProgress(current: Long, total: Long) {
                Log.d(TAG, "DB download $current/$total")
            }
        })
    }
}
