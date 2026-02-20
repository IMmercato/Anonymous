package com.example.anonymous.i2p

import android.content.Context
import android.util.Log
import com.example.anonymous.i2p.config.I2pdConfig
import kotlinx.coroutines.*
import java.io.File
import java.net.Socket
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/**
 * I2P Daemon Manager - SINGLETON with proper crash detection and recovery.
 */
class I2pdDaemon private constructor(private val context: Context) {

    companion object {
        private const val TAG = "I2pDaemon"
        private const val SAM_PORT = 7656
        private const val STARTUP_TIMEOUT_MS = 300_000L
        private const val NATIVE_START_TIMEOUT_MS = 60_000L
        private const val NATIVE_COLD_START_WAIT_MS = 15_000L

        @Volatile private var instance: I2pdDaemon? = null
        private val instanceLock = Any()
        private var processId: Int = -1

        /**
         * Get or create the daemon instance. Handles process death detection.
         */
        fun getInstance(context: Context): I2pdDaemon {
            val currentPid = android.os.Process.myPid()

            // Process death detection - force new instance if process changed
            if (processId != -1 && processId != currentPid) {
                Log.w(TAG, "NEW PROCESS DETECTED: $processId -> $currentPid")
                synchronized(instanceLock) {
                    instance = null
                }
            }
            processId = currentPid

            return instance ?: synchronized(instanceLock) {
                instance ?: I2pdDaemon(context.applicationContext).also {
                    instance = it
                    Log.i(TAG, "I2pdDaemon created in pid=$currentPid")
                }
            }
        }

        /**
         * Get existing instance only if in same process and not fatally failed.
         */
        fun getExistingInstance(): I2pdDaemon? {
            val currentPid = android.os.Process.myPid()
            if (processId != -1 && processId != currentPid) {
                Log.w(TAG, "Process mismatch: stored=$processId, current=$currentPid")
                return null
            }
            val inst = instance ?: return null
            // Don't return if permanently failed
            if (inst.startupState.get() is StartupState.PermanentlyFailed) {
                Log.e(TAG, "Daemon permanently failed, cannot reuse instance")
                return null
            }
            return inst
        }

        /**
         * Force reset for testing or process death recovery.
         */
        @JvmStatic
        fun forceReset() {
            synchronized(instanceLock) {
                val oldState = instance?.startupState?.get()
                Log.w(TAG, "Force reset called. Old state: $oldState")
                instance = null
                processId = -1
            }
        }
    }

    sealed class DaemonState {
        object Idle : DaemonState()
        object Starting : DaemonState()
        object BuildingTunnels : DaemonState()
        object WaitingForNetwork : DaemonState()
        object Reseeding : DaemonState()
        object Ready : DaemonState()
        data class Error(val message: String, val isPermanent: Boolean = false) : DaemonState()
    }

    private sealed class StartupState {
        object Idle : StartupState()
        object Starting : StartupState()
        data class Running(val samReady: Boolean = false) : StartupState()
        data class Failed(val error: String, val isNativeCrash: Boolean = false) : StartupState()
        data class PermanentlyFailed(val error: String) : StartupState()
    }

    private val startupState = AtomicReference<StartupState>(StartupState.Idle)
    private val currentDaemonState = AtomicReference<DaemonState>(DaemonState.Idle)

    @Volatile private var nativeThread: Thread? = null
    @Volatile private var monitorThread: Thread? = null
    @Volatile private var samReady = false

    private val listeners = mutableListOf<DaemonStateListener>()
    private val listenersLock = Any()
    private val startupLock = Object()

    interface DaemonStateListener {
        fun onStateChanged(state: DaemonState)
        fun onError(error: String, isPermanent: Boolean)
    }

    /**
     * Start the I2P daemon. This is the ONLY entry point for initialization.
     *
     * @return true if daemon is ready for SAM connections, false if startup failed
     */
    fun start(): Boolean {
        Log.i(TAG, "start() called, current state: ${startupState.get()}")

        // Fast path: already running and ready
        val current = startupState.get()
        if (current is StartupState.Running && current.samReady) {
            Log.d(TAG, "Already running and SAM ready")
            return true
        }

        // Check for permanent failure (native crash previously)
        if (current is StartupState.PermanentlyFailed) {
            Log.e(TAG, "Daemon permanently failed, cannot start: ${current.error}")
            notifyError(current.error, isPermanent = true)
            return false
        }

        // Check for previous failure
        if (current is StartupState.Failed) {
            if (current.isNativeCrash) {
                // Native crashes are permanent - require process restart
                val permError = "I2P native crash detected - restart app to retry"
                startupState.set(StartupState.PermanentlyFailed(permError))
                notifyError(permError, isPermanent = true)
                return false
            }
            // Soft failure - can retry
            Log.w(TAG, "Previous soft failure: ${current.error}, allowing retry")
        }

        // Attempt state transition: Idle/Failed -> Starting
        if (!startupState.compareAndSet(current, StartupState.Starting)) {
            // Another thread is starting, wait for result
            Log.d(TAG, "Another thread is starting, waiting...")
            return waitForStartupResult()
        }

        // We won the race - actually start the daemon
        return doStart()
    }

