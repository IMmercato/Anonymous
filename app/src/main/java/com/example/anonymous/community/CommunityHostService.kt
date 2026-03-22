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
import android.util.Base64
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.anonymous.MainActivity
import com.example.anonymous.R
import com.example.anonymous.i2p.SAMClient
import com.example.anonymous.media.MediaChunkManager
import com.example.anonymous.media.MediaProtocol
import com.example.anonymous.network.model.CommunityMessage
import com.example.anonymous.network.model.CommunityPacket
import com.example.anonymous.repository.CommunityRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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
import androidx.core.content.edit

class CommunityHostService : Service() {
    override fun onBind(p0: Intent?): IBinder? = null

    private data class MemberSocket(
        val writer: BufferedWriter,
        val peerDest: String
    )

    private val activeMembers = CopyOnWriteArrayList<MemberSocket>()

    private val replayBuffer = ArrayDeque<String>(REPLAY_BUFFER_SIZE)
    private val replayLock = Any()

    private var scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var wakeLock: PowerManager.WakeLock? = null
    private val json = Json { ignoreUnknownKeys = true }
    private lateinit var mediaManager: MediaChunkManager
    private var currentCommunityB32: String? = null

    @Volatile private var currentHostSession: SAMClient.SAMSession? = null
    @Volatile private var isLoopRunning = false
    @Volatile private var loopGeneration = 0
    @Volatile private var communityGroupKey: ByteArray? = null

    companion object {
        private const val TAG = "CommunityHostService"
        private const val NOTIFICATION_ID = 2
        private const val CHANNEL_ID = "community_host"
        private const val WAKELOCK_TAG = "Anonymous:CommunityHostWakeLock"
        private const val REPLAY_BUFFER_SIZE = 100
        private const val PREFS_NAME = "community_host_prefs"
        private const val PREF_KEY_B32 = "persisted_community_b32"
        const val EXTRA_COMMUNITY_B32 = "community_b32"
        const val EXTRA_INJECT_PACKET = "inject_packet"

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

        @Volatile var localPacketSink: ((String) -> Unit)? = null
        @Volatile var hostSessionActive: Boolean = false
    }

