package com.sclassfencing.app

import android.app.Application
import android.content.Context
import android.util.Log
import androidx.multidex.MultiDex
import com.secneo.sdk.Helper
import dji.common.error.DJIError
import dji.common.product.Model
import dji.sdk.base.BaseComponent
import dji.sdk.base.BaseProduct
import dji.sdk.sdkmanager.DJISDKInitEvent
import dji.sdk.sdkmanager.DJISDKManager
import java.util.concurrent.Executors

class DJIApplication : Application() {

    companion object {
        private const val TAG = "DJIApplication"

        @Volatile var isSDKRegistered = false
            private set

        @Volatile var connectedProduct: BaseProduct? = null
            private set

        @Volatile var sdkInitError: String? = null
            private set

        val isDeviceConnected get() = connectedProduct?.isConnected == true
        val connectedModelName get() = connectedProduct?.model?.displayName ?: "Unknown"
    }

    private val executor = Executors.newSingleThreadExecutor()

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base)
        try { MultiDex.install(this) } catch (e: Exception) { Log.e(TAG, "MultiDex: ${e.message}") }
        try {
            Helper.install(this)
            Log.i(TAG, "DJI Helper installed")
        } catch (e: Exception) {
            Log.e(TAG, "DJI Helper failed: ${e.message}")
        }
    }

    override fun onCreate() {
        super.onCreate()
        executor.execute { initDJISDK() }
    }

    private fun initDJISDK() {
        try {
            DJISDKManager.getInstance().registerApp(this, object : DJISDKManager.SDKManagerCallback {

                override fun onRegister(error: DJIError?) {
                    if (error == DJIError.REGISTRATION_SUCCESS || error == null) {
                        isSDKRegistered = true
                        sdkInitError = null
                        Log.i(TAG, "DJI SDK v4 registered OK")
                        DJISDKManager.getInstance().startConnectionToProduct()
                    } else {
                        isSDKRegistered = false
                        sdkInitError = "Registration failed: ${error.description}"
                        Log.e(TAG, sdkInitError!!)
                    }
                }

                override fun onProductDisconnect() {
                    connectedProduct = null
                    Log.i(TAG, "Product disconnected")
                }

                override fun onProductConnect(product: BaseProduct?) {
                    connectedProduct = product
                    Log.i(TAG, "Product connected: ${product?.model?.displayName}")
                }

                override fun onProductChanged(product: BaseProduct?) {
                    connectedProduct = product
                    Log.i(TAG, "Product changed: ${product?.model?.displayName}")
                }

                override fun onComponentChange(
                    key: BaseProduct.ComponentKey?,
                    old: BaseComponent?,
                    new: BaseComponent?
                ) {}

                override fun onInitProcess(event: DJISDKInitEvent?, progress: Int) {
                    Log.d(TAG, "SDK init: $event  progress=$progress%")
                }

                override fun onDatabaseDownloadProgress(current: Long, total: Long) {
                    Log.d(TAG, "DB download $current/$total")
                }
            })
        } catch (e: Exception) {
            sdkInitError = "SDK init exception: ${e.message}"
            Log.e(TAG, sdkInitError!!, e)
        } catch (e: Error) {
            sdkInitError = "SDK native error: ${e.message}"
            Log.e(TAG, sdkInitError!!, e)
        }
    }

    override fun onTerminate() {
        super.onTerminate()
        executor.shutdownNow()
        try { DJISDKManager.getInstance().destroy() } catch (e: Exception) {}
    }
}