    /**
     * Wait for daemon to be ready (for coroutine use).
     */
    suspend fun waitForReady(timeoutMs: Long = STARTUP_TIMEOUT_MS): Boolean = withContext(Dispatchers.IO) {
        val deadline = System.currentTimeMillis() + timeoutMs
        var lastLog = System.currentTimeMillis()

        while (System.currentTimeMillis() < deadline) {
            val state = startupState.get()

            when (state) {
                is StartupState.Running -> if (state.samReady) return@withContext true
                is StartupState.PermanentlyFailed -> {
                    Log.e(TAG, "waitForReady: permanently failed")
                    return@withContext false
                }
                is StartupState.Failed -> {
                    Log.e(TAG, "waitForReady: failed - ${state.error}")
                    return@withContext false
                }
                else -> { /* still starting */ }
            }

            val now = System.currentTimeMillis()
            if (now - lastLog >= 30_000) {
                val remaining = (deadline - now) / 1000
                Log.d(TAG, "waitForReady: state=$state, ${remaining}s remaining")
                lastLog = now
            }

            delay(500)
        }

        Log.w(TAG, "waitForReady: TIMEOUT after ${timeoutMs / 1000}s")
        false
    }

    /**
     * Check if daemon is fully ready for SAM operations.
     */
    fun isReady(): Boolean {
        val state = startupState.get()
        return state is StartupState.Running && state.samReady && samReady
    }

    /**
     * Check if daemon is running (native thread alive).
     */
    fun isRunning(): Boolean {
        val state = startupState.get()
        return state is StartupState.Running ||
                (state is StartupState.Starting && nativeThread?.isAlive == true)
    }

    /**
     * Get current state for UI display.
     */
    fun getState(): DaemonState = currentDaemonState.get()

    /**
     * Add state change listener.
     */
    fun addListener(l: DaemonStateListener) = synchronized(listenersLock) { listeners.add(l) }

    /**
     * Remove state change listener.
     */
    fun removeListener(l: DaemonStateListener) = synchronized(listenersLock) { listeners.remove(l) }

    /**
     * EMERGENCY ONLY: Mark daemon as failed if external crash detected.
     */
    fun markNativeCrashed(reason: String) {
        Log.e(TAG, "External crash notification: $reason")
        val current = startupState.get()
        if (current !is StartupState.PermanentlyFailed) {
            startupState.set(StartupState.PermanentlyFailed("Native crash: $reason"))
            currentDaemonState.set(DaemonState.Error(reason, isPermanent = true))
            notifyError(reason, isPermanent = true)
        }
    }


    
    private fun doStart(): Boolean {
        Log.i(TAG, "=== STARTING I2P DAEMON ===")
        updateDaemonState(DaemonState.Starting)

        return try {
            // Verify JNI bridge is functional
            verifyJNIBridge()

            // Prepare data directory (clean corrupted files from previous crashes)
            prepareDataDirectory()

            // Write fresh configuration
            val dataDir = writeConfiguration()

            // Start native daemon with timeout protection
            val nativeStarted = startNativeDaemonWithTimeout(dataDir)
            if (!nativeStarted) {
                return false
            }

            // Wait for cold-start initialization
            performColdStartWait()

            // Launch SAM readiness monitor
            launchSamMonitor()

            // Block until SAM ready or timeout/failure
            val success = waitForStartupResult()

            if (success) {
                Log.i(TAG, "=== I2P DAEMON READY ===")
                startupState.set(StartupState.Running(samReady = true))
            } else {
                Log.e(TAG, "=== I2P DAEMON FAILED TO READY ===")
                // State already set by failure path
            }

            success

        } catch (e: Exception) {
            Log.e(TAG, "Fatal error in doStart()", e)
            val errorMsg = e.message ?: "Unknown startup error"
            startupState.set(StartupState.Failed(errorMsg))
            updateDaemonState(DaemonState.Error(errorMsg))
            notifyError(errorMsg, isPermanent = false)
            false
        }
    }

    private fun verifyJNIBridge() {
        try {
            val abi = I2pdJNI.getABICompiledWith()
            Log.i(TAG, "JNI bridge OK, ABI: $abi")
        } catch (e: Exception) {
            Log.e(TAG, "JNI bridge verification failed", e)
            throw IllegalStateException("JNI bridge not functional: ${e.message}")
        }
    }

