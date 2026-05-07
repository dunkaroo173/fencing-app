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
        // DJI SDK requires this helper for class loading
        try {
            com.secneo.sdk.Helper.install(this)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to install DJI Helper: ${e.message}")
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
                Log.i(TAG, "DJI SDK registered successfully")
            }

            override fun onRegisterFailure(error: IDJIError?) {
                isSDKRegistered = false
                Log.e(TAG, "DJI SDK registration failed: ${error?.description()}")
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
                Log.d(TAG, "SDK init progress: $event ($totalProgress%)")
            }

            override fun onDatabaseDownloadProgress(current: Long, total: Long) {
                Log.d(TAG, "Database download: $current / $total")
            }
        })
    }
}
