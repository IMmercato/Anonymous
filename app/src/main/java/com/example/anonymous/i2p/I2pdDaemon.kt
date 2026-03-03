package com.example.anonymous.i2p

import android.content.Context
import android.system.Os
import android.system.OsConstants
import android.util.Log
import com.example.anonymous.i2p.config.I2pdConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.io.PrintWriter
import java.io.StringWriter
import java.net.Socket
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/**
 * I2P Daemon Manager – runs in the :i2pd process.
 */
class I2pdDaemon private constructor(private val context: Context) {

    companion object {
        private const val TAG = "I2pDaemon"
        private const val SAM_PORT = 7656
        private const val STARTUP_TIMEOUT_MS = 600_000L // 10 minutes
        private const val NATIVE_START_TIMEOUT_MS = 120_000L // 2 min
        private const val HEALTH_CHECK_INTERVAL_MS = 5_000L
        private const val HEARTBEAT_TIMEOUT_MS = 10_000L
        private const val TOTAL_STEPS = 6

        private const val NATIVE_THREAD_PRIORITY = Thread.NORM_PRIORITY + 1
        private const val MONITOR_THREAD_PRIORITY = Thread.NORM_PRIORITY

        private val CRASH_INDICATORS = listOf(
            "F/libc", "Fatal signal", "SIGSEGV", "SIGABRT", "SIGILL",
            "tombstone", "i2pd-native", "i2pd", "runtime.cc", "Build fingerprint"
        )

        @Volatile private var instance: I2pdDaemon? = null
        private val instanceLock = Any()
        private var processId: Int = -1

        @Volatile private var lastGlobalHeartbeatMs: Long = 0

        init {
            try {
                System.loadLibrary("i2pd")
                Log.i(TAG, "✅ Native library (i2pd) loaded successfully in process ${android.os.Process.myPid()}")
            } catch (e: UnsatisfiedLinkError) {
                Log.e(TAG, "❌ Failed to load native library in process ${android.os.Process.myPid()}", e)
                throw RuntimeException("Cannot load i2pd native library. ABI mismatch or missing .so file", e)
            } catch (e: Exception) {
                Log.e(TAG, "❌ Unknown error loading native library", e)
                throw e
            }
        }

        fun getInstance(context: Context): I2pdDaemon {
            val currentPid = android.os.Process.myPid()
            if (processId != -1 && processId != currentPid) {
                Log.w(TAG, "🚨 NEW PROCESS DETECTED: $processId -> $currentPid")
                synchronized(instanceLock) { instance = null }
            }
            processId = currentPid
            return instance ?: synchronized(instanceLock) {
                instance ?: I2pdDaemon(context.applicationContext).also {
                    instance = it
                    Log.i(TAG, "✅ I2pdDaemon created in pid=$currentPid")
                }
            }
        }

        @JvmStatic fun forceNewInstance() {
            synchronized(instanceLock) {
                Log.w(TAG, "🔄 Force new instance requested")
                instance = null
                processId = -1
            }
        }

        fun getExistingInstance(): I2pdDaemon? {
            val currentPid = android.os.Process.myPid()
            if (processId != -1 && processId != currentPid) {
                Log.w(TAG, "⚠️ Process mismatch: stored=$processId, current=$currentPid")
                return null
            }
            val inst = instance ?: return null
            if (inst.startupState.get() is StartupState.PermanentlyFailed) {
                Log.e(TAG, "🚫 Daemon permanently failed, cannot reuse")
                return null
            }
            return inst
        }

        @JvmStatic fun forceReset() {
            synchronized(instanceLock) {
                val oldState = instance?.startupState?.get()
                Log.w(TAG, "🔄 Force reset. Old state: $oldState")
                instance = null
                processId = -1
            }
        }

        @JvmStatic fun updateNativeHeartbeat() {
            lastGlobalHeartbeatMs = System.currentTimeMillis()
        }

        fun getLastHeartbeatMs(): Long = lastGlobalHeartbeatMs
    }

    // ═══════════════════════════════════════════════════════════════════════
    // STATE MANAGEMENT
    // ═══════════════════════════════════════════════════════════════════════

    sealed class DaemonState {
        object Idle : DaemonState() { override fun toString() = "IDLE" }
        object Starting : DaemonState() { override fun toString() = "STARTING" }
        object BuildingTunnels : DaemonState() { override fun toString() = "BUILDING_TUNNELS" }
        object WaitingForNetwork : DaemonState() { override fun toString() = "WAITING_FOR_NETWORK" }
        object Reseeding : DaemonState() { override fun toString() = "RESEEDING" }
        object Ready : DaemonState() { override fun toString() = "READY" }
        data class Error(val message: String, val isPermanent: Boolean = false) : DaemonState() {
            override fun toString() = "ERROR(permanent=$isPermanent): $message"
        }
    }