    private fun prepareDataDirectory() {
        val dataDir = File(context.filesDir, "i2pd")

        if (!dataDir.exists()) {
            Log.d(TAG, "Creating fresh data directory")
            dataDir.mkdirs()
            return
        }

        Log.i(TAG, "Preparing data directory (cleaning potential corruption)...")

        // Preserve router identity if exists
        val routerKeys = File(dataDir, "router.keys")
        val routerInfo = File(dataDir, "router.info")
        val preservedFiles = mutableMapOf<String, ByteArray?>()

        if (routerKeys.exists()) {
            preservedFiles["router.keys"] = routerKeys.readBytes()
            Log.d(TAG, "Preserving router.keys (${preservedFiles["router.keys"]?.size} bytes)")
        }
        if (routerInfo.exists()) {
            preservedFiles["router.info"] = routerInfo.readBytes()
            Log.d(TAG, "Preserving router.info (${preservedFiles["router.info"]?.size} bytes)")
        }

        // Delete directories that cause corruption on crash recovery
        val toClean = listOf("netDb", "addressbook", "i2pd.log", "i2pd.conf", "peerProfiles")
        for (name in toClean) {
            val f = File(dataDir, name)
            if (f.exists()) {
                val deleted = f.deleteRecursively()
                Log.d(TAG, "  Cleaned $name: $deleted")
            }
        }

        // Delete lock/pid files
        dataDir.listFiles()?.forEach { file ->
            if (file.name.endsWith(".pid") || file.name.endsWith(".lock") ||
                file.name == "i2pd.running" || file.name.endsWith(".dat")) {
                val deleted = file.delete()
                Log.d(TAG, "  Deleted lock file ${file.name}: $deleted")
            }
        }

        // Restore preserved identity files
        preservedFiles.forEach { (name, data) ->
            data?.let {
                File(dataDir, name).writeBytes(it)
                Log.d(TAG, "  Restored $name")
            }
        }

        Log.i(TAG, "Data directory prepared")
    }

    private fun writeConfiguration(): String {
        I2pdConfig.writeConfig(context)
        val dataDir = File(context.filesDir, "i2pd").absolutePath
        I2pdJNI.setDataDir(dataDir)
        Log.d(TAG, "Configuration written, dataDir=$dataDir")
        return dataDir
    }

    private fun startNativeDaemonWithTimeout(dataDir: String): Boolean {
        val executor = Executors.newSingleThreadExecutor()
        val future: Future<Boolean>

        nativeThread = thread(name = "i2pd-native", isDaemon = true, priority = Thread.MIN_PRIORITY) {
            Log.i(TAG, "Native thread started")

            try {
                I2pdJNI.setDataDir(dataDir)
                val result = I2pdJNI.startDaemon()

                // If we get here, startDaemon() returned (it shouldn't during normal operation)
                Log.w(TAG, "startDaemon() returned unexpectedly: $result")

                // Check if it was a clean shutdown or error
                if (result.contains("ERROR", ignoreCase = true) ||
                    result.contains("FAIL", ignoreCase = true)) {
                    throw IllegalStateException("Native daemon reported error: $result")
                }

            } catch (e: Throwable) {
                Log.e(TAG, "Native daemon crashed", e)

                // Determine if this is recoverable
                val isNativeCrash = when (e) {
                    is UnsatisfiedLinkError, is NoClassDefFoundError -> true
                    else -> {
                        // Check for native crash indicators in message
                        val msg = e.message ?: ""
                        msg.contains("SIGSEGV") || msg.contains("SIGABRT") ||
                                msg.contains("native") || msg.contains("libc")
                    }
                }

                val errorState = if (isNativeCrash) {
                    StartupState.PermanentlyFailed("Native crash: ${e.message}")
                } else {
                    StartupState.Failed("Native error: ${e.message}", isNativeCrash = false)
                }

                startupState.set(errorState)
                updateDaemonState(DaemonState.Error(e.message ?: "Native error", isPermanent = isNativeCrash))
                notifyError(e.message ?: "Native error", isPermanent = isNativeCrash)
            }
        }

        // Wait for native thread to either crash immediately or start successfully
        Log.d(TAG, "Waiting for native daemon to initialize (timeout: ${NATIVE_START_TIMEOUT_MS}ms)...")

        return try {
            // Give the native thread time to either start or crash
            Thread.sleep(2000) // Initial startup window

            if (nativeThread?.isAlive != true) {
                // Thread died immediately
                val state = startupState.get()
                if (state is StartupState.Failed || state is StartupState.PermanentlyFailed) {
                    Log.e(TAG, "Native thread failed immediately: $state")
                    return false
                }
                throw IllegalStateException("Native thread died without error state")
            }

            Log.i(TAG, "Native thread appears to be running")
            true

        } catch (e: InterruptedException) {
            Log.e(TAG, "Interrupted while waiting for native start")
            Thread.currentThread().interrupt()
            false
        }
    }

