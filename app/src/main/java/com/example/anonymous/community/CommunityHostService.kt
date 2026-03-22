package com.example.anonymous.community

import android.content.Context
import android.util.Base64
import android.util.Log
import androidx.core.content.edit
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
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList

class CommunityHostService private constructor(private val context: Context) {

    private data class MemberSocket(
        val writer: BufferedWriter,
        val peerDest: String
    )

    private val activeMembers = CopyOnWriteArrayList<MemberSocket>()
    private val replayBuffer = ArrayDeque<String>(REPLAY_BUFFER_SIZE)
    private val replayLock = Any()

    private var scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val json = Json { ignoreUnknownKeys = true }
    private val mediaManager = MediaChunkManager.getInstance(context)
    private var currentCommunityB32: String? = null

    @Volatile private var currentHostSession: SAMClient.SAMSession? = null
    @Volatile private var isLoopRunning = false
    @Volatile private var loopGeneration = 0
    @Volatile private var communityGroupKey: ByteArray? = null

    companion object {
        private const val TAG = "CommunityHostService"
        private const val REPLAY_BUFFER_SIZE = 100
        private const val PREFS_NAME = "community_host_prefs"
        private const val PREF_KEY_B32 = "persisted_community_b32"

        @Volatile private var instance: CommunityHostService? = null

        fun getInstance(context: Context): CommunityHostService = instance ?: synchronized(this) { instance ?: CommunityHostService(context.applicationContext).also { instance = it } }

        fun start(context: Context, communityB32: String) {
            getInstance(context).startHosting(communityB32)
        }

        fun stop(context: Context) {
            getInstance(context).stopHosting()
        }

        @Volatile var localPacketSink: ((String) -> Unit)? = null
        @Volatile var hostSessionActive: Boolean = false
    }

    private fun startHosting(communityId: String) {
        if (!scope.isActive) {
            Log.i(TAG, "Scope was cancelled - rebuilding for new start")
            scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        }

        localPacketSink = ::injectLocalPacket

        val communityChanged = currentCommunityB32 != null && currentCommunityB32 != communityId
        currentCommunityB32 = communityId
        persistB32(communityId)

        if (!isLoopRunning || communityChanged) {
            if (communityChanged) {
                Log.i(TAG, "Community changed to $communityId - restarting host loop")
                scope.coroutineContext[Job]?.children?.forEach { it.cancel() }
            }
            isLoopRunning = true
            val gen = ++loopGeneration
            Log.i(TAG, "Launching runHostLoop gen=$gen for $communityId")
            scope.launch { runHostLoop(communityId, gen) }
        } else {
            Log.i(TAG, "Host loop already running for $communityId (gen=$loopGeneration) - skipping relaunch")
        }
    }

    private fun stopHosting() {
        localPacketSink = null
        hostSessionActive = false
        isLoopRunning = false
        communityGroupKey = null
        currentHostSession?.let {
            runCatching { SAMClient.getInstance().removeSession(it.id) }
            currentHostSession = null
        }
        scope.cancel()
    }

