package com.example.anonymous.network

import android.content.Context
import android.util.Log
import com.example.anonymous.network.model.GraphQLSubscriptionMessage
import com.example.anonymous.network.model.MessageResponse
import com.example.anonymous.utils.PrefsHelper
import com.google.gson.Gson
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import okhttp3.Request
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.TimeUnit

class GraphQLSubscriptionService(private val context: Context) {
    private val TAG = "GraphQLSubscriptionService"
    private val gson = Gson()
    private var webSocket: WebSocket? = null
    private var isConnectionAcknowledged = false

    data class SubscriptionData(
        val newMessage: MessageResponse?
    )

    fun initializeWebSocket(): Boolean {
        return try {
            val sessionToken = PrefsHelper.getSessionToken(context)
            if (sessionToken.isNullOrEmpty()) {
                Log.e(TAG, "No session token available for WebSocket")
                return false
            }

            val client = OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .build()

            val request = Request.Builder()
                .url("wss://immercato.hackclub.app/graphql")
                .header("Sec-WebSocket-Protocol", "graphql-ws")
                .build()

            webSocket = client.newWebSocket(request, object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    Log.d(TAG, "WebSocket connection opened")
                    Log.d(TAG, "Response headers: ${response.protocol}")
                    val initMessage = mapOf(
                        "type" to "connection_init",
                        "payload" to mapOf(
                            "Authorization" to "Bearer $sessionToken"
                        )
                    )
                    val jsonMessage = gson.toJson(initMessage)
                    Log.d(TAG, "Sending connection_init: $jsonMessage")
                    webSocket.send(jsonMessage)
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    Log.d(TAG, "WebSocket message received: $text")
                    try {
                        val message = gson.fromJson(text, Map::class.java)
                        val type = message["type"] as? String

                        when (type) {
                            "connection_ack" -> {
                                Log.d(TAG, "WebSocket connection acknowledged")
                                isConnectionAcknowledged = true
                                startNewMessageSubscription()
                            }
                            "ka" -> {
                                Log.d(TAG, "Keep-alive received")
                            }
                            "data" -> {
                                Log.d(TAG, "Subscription data received")
                            }
                            "error" -> {
                                Log.e(TAG, "Subscription error: $text")
                            }
                            "complete" -> {
                                Log.d(TAG, "Subscription completed")
                            }
                            else -> {
                                Log.d(TAG, "Received message type: $type")
                            }
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error parsing WebSocket message", e)
                    }
                }

                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    Log.d(TAG, "WebSocket closing: $code - $reason")
                    webSocket.close(1000, null)
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    Log.d(TAG, "WebSocket closed: $code - $reason")
                    isConnectionAcknowledged = false
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    Log.e(TAG, "WebSocket connection failed: ${t.message}")
                    response?.let {
                        Log.e(TAG, "Response code: ${it.code}, message: ${it.message}")
                        Log.e(TAG, "Response headers: ${it.headers}")
                    }
                    isConnectionAcknowledged = false
                }
            })

            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize WebSocket", e)
            false
        }
    }

    private fun startNewMessageSubscription() {
        if (!isConnectionAcknowledged) {
            Log.w(TAG, "Cannot start subscription - connection not acknowledged")
            return
        }

        val subscriptionMessage = mapOf(
            "id" to "1",
            "type" to "start",
            "payload" to mapOf(
                "query" to """
                    subscription {
                        newMessage {
                            id
                            encryptedContent
                            iv
                            authTag
                            version
                            dhPublicKey
                            sender { id }
                            receiver { id }
                            isRead
                            createdAt
                        }
                    }
                """.trimIndent()
            )
        )
        val jsonMessage = gson.toJson(subscriptionMessage)
        Log.d(TAG, "Sending subscription: $jsonMessage")
        webSocket?.send(jsonMessage)
    }

    fun subscribeToNewMessages(): Flow<SubscriptionData> = callbackFlow {
        val listener = object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    Log.d(TAG, "Raw WebSocket message: $text")

                    val message = gson.fromJson(text, Map::class.java)
                    val type = message["type"] as? String
                    if (type == "data") {
                        val payload = message["payload"] as? Map<*, *>
                        val data = payload?.get("data") as? Map<*, *>
                        val newMessageData = data?.get("newMessage") as? Map<*, *>
                        if (newMessageData != null) {
                            val messageResponse = gson.fromJson(
                                gson.toJson(newMessageData),
                                MessageResponse::class.java
                            )
                            val subscriptionData = SubscriptionData(messageResponse)
                            Log.d(TAG, "Emitting subscription data: ${messageResponse.id}")
                            trySend(subscriptionData)
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error processing subscription message", e)
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "WebSocket subscription failed", t)
                close(t)
            }
        }

        val sessionToken = PrefsHelper.getSessionToken(context)
        if (sessionToken.isNullOrEmpty()) {
            close(Exception("No session token available"))
            return@callbackFlow
        }

        val client = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()

        val request = Request.Builder()
            .url("wss://immercato.hackclub.app/graphql")
            .addHeader("Sec-WebSocket-Protocol","graphql-ws")
            .build()

        val webSocket = client.newWebSocket(request, listener)
        this@GraphQLSubscriptionService.webSocket = webSocket

        val initMessage = mapOf(
            "type" to "connection_init",
            "payload" to mapOf(
                "Authorization" to "Bearer $sessionToken"
            )
        )
        webSocket.send(gson.toJson(initMessage))

        awaitClose {
            Log.d(TAG, "Closing WebSocket subscription")
            val stopMessage = mapOf(
                "id" to "1",
                "type" to "stop"
            )
            webSocket.send(gson.toJson(stopMessage))

            val terminateMessage = mapOf(
                "type" to "connection_terminate"
            )
            webSocket.send(gson.toJson(terminateMessage))

            webSocket.close(1000, "Subscription ended")
        }
    }

    fun closeWebSocket() {
        if (webSocket != null) {
            val terminateMessage = mapOf(
                "type" to "connection_terminate"
            )
            webSocket?.send(gson.toJson(terminateMessage))
            webSocket?.close(1000, "Normal closure")
            webSocket = null
            isConnectionAcknowledged = false
            Log.d(TAG, "WebSocket closed")
        }
    }
}