    private fun performColdStartWait() {
        Log.d(TAG, "Cold-start wait: ${NATIVE_COLD_START_WAIT_MS}ms...")
        try {
            Thread.sleep(NATIVE_COLD_START_WAIT_MS)
        } catch (e: InterruptedException) {
            Log.w(TAG, "Cold-start wait interrupted")
            Thread.currentThread().interrupt()
        }
    }

    private fun launchSamMonitor() {
        // Stop any existing monitor
        monitorThread?.interrupt()

        monitorThread = thread(name = "i2pd-sam-monitor", isDaemon = true, priority = Thread.MIN_PRIORITY) {
            Log.i(TAG, "SAM monitor started")

            val limit = (STARTUP_TIMEOUT_MS / 1000).toInt()
            var elapsed = 0

            while (elapsed < limit && !Thread.currentThread().isInterrupted) {
                // Check if we've already failed
                val currentState = startupState.get()
                if (currentState is StartupState.Failed || currentState is StartupState.PermanentlyFailed) {
                    Log.e(TAG, "SAM monitor detected failure state, exiting")
                    return@thread
                }

                // Check SAM via JNI
                val samUpViaJni = try {
                    I2pdJNI.getSAMState()
                } catch (e: Exception) {
                    Log.w(TAG, "getSAMState() threw at ${elapsed}s: ${e.message}")
                    false
                }

                if (samUpViaJni) {
                    Log.d(TAG, "SAM state=true at ${elapsed}s, verifying with socket...")
                    if (isSamPortOpen()) {
                        Log.i(TAG, "SAM fully ready after ${elapsed}s!")
                        samReady = true
                        startupState.set(StartupState.Running(samReady = true))
                        updateDaemonState(DaemonState.Ready)
                        synchronized(startupLock) { startupLock.notifyAll() }
                        return@thread
                    }
                }

                // Update progress state
                updateProgressState(elapsed)

                try {
                    Thread.sleep(2000)
                } catch (_: InterruptedException) {
                    return@thread
                }
                elapsed += 2
            }

            // Timeout
            Log.e(TAG, "SAM monitor timeout after ${elapsed}s")
            val timeoutError = "I2P timeout - SAM not ready after ${elapsed}s"
            startupState.set(StartupState.Failed(timeoutError))
            updateDaemonState(DaemonState.Error(timeoutError))
            notifyError(timeoutError, isPermanent = false)
            synchronized(startupLock) { startupLock.notifyAll() }
        }
    }

    private fun updateProgressState(elapsed: Int) {
        val newState = when {
            elapsed < 20 -> DaemonState.Starting
            elapsed < 60 -> DaemonState.BuildingTunnels
            elapsed < 120 -> DaemonState.Reseeding
            else -> DaemonState.WaitingForNetwork
        }

        if (newState != currentDaemonState.get()) {
            Log.d(TAG, "State: ${currentDaemonState.get()} -> $newState (${elapsed}s)")
            updateDaemonState(newState)
        }
    }

    private fun waitForStartupResult(): Boolean {
        synchronized(startupLock) {
            var waited = 0
            val maxWait = STARTUP_TIMEOUT_MS
            val checkInterval = 100L

            while (waited < maxWait) {
                val state = startupState.get()

                when (state) {
                    is StartupState.Running -> if (state.samReady) return true
                    is StartupState.Failed -> return false
                    is StartupState.PermanentlyFailed -> return false
                    else -> { /* still starting */ }
                }

                try {
                    startupLock.wait(checkInterval)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return false
                }
                waited += checkInterval.toInt()
            }
        }

        Log.w(TAG, "waitForStartupResult: timeout")
        return false
    }

    private fun isSamPortOpen(): Boolean {
        return try {
            Socket("127.0.0.1", SAM_PORT).use { socket ->
                socket.isConnected
            }
        } catch (_: Exception) {
            false
        }
    }

    private fun updateDaemonState(state: DaemonState) {
        val oldState = currentDaemonState.getAndSet(state)
        if (oldState != state) {
            synchronized(listenersLock) {
                listeners.forEach { listener ->
                    try {
                        listener.onStateChanged(state)
                    } catch (e: Exception) {
                        Log.e(TAG, "Listener error in onStateChanged", e)
                    }
                }
            }
        }
    }

    private fun notifyError(error: String, isPermanent: Boolean) {
        Log.e(TAG, "notifyError: $error (permanent=$isPermanent)")
        synchronized(listenersLock) {
            listeners.forEach { listener ->
                try {
                    listener.onError(error, isPermanent)
                } catch (e: Exception) {
                    Log.e(TAG, "Listener error in onError", e)
                }
            }
        }
    }
}