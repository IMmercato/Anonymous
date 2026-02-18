package com.example.anonymous.i2p

import android.content.Context
import android.util.Log
import com.example.anonymous.i2p.config.I2pdConfig
import kotlinx.coroutines.*
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.thread

class I2pdDaemon private constructor(private val context: Context) {
    companion object {
        private const val TAG = "I2pDaemon"
        private const val NATIVE_START_TIMEOUT = 30_000L

        @Volatile
        private var instance: I2pdDaemon? = null
        private val instanceLock = Any()

        fun getInstance(context: Context): I2pdDaemon {
            return instance ?: synchronized(instanceLock) {
                instance ?: I2pdDaemon(context.applicationContext).also {
                    instance = it
                }
            }
        }

        fun getExistingInstance(): I2pdDaemon? = instance
    }

    private val isRunning = AtomicBoolean(false)
    private val isReady = AtomicBoolean(false)
    private val isCleaningUp = AtomicBoolean(false)
    private val listeners = mutableListOf<DaemonStateListener>()
    private val listenersLock = Any()
    private val startupLock = ReentrantLock()

    // CRITICAL: Use a dedicated Thread, not coroutine, for startDaemon()
    private var daemonThread: Thread? = null
    private val daemonThreadLock = Object()

    interface DaemonStateListener {
        fun onStateChanged(state: DaemonState)
        fun onError(error: String)
    }

    enum class DaemonState {
        STOPPED,
        STARTING,
        BUILDING_TUNNELS,
        WAITING_FOR_NETWORK,
        READY,
        ERROR
    }

    @Volatile
    private var currentState = DaemonState.STOPPED
        private set(value) {
            field = value
            synchronized(listenersLock) {
                listeners.forEach { listener ->
                    try {
                        listener.onStateChanged(value)
                    } catch (e: Exception) {
                        Log.e(TAG, "Listener error", e)
                    }
                }
            }
        }

    /**
     * Start the i2pd daemon - runs in a dedicated persistent thread
     */
    fun start(): Boolean {
        if (!startupLock.tryLock()) {
            Log.w(TAG, "Start already in progress, skipping")
            return isRunning.get()
        }

        try {
            if (isRunning.get() || currentState == DaemonState.READY) {
                Log.w(TAG, "Daemon already running")
                return true
            }

            if (isCleaningUp.get()) {
                Log.w(TAG, "Cannot start while cleaning up")
                return false
            }

            // Ensure clean state
            if (currentState != DaemonState.STOPPED) {
                Log.w(TAG, "Daemon not stopped (${currentState.name}), forcing cleanup...")
                forceStop()
            }

            return try {
                currentState = DaemonState.STARTING

                val configFile = I2pdConfig.writeConfig(context)
                Log.i(TAG, "Config written to: ${configFile.absolutePath}")

                val dataDir = File(context.filesDir, "i2pd").absolutePath
                I2pdJNI.setDataDir(dataDir)

                // CRITICAL: Run startDaemon() in a dedicated Thread, NOT a coroutine
                // This prevents the mutex destroyed crash because the thread persists
                // until we explicitly call stopDaemon()
                daemonThread = thread(name = "I2pdDaemonThread", priority = Thread.MIN_PRIORITY) {
                    try {
                        Log.i(TAG, "Starting native daemon thread...")
                        isRunning.set(true)

                        // This call blocks until stopDaemon() is called
                        val result = I2pdJNI.startDaemon()

                        Log.i(TAG, "Native daemon stopped with result: $result")
                    } catch (e: Exception) {
                        Log.e(TAG, "Native daemon error", e)
                        if (!isCleaningUp.get()) {
                            currentState = DaemonState.ERROR
                            notifyError(e.message ?: "Native daemon error")
                        }
                    } finally {
                        isRunning.set(false)
                        isReady.set(false)
                        if (currentState != DaemonState.ERROR) {
                            currentState = DaemonState.STOPPED
                        }
                    }
                }

                true
            } catch (e: Exception) {
                Log.e(TAG, "Exception starting daemon", e)
                currentState = DaemonState.ERROR
                notifyError(e.message ?: "Unknown error")
                false
            }
        } finally {
            startupLock.unlock()
        }
    }

