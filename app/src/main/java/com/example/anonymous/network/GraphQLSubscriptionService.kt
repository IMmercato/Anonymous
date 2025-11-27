package com.example.anonymous.network

import android.content.Context
import android.util.Log
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

    data class SubscriptionData(
        val newMessage: MessageResponse?
    )

    fun subscribeToNewMessages(): Flow<SubscriptionData> = callbackFlow {
        Log.d(TAG, "Attempting to subscribe to new message...")

        val sessionToken = PrefsHelper.getSessionToken(context)
        if (sessionToken.isNullOrEmpty()) {
            close(Exception("No session token available for WebSocket"))
            return@callbackFlow
        }

        val client = OkHttpClient.Builder()
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .build()

        val request = Request.Builder()
            .url("wss://immercato.hackclub.app/graphql")
            .addHeader("Sec-WebSocket-Protocol", "graphql-transport-ws")
            .addHeader("Authorization", "Bearer $sessionToken")
            .build()

        val initMessage = gson.toJson(mapOf(
            "type" to "connection_init",
            "payload" to mapOf(
                "Authorization" to "Bearer $sessionToken"
            )
        ))

        val subscriptionMessage = gson.toJson(mapOf(
            "id" to "1",
            "type" to "subscribe",
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
        ))

        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.d(TAG, "WebSocket connection opened. Sending connection_init...")
                webSocket.send(initMessage)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                Log.d(TAG, "WebSocket message received: $text")
                try {
                    val message = gson.fromJson(text, Map::class.java)
                    val type = message["type"] as? String

                    when (type) {
                        "connection_ack" -> {
                            Log.d(TAG, "WebSocket connection acknowledged. Sending subscription...")
                            webSocket.send(subscriptionMessage)
                        }
                        "ka" -> {
                            Log.d(TAG, "Keep-alive received")
                        }
                        "next" -> {
                            Log.d(TAG, "Subscription data received (type: next)")
                            val payload = message["payload"] as? Map<*, *>
                            val errors = payload?.get("errors") as? List<*>
                            if (errors != null && errors.isNotEmpty()) {
                                Log.e(TAG, "GraphQL error is subscription: $errors")
                                close(Exception("GraphQL error in subscription: $errors"))
                                return
                            }
                            val data = payload?.get("data") as? Map<*, *>
                            val newMessageData = data?.get("newMessage") as? Map<*, *>
                            if (newMessageData != null) {
                                val messageResponse = gson.fromJson(
                                    gson.toJson(newMessageData),
                                    MessageResponse::class.java
                                )
                                val subscriptionData = SubscriptionData(messageResponse)
                                Log.d(TAG, "Emitting subscription data for message: ${messageResponse.id}")
                                trySend(subscriptionData)
                            }
                        }
                        "data" -> {
                            Log.d(TAG, "Subscription data received (type: data)")
                            val payload = message["payload"] as? Map<*, *>
                            val data = payload?.get("data") as? Map<*, *>
                            val newMessageData = data?.get("newMessage") as? Map<*, *>
                            if (newMessageData != null) {
                                val messageResponse = gson.fromJson(
                                    gson.toJson(newMessageData),
                                    MessageResponse::class.java
                                )
                                val subscriptionData = SubscriptionData(messageResponse)
                                Log.d(TAG, "Emitting subscription data for message: ${messageResponse.id}")
                                trySend(subscriptionData)
                            }
                        }
                        "error" -> {
                            Log.e(TAG, "Subscription error: $text")
                            close(Exception("Subscription error: $text"))
                        }
                        "complete" -> {
                            Log.d(TAG, "Subscription completed")
                            close()
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
                close()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "WebSocket connection failed: ${t.message}", t)
                close(t)
            }
        }

        val webSocket = client.newWebSocket(request, listener)

        awaitClose {
            Log.d(TAG, "Flow is closing. Stopping WebSocket subscription...")
            val stopMessage = gson.toJson(mapOf(
                "id" to "1",
                "type" to "stop"
            ))
            webSocket.send(stopMessage)
            webSocket.close(1000, "Subscription ended")
        }
    }
}