    override fun onCreate() {
        super.onCreate()
        mediaManager = MediaChunkManager.getInstance(this)
        createNotificationChannel()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, buildNotification("Starting…"), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, buildNotification("Starting…"))
        }

        acquireWakeLock()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        intent?.getStringExtra(EXTRA_INJECT_PACKET)?.let { rawPacket ->
            injectLocalPacket(rawPacket)
            return START_REDELIVER_INTENT
        }

        val communityId = intent?.getStringExtra(EXTRA_COMMUNITY_B32) ?: currentCommunityB32 ?: loadPersistedB32()
        if (communityId == null) {
            Log.e(TAG, "Started without community ID - stopping")
            stopSelf()
            return START_NOT_STICKY
        }
        persistB32(communityId)

        if (wakeLock?.isHeld != true) acquireWakeLock()

        if (!scope.isActive) {
            Log.i(TAG, "Scope was cancelled - rebuilding for new start")
            scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        }

        localPacketSink = ::injectLocalPacket

        val communityChanged = currentCommunityB32 != null && currentCommunityB32 != communityId

        currentCommunityB32 = communityId

        if (!isLoopRunning || communityChanged) {
            if (communityChanged) {
                Log.i(TAG, "Community changed to $communityId - restarting host loop")
                scope.coroutineContext[Job]?.children?.forEach { it.cancel() }
            }
            isLoopRunning = true
            val gen = ++loopGeneration
            scope.launch { runHostLoop(communityId, gen) }
        } else {
            Log.d(TAG, "Host loop already running for $communityId - skipping duplicate launch")
        }

        return START_REDELIVER_INTENT
    }

    override fun onDestroy() {
        localPacketSink = null
        hostSessionActive = false
        isLoopRunning = false
        communityGroupKey = null
        currentHostSession?.let {
            runCatching { SAMClient.getInstance().removeSession(it.id) }
            currentHostSession = null
        }
        releaseWakeLock()
        scope.cancel()
        super.onDestroy()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        val intent = Intent(this, CommunityHostService::class.java).apply {
            currentCommunityB32?.let { putExtra(EXTRA_COMMUNITY_B32, it) }
        }
        startService(intent)
        super.onTaskRemoved(rootIntent)
    }

    private suspend fun runHostLoop(communityB32: String, generation: Int) {
        var community = CommunityRepository.getInstance(this).getByB32(communityB32)
        if (community == null) {
            Log.w(TAG, "Community $communityB32 not found on first lookup - waiting for DataStore flush...")
            var retries = 0
            while (community == null && retries < 10 && scope.isActive) {
                delay(500L)
                community = CommunityRepository.getInstance(this).getByB32(communityB32)
                retries++
            }
        }
        if (community == null) {
            Log.e(TAG, "Community $communityB32 not found after retries - stopping")
            if (loopGeneration == generation) isLoopRunning = false
            stopSelf()
            return
        }

        // Cache GroupKey
        communityGroupKey = Base64.decode(community.groupKeyBase64, Base64.NO_WRAP)

        val sam = SAMClient.getInstance()
        updateNotification("Hosting ${community.name}")

        val tunnelsReady = waitForTunnelsReady(sam, 300_000L)
        if (!tunnelsReady) {
            Log.e(TAG, "Timed out waiting for I2P tunnels via service restart")
            if (loopGeneration == generation) isLoopRunning = false
            delay(30_000L)
            start(this, communityB32)
            return
        }

        var session: SAMClient.SAMSession? = null
        var retryDelay = 10_000L

        try {
            while (scope.isActive) {
                // Session creation
                try {
                    Log.i(TAG, "Creating host session for ${community.name} (this can take 1-3min)...")
                    session = sam.createStreamSession(community.samPrivateKey).getOrThrow()
                    currentHostSession = session
                    if (session.b32Address != community.b32Address) {
                        Log.e(TAG, "WRONG SESSION ADDRESS: got ${session.b32Address}, expected ${community.b32Address}. Private key mismatch!")
                        sam.removeSession(session.id)
                        session = null
                        delay(retryDelay)
                        continue
                    }
                    Log.i(TAG, "Host session open: ${session.b32Address} (expected: ${community.b32Address})")
                    retryDelay = 10_000L
                } catch (_: CancellationException) {
                    break
                } catch (e: Exception) {
                    Log.e(TAG, "Session creation failed: ${e.message} - retry in ${retryDelay / 1000}s")
                    delay(retryDelay)
                    retryDelay = minOf(retryDelay * 2, 120_000L)
                    continue
                }

                val leaseSetReady = waitForLeaseSetPropagation(sam, session.b32Address, 180_000L)
                if (!leaseSetReady) {
                    Log.w(TAG, "LeaseSet not confirmed after 3min - proceeding anyway")
                } else {
                    Log.i(TAG, "LeaseSet confirmed - members can connect")
                }

                // Accept loop
                hostSessionActive = true
                Log.i(TAG, "Accept loop started - hostSessionActive=true")
                while (scope.isActive) {
                    try {
                        val (socket, peerDest) = sam.acceptConnection(session!!.id).getOrThrow()
                        Log.i(TAG, "New member connected (${peerDest.take(20)}...)")
                        scope.launch { handleMember(socket, peerDest) }
                    } catch (_: CancellationException) {
                        return
                    } catch (e: Exception) {
                        if (!scope.isActive) return

                        val sessionDead = session!!.sessionSocket.isClosed
                        val samUnreachable = !runCatching { sam.connect() }.getOrDefault(true)

                        if (sessionDead || samUnreachable) {
                            Log.e(TAG, "Session dead (socketClosed=$sessionDead, samDown=$samUnreachable): ${e.message}")
                            hostSessionActive = false
                            runCatching { sam.removeSession(session.id) }
                            currentHostSession = null
                            session = null
                            delay(retryDelay)
                            retryDelay = minOf(retryDelay * 2, 120_000L)
                            break
                        } else {
                            Log.w(TAG, "Accept failed but session alive - retrying in 2s: ${e.message}")
                            delay(2_000L)
                        }
                    }
                }
            }
        } finally {
            if (loopGeneration == generation) {
                isLoopRunning = false
                hostSessionActive = false
            }
            session?.let { runCatching { sam.removeSession(it.id) } }
            if (currentHostSession?.id == session?.id) currentHostSession = null
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
        } catch (e: Exception) {
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
                            from.writer.newLine()
                            from.writer.flush()
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to serve chunk $index for $mediaId: ${e.message}")
                        }
                    } else {
                        Log.w(TAG, "MEDIA_GET for $mediaId chunk $index - not in cache yet")
                    }
                }
                else -> {
                    appendToReplayBuffer(raw)
                    fanOut(raw, excluding = from)
                    if (packet.type == MediaProtocol.TYPE_MSG) persistIncomingMsg(packet)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse packet: ${e.message} - raw len=${raw.length}")
        }
    }

    private fun persistIncomingMsg(packet: CommunityPacket) {
        val key = communityGroupKey ?: return
        val b32 = currentCommunityB32 ?: return
        scope.launch {
            try {
                val plaintext = CommunityEncryption.decrypt(packet.payload, key)
                CommunityRepository.getInstance(this@CommunityHostService).addMessage(
                    CommunityMessage(
                        id = "${packet.senderB32.take(8)}_${packet.timestamp}",
                        senderB32 = packet.senderB32,
                        senderName = packet.senderName ?: packet.senderB32.take(12),
                        content = plaintext,
                        timestamp = packet.timestamp,
                        communityB32 = b32,
                        mediaId = packet.mediaId
                    )
                )
            } catch (e: Exception) {
                Log.w(TAG, "persistIncomingMsg failed: ${e.message}")
            }
        }
    }

    private fun fanOut(packet: String, excluding: MemberSocket?) {
        val dead = mutableListOf<MemberSocket>()
        activeMembers.forEach { member ->
            if (excluding == null || member !== excluding) {
                try {
                    member.writer.write(packet)
                    member.writer.newLine()
                    member.writer.flush()
                } catch (_: Exception) {
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
                    writer.write(packet)
                    writer.newLine()
                    writer.flush()
                } catch (_: Exception) { }
            }
        }
    }

    private fun appendToReplayBuffer(packet: String) {
        synchronized(replayLock) {
            if (replayBuffer.size >= REPLAY_BUFFER_SIZE) replayBuffer.removeFirst()
            replayBuffer.addLast(packet)
        }
    }

    private fun injectLocalPacket(raw: String) {
        try {
            val packet = json.decodeFromString<CommunityPacket>(raw)
            if (packet.type != MediaProtocol.TYPE_MEDIA_CHUNK) {
                appendToReplayBuffer(raw)
            }
        } catch (_: Exception) {
            appendToReplayBuffer(raw)   // unknown format
        }
        fanOut(raw, excluding = null)
    }

    private fun persistB32(b32: String) {
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit {
                putString(PREF_KEY_B32, b32)
            }
    }

    private fun loadPersistedB32(): String? = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getString(PREF_KEY_B32, null)

    private suspend fun waitForTunnelsReady(sam: SAMClient, timeout: Long = 300_000L): Boolean {
        val deadline = System.currentTimeMillis() + timeout
        var attempt = 0
        Log.i(TAG, "Waiting for I2P tunnels to be ready...")
        while (scope.isActive && System.currentTimeMillis() < deadline) {
            attempt++
            val probe = runCatching { sam.createStreamSession(savedPrivateKey = null).getOrThrow() }
            if (probe.isSuccess) {
                sam.removeSession(probe.getOrThrow().id)
                Log.i(TAG, "Tunnels ready after $attempt attempts")
                return true
            }
            Log.d(TAG, "Tunnels probe attempt $attempt failed - retrying in 10s")
            delay(10_000L)      // 10s
        }
        return false
    }

    private suspend fun waitForLeaseSetPropagation(sam: SAMClient, b32: String, timeout: Long): Boolean = withContext(Dispatchers.IO) {
        val deadline = System.currentTimeMillis() + timeout
        var attempt = 0
        while (scope.isActive && System.currentTimeMillis() < deadline) {
            attempt++
            if (sam.namingLookup(b32).isSuccess) {
                Log.i(TAG, "LeaseSet visible after $attempt attempts")
                return@withContext true
            }
            Log.d(TAG, "LeaseSet not visible yet (attempt $attempt)...")
            delay(10_000L)
        }
        false
    }

    // WakeLock

    private fun acquireWakeLock() {
        try {
            if (wakeLock?.isHeld == true) return
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKELOCK_TAG).apply { acquire() }
        } catch (e: Exception) { Log.e(TAG, "WakeLock acquire failed: ${e.message}") }
    }

    private fun releaseWakeLock() {
        try { if (wakeLock?.isHeld == true) wakeLock?.release() } catch (_: Exception) { }
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