    private sealed class StartupState {
        object Idle : StartupState() { override fun toString() = "IDLE" }
        object Starting : StartupState() { override fun toString() = "STARTING" }
        data class Running(val samReady: Boolean = false, val startTime: Long = System.currentTimeMillis()) : StartupState() {
            override fun toString() = "RUNNING(sam=$samReady, elapsed=${(System.currentTimeMillis()-startTime)/1000}s)"
        }
        data class Failed(val error: String, val isNativeCrash: Boolean = false, val timestamp: Long = System.currentTimeMillis()) : StartupState() {
            override fun toString() = "FAILED(nativeCrash=$isNativeCrash): $error"
        }
        data class PermanentlyFailed(val error: String, val timestamp: Long = System.currentTimeMillis()) : StartupState() {
            override fun toString() = "PERMANENT_FAIL: $error"
        }
    }

    private val startupState = AtomicReference<StartupState>(StartupState.Idle)
    private val currentDaemonState = AtomicReference<DaemonState>(DaemonState.Idle)

    @Volatile private var nativeThread: Thread? = null
    @Volatile private var monitorThread: Thread? = null
    @Volatile private var samReady = false
    @Volatile private var lastNativeHeartbeat: Long = 0
    @Volatile private var nativeThreadId: Long = -1
    @Volatile private var heartbeatThread: Thread? = null
    @Volatile private var nativeIsRunning = false

    private val stateHistory = ConcurrentLinkedQueue<String>()
    private val listeners = mutableListOf<DaemonStateListener>()
    private val listenersLock = Any()
    private val startupLock = Object()

    interface DaemonStateListener {
        fun onStateChanged(state: DaemonState)
        fun onError(error: String, isPermanent: Boolean)
        fun onDiagnostic(message: String)
    }

    fun start(): Boolean {
        val currentState = startupState.get()
        logDiagnostic("🚀 start() called | Current state: $currentState | Thread: ${Thread.currentThread().name}")

        if (currentState is StartupState.Running && currentState.samReady) {
            logDiagnostic("✅ Already running and SAM ready")
            return true
        }

        if (currentState is StartupState.PermanentlyFailed) {
            val msg = "🚫 Daemon permanently failed: ${currentState.error}"
            logDiagnostic(msg)
            notifyError(msg, isPermanent = true)
            return false
        }

        if (currentState is StartupState.Failed) {
            if (currentState.isNativeCrash) {
                val permError = "💥 Native crash detected previously - restart app to retry"
                startupState.set(StartupState.PermanentlyFailed(permError))
                notifyError(permError, isPermanent = true)
                return false
            }
            logDiagnostic("⚠️ Previous soft failure: ${currentState.error}, allowing retry")
        }

        if (!startupState.compareAndSet(currentState, StartupState.Starting)) {
            logDiagnostic("⏳ Another thread starting, waiting for result...")
            return waitForStartupResult()
        }

        logStateTransition("STARTING", "CAS succeeded from $currentState")
        return doStart()
    }

    suspend fun waitForReady(timeoutMs: Long = STARTUP_TIMEOUT_MS): Boolean = withContext(Dispatchers.IO) {
        val deadline = System.currentTimeMillis() + timeoutMs
        var lastLog = System.currentTimeMillis()
        var lastState: StartupState? = null

        logDiagnostic("⏱️ waitForReady started (timeout=${timeoutMs/1000}s)")

        while (System.currentTimeMillis() < deadline) {
            val state = startupState.get()

            if (state != lastState) {
                logDiagnostic("📊 State change: $lastState -> $state")
                lastState = state
            }

            when (state) {
                is StartupState.Running -> {
                    if (state.samReady) {
                        logDiagnostic("✅ SAM ready detected")
                        return@withContext true
                    }
                    val elapsed = System.currentTimeMillis() - state.startTime
                    if (elapsed > STARTUP_TIMEOUT_MS) {
                        logDiagnostic("⏰ Timeout while in RUNNING state (${elapsed}ms elapsed)")
                        return@withContext false
                    }
                }
                is StartupState.PermanentlyFailed -> {
                    logDiagnostic("🚫 Permanently failed during wait")
                    return@withContext false
                }
                is StartupState.Failed -> {
                    logDiagnostic("❌ Failed during wait: ${state.error}")
                    return@withContext false
                }
                else -> { /* still starting */ }
            }

            val now = System.currentTimeMillis()
            if (now - lastLog >= 30_000) {
                val remaining = (deadline - now) / 1000
                val nativeAlive = nativeThread?.isAlive == true
                val lastHeartbeat = if (lastNativeHeartbeat > 0) (now - lastNativeHeartbeat)/1000 else -1
                val globalHeartbeat = if (lastGlobalHeartbeatMs > 0) (now - lastGlobalHeartbeatMs)/1000 else -1
                logDiagnostic("⏳ Waiting... state=$state, remaining=${remaining}s, nativeAlive=$nativeAlive, " +
                        "localHeartbeat=${lastHeartbeat}s ago, globalHeartbeat=${globalHeartbeat}s ago")
                lastLog = now
            }

            delay(500)
        }

        logDiagnostic("⏰ waitForReady TIMEOUT after ${timeoutMs/1000}s")
        false
    }

