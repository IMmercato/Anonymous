package com.example.anonymous.community

import android.content.Context
import android.net.Uri
import android.util.Base64
import android.util.Log
import androidx.core.content.edit
import com.example.anonymous.i2p.SAMClient
import com.example.anonymous.media.MediaChunkManager
import com.example.anonymous.media.MediaProtocol
import com.example.anonymous.network.model.Community
import com.example.anonymous.network.model.CommunityMessage
import com.example.anonymous.network.model.CommunityPacket
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.util.concurrent.ConcurrentLinkedDeque

@Serializable
private data class QueuedCommunityMsg(val text: String, val timestamp: Long)

class CommunityMemberClient(
    private val community: Community,
    private val myB32: String,
    private val myName: String,
    private val onMessage: (CommunityMessage) -> Unit,
    private val onConnectionStateChanged: (connected: Boolean) -> Unit = { }
) {
    companion object {
        private const val TAG = "CommunityMemberClient"
        private const val RECONNECT_MS = 15_000L
        private const val RECONNECT_MAX_MS = 5 * 60_000L      // 5 minutes cap
        private const val CHUNK_SEND_DELAY_MS = 30L
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val groupKey = Base64.decode(community.groupKeyBase64, Base64.NO_WRAP)
    private val json = Json { ignoreUnknownKeys = true }
    private lateinit var mediaManager: MediaChunkManager

    @Volatile private var writer: BufferedWriter? = null
    @Volatile private var appContext: Context? = null

    // Messages queued while not connected
    private val pendingMessages = ConcurrentLinkedDeque<Pair<String, Long>>()

    fun connect(context: Context) {
        appContext = context.applicationContext
        mediaManager = MediaChunkManager.getInstance(context)
        loadOfflineQueue()
        scope.launch { connectLoop() }
    }

    // Offline
    private fun saveOfflineQueue() {
        val context = appContext ?: return
        val prefs = context.getSharedPreferences("comm_queue_$myB32", Context.MODE_PRIVATE)
        val list = pendingMessages.map { QueuedCommunityMsg(it.first, it.second) }
        prefs.edit { putString(community.b32Address, json.encodeToString(list)) }
    }
    private fun loadOfflineQueue() {
        val context = appContext ?: return
        val prefs = context.getSharedPreferences("comm_queue_$myB32", Context.MODE_PRIVATE)
        val saved = prefs.getString(community.b32Address, null)
        if (saved != null) {
            try {
                val list = json.decodeFromString<List<QueuedCommunityMsg>>(saved)
                list.forEach { pendingMessages.offer(it.text to it.timestamp) }
                Log.i(TAG, "Loaded ${list.size} offline messages for ${community.name}")
            } catch (e: Exception) { Log.e(TAG, "Failed to load offline queue", e) }
        }

    }

    fun sendMessage(text: String, timestamp: Long = System.currentTimeMillis()) {
        scope.launch {
            val w = writer ?: run {
                Log.w(TAG, "sendMessage: not connected - queueing")
                pendingMessages.offer(text to timestamp)
                saveOfflineQueue()
                return@launch
            }
            doSendText(w, text, timestamp)
        }
    }

    private fun doSendText(w: BufferedWriter, text: String, timestamp: Long) {
        try {
            val encrypted = CommunityEncryption.encrypt(text, groupKey)
            val packet = CommunityPacket(
                type = MediaProtocol.TYPE_MSG,
                senderB32 = myB32,
                senderName = myName,
                payload = encrypted,
                timestamp = timestamp
            )
            sendLine(w, json.encodeToString(packet))
        } catch (e: Exception) {
            Log.e(TAG, "doSendText failed: ${e.message}")
            writer = null
        }
    }

    fun sendMedia(uri: Uri, caption: String = "", timestamp: Long = System.currentTimeMillis()): String? {
        val w = writer ?: run { Log.w(TAG, "sendMedia: not connected"); return null }

        val (meta, chunks) = mediaManager.prepareMedia(uri) ?: run {
            Log.e(TAG, "sendMedia: could not compress/chunk image")
            return null
        }

        scope.launch {
            try {
                val metaPacket = CommunityPacket(
                    type = MediaProtocol.TYPE_MEDIA_META,
                    senderB32 = myB32,
                    senderName = myName,
                    timestamp = timestamp,
                    meta = meta
                )
                sendLine(w, json.encodeToString(metaPacket))

                val msgText = caption.ifBlank { "media" }
                val encrypted = CommunityEncryption.encrypt(msgText, groupKey)
                val msgPacket = CommunityPacket(
                    type = MediaProtocol.TYPE_MSG,
                    senderB32 = myB32,
                    senderName = myName,
                    payload = encrypted,
                    timestamp = timestamp,
                    mediaId = meta.mediaId
                )
                sendLine(w, json.encodeToString(msgPacket))

                chunks.forEachIndexed { index, chunkData ->
                    val chunkPacket = CommunityPacket(
                        type = MediaProtocol.TYPE_MEDIA_CHUNK,
                        senderB32 = myB32,
                        mediaId = meta.mediaId,
                        chunkIndex = index,
                        chunkData = chunkData,
                        timestamp = timestamp
                    )
                    sendLine(w, json.encodeToString(chunkPacket))
                    delay(CHUNK_SEND_DELAY_MS)
                }

                Log.i(TAG, "Media ${meta.mediaId} uploaded (${chunks.size} chunks)")
            } catch (e: Exception) {
                Log.e(TAG, "sendMedia upload failed: ${e.message}")
                writer = null
            }
        }

        return meta.mediaId
    }

    fun pullMissingChunks(mediaId: String) {
        scope.launch {
            val w = writer ?: run { Log.w(TAG, "pullMissingChunks: not connected"); return@launch }
            val missing = mediaManager.getMissingChunkIndices(mediaId)
            if (missing.isEmpty()) return@launch

            Log.i(TAG, "Pulling ${missing.size} missing chunks for $mediaId")
            missing.forEach { index ->
                val getPacket = CommunityPacket(
                    type = MediaProtocol.TYPE_MEDIA_GET,
                    senderB32 = myB32,
                    mediaId = mediaId,
                    chunkIndex = index,
                    timestamp = System.currentTimeMillis()
                )
                sendLine(w, json.encodeToString(getPacket))
                delay(10L)
            }
        }
    }

    fun disconnect() {
        scope.cancel()
        writer = null
    }

    private suspend fun connectLoop() {
        val sam = SAMClient.getInstance()
        var sessionId: String? = null
        var reconnectDelayMs = RECONNECT_MS

        try {
            while (scope.isActive) {
                try {
                    if (sessionId == null) {
                        val session = sam.createStreamSession().getOrThrow()
                        sessionId = session.id
                    }

                    val socket = sam.connectToPeer(sessionId, community.b32Address).getOrThrow()

                    val w = BufferedWriter(OutputStreamWriter(socket.getOutputStream()))
                    val r = BufferedReader(InputStreamReader(socket.getInputStream()))
                    writer = w
                    reconnectDelayMs = RECONNECT_MS
                    onConnectionStateChanged(true)
                    Log.i(TAG, "Connected to community ${community.name}")

                    // Flush offline queue
                    var queued = pendingMessages.poll()
                    var queueChanged = false
                    while (queued != null) {
                        val (text, ts) = queued
                        Log.d(TAG, "Flushing queued message ts=$ts")
                        doSendText(w, text, ts)
                        queueChanged = true
                        queued = pendingMessages.poll()
                    }
                    if (queueChanged) saveOfflineQueue()

                    try {
                        var line: String?
                        while (r.readLine().also { line = it } != null) { handlePacket(line!!) }
                    } finally {
                        runCatching { socket.close() }
                    }
                } catch (_: CancellationException) {
                    break
                } catch (e: Exception) {
                    Log.w(TAG, "Community connection lost: ${e.message}")
                    val isLeaseSetError = e.message?.contains("LeaseSet", ignoreCase = true) == true || e.message?.contains("CANT_REACH", ignoreCase = true) == true
                    val liveSession = sessionId?.let { id ->
                        sam.getActiveSessions().find { it.id == id }
                    }
                    if (!isLeaseSetError && (liveSession == null || liveSession.sessionSocket.isClosed)) {
                        sessionId?.let { sam.removeSession(it) }
                        sessionId = null
                    }
                    if (isLeaseSetError) reconnectDelayMs = RECONNECT_MS
                    else reconnectDelayMs = minOf(reconnectDelayMs * 2, RECONNECT_MAX_MS)
                } finally {
                    writer = null
                    onConnectionStateChanged(false)
                }

                if (scope.isActive) {
                    Log.i(TAG, "Reconnecting to ${community.name} in ${reconnectDelayMs / 1000}s")
                    delay(reconnectDelayMs)
                }
            }
        } finally {
            sessionId?.let {
                sam.removeSession(it)
                Log.d(TAG, "Session $it cleaned up on connectLoop exit")
            }
        }
    }

    private fun handlePacket(raw: String) {
        try {
            val packet = json.decodeFromString<CommunityPacket>(raw)
            when (packet.type) {
                MediaProtocol.TYPE_MSG -> handleMessage(packet)
                MediaProtocol.TYPE_MEDIA_META -> handleMediaMeta(packet)
                MediaProtocol.TYPE_MEDIA_CHUNK -> handleMediaChunk(packet)
                else -> Log.v(TAG, "Unknown packet type: ${packet.type}")
            }
        } catch (e: Exception) {
            Log.w(TAG, "handlePacket error: ${e.message}")
        }
    }

    private fun handleMessage(packet: CommunityPacket) {
        val plaintext = CommunityEncryption.decrypt(packet.payload, groupKey)
        val id = "${packet.senderB32.take(8)}_${packet.timestamp}"
        onMessage(
            CommunityMessage(
                id = id,
                senderB32 = packet.senderB32,
                senderName = packet.senderName,
                content = plaintext,
                timestamp = packet.timestamp,
                communityB32 = community.b32Address,
                mediaId = packet.mediaId
            )
        )
    }

    private fun handleMediaMeta(packet: CommunityPacket) {
        val meta = packet.meta ?: return
        mediaManager.registerMeta(meta)
        Log.d(TAG, "Registered media meta ${meta.mediaId} (${meta.chunkCount} chunks)")
    }

    private fun handleMediaChunk(packet: CommunityPacket) {
        val mediaId = packet.mediaId ?: return
        if (packet.chunkIndex < 0 || packet.chunkData.isEmpty()) return
        mediaManager.onChunkReceived(mediaId, packet.chunkIndex, packet.chunkData)
    }

    private fun sendLine(writer: BufferedWriter, line: String) {
        writer.write(line)
        writer.newLine()
        writer.flush()
    }
}