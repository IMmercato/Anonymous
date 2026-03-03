package com.example.anonymous.i2p

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.anonymous.MainActivity
import com.example.anonymous.R
import kotlinx.coroutines.*

class I2pdService : Service() {

    private val binder = LocalBinder()
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var wakeLock: PowerManager.WakeLock? = null

    inner class LocalBinder : Binder() {
        fun getService(): I2pdService = this@I2pdService
    }

    companion object {
        private const val TAG = "I2pdService"
        private const val NOTIFICATION_ID = 1
        private const val CHANNEL_ID = "i2pd_foreground"
        private const val WAKELOCK_TIMEOUT_MS = 30 * 60 * 1000L

        @Volatile private var daemonStarted = false

        fun start(context: Context) {
            Log.i(TAG, "start() called from ${context.javaClass.simpleName}")
            val intent = Intent(context, I2pdService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            Log.i(TAG, "stop() called")
            context.stopService(Intent(context, I2pdService::class.java))
        }
    }

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "I2pdService created in process ${android.os.Process.myPid()}")
        createNotificationChannel()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                buildNotification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIFICATION_ID, buildNotification())
        }
        Log.d(TAG, "startForeground() called")

        acquireWakeLock()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i(TAG, "onStartCommand flags=$flags startId=$startId")

        if (wakeLock?.isHeld != true) {
            acquireWakeLock()
        }

        if (!daemonStarted) {
            daemonStarted = true
            serviceScope.launch {
                startDaemon()
            }
        } else {
            Log.i(TAG, "Daemon already started in this process — skipping re-launch")
        }

        return START_STICKY
    }

    private fun acquireWakeLock() {
        try {
            if (wakeLock?.isHeld == true) return
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "Anonymous:I2pdWakeLock"
            ).apply {
                acquire(WAKELOCK_TIMEOUT_MS)
            }
            Log.i(TAG, "WakeLock acquired (timeout=${WAKELOCK_TIMEOUT_MS / 6000}min)")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to acquire WakeLock: ${e.message}")
        }
    }

    private fun releaseWakeLock() {
        try {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
                Log.i(TAG, "WakeLock released")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing WakeLock: ${e.message}")
        }
    }

    private suspend fun startDaemon() {
        Log.i(TAG, "Starting I2P daemon from within service process")
        val daemon = I2pdDaemon.getInstance(applicationContext)
        val started = daemon.start()
        if (started) {
            Log.i(TAG, "I2P daemon started successfully")
        } else {
            Log.e(TAG, "Failed to start I2P daemon")
        }
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        Log.i(TAG, "I2pdService onDestroy — NOT stopping daemon (intentional)")
        serviceScope.cancel()
        releaseWakeLock()
        super.onDestroy()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        Log.i(TAG, "onTaskRemoved — restarting service to keep daemon alive")
        startService(Intent(this, I2pdService::class.java))
        super.onTaskRemoved(rootIntent)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "I2P Network Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Anonymous I2P routing service"
                setShowBadge(false)
                enableLights(false)
                enableVibration(false)
            }
            getSystemService(NotificationManager::class.java)
                .createNotificationChannel(channel)
            Log.d(TAG, "Notification channel created")
        }
    }

    private fun buildNotification(): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Anonymous Network")
            .setContentText("I2P routing active")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setSilent(true)
            .build()
    }
}