    /**
     * Wait for SAM bridge to be ready
     */
    suspend fun waitForReady(timeoutMs: Long = 60000): Boolean = withContext(Dispatchers.IO) {
        if (isReady.get()) return@withContext true

        var attempts = 0
        val maxAttempts = (timeoutMs / 1000).toInt()

        while (attempts < maxAttempts && !isCleaningUp.get() && isRunning.get()) {
            try {
                if (I2pdJNI.getSAMState()) {
                    isReady.set(true)
                    currentState = DaemonState.READY
                    Log.i(TAG, "SAM bridge ready")
                    return@withContext true
                }
            } catch (e: Exception) {
                Log.w(TAG, "Error checking SAM state: ${e.message}")
            }

            delay(1000)
            attempts++

            // Update state based on progress
            when {
                attempts in 3..10 -> currentState = DaemonState.BUILDING_TUNNELS
            }
        }

        if (!isReady.get()) {
            Log.e(TAG, "Timeout waiting for SAM bridge after ${attempts}s")
            currentState = DaemonState.ERROR
            notifyError("SAM bridge timeout")
        }

        isReady.get()
    }

    private fun notifyError(error: String) {
        synchronized(listenersLock) {
            listeners.forEach { listener ->
                try {
                    listener.onError(error)
                } catch (e: Exception) {
                    Log.e(TAG, "Listener error", e)
                }
            }
        }
    }

    /**
     * Stop the daemon - properly shuts down the native thread
     */
    fun stop() {
        if (!isRunning.get() && currentState == DaemonState.STOPPED) {
            Log.d(TAG, "Daemon already stopped")
            return
        }

        Log.i(TAG, "Stopping i2pd daemon...")
        isCleaningUp.set(true)
        isRunning.set(false)

        try {
            // This signals the native daemon to stop, which will cause
            // startDaemon() to return and the daemonThread to exit naturally
            I2pdJNI.stopDaemon()
        } catch (e: Exception) {
            Log.e(TAG, "Error calling stopDaemon", e)
        }

        // Wait for the daemon thread to finish
        synchronized(daemonThreadLock) {
            daemonThread?.let { thread ->
                try {
                    if (thread.isAlive) {
                        Log.i(TAG, "Waiting for daemon thread to finish...")
                        thread.join(5000) // Wait up to 5 seconds
                        if (thread.isAlive) {
                            Log.w(TAG, "Daemon thread did not finish in time")
                        }
                    }
                } catch (e: InterruptedException) {
                    Log.w(TAG, "Interrupted while waiting for daemon thread", e)
                }
            }
            daemonThread = null
        }

        isCleaningUp.set(false)
        isReady.set(false)
        currentState = DaemonState.STOPPED
        Log.i(TAG, "i2pd daemon stopped")
    }

    private fun forceStop() {
        try {
            I2pdJNI.stopDaemon()
        } catch (e: Exception) {
            Log.w(TAG, "Error during force stop", e)
        }

        synchronized(daemonThreadLock) {
            daemonThread?.interrupt()
            daemonThread = null
        }

        isRunning.set(false)
        isReady.set(false)
        currentState = DaemonState.STOPPED
    }

    fun isRunning(): Boolean = isRunning.get()
    fun isReady(): Boolean = isReady.get()
    fun getState(): DaemonState = currentState

    fun addListener(listener: DaemonStateListener) {
        synchronized(listenersLock) {
            listeners.add(listener)
        }
    }

    fun removeListener(listener: DaemonStateListener) {
        synchronized(listenersLock) {
            listeners.remove(listener)
        }
    }

    fun cleanup() {
        Log.i(TAG, "Cleaning up I2P daemon...")
        stop()

        synchronized(listenersLock) {
            listeners.clear()
        }
        Log.i(TAG, "Cleanup complete")
    }
}