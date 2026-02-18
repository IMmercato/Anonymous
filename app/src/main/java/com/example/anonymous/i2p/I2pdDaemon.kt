package com.example.anonymous.i2p

import android.content.Context
import android.util.Log
import com.example.anonymous.i2p.config.I2pdConfig
import kotlinx.coroutines.*
import java.io.File
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * EMERGENCY FIX: The native i2pd library crashes if stopDaemon() is called
 * and then any other function is called. The daemon MUST live for the
 * entire process lifetime.
 */
class I2pdDaemon private constructor(private val context: Context) {

    companion object {
        private const val TAG = "I2pDaemon"
        private const val SAM_PORT = 7656
        private const val STARTUP_TIMEOUT_MS = 120_000L

        @Volatile private var instance: I2pdDaemon? = null
        private val instanceLock = Any()

        fun getInstance(context: Context): I2pdDaemon =
            instance ?: synchronized(instanceLock) {
                instance ?: I2pdDaemon(context.applicationContext).also {
                    instance = it
                    Log.i(TAG, "I2pdDaemon singleton created")
                }
            }

        fun getExistingInstance(): I2pdDaemon? = instance
    }

    enum class DaemonState { STOPPED, STARTING, BUILDING_TUNNELS, WAITING_FOR_NETWORK, READY, ERROR }

    interface DaemonStateListener {
        fun onStateChanged(state: DaemonState)
        fun onError(error: String)
    }

    private val nativeStarted = AtomicBoolean(false)
    private val samReady = AtomicBoolean(false)
    @Volatile private var currentState = DaemonState.STOPPED
    @Volatile private var monitorThread: Thread? = null
    private val listeners = mutableListOf<DaemonStateListener>()
    private val listenersLock = Any()

    // EMERGENCY: Track if we've ever tried to stop - if yes, NEVER allow start again
    private val hasEverStopped = AtomicBoolean(false)

    fun start(): Boolean {
        // EMERGENCY CHECK: If we ever stopped, we cannot restart
        if (hasEverStopped.get()) {
            Log.e(TAG, "EMERGENCY: Cannot start - daemon was previously stopped!")
            return false
        }

        if (samReady.get()) return true

        if (!nativeStarted.compareAndSet(false, true)) {
            Log.d(TAG, "Already starting, waiting...")
            return blockUntilSamReady(STARTUP_TIMEOUT_MS)
        }

        Log.i(TAG, ">>> STARTING NATIVE DAEMON <<<")
        currentState = DaemonState.STARTING
        notifyStateChanged(DaemonState.STARTING)

        return try {
            I2pdConfig.writeConfig(context)
            I2pdJNI.setDataDir(File(context.filesDir, "i2pd").absolutePath)

            thread(name = "i2pd-native", isDaemon = true, priority = Thread.MIN_PRIORITY) {
                try {
                    Log.i(TAG, "Native thread running...")
                    val result = I2pdJNI.startDaemon()
                    Log.i(TAG, "Native daemon exited: $result")
                } catch (e: Exception) {
                    Log.e(TAG, "Native daemon crashed!", e)
                    currentState = DaemonState.ERROR
                    notifyError("Native crash: ${e.message}")
                }
            }

            Thread.sleep(1500)
            launchSamMonitor()
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start", e)
            currentState = DaemonState.ERROR
            false
        }
    }

    suspend fun waitForReady(timeoutMs: Long = STARTUP_TIMEOUT_MS): Boolean =
        withContext(Dispatchers.IO) {
            val deadline = System.currentTimeMillis() + timeoutMs
            while (System.currentTimeMillis() < deadline) {
                if (samReady.get()) return@withContext true
                if (currentState == DaemonState.ERROR) return@withContext false
                delay(200)
            }
            false
        }

    fun isRunning() = nativeStarted.get()
    fun isReady() = samReady.get()
    fun getState() = currentState

    fun addListener(l: DaemonStateListener) = synchronized(listenersLock) { listeners.add(l) }
    fun removeListener(l: DaemonStateListener) = synchronized(listenersLock) { listeners.remove(l) }

    /**
     * ⚠️⚠️⚠️ NEVER CALL THIS UNLESS APP IS COMPLETELY EXITING ⚠️⚠️⚠️
     * Calling this will destroy native mutexes and crash the app forever!
     */
    fun shutdownForProcessExit() {
        Log.e(TAG, ">>> shutdownForProcessExit() CALLED <<<")

        // EMERGENCY: Mark that we've stopped - prevents any future starts
        hasEverStopped.set(true)

        if (!nativeStarted.get()) {
            Log.w(TAG, "Not running, nothing to stop")
            return
        }

        Log.e(TAG, "!!! CALLING stopDaemon() - APP WILL CRASH IF RESTARTED !!!")
        try {
            I2pdJNI.stopDaemon()
        } catch (e: Exception) {
            Log.e(TAG, "stopDaemon() failed", e)
        }
    }

    @Deprecated("NEVER USE - causes crashes")
    fun stop() = shutdownForProcessExit()
    fun cleanup() = shutdownForProcessExit()

    // This method is NOT SAFE - removed functionality
    fun prepareForNewIdentity() {
        Log.e(TAG, "prepareForNewIdentity() called but this is NOT IMPLEMENTED - would crash!")
    }

    private fun launchSamMonitor() {
        monitorThread?.interrupt()

        monitorThread = thread(name = "i2pd-monitor", isDaemon = true, priority = Thread.MIN_PRIORITY) {
            var elapsed = 0
            val limit = (STARTUP_TIMEOUT_MS / 1000).toInt()

            while (elapsed < limit) {
                if (Thread.currentThread().isInterrupted) return@thread

                if (isSamPortOpen()) {
                    samReady.set(true)
                    currentState = DaemonState.READY
                    Log.i(TAG, ">>> SAM READY <<<")
                    notifyStateChanged(DaemonState.READY)
                    return@thread
                }

                val progress = when {
                    elapsed in 10..29 -> DaemonState.BUILDING_TUNNELS
                    elapsed >= 30 -> DaemonState.WAITING_FOR_NETWORK
                    else -> null
                }
                if (progress != null && progress != currentState) {
                    currentState = progress
                    notifyStateChanged(progress)
                }

                try { Thread.sleep(1000) } catch (_: InterruptedException) { return@thread }
                elapsed++
            }

            Log.e(TAG, "SAM timeout!")
            currentState = DaemonState.ERROR
            notifyError("I2P timeout")
        }
    }

    private fun isSamPortOpen(): Boolean = try {
        Socket("127.0.0.1", SAM_PORT).use { it.soTimeout = 1000; true }
    } catch (_: Exception) { false }

    private fun blockUntilSamReady(timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (samReady.get()) return true
            if (currentState == DaemonState.ERROR) return false
            Thread.sleep(200)
        }
        return false
    }

    private fun notifyStateChanged(state: DaemonState) = synchronized(listenersLock) {
        listeners.forEach { try { it.onStateChanged(state) } catch (e: Exception) { Log.e(TAG, "Listener error", e) } }
    }

    private fun notifyError(error: String) = synchronized(listenersLock) {
        listeners.forEach { try { it.onError(error) } catch (e: Exception) { Log.e(TAG, "Listener error", e) } }
    }
}