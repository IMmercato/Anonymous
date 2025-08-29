package com.example.anonymous.network

import android.content.Context
import android.util.Base64
import android.util.Log
import com.example.anonymous.crypto.CryptoManager
import com.example.anonymous.network.model.Message
import com.example.anonymous.utils.PrefsHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.TimeUnit

class MessageWebSocketService(private val context: Context) {
    private val TAG = "MessageWebSocketService"
    private var webSocket: WebSocket? = null
    private var isConnected = false

    private val client = OkHttpClient.Builder()
        .readTimeout(3, TimeUnit.SECONDS)
        .build()

    fun connect() {
        val token = PrefsHelper.getSessionToken(context)
        val request = Request.Builder()
            .url("wss://immercato.hackclub.app/ws?token=$token")
            .build()

        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: okhttp3.Response) {
                isConnected = true
                Log.d(TAG, "WebSocket connection opened")
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                Log.d(TAG, "Received message: $text")
                handleIncomingMessage(text)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                isConnected = false
                Log.d(TAG, "WebSocket connection closed: $reason")
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: okhttp3.Response?) {
                isConnected = false
                Log.e(TAG, "WebSocket connection failed", t)
            }
        })
    }

    fun disconnect() {
        webSocket?.close(1000, "Normal closure")
        isConnected = false
    }

    suspend fun sendMessage(receiverId: String, content: String): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                // Generate or get session key pair
                var keyPair = CryptoManager.getKeyPair(context, receiverId)
                if (keyPair == null) {
                    keyPair = CryptoManager.generateECDHKeyPair()
                    CryptoManager.saveKeyPair(context, receiverId, keyPair)
                }

                // Get receiver's public key (in real app, fetch from server)
                // For now, we'll assume we have it from QR scan
                val receiverPublicKey = getReceiverPublicKey(receiverId)

                if (receiverPublicKey == null) {
                    Log.e(TAG, "No public key found for receiver: $receiverId")
                    return@withContext false
                }

                // Perform ECDH key exchange
                val sharedSecret = CryptoManager.performECDH(keyPair.private, receiverPublicKey)
                val aesKey = CryptoManager.deriveAESKey(sharedSecret)

                // Encrypt message
                val encryptedMessage = CryptoManager.encryptMessage(content, aesKey)

                // Create message payload
                val message = Message(
                    id = UUID.randomUUID().toString(),
                    content = content,
                    encryptedContent = encryptedMessage.ciphertext,
                    senderId = PrefsHelper.getUserUuid(context) ?: "",
                    receiverId = receiverId,
                    timestamp = System.currentTimeMillis(),
                    isRead = false,
                    iv = encryptedMessage.iv,
                    dhPublicKey = Base64.encodeToString(keyPair.public.encoded, Base64.NO_WRAP)
                )

                // Send via WebSocket
                val jsonMessage = JSONObject().apply {
                    put("type", "message")
                    put("receiverId", receiverId)
                    put("encryptedContent", encryptedMessage.ciphertext)
                    put("iv", encryptedMessage.iv)
                    put("dhPublicKey", Base64.encodeToString(keyPair.public.encoded, Base64.NO_WRAP))
                    put("timestamp", System.currentTimeMillis())
                }

                webSocket?.send(jsonMessage.toString())
                true
            } catch (e: Exception) {
                Log.e(TAG, "Failed to send message", e)
                false
            }
        }
    }

    private fun handleIncomingMessage(text: String) {
        try {
            val json = JSONObject(text)
            when (json.getString("type")) {
                "message" -> handleIncomingMessage(json)
                //"ack" -> handleAck(json)
                // Add other message types as needed
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse incoming message", e)
        }
    }

    private fun handleIncomingMessage(json: JSONObject) {
        val senderId = json.getString("senderId")
        val encryptedContent = json.getString("encryptedContent")
        val iv = json.getString("iv")
        val dhPublicKey = json.getString("dhPublicKey")

        // Decrypt the message
        try {
            val keyPair = CryptoManager.getKeyPair(context, senderId)
            if (keyPair != null) {
                val senderPublicKey = decodePublicKey(dhPublicKey)
                val sharedSecret = CryptoManager.performECDH(keyPair.private, senderPublicKey)
                val aesKey = CryptoManager.deriveAESKey(sharedSecret)

                val decryptedContent = CryptoManager.decryptMessage(
                    CryptoManager.EncryptedMessage(iv, encryptedContent),
                    aesKey
                )

                // Save to local repository
                val message = Message(
                    id = json.getString("id"),
                    content = decryptedContent,
                    encryptedContent = encryptedContent,
                    senderId = senderId,
                    receiverId = PrefsHelper.getUserUuid(context) ?: "",
                    timestamp = json.getLong("timestamp"),
                    isRead = false,
                    iv = iv,
                    dhPublicKey = dhPublicKey
                )

                // Save to local repository
                val repository = com.example.anonymous.repository.MessageRepository(context)
                // repository.addMessage(message) - You'll need to implement this
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to decrypt incoming message", e)
        }
    }

    private fun getReceiverPublicKey(receiverId: String): java.security.PublicKey? {
        // In real implementation, fetch from server or local storage
        // For now, return null - you'll need to implement this
        return null
    }

    private fun decodePublicKey(encodedKey: String): java.security.PublicKey {
        val keyBytes = Base64.decode(encodedKey, Base64.NO_WRAP)
        val keySpec = java.security.spec.X509EncodedKeySpec(keyBytes)
        return java.security.KeyFactory.getInstance("EC").generatePublic(keySpec)
    }
}