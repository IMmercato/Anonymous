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
                .header("Authorization", "Bearer $sessionToken")
                .header("Sec-WebSocket-Protocol", "graphql-ws")
                .build()

            webSocket = client.newWebSocket(request, object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    Log.d(TAG, "WebSocket connection opened")
                    Log.d(TAG, "Response headers: ${response.headers}")
                    val initMessage = """
                        {
                            "type": "connection_init",
                            "payload": {}
                        }
                    """.trimIndent()
                    webSocket.send(initMessage)
                    Log.d(TAG, "Sent connection_init")
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    Log.d(TAG, "WebSocket message received: $text")
                    try {
                        val message = gson.fromJson(text, GraphQLSubscriptionMessage::class.java)
                        when (message.type) {
                            "connection_ack" -> {
                                Log.d(TAG, "WebSocket connection acknowledged")
                                startNewMessageSubscription(webSocket)
                            }
                            "data" -> {
                                Log.d(TAG, "Subscription data received")
                            }
                            else -> {
                                Log.d(TAG, "Received message type: ${message.type}")
                            }
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error parsing WebSocket message", e)
                    }
                }

                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    Log.d(TAG, "WebSocket closing: $code - $reason")
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    Log.d(TAG, "WebSocket closed: $code - $reason")
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    Log.e(TAG, "WebSocket connection failed: ${t.message}")
                    response?.let {
                        Log.e(TAG, "Response code: ${it.code}, message: ${it.message}")
                        Log.e(TAG, "Response headers: ${it.headers}")
                    }
                }
            })

            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize WebSocket", e)
            false
        }
    }

    private fun startNewMessageSubscription(webSocket: WebSocket) {
        val subscriptionQuery = """
            {
                "id": "1",
                "type": "subscribe",
                "payload": {
                    "query": "subscription NewMessage { newMessage { id encryptedContent iv authTag version dhPublicKey sender { id } receiver { id } isRead createdAt } }"
                }
            }
        """.trimIndent()

        webSocket.send(subscriptionQuery)
        Log.d(TAG, "New message subscription started")
    }

    fun subscribeToNewMessages(): Flow<SubscriptionData> = callbackFlow {
        val listener = object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    Log.d(TAG, "Raw WebSocket message: $text")

                    val message = gson.fromJson(text, GraphQLSubscriptionMessage::class.java)
                    when (message.type) {
                        "next" -> {
                            message.payload?.let {payload ->
                                val dataElement = payload["data"]
                                if (dataElement != null) {
                                    val subscriptionData = gson.fromJson(
                                        gson.toJsonTree(dataElement),
                                        SubscriptionData::class.java
                                    )
                                    Log.d(TAG, "Emitting subscription data: ${subscriptionData.newMessage?.id}")
                                    trySend(subscriptionData)
                                } else {
                                    Log.wtf(TAG, "No data field in payload")
                                }
                            }
                        }
                        "complete" -> {
                            Log.d(TAG, "Subscription completed")
                        }
                        "error" -> {
                            Log.e(TAG, "Subscription error: ${message.payload}")
                        }
                        else -> {
                            Log.d(TAG, "Received message type: ${message.type}")
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error processing subscription message", e)
                }
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
            .header("Authorization", "Bearer $sessionToken")
            .header("Sec-WebSocket-Protocol","graphql-ws")
            .build()

        val webSocket = client.newWebSocket(request, listener)
        this@GraphQLSubscriptionService.webSocket = webSocket

        awaitClose {
            Log.d(TAG, "Closing WebSocket subscription")
            webSocket.close(1000, "Subscription ended")
        }
    }

    fun closeWebSocket() {
        webSocket?.close(1000, "Normal closure")
        webSocket = null
        Log.d(TAG, "WebSocket closed")
    }
}