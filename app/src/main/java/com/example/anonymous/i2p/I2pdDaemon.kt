package com.example.anonymous.i2p

import android.content.Context
import android.util.Log
import com.example.anonymous.i2p.config.I2pdConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

class I2pdDaemon private constructor(private val context: Context) {
    companion object {
        private const val TAG = "I2pDaemon"

        @Volatile
        private var instance : I2pdDaemon? = null

        fun getInstance(context: Context): I2pdDaemon {
            return instance ?: synchronized(this) {
                instance ?: I2pdDaemon(context.applicationContext).also {
                    instance = it
                }
            }
        }
    }

    private val isRunning = AtomicBoolean(false)
    private val isReady = AtomicBoolean(false)
    private val daemonScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val listeners = mutableListOf<DaemonStateListener>()

    interface DaemonStateListener {
        fun onStateChanged(state: DaemonState)
        fun onError(error: String)
    }

    enum class DaemonState {
        STOPPED,
        STARTING,
        WAITING_FOR_NETWORK,
        BUILDING_TUNNELS,
        READY,
        ERROR
    }

    private var currentState = DaemonState.STOPPED
        set(value) {
            field = value
            listeners.forEach { it.onStateChanged(value) }
        }

    /**
     * Start the i2pd daemon
     */
    fun start(): Boolean {
        if (isRunning.get()) {
            Log.w(TAG, "Daemon already running")
            return true
        }

        return try {
            currentState = DaemonState.STARTING

            val configFile = I2pdConfig.writeConfig(context)
            Log.i(TAG, "Config written to: ${configFile.absolutePath}")

            // Set data directory for JNI
            val dataDir = File(context.filesDir, "i2pd").absolutePath
            I2pdJNI.setDataDir(dataDir)

            // Start daemon in background
            daemonScope.launch {
                val result = I2pdJNI.startDaemon()

                if (result == "ok") {
                    isRunning.set(true)
                    Log.i(TAG, "i2pd daemon started successfully")

                    waitForSamReady()
                } else {
                    Log.e(TAG, "Failed to start i2pd: $result")
                    currentState = DaemonState.ERROR
                    listeners.forEach { it.onError(result) }
                }
            }

            true
        } catch (e: Exception) {
            Log.e(TAG, "Exception starting daemon", e)
            currentState = DaemonState.ERROR
            listeners.forEach { it.onError(e.message ?: "Unknown error") }
            false
        }
    }

    /**
     * SAM bridge
     */
    private suspend fun waitForSamReady() {
        currentState = DaemonState.BUILDING_TUNNELS

        var attempts = 0
        val maxAttempts = 60

        while (attempts < maxAttempts) {
            if (I2pdJNI.getSAMState()) {
                isReady.set(true)
                currentState = DaemonState.READY
                Log.i(TAG, "SAM bridge ready on 127.0.0.1:7656")
                return
            }

            delay(1000)
            attempts++
        }

        Log.e(TAG, "Timeout waiting for SAM bridge")
        currentState = DaemonState.ERROR
        listeners.forEach { it.onError("SAM bridge timeout") }
    }

    /**
     * Stop i2pd daemon
     */
    fun stop() {
        if (!isRunning.get()) return

        try {
            I2pdJNI.stopDaemon()
            isRunning.set(false)
            isReady.set(false)
            currentState = DaemonState.STOPPED
            Log.i(TAG, "i2pd daemon stopped")
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping daemon", e)
        }
    }

    fun isRunning(): Boolean = isRunning.get()

    fun isReady(): Boolean = isReady.get()

    fun getState(): DaemonState = currentState

    fun addListener(listener: DaemonStateListener) {
        listeners.add(listener)
    }

    fun removeListener(listener: DaemonStateListener) {
        listeners.remove(listener)
    }

    fun getWebConsoleAddress(): String? {
        return if (isReady()) I2pdJNI.getWebConsAddr() else null
    }

    fun cleanup() {
        stop()
        daemonScope.cancel()
    }
}