    private suspend fun runHostLoop(communityB32: String, generation: Int) {
        Log.i(TAG, "runHostLoop START gen=$generation b32=$communityB32")
        try {
            runHostLoopInner(communityB32, generation)
        } catch (e: CancellationException) {
            Log.i(TAG, "runHostLoop CANCELLED gen=$generation")
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "runHostLoop CRASHED unexpectedly gen=$generation: ${e.message}", e)
        } finally {
            if (loopGeneration == generation) {
                isLoopRunning = false
                hostSessionActive = false
            }
            Log.i(TAG, "runHostLoop DONE gen=$generation isLoopRunning=$isLoopRunning")
        }
    }

    private suspend fun runHostLoopInner(communityB32: String, generation: Int) {
        // Find Community
        var community = CommunityRepository.getInstance(context).getByB32(communityB32)
        if (community == null) {
            Log.w(TAG, "Community not found on first lookup - waiting for DataStore flush...")
            var retries = 0
            while (community == null && retries < 10 && scope.isActive) {
                delay(500L)
                community = CommunityRepository.getInstance(context).getByB32(communityB32)
                retries++
            }
        }
        if (community == null) {
            Log.e(TAG, "Community $communityB32 not found after retries - stopping")
            return
        }
        Log.i(
            TAG,
            "Community found: '${community.name}' samKey=${if (community.samPrivateKey != null) "present" else "MISSING"}"
        )

        // Validate & Cache GroupKey
        val groupKey = try {
            Base64.decode(community.groupKeyBase64, Base64.NO_WRAP)
        } catch (e: Exception) {
            Log.e(TAG, "Invalid groupKeyBase64 - cannot host: ${e.message}")
            return
        }
        communityGroupKey = groupKey

        if (community.samPrivateKey == null) {
            Log.e(
                TAG,
                "Community '${community.name}' has no SAM private key - only the creator can host"
            )
            return
        }

        val sam = SAMClient.getInstance()

        val samReady = waitForSamBridge(sam, 300_000L)
        if (!samReady) {
            Log.e(TAG, "SAM bridge not reachable after 5min - giving up")
            return
        }

        var session: SAMClient.SAMSession? = null
        var retryDelay = 10_000L

        while (scope.isActive && loopGeneration == generation) {
            // Session creation
            try {
                Log.i(
                    TAG,
                    "Creating host SAM session for '${community.name}' (may take 30-90 s)..."
                )
                session = sam.createStreamSession(community.samPrivateKey).getOrThrow()
                currentHostSession = session
                if (!session.b32Address.equals(community.b32Address, ignoreCase = true)) {
                    Log.e(
                        TAG,
                        "Address mismatch: session=${session.b32Address} expected=${community.b32Address} - key mismatch?"
                    )
                    sam.removeSession(session.id)
                    currentHostSession = null
                    session = null
                    delay(retryDelay)
                    retryDelay = minOf(retryDelay * 2, 120_000L)
                    continue
                }
                Log.i(TAG, "Host SAM session ready: ${session.b32Address}")

                hostSessionActive = true
                retryDelay = 10_000L

                // LeaseSet
                val capturedSession = session
                scope.launch {
                    val ready =
                        waitForLeaseSetPropagation(sam, capturedSession.b32Address, 180_000L)
                    if (ready) Log.i(TAG, "LeaseSet confirmed - members can now connect")
                    else Log.w(
                        TAG,
                        "LeaseSet not confirmed after 3min - members will retry on their own"
                    )
                }
            } catch (_: CancellationException) {
                break
            } catch (e: Exception) {
                Log.e(TAG, "Session creation failed: ${e.message} - retry in ${retryDelay / 1000}s")
                hostSessionActive = false
                delay(retryDelay)
                retryDelay = minOf(retryDelay * 2, 120_000L)
                continue
            }

            // Accept loop
            Log.i(TAG, "Accept loop started for '${community.name}'")
            while (scope.isActive && loopGeneration == generation) {
                try {
                    val (socket, peerDest) = sam.acceptConnection(session!!.id).getOrThrow()
                    Log.i(TAG, "New member connected (${peerDest.take(20)}...)")
                    scope.launch { handleMember(socket, peerDest) }
                } catch (_: CancellationException) {
                    return
                } catch (e: Exception) {
                    if (!scope.isActive || loopGeneration != generation) return

                    val sessionDead = session?.sessionSocket?.isClosed ?: true

                    if (sessionDead) {
                        Log.e(TAG, "Session dead (closed=$sessionDead): ${e.message}")
                        hostSessionActive = false
                        session?.id?.let { runCatching { sam.removeSession(it) } }
                        currentHostSession = null
                        session = null
                        delay(retryDelay)
                        retryDelay = minOf(retryDelay * 2, 120_000L)
                        break
                    } else {
                        Log.w(TAG, "No peer connected yet - continuing to wait")
                    }
                }
            }
        }

        // Cleanup
        session?.let { runCatching { sam.removeSession(it.id) } }
        if (currentHostSession?.id == session?.id) currentHostSession = null
        Log.i(TAG, "Host loop exited cleanly gen=$generation")
    }

    private suspend fun handleMember(socket: Socket, peerDest: String) = withContext(Dispatchers.IO) {
        val writer = BufferedWriter(OutputStreamWriter(socket.getOutputStream()))
        val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
        val member = MemberSocket(writer, peerDest)

        activeMembers.add(member)
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
                    appendToReplayBuffer(raw)
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
                CommunityRepository.getInstance(context).addMessage(
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
            appendToReplayBuffer(raw)
        }
        fanOut(raw, excluding = null)
    }

    private fun persistB32(b32: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit {
                putString(PREF_KEY_B32, b32)
            }
    }

    private suspend fun waitForSamBridge(sam: SAMClient, timeout: Long = 300_000L): Boolean {
        val deadline = System.currentTimeMillis() + timeout
        var attempt = 0
        Log.i(TAG, "Waiting for SAM bridge to be reachable...")
        while (scope.isActive && System.currentTimeMillis() < deadline) {
            attempt++
            if (sam.connect()) {
                Log.i(TAG, "SAM bridge reachable (attempt $attempt)")
                return true
            }
            Log.d(TAG, "SAM bridge not ready yet (attempt $attempt) - retrying in 5s")
            delay(5_000L)      // 5s
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
}