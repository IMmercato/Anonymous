package com.example.anonymous.i2p

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.anonymous.MainActivity
import com.example.anonymous.R

class I2pdService : Service() {

    private val binder = LocalBinder()
    private var daemon: I2pdDaemon? = null
    private var isRunning = false

    inner class LocalBinder : Binder() {
        fun getService(): I2pdService = this@I2pdService
    }

    companion object {
        private const val TAG = "I2pdService"
        private const val NOTIFICATION_ID = 1
        private const val CHANNEL_ID = "i2pd_foreground"

        fun start(context: Context) {
            val intent = Intent(context, I2pdService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, I2pdService::class.java)
            context.stopService(intent)
        }
    }

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "I2pdService created in process ${android.os.Process.myPid()}")
        createNotificationChannel()
        // Start as foreground IMMEDIATELY - required by Android 8+
        startForeground(NOTIFICATION_ID, buildNotification())
        daemon = I2pdDaemon.getInstance(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!isRunning) {
            Log.i(TAG, "Starting I2P daemon in foreground service")
            // Ensure we're foreground (in case onCreate wasn't called)
            startForeground(NOTIFICATION_ID, buildNotification())
            daemon?.start()
            isRunning = true
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        Log.i(TAG, "I2pdService destroying - BUT NOT STOPPING DAEMON")
        isRunning = false
        // DO NOT stop daemon - let it run until process dies naturally
        super.onDestroy()
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
            }

            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Anonymous Network")
            .setContentText("I2P routing active...")
            .setSmallIcon(R.drawable.ic_launcher_foreground) // Use your actual icon
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }
}