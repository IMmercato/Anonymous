package com.example.anonymous.community

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.anonymous.MainActivity
import com.example.anonymous.R
import com.example.anonymous.i2p.SAMClient
import com.example.anonymous.media.MediaChunkManager
import com.example.anonymous.media.MediaProtocol
import com.example.anonymous.network.model.CommunityPacket
import com.example.anonymous.repository.CommunityRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.BufferedReader
import java.net.Socket
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.util.concurrent.CopyOnWriteArrayList

class CommunityHostService : Service() {
    override fun onBind(p0: Intent?): IBinder? = null

    private data class MemberSocket(
        val writer: BufferedWriter,
        val peerDest: String
    )

    private val activeMembers = CopyOnWriteArrayList<MemberSocket>()

    private val replayBuffer = ArrayDeque<String>(REPLAY_BUFFER_SIZE)
    private val replayLock = Any()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var wakeLock: PowerManager.WakeLock? = null
    private val json = Json { ignoreUnknownKeys = true }
    private lateinit var mediaManager: MediaChunkManager

    companion object {
        private const val TAG = "CommunityHostService"
        private const val NOTIFICATION_ID = 2
        private const val CHANNEL_ID = "community_host"
        private const val WAKELOCK_TAG = "Anonymous:CommunityHostWakeLock"
        private const val REPLAY_BUFFER_SIZE = 100
        const val EXTRA_COMMUNITY_B32 = "community_b32"

        fun start(context: Context, communityB32: String) {
            val intent = Intent(context, CommunityHostService::class.java)
                .putExtra(EXTRA_COMMUNITY_B32, communityB32)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                context.startForegroundService(intent)
            else
                context.startService(intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, CommunityHostService::class.java))
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, buildNotification("Starting…"), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, buildNotification("Starting…"))
        }

        acquireWakeLock()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val communityId = intent?.getStringExtra(EXTRA_COMMUNITY_B32)
        if (communityId == null) {
            Log.e(TAG, "Started without community ID - stopping")
            stopSelf()
            return START_NOT_STICKY
        }

        if (wakeLock?.isHeld != true) acquireWakeLock()

        scope.launch { runHostLoop(communityId) }

        return START_STICKY
    }

    override fun onDestroy() {
        releaseWakeLock()
        scope.cancel()
        super.onDestroy()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        startService(Intent(this, CommunityHostService::class.java))
        super.onTaskRemoved(rootIntent)
    }

    private suspend fun runHostLoop(communityB32: String) {
        val community = CommunityRepository.getInstance(this).getByB32(communityB32)
        if (community == null) {
            Log.e(TAG, "Community $communityB32 not found - stopping")
            stopSelf()
            return
        }
        if (community.samPrivateKey == null) {
            Log.e(TAG, "Community has no private key - not the creator's device?")
            stopSelf()
            return
        }

        val sam = SAMClient.getInstance()
        updateNotification("Hosting ${community.name}")

        var retryDelay = 10_000L

        while (scope.isActive) {
            try {
                val session = sam.createStreamSession().getOrThrow()
                Log.i(TAG, "Host session open: ${community.b32Address}")
                retryDelay = 10_000L

                while (scope.isActive) {
                    val (socket, peerDest) = sam.acceptConnection(session.id).getOrThrow()
                    Log.i(TAG, "New member connected (${peerDest.take(20)}...)")
                    scope.launch { handleMember(socket, peerDest) }
                }
            } catch (_ : CancellationException) {
                break
            } catch (e : Exception) {
                Log.e(TAG, "Host loop error: ${e.message} - retry in ${retryDelay / 1000}s")
                delay(retryDelay)
                retryDelay = minOf(retryDelay * 2, 120_000L)
            }
        }

        Log.i(TAG, "Host loop exited cleanly")
    }

    private suspend fun handleMember(socket: Socket, peerDest: String) = withContext(Dispatchers.IO) {
        val writer = BufferedWriter(OutputStreamWriter(socket.getOutputStream()))
        val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
        val member = MemberSocket(writer, peerDest)

        activeMembers.add(member)
        updateNotification("Hosting · ${activeMembers.size} members online")

        try {
            replayHistory(writer)

            var line: String?
            while (reader.readLine().also { line = it } != null) {
                routePacket(line!!, from = member)
            }
        } catch (e : Exception) {
            Log.d(TAG, "Member socket closed: ${e.message}")
        } finally {
            activeMembers.remove(member)
            runCatching { socket.close() }
            updateNotification("Hosting · ${activeMembers.size} members online")
            Log.i(TAG, "Member disconnected. Remaining: ${activeMembers.size}")
        }
    }

    /**
     * • MSG / MEDIA_META  → replay buffer + fan-out
     * • MEDIA_CHUNK       → MediaChunkManager cache + fan-out (NO replay)
     * • MEDIA_GET         → serve chunk from cache back to requesting member only
     */
    private fun routePacket(raw: String, from: MemberSocket) {
        try {
            val packet = json.decodeFromString<CommunityPacket>(raw)

            when (packet.type) {
                MediaProtocol.TYPE_MEDIA_META -> {
                    packet.meta?.let { mediaManager.registerMeta(it) }
                    appendToReplayBuffer(raw)       // meta is small - replay it
                    fanOut(raw, excluding = from)
                }

                MediaProtocol.TYPE_MEDIA_CHUNK -> {
                    val mediaId = packet.mediaId
                    if (mediaId != null && packet.chunkIndex >= 0 && packet.chunkData.isNotEmpty()) {
                        mediaManager.onChunkReceived(mediaId, packet.chunkIndex, packet.chunkData)
                    }
                    fanOut(raw, excluding = from)
                }

                MediaProtocol.TYPE_MEDIA_GET -> {
                    val mediaId = packet.mediaId ?: return
                    val index = packet.chunkIndex
                    val chunkB64 = mediaManager.getChunk(mediaId, index)

                    if (chunkB64 != null) {
                        val response = json.encodeToString(
                            CommunityPacket(
                                type = MediaProtocol.TYPE_MEDIA_CHUNK,
                                senderB32 = "host",
                                mediaId = mediaId,
                                chunkIndex = index,
                                chunkData = chunkB64,
                                timestamp = System.currentTimeMillis()
                            )
                        )
                        try {
                            from.writer.write(response)
                            from.writer.newLine(); from.writer.flush()
                        } catch (e : Exception) {
                            Log.e(TAG, "Failed to serve chunk $index for $mediaId: ${e.message}")
                        }
                    } else {
                        Log.w(TAG, "MEDIA_GET for $mediaId chunk $index - not in cache yet")
                    }
                }
                else -> {
                    appendToReplayBuffer(raw)
                    fanOut(raw, excluding = from)
                }
            }
        } catch (e : Exception) {
            Log.w(TAG, "Failed to parse packet: ${e.message} - raw len=${raw.length}")
        }
    }

    private fun fanOut(packet: String, excluding: MemberSocket) {
        val dead = mutableListOf<MemberSocket>()
        activeMembers.forEach { member ->
            if (member !== excluding) {
                try {
                    member.writer.write(packet); member.writer.newLine(); member.writer.flush()
                } catch (_ : Exception) {
                    dead.add(member)
                }
            }
        }
        if (dead.isNotEmpty()) activeMembers.removeAll(dead.toSet())
    }

    private fun replayHistory(writer: BufferedWriter) {
        synchronized(replayLock) {
            replayBuffer.forEach { packet ->
                try {
                    writer.write(packet); writer.newLine(); writer.flush()
                } catch (_ : Exception) { }
            }
        }
    }

    private fun appendToReplayBuffer(packet: String) {
        synchronized(replayLock) {
            if (replayBuffer.size >= REPLAY_BUFFER_SIZE) replayBuffer.removeFirst()
            replayBuffer.addLast(packet)
        }
    }

    // WakeLock

    private fun acquireWakeLock() {
        try {
            if (wakeLock?.isHeld == true) return
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKELOCK_TAG).apply { acquire() }
        } catch (e : Exception) { Log.e(TAG, "WakeLock acquire failed: ${e.message}") }
    }

    private fun releaseWakeLock() {
        try { if (wakeLock?.isHeld == true) wakeLock?.release() } catch (_ : Exception) { }
    }

    // Notification

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, buildNotification(text))
    }

    private fun buildNotification(text: String = "Community host active"): Notification {
        val pi = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Community Host")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(pi)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setSilent(true)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "Community Host Service", NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keep your community relay running"
                setShowBadge(false); enableLights(false); enableVibration(false)
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }
}