    fun isReady(): Boolean {
        val state = startupState.get()
        return state is StartupState.Running && state.samReady && samReady
    }

    fun isRunning(): Boolean {
        val state = startupState.get()
        val threadAlive = nativeThread?.isAlive == true
        return (state is StartupState.Running || (state is StartupState.Starting && threadAlive))
    }

    fun getState(): DaemonState = currentDaemonState.get()
    fun getStateHistory(): List<String> = stateHistory.toList()

    fun addListener(l: DaemonStateListener) = synchronized(listenersLock) { listeners.add(l) }
    fun removeListener(l: DaemonStateListener) = synchronized(listenersLock) { listeners.remove(l) }

    fun markNativeCrashed(reason: String) {
        logDiagnostic("💥 External crash notification: $reason")
        val current = startupState.get()
        if (current !is StartupState.PermanentlyFailed) {
            startupState.set(StartupState.PermanentlyFailed("Native crash: $reason"))
            currentDaemonState.set(DaemonState.Error(reason, isPermanent = true))
            notifyError(reason, isPermanent = true)
        }
    }

    private fun doStart(): Boolean {
        logDiagnostic("═══════════════════════════════════════════════════════")
        logDiagnostic("🚀 I2P DAEMON START SEQUENCE INITIATED")
        logDiagnostic("═══════════════════════════════════════════════════════")
        updateDaemonState(DaemonState.Starting)

        return try {
            logStep(1, "JNI Bridge Verification")
            verifyJNIBridge()

            logStep(2, "Data Directory Preparation")
            prepareDataDirectory()

            logStep(3, "Configuration")
            val dataDir = writeConfiguration()

            // ── DIAGNOSTIC: Log config + cert tree before calling into native ──
            debugVerifySetup(dataDir)

            logStep(4, "Native Daemon Launch")
            val nativeStarted = startNativeDaemonWithTimeout(dataDir)
            if (!nativeStarted) {
                logDiagnostic("❌ Native daemon failed to start")
                return false
            }

            logStep(5, "Health Monitors")
            launchHealthMonitor()
            launchSamMonitor()

            logStep(6, "Waiting for SAM Ready")
            val success = waitForStartupResult()

            if (success) {
                logDiagnostic("✅ I2P DAEMON READY")
                startupState.set(StartupState.Running(samReady = true, startTime = System.currentTimeMillis()))
            } else {
                logDiagnostic("❌ I2P DAEMON FAILED TO REACH READY STATE")
            }

            success

        } catch (e: Exception) {
            logDiagnostic("💥 FATAL EXCEPTION in doStart(): ${e.javaClass.simpleName}: ${e.message}")
            Log.e(TAG, "Fatal error in doStart()", e)
            val errorMsg = "${e.javaClass.simpleName}: ${e.message}"
            startupState.set(StartupState.Failed(errorMsg))
            updateDaemonState(DaemonState.Error(errorMsg))
            notifyError(errorMsg, isPermanent = false)
            false
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // DIAGNOSTIC: dump config file and certificate tree to logcat so we can
    // see exactly what i2pd will find when it starts.
    // ─────────────────────────────────────────────────────────────────────────
    private fun debugVerifySetup(dataDir: String) {
        logDiagnostic("🔍 ══ PRE-START DIAGNOSTIC ══")

        val base = java.io.File(dataDir)
        val conf = java.io.File(base, "i2pd.conf")
        val certDir = java.io.File(base, "certificates")
        val caFile = java.io.File(base, "cacert.pem")

        // Config file
        if (conf.exists()) {
            logDiagnostic("🔍 Config: ${conf.absolutePath} (${conf.length()} bytes)")
            conf.readText().lines().forEachIndexed { i, line ->
                logDiagnostic("🔍   ${"%3d".format(i + 1)}: $line")
            }
        } else {
            logDiagnostic("🔍 ❌ Config file MISSING at ${conf.absolutePath}")
        }

        // Certificate tree
        if (certDir.exists()) {
            logDiagnostic("🔍 Cert dir: ${certDir.absolutePath}")
            certDir.walkTopDown().forEach { f ->
                if (f.isDirectory) {
                    logDiagnostic("🔍   [dir]  ${f.relativeTo(certDir)}/")
                } else {
                    logDiagnostic("🔍   [file] ${f.relativeTo(certDir)} (${f.length()} bytes)")
                }
            }
        } else {
            logDiagnostic("🔍 ❌ Cert directory MISSING at ${certDir.absolutePath}")
        }

        // CA bundle
        if (caFile.exists()) {
            logDiagnostic("🔍 CA bundle: ${caFile.absolutePath} (${caFile.length()} bytes)")
        } else {
            logDiagnostic("🔍 ❌ CA bundle MISSING at ${caFile.absolutePath}")
        }

        // netDb size
        val netDb = java.io.File(base, "netDb")
        val netDbCount = netDb.listFiles()?.size ?: 0
        logDiagnostic("🔍 netDb: $netDbCount router infos")

        // JNI data-dir value
        try {
            val jniDir = I2pdJNI.getDataDir()
            logDiagnostic("🔍 JNI getDataDir() = $jniDir")
            if (jniDir != dataDir) {
                logDiagnostic("🔍 ⚠️  JNI dataDir MISMATCH: expected=$dataDir, got=$jniDir")
            }
        } catch (e: Exception) {
            logDiagnostic("🔍 ⚠️  getDataDir() threw: ${e.message}")
        }

        logDiagnostic("🔍 ══ END DIAGNOSTIC ══")
    }

    private fun verifyJNIBridge() {
        try {
            val abi = I2pdJNI.getABICompiledWith()
            logDiagnostic("✅ JNI bridge OK | ABI: $abi | PID: ${android.os.Process.myPid()}")
        } catch (e: Exception) {
            logDiagnostic("❌ JNI bridge verification FAILED: ${e.message}")
            throw IllegalStateException("JNI bridge not functional: ${e.message}")
        }
    }

    private fun prepareDataDirectory() {
        val dataDir = java.io.File(context.filesDir, "i2pd")
        logDiagnostic("📁 Data directory: ${dataDir.absolutePath}")

        if (!dataDir.exists()) {
            logDiagnostic("📁 Creating fresh data directory")
            dataDir.mkdirs()
            return
        }

        logDiagnostic("🧹 Cleaning only lock/pid files (preserving netDb & addressbook)...")

        dataDir.listFiles()?.forEach { file ->
            if (file.name.endsWith(".pid") || file.name.endsWith(".lock") ||
                file.name == "i2pd.running" || file.name.endsWith(".dat")) {
                val deleted = file.delete()
                logDiagnostic("  🔓 Lock file ${file.name} deleted: $deleted")
            }
        }

        java.io.File(dataDir, "i2pd.conf").takeIf { it.exists() }?.let {
            val deleted = it.delete()
            logDiagnostic("  🗑️ Stale i2pd.conf deleted: $deleted")
        }

        val netDbSize = java.io.File(dataDir, "netDb").listFiles()?.size ?: 0
        logDiagnostic("  📦 netDb preserved ($netDbSize router infos)")
    }

    private fun writeConfiguration(): String {
        logDiagnostic("📋 Copying SSL/reseed certificates from assets...")
        I2pdConfig.copyCertificates(context)

        logDiagnostic("📋 Copying CA bundle from assets...")
        I2pdConfig.copyCABundle(context)

        I2pdConfig.writeConfig(context)
        val dataDir = java.io.File(context.filesDir, "i2pd").absolutePath
        I2pdJNI.setDataDir(dataDir)
        logDiagnostic("📝 Config written | dataDir=$dataDir")
        return dataDir
    }

    private fun startNativeDaemonWithTimeout(dataDir: String): Boolean {
        logDiagnostic("🔥 Starting native thread with priority $NATIVE_THREAD_PRIORITY")

        // Set SSL_CERT_FILE environment variable so that OpenSSL can find our CA bundle
        val caFile = File(dataDir, "cacert.pem").absolutePath
        try {
            Os.setenv("SSL_CERT_FILE", caFile, true)
            logDiagnostic("✅ Set SSL_CERT_FILE=$caFile")
        } catch (e: Exception) {
            logDiagnostic("⚠️ Failed to set SSL_CERT_FILE: ${e.message}")
        }

        // ── Redirect native stdout + stderr to a file BEFORE startDaemon() ───
        // i2pd always prints the real error message to stderr before calling
        // exit(). On Android there is no terminal so that output vanishes
        // unless we redirect fd 1 and fd 2 to a file we can read back.
        val nativeOutputFile = redirectNativeOutput(dataDir)

        // Tail both the i2pd log file and the native stderr capture file.
        startLogTailThread(dataDir)
        if (nativeOutputFile != null) startNativeOutputTailThread(nativeOutputFile)

        nativeIsRunning = true   // arm the heartbeat guard

        nativeThread = thread(name = "i2pd-native", isDaemon = true, priority = NATIVE_THREAD_PRIORITY) {
            val tid = Thread.currentThread().id
            nativeThreadId = tid
            lastNativeHeartbeat = System.currentTimeMillis()
            updateNativeHeartbeat()

            logDiagnostic("🧵 Native thread STARTED | TID=$tid | Priority=${Thread.currentThread().priority}")

            try {
                Thread.currentThread().name = "i2pd-native-$tid"

                // NOTE: setDataDir() was already called in writeConfiguration().
                // Do NOT call it again here — calling it twice resets i2pd
                // internal state and can cause silent early exit().

                updateNativeHeartbeat()

                logDiagnostic("🚀 Calling I2pdJNI.startDaemon() — blocks until daemon exits")

                // FIX: Heartbeat thread now only fires while nativeIsRunning is true.
                // As soon as startDaemon() returns (or the whole block exits), we set
                // nativeIsRunning = false so the heartbeat stops immediately.
                heartbeatThread = thread(name = "native-heartbeat", isDaemon = true) {
                    while (!Thread.currentThread().isInterrupted && nativeIsRunning) {
                        try {
                            Thread.sleep(5000)
                            if (nativeIsRunning) {   // re-check after sleep
                                updateNativeHeartbeat()
                            }
                        } catch (_: InterruptedException) {
                            return@thread
                        }
                    }
                    logDiagnostic("💓 Heartbeat thread exiting (nativeIsRunning=$nativeIsRunning)")
                }

                val result = I2pdJNI.startDaemon()

                // ── startDaemon() returned — daemon has exited ──────────────────
                nativeIsRunning = false              // FIX: stop the heartbeat NOW
                heartbeatThread?.interrupt()
                lastNativeHeartbeat = System.currentTimeMillis()
                updateNativeHeartbeat()

                logDiagnostic("⚠️ startDaemon() RETURNED: '$result'")

                if (result.contains("ERROR", ignoreCase = true) ||
                    result.contains("FAIL", ignoreCase = true)) {
                    throw IllegalStateException("Native daemon reported error: $result")
                }

                logDiagnostic("🛑 Native daemon exited normally (result: $result)")

            } catch (e: Throwable) {
                nativeIsRunning = false              // FIX: ensure heartbeat stops on exception too
                heartbeatThread?.interrupt()
                lastNativeHeartbeat = System.currentTimeMillis()
                updateNativeHeartbeat()

                val isFatal = when (e) {
                    is UnsatisfiedLinkError, is NoClassDefFoundError -> true
                    else -> {
                        val msg = e.message ?: ""
                        msg.contains("SIGSEGV") || msg.contains("SIGABRT") ||
                                msg.contains("SIGILL") || msg.contains("signal") ||
                                msg.contains("native") || msg.contains("libc") ||
                                msg.contains("tombstone")
                    }
                }

                logDiagnostic("💥 NATIVE THREAD EXCEPTION | ${e.javaClass.simpleName}: ${e.message} | Fatal=$isFatal")
                Log.e(TAG, "Native daemon error", e)

                val sw = StringWriter()
                e.printStackTrace(PrintWriter(sw))
                logDiagnostic("Stack: ${sw.toString().take(500)}")

                val errorState = if (isFatal) {
                    StartupState.PermanentlyFailed("Native crash: ${e.javaClass.simpleName}: ${e.message}")
                } else {
                    StartupState.Failed("Native error: ${e.message}", isNativeCrash = isFatal)
                }

                startupState.set(errorState)
                updateDaemonState(DaemonState.Error(e.message ?: "Native error", isPermanent = isFatal))
                notifyError(e.message ?: "Native error", isPermanent = isFatal)
            }
        }

        // ── Wait loop ─────────────────────────────────────────────────────────
        logDiagnostic("⏱️ Waiting for native thread initialization (max ${NATIVE_START_TIMEOUT_MS}ms)...")

        val startWait = System.currentTimeMillis()
        var lastLog = startWait
        var consecutiveDeadChecks = 0

        while (System.currentTimeMillis() - startWait < NATIVE_START_TIMEOUT_MS) {
            val elapsed = System.currentTimeMillis() - startWait
            val isAlive = nativeThread?.isAlive == true
            val state = startupState.get()
            val hasHeartbeat = lastGlobalHeartbeatMs > 0
            val timeSinceHeartbeat = if (hasHeartbeat) System.currentTimeMillis() - lastGlobalHeartbeatMs else Long.MAX_VALUE

            if (state is StartupState.Failed || state is StartupState.PermanentlyFailed) {
                logDiagnostic("❌ Native thread failed after ${elapsed}ms: $state")
                return false
            }

            if (!isAlive) {
                consecutiveDeadChecks++
                if (consecutiveDeadChecks > 3) {
                    logDiagnostic("💀 Native thread died silently after ${elapsed}ms")
                    checkLogcatForCrashes()
                    captureTombstones()
                    startupState.set(StartupState.PermanentlyFailed("Native thread died silently"))
                    return false
                }
            } else {
                consecutiveDeadChecks = 0
            }

            // FIX: Only accept heartbeat as "alive" if nativeIsRunning is still true.
            // If nativeIsRunning is false the heartbeat thread has already stopped and
            // the timestamp is stale from the moment startDaemon() returned.
            if (isAlive && elapsed > 3000 && nativeIsRunning) {
                if (hasHeartbeat && timeSinceHeartbeat < 10000) {
                    logDiagnostic("✅ Native thread alive with heartbeat after ${elapsed}ms | TID=$nativeThreadId")
                    return true
                }
                if (isSamPortOpen()) {
                    logDiagnostic("✅ SAM port open after ${elapsed}ms")
                    return true
                }
            }

            if (System.currentTimeMillis() - lastLog > 2000) {
                logDiagnostic("⏳ Waiting... elapsed=${elapsed}ms, alive=$isAlive, nativeRunning=$nativeIsRunning, " +
                        "hasHeartbeat=$hasHeartbeat, timeSinceHeartbeat=${timeSinceHeartbeat}ms, state=$state")
                lastLog = System.currentTimeMillis()
            }

            Thread.sleep(100)
        }

        logDiagnostic("⏰ Native start timeout after ${NATIVE_START_TIMEOUT_MS}ms")
        return false
    }

    /**
     * Redirect native file descriptors 1 (stdout) and 2 (stderr) to a file
     * before startDaemon() is called. i2pd prints its startup errors to stderr
     * using printf/fprintf before it ever opens its own log file. On Android
     * those writes go to /dev/null unless we intercept them here.
     *
     * Returns the output file, or null if the redirect failed.
     */
    private fun redirectNativeOutput(dataDir: String): java.io.File? {
        return try {
            val outFile = java.io.File(dataDir, "i2pd_native_output.txt")
            // Truncate on each run so we always see fresh output
            outFile.writeText("")

            val fd = Os.open(
                outFile.absolutePath,
                OsConstants.O_WRONLY or OsConstants.O_CREAT or OsConstants.O_TRUNC,
                0x1B6 // 0666
            )
            Os.dup2(fd, 1) // replace stdout
            Os.dup2(fd, 2) // replace stderr
            Os.close(fd)

            logDiagnostic("📢 Native stdout+stderr redirected to ${outFile.absolutePath}")
            outFile
        } catch (e: Exception) {
            logDiagnostic("⚠️ Could not redirect native output: ${e.message}")
            null
        }
    }

    /**
     * Tail the native stderr/stdout capture file continuously, piping every
     * line into logcat under the tag "i2pd-stderr". This gives us visibility
     * into what i2pd actually says before it calls exit().
     */
    private fun startNativeOutputTailThread(file: java.io.File) {
        thread(name = "i2pd-stderr-tail", isDaemon = true) {
            // Give the file a moment to receive content after startDaemon() is called
            Thread.sleep(500)
            try {
                file.bufferedReader().use { reader ->
                    while (!Thread.currentThread().isInterrupted) {
                        val line = reader.readLine()
                        if (line != null) {
                            Log.w("i2pd-stderr", line)
                            logDiagnostic("📢 [native-stderr] $line")
                        } else {
                            // Nothing new yet — check if the native process is still running
                            if (!nativeIsRunning && nativeThread?.isAlive == false) {
                                // Process ended; do one final drain
                                var last = reader.readLine()
                                while (last != null) {
                                    Log.w("i2pd-stderr", last)
                                    logDiagnostic("📢 [native-stderr] $last")
                                    last = reader.readLine()
                                }
                                break
                            }
                            Thread.sleep(100)
                        }
                    }
                }
            } catch (_: InterruptedException) {
            } catch (e: Exception) {
                logDiagnostic("📢 stderr-tail error: ${e.message}")
            }
        }
    }

    /**
     * Tail the i2pd log file in a background thread.
     * This lets us see any lines i2pd emits before it dies (even if it dies before
     * the file-logger initialises, the absence of the file itself is a diagnosis).
     */
    private fun startLogTailThread(dataDir: String) {
        thread(name = "i2pd-log-tail", isDaemon = true) {
            val logFile = java.io.File(dataDir, "i2pd.log")
            var waited = 0
            logDiagnostic("📜 Log-tail: waiting for ${logFile.absolutePath} to appear...")

            while (!logFile.exists() && waited < 8000) {
                Thread.sleep(200)
                waited += 200
            }

            if (!logFile.exists()) {
                logDiagnostic("🚨 LOG-TAIL: i2pd.log NEVER CREATED after 8s! " +
                        "i2pd exited before its own file-logger initialised — " +
                        "almost certainly a certificate path or config parse error.")
                return@thread
            }

            logDiagnostic("📜 Log-tail: file appeared after ${waited}ms, reading...")
            try {
                logFile.bufferedReader().use { reader ->
                    while (!Thread.currentThread().isInterrupted) {
                        val line = reader.readLine()
                        if (line != null) {
                            Log.d("i2pd-native-log", line)
                        } else {
                            Thread.sleep(150)
                        }
                    }
                }
            } catch (_: InterruptedException) {
                // expected shutdown
            } catch (e: Exception) {
                logDiagnostic("📜 Log-tail thread error: ${e.message}")
            }
        }
    }

    private fun launchHealthMonitor() {
        thread(name = "i2pd-health-monitor", isDaemon = true, priority = MONITOR_THREAD_PRIORITY) {
            logDiagnostic("🏥 Health monitor started")
            var checkCount = 0

            while (!Thread.currentThread().isInterrupted) {
                try {
                    Thread.sleep(HEALTH_CHECK_INTERVAL_MS)
                    checkCount++

                    val now = System.currentTimeMillis()
                    val state = startupState.get()
                    val threadAlive = nativeThread?.isAlive == true
                    val timeSinceHeartbeat = now - lastGlobalHeartbeatMs

                    if (state is StartupState.Running && threadAlive &&
                        lastGlobalHeartbeatMs > 0 && timeSinceHeartbeat > HEARTBEAT_TIMEOUT_MS) {

                        logDiagnostic("🚨 NATIVE THREAD STUCK: Alive but no heartbeat for ${timeSinceHeartbeat}ms")
                        checkLogcatForCrashes()
                        captureTombstones()

                        startupState.set(StartupState.PermanentlyFailed(
                            "Native thread stuck (no heartbeat for ${timeSinceHeartbeat}ms)"
                        ))
                        updateDaemonState(DaemonState.Error("Native daemon unresponsive", isPermanent = true))
                        notifyError("Native daemon unresponsive", isPermanent = true)
                        return@thread
                    }

                    if (state is StartupState.Running && threadAlive && timeSinceHeartbeat > 30_000) {
                        logDiagnostic("⚠️ HEALTH WARNING: No native heartbeat for ${timeSinceHeartbeat/1000}s")
                    }

                    if (checkCount % 12 == 0) {
                        val lastHb = if (lastGlobalHeartbeatMs > 0) (now - lastGlobalHeartbeatMs)/1000 else -1
                        logDiagnostic("💓 Health check #$checkCount | Thread alive: $threadAlive | " +
                                "Last heartbeat: ${lastHb}s ago | State: $state")
                    }

                } catch (e: InterruptedException) {
                    logDiagnostic("🏥 Health monitor interrupted")
                    return@thread
                }
            }
        }
    }

    private fun launchSamMonitor() {
        monitorThread?.interrupt()

        monitorThread = thread(name = "i2pd-sam-monitor", isDaemon = true, priority = MONITOR_THREAD_PRIORITY) {
            logDiagnostic("📡 SAM monitor started")
            val limit = (STARTUP_TIMEOUT_MS / 1000).toInt()
            var elapsed = 0

            while (elapsed < limit && !Thread.currentThread().isInterrupted) {
                val currentState = startupState.get()

                if (currentState is StartupState.Failed || currentState is StartupState.PermanentlyFailed) {
                    logDiagnostic("📡 SAM monitor detected failure, exiting")
                    return@thread
                }

                val samUpViaJni = try {
                    I2pdJNI.getSAMState()
                } catch (e: Exception) {
                    logDiagnostic("⚠️ getSAMState() threw at ${elapsed}s: ${e.message}")
                    false
                }

                if (samUpViaJni) {
                    logDiagnostic("📡 SAM state=true at ${elapsed}s, verifying with socket...")
                    if (isSamPortOpen()) {
                        val totalTime = elapsed
                        logDiagnostic("🎉 SAM FULLY READY after ${totalTime}s!")
                        samReady = true
                        startupState.set(StartupState.Running(samReady = true, startTime = System.currentTimeMillis()))
                        updateDaemonState(DaemonState.Ready)
                        synchronized(startupLock) { startupLock.notifyAll() }
                        return@thread
                    } else {
                        logDiagnostic("⚠️ SAM JNI says ready but socket not open yet")
                    }
                }

                updateProgressState(elapsed)

                try {
                    Thread.sleep(2000)
                } catch (_: InterruptedException) {
                    return@thread
                }
                elapsed += 2
            }

            logDiagnostic("⏰ SAM monitor TIMEOUT after ${elapsed}s")
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
            logDiagnostic("📊 Progress: ${currentDaemonState.get()} -> $newState (${elapsed}s elapsed)")
            updateDaemonState(newState)
        }
    }

    private fun waitForStartupResult(): Boolean {
        synchronized(startupLock) {
            var waited = 0
            val maxWait = STARTUP_TIMEOUT_MS.toInt()
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
        logDiagnostic("⏰ waitForStartupResult timeout")
        return false
    }

    private fun isSamPortOpen(): Boolean {
        return try {
            Socket("127.0.0.1", SAM_PORT).use { socket ->
                val connected = socket.isConnected
                logDiagnostic("🔌 SAM port check: connected=$connected")
                connected
            }
        } catch (e: Exception) {
            false
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // DIAGNOSTIC UTILITIES
    // ═══════════════════════════════════════════════════════════════════════

    private fun logStep(step: Int, description: String) {
        val msg = "STEP $step/$TOTAL_STEPS: $description"
        logDiagnostic(msg)
    }

    private fun logDiagnostic(message: String) {
        val timestamp = System.currentTimeMillis()
        val threadInfo = "${Thread.currentThread().name}|${Thread.currentThread().id}"
        val fullMsg = "[$timestamp][$threadInfo] $message"

        stateHistory.offer(fullMsg)
        if (stateHistory.size > 100) stateHistory.poll()

        Log.i(TAG, fullMsg)

        synchronized(listenersLock) {
            listeners.forEach {
                try { it.onDiagnostic(fullMsg) } catch (e: Exception) { }
            }
        }
    }

    private fun logStateTransition(toState: String, reason: String) {
        logDiagnostic("🔄 STATE TRANSITION -> $toState | Reason: $reason")
    }

    private fun updateDaemonState(state: DaemonState) {
        val oldState = currentDaemonState.getAndSet(state)
        if (oldState != state) {
            logStateTransition(state.toString(), "from $oldState")
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
        Log.e(TAG, "🚨 notifyError: $error (permanent=$isPermanent)")
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

    private fun captureTombstones() {
        logDiagnostic("🔍 Checking for tombstones...")
        try {
            val tombstoneDir = java.io.File("/data/tombstones/")
            if (tombstoneDir.exists() && tombstoneDir.isDirectory) {
                val recentTombstones = tombstoneDir.listFiles()
                    ?.filter { it.isFile && it.name.startsWith("tombstone_") }
                    ?.filter { System.currentTimeMillis() - it.lastModified() < 60000 }
                    ?.sortedByDescending { it.lastModified() }
                    ?.take(3) ?: emptyList()

                if (recentTombstones.isEmpty()) {
                    logDiagnostic("📋 No recent tombstones found")
                } else {
                    recentTombstones.forEach { tombstone ->
                        logDiagnostic("📋 TOMBSTONE FOUND: ${tombstone.name} (${tombstone.length()} bytes)")
                        try {
                            tombstone.readLines().take(50).forEach { line ->
                                if (line.contains("i2pd", ignoreCase = true) ||
                                    line.contains("libi2pd", ignoreCase = true) ||
                                    line.contains("signal", ignoreCase = true) ||
                                    line.contains("Build fingerprint", ignoreCase = true) ||
                                    line.contains("Abort message", ignoreCase = true)) {
                                    logDiagnostic("  > $line")
                                }
                            }
                        } catch (e: Exception) {
                            logDiagnostic("  ⚠️ Could not read tombstone: ${e.message}")
                        }
                    }
                }
            } else {
                logDiagnostic("📋 Tombstone directory not accessible")
            }
        } catch (e: Exception) {
            logDiagnostic("⚠️ Could not read tombstones: ${e.message}")
        }
    }

    private fun checkLogcatForCrashes() {
        logDiagnostic("🔍 Checking logcat for crash indicators...")
        try {
            val process = Runtime.getRuntime().exec("logcat -d -t 500 *:E")
            val reader = BufferedReader(InputStreamReader(process.inputStream))
            val recentLines = reader.readLines()
                .filter { line ->
                    CRASH_INDICATORS.any { indicator ->
                        line.contains(indicator, ignoreCase = true)
                    }
                }
                .takeLast(20)

            if (recentLines.isNotEmpty()) {
                logDiagnostic("💥 RECENT CRASH LOGS:")
                recentLines.forEach { logDiagnostic("  ! ${it.take(200)}") }
            } else {
                logDiagnostic("📋 No crash indicators in recent logs")
            }

            reader.close()
            process.destroy()
        } catch (e: Exception) {
            logDiagnostic("⚠️ Logcat check failed: ${e.message}")
        }
    }
}