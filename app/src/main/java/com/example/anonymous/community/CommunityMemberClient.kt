package com.example.anonymous.community

import android.util.Base64
import android.util.Log
import com.example.anonymous.i2p.SAMClient
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
import kotlinx.serialization.json.Json
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter

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
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val groupKey = Base64.decode(community.groupKeyBase64, Base64.NO_WRAP)
    private val json = Json { ignoreUnknownKeys = true }

    @Volatile private var writer: BufferedWriter? = null

    fun connect() {
        scope.launch { connectLoop() }
    }

    fun sendMessage(text: String) {
        scope.launch {
            val w = writer ?: run {
                Log.w(TAG, "sendMessage called while disconnected - dropping")
                return@launch
            }
            try {
                val encrypted = CommunityEncryption.encrypt(text, groupKey)
                val packet = CommunityPacket(
                    type = "MSG",
                    senderB32 = myB32,
                    senderName = myName,
                    payload = encrypted,
                    timestamp = System.currentTimeMillis()
                )
                val line = json.encodeToString(packet)
                w.write(line); w.newLine(); w.flush()
                Log.d(TAG, "Sent message to community ${community.name}")
            } catch (e : Exception) {
                Log.e(TAG, "sendMessage failed ${e.message}")
                writer = null
            }
        }
    }

    fun disconnect() {
        scope.cancel()
        writer = null
    }

    private suspend fun connectLoop() {
        val sam = SAMClient.getInstance()

        while (scope.isActive) {
            try {
                val session = sam.createStreamSession().getOrThrow()
                val socket = sam.connectToPeer(session.id, community.b32Address).getOrThrow()

                val w = BufferedWriter(OutputStreamWriter(socket.getOutputStream()))
                val r = BufferedReader(InputStreamReader(socket.getInputStream()))
                writer = w

                onConnectionStateChanged(true)
                Log.i(TAG, "Connected to community ${community.name}")

                var line: String?
                while (r.readLine().also { line = it } != null) { handlePacket(line!!) }
            } catch (_ : CancellationException) {
                break
            } catch (e : Exception) {
                Log.w(TAG, "Community connection lost: ${e.message}")
            } finally {
                writer = null
                onConnectionStateChanged(false)
            }

            if (scope.isActive) {
                Log.i(TAG, "Reconnecting to ${community.name} in ${RECONNECT_MS / 1000}s")
                delay(RECONNECT_MS)
            }
        }
    }

    private fun handlePacket(raw : String) {
        try {
            val packet = json.decodeFromString<CommunityPacket>(raw)
            val plaintext = CommunityEncryption.decrypt(packet.payload, groupKey)

            val messageId = "${packet.senderB32.take(8)}_${packet.timestamp}"

            onMessage(
                CommunityMessage(
                    id = messageId,
                    senderB32 = packet.senderB32,
                    senderName = packet.senderName,
                    content = plaintext,
                    timestamp = packet.timestamp,
                    communityB32 = community.b32Address
                )
            )
        } catch (e : Exception) {
            Log.w(TAG, "Dropped undecryptable packet: ${e.message}")
        }
    }
}