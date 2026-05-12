package com.sclassfencing.app

import android.app.Application
import android.content.Context
import android.util.Log
import androidx.multidex.MultiDex
import dji.v5.common.error.IDJIError
import dji.v5.common.register.DJISDKInitEvent
import dji.v5.manager.SDKManager
import dji.v5.manager.interfaces.SDKManagerCallback
import java.util.concurrent.Executors

class DJIApplication : Application() {

    companion object {
        private const val TAG = "DJIApplication"

        @Volatile var isSDKRegistered = false
            private set

        @Volatile var connectedProductTypeId: Int = -1
            private set

        @Volatile var sdkInitError: String? = null
            private set

        val isDeviceConnected get() = connectedProductTypeId >= 0
        val connectedModelName get() = if (isDeviceConnected) "OM 7P ($connectedProductTypeId)" else "None"
    }

    private val executor = Executors.newSingleThreadExecutor()

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base)
        // Helper must be called before MultiDex — use the thread's context classloader
        // so reflection finds the class in the runtime artifact, not the stub.
        installDJIHelper()
        MultiDex.install(this)
    }

    private fun installDJIHelper() {
        try {
            val cl = Thread.currentThread().contextClassLoader ?: classLoader
            val cls = Class.forName("com.secneo.sdk.Helper", true, cl)
            cls.getMethod("install", Application::class.java).invoke(null, this)
            Log.i(TAG, "DJI Helper installed OK")
        } catch (e: Exception) {
            Log.e(TAG, "DJI Helper failed: ${e.javaClass.simpleName}: ${e.message}")
            sdkInitError = "Helper failed: ${e.message}"
        } catch (e: Error) {
            Log.e(TAG, "DJI Helper error: ${e.javaClass.simpleName}: ${e.message}")
            sdkInitError = "Helper error: ${e.message}"
        }
    }

    override fun onCreate() {
        super.onCreate()
        if (sdkInitError == null) {
            executor.execute { initDJISDK() }
        }
    }

    private fun initDJISDK() {
        try {
            SDKManager.getInstance().init(this, object : SDKManagerCallback {

                override fun onRegisterSuccess() {
                    isSDKRegistered = true
                    sdkInitError = null
                    Log.i(TAG, "DJI SDK registered OK")
                }

                override fun onRegisterFailure(error: IDJIError?) {
                    isSDKRegistered = false
                    sdkInitError = "Registration failed: $error"
                    Log.e(TAG, sdkInitError!!)
                }

                override fun onProductDisconnect(productTypeId: Int) {
                    connectedProductTypeId = -1
                    Log.i(TAG, "Product disconnected")
                }

                override fun onProductConnect(productTypeId: Int) {
                    connectedProductTypeId = productTypeId
                    Log.i(TAG, "Product connected typeId=$productTypeId")
                }

                override fun onProductChanged(productTypeId: Int) {
                    connectedProductTypeId = productTypeId
                    Log.i(TAG, "Product changed typeId=$productTypeId")
                }

                override fun onInitProcess(event: DJISDKInitEvent, totalProgress: Int) {
                    Log.d(TAG, "SDK init: $event  progress=$totalProgress%")
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
        try { SDKManager.getInstance().destroy() } catch (e: Exception) {}
    }
}
