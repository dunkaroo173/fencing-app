package com.sclassfencing.app

import android.app.Application
import android.content.Context
import android.util.Log
import androidx.multidex.MultiDex
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Proxy
import java.util.concurrent.Executors

class DJIApplication : Application() {

    companion object {
        private const val TAG = "DJIApplication"

        @Volatile var isSDKRegistered = false
        @Volatile var connectedProductTypeId: Int = -1
        @Volatile var sdkInitError: String? = null

        val isDeviceConnected get() = connectedProductTypeId >= 0
        val connectedModelName get() = if (isDeviceConnected) "OM 7P ($connectedProductTypeId)" else "None"
    }

    private val executor = Executors.newSingleThreadExecutor()

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base)
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
            val cl = Thread.currentThread().contextClassLoader ?: classLoader
            val callbackClass = Class.forName(
                "dji.v5.manager.interfaces.SDKManagerCallback", true, cl
            )
            val callback = Proxy.newProxyInstance(
                cl, arrayOf(callbackClass),
                InvocationHandler { _, method, args ->
                    when (method.name) {
                        "onRegisterSuccess" -> {
                            isSDKRegistered = true
                            sdkInitError = null
                            Log.i(TAG, "DJI SDK registered OK")
                        }
                        "onRegisterFailure" -> {
                            isSDKRegistered = false
                            sdkInitError = "Registration failed: ${args?.getOrNull(0)}"
                            Log.e(TAG, sdkInitError!!)
                        }
                        "onProductConnect" -> {
                            connectedProductTypeId = (args?.getOrNull(0) as? Int) ?: -1
                            Log.i(TAG, "Product connected typeId=$connectedProductTypeId")
                        }
                        "onProductDisconnect" -> {
                            connectedProductTypeId = -1
                            Log.i(TAG, "Product disconnected")
                        }
                        "onProductChanged" -> {
                            connectedProductTypeId = (args?.getOrNull(0) as? Int) ?: -1
                            Log.i(TAG, "Product changed typeId=$connectedProductTypeId")
                        }
                        "onInitProcess" -> {
                            Log.d(TAG, "SDK init: ${args?.getOrNull(0)} progress=${args?.getOrNull(1)}%")
                        }
                        "onDatabaseDownloadProgress" -> {
                            Log.d(TAG, "DB download ${args?.getOrNull(0)}/${args?.getOrNull(1)}")
                        }
                        else -> Log.v(TAG, "SDKCallback.${method.name} called")
                    }
                    null
                }
            )

            val sdkManagerClass = Class.forName("dji.v5.manager.SDKManager", true, cl)
            val instance = sdkManagerClass.getMethod("getInstance").invoke(null)
            sdkManagerClass
                .getMethod("init", Context::class.java, callbackClass)
                .invoke(instance, this, callback)

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
        try {
            val cl = Thread.currentThread().contextClassLoader ?: classLoader
            val cls = Class.forName("dji.v5.manager.SDKManager", true, cl)
            val instance = cls.getMethod("getInstance").invoke(null)
            cls.getMethod("destroy").invoke(instance)
        } catch (e: Exception) {
            Log.v(TAG, "SDK destroy skipped: ${e.message}")
        }
    }
}
