package com.example.anonymous.network

import android.content.Context
import android.util.Log
import com.example.anonymous.network.model.*
import com.example.anonymous.utils.PrefsHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import retrofit2.Response
import java.util.concurrent.atomic.AtomicBoolean

class GraphQLMessageService(private val context: Context) {
    private val TAG = "GraphQLMessageService"
    private val service = GraphQLService.create(context)
    val cryptoService = GraphQLCryptoService(context)
    private val subscriptionService = GraphQLSubscriptionService(context)

    private var isRealTimeActive = AtomicBoolean(false)
    private var refreshJob: Job? = null
    private var subscriptionJob: Job? = null

    private val _newMessages = MutableSharedFlow<Message>()
    val newMessages: SharedFlow<Message> = _newMessages.asSharedFlow()

    suspend fun initializeRealTimeMessaging(contactId: String) {
        Log.d(TAG, "Initializing real-time messaging...")

        val webSocketSuccess = subscriptionService.initializeWebSocket()

        if (webSocketSuccess) {
            startSubscription(contactId)
            isRealTimeActive.set(true)
            Log.d(TAG, "Real-time messaging activated via WebSocket")
        } else {
            startPollingFallback(contactId)
            Log.d(TAG, "WebSocket failed, falling back to polling")
        }
    }

    private fun startSubscription(contactId: String) {
        subscriptionJob = CoroutineScope(Dispatchers.IO).launch {
            try {
                subscriptionService.subscribeToNewMessages().collect { subscriptionData ->
                    val messageResponse = subscriptionData.newMessage ?: run {
                        Log.w(TAG, "Received subscription data but 'newMessage' field was null.")
                        return@collect
                    }

                    // Verify this message is relevant to our current contact
                    val senderId = messageResponse.sender!!.id
                    val receiverId = messageResponse.receiver!!.id

                    if (senderId == contactId || receiverId == contactId) {
                        try {
                            val encryptedData = EncryptedMessageData(
                                encryptedContent = messageResponse.encryptedContent,
                                iv = messageResponse.iv,
                                authTag = messageResponse.authTag,
                                version = messageResponse.version,
                                dhPublicKey = messageResponse.dhPublicKey,
                                senderId = senderId
                            )

                            val decryptedContent = cryptoService.decryptMessage(encryptedData, senderId)

                            val decryptedMessage = Message(
                                id = messageResponse.id,
                                content = decryptedContent,
                                encryptedContent = messageResponse.encryptedContent,
                                senderId = senderId,
                                receiverId = receiverId,
                                timestamp = parseTimestamp(messageResponse.createdAt),
                                isRead = messageResponse.isRead,
                                iv = messageResponse.iv,
                                authTag = messageResponse.authTag,
                                version = messageResponse.version,
                                dhPublicKey = messageResponse.dhPublicKey
                            )

                            _newMessages.emit(decryptedMessage)
                            Log.d(TAG, "Real-time message processed and emitted: ${decryptedMessage.id}")

                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to decrypt real-time message ${messageResponse.id}", e)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Subscription flow error", e)
                // Fall back to polling if subscription fails
                startPollingFallback(contactId)
            }
        }
    }

    private fun startPollingFallback(contactId: String) {
        refreshJob = CoroutineScope(Dispatchers.IO).launch {
            while (isActive) {
                try {
                    val messages = fetchMessagesForContact(contactId)
                    delay(5000)
                } catch (e: Exception) {
                    Log.e(TAG, "Polling error", e)
                    delay(10000)
                }
            }
        }
    }

    fun stopRealTimeMessaging() {
        isRealTimeActive.set(false)
        subscriptionJob?.cancel()
        refreshJob?.cancel()
        Log.d(TAG, "Real-time messaging stopped")
    }

    fun isRealTimeActive(): Boolean = isRealTimeActive.get()

    suspend fun sendMessage(receiverId: String, content: String): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                val sessionToken = PrefsHelper.getSessionToken(context)
                if (sessionToken.isNullOrEmpty()) {
                    Log.e(TAG, "No session token found")
                    return@withContext false
                }

                val encryptedData = cryptoService.sendEncryptedMessage(receiverId, content)

                Log.d(TAG, "Sending ENCRYPTED message to: $receiverId")
                Log.d(TAG, "Original content length: ${content.length}")
                Log.d(TAG, "Encrypted content length: ${encryptedData.encryptedContent.length}")

                val query = QueryBuilder.sendEncryptedMessage(
                    receiverId = receiverId,
                    encryptedContent = encryptedData.encryptedContent,
                    iv = encryptedData.iv,
                    authTag = encryptedData.authTag,
                    version = encryptedData.version,
                    dhPublicKey = encryptedData.dhPublicKey
                )

                val variables = mapOf(
                    "receiverId" to receiverId,
                    "encryptedContent" to encryptedData.encryptedContent,
                    "iv" to encryptedData.iv,
                    "authTag" to encryptedData.authTag,
                    "version" to encryptedData.version,
                    "dhPublicKey" to encryptedData.dhPublicKey
                )

                Log.d(TAG, "GraphQL Query: $query")
                Log.d(TAG, "Variables: $variables")

                val request = GraphQLRequest(query, variables)
                val response: Response<GraphQLResponse<SendMessageData>> = service.sendMessage(request)

                logResponseDetails(response)

                if (response.isSuccessful && response.body()?.errors.isNullOrEmpty()) {
                    Log.d(TAG, "Encrypted message sent successfully")
                    true
                } else {
                    val errorBody = response.errorBody()?.string()
                    Log.e(TAG, "Failed to send encrypted message: $errorBody")
                    false
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error sending encrypted message", e)
                false
            }
        }
    }

    suspend fun fetchMessages(): List<Message> {
        return withContext(Dispatchers.IO) {
            try {
                val userId = PrefsHelper.getUserUuid(context) ?: return@withContext emptyList()
                val sessionToken = PrefsHelper.getSessionToken(context)
                if (sessionToken.isNullOrEmpty()) {
                    Log.e(TAG, "No session token found")
                    return@withContext emptyList()
                }

                val query = QueryBuilder.getEncryptedMessages(userId)
                val request = GraphQLRequest(query)
                val response: Response<GraphQLResponse<GetMessagesData>> = service.getMessages(request)

                if (response.isSuccessful && response.body()?.errors.isNullOrEmpty()) {
                    response.body()?.data?.getMessages?.mapNotNull { messageResponse ->
                        try {
                            // Decrypt the message
                            val encryptedData = EncryptedMessageData(
                                encryptedContent = messageResponse.encryptedContent,
                                iv = messageResponse.iv,
                                authTag = messageResponse.authTag,
                                version = messageResponse.version,
                                dhPublicKey = messageResponse.dhPublicKey,
                                senderId = messageResponse.sender?.id ?: ""
                            )

                            val decryptedContent = cryptoService.decryptMessage(encryptedData, messageResponse.sender?.id ?: "")

                            // Create the Message object with *decrypted* content for the UI
                            Message(
                                id = messageResponse.id,
                                content = decryptedContent, // <-- Decrypted content for UI
                                encryptedContent = messageResponse.encryptedContent, // <-- Original encrypted content
                                senderId = messageResponse.sender?.id ?: "",
                                receiverId = messageResponse.receiver?.id ?: "",
                                timestamp = parseTimestamp(messageResponse.createdAt),
                                isRead = messageResponse.isRead,
                                iv = messageResponse.iv,
                                authTag = messageResponse.authTag,
                                version = messageResponse.version,
                                dhPublicKey = messageResponse.dhPublicKey
                            )
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to decrypt message ${messageResponse.id}", e)
                            null
                        }
                    } ?: emptyList()
                } else {
                    val errorBody = response.errorBody()?.string()
                    Log.e(TAG, "Failed to fetch messages: $errorBody")
                    emptyList()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error fetching messages", e)
                emptyList()
            }
        }
    }

    suspend fun fetchMessagesForContact(contactId: String): List<Message> {
        return withContext(Dispatchers.IO) {
            try {
                val allMessages = fetchMessages() // This now returns messages with decrypted content
                val contactMessages = allMessages.filter {
                    it.senderId == contactId || it.receiverId == contactId
                }

                Log.d(TAG, "Found ${contactMessages.size} messages for contact $contactId")
                return@withContext contactMessages
            } catch (e: Exception) {
                Log.e(TAG, "Error fetching messages for contact", e)
                emptyList()
            }
        }
    }

    private fun parseTimestamp(timestampString: String): Long {
        return try {
            // Use the same logic as GraphQLSubscriptionService
            val formatter = java.time.format.DateTimeFormatter.ISO_DATE_TIME
            val instant = java.time.Instant.from(formatter.parse(timestampString))
            instant.toEpochMilli()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse timestamp: $timestampString", e)
            System.currentTimeMillis()
        }
    }

    private fun logResponseDetails(response: Response<*>) {
        Log.d(TAG, "Response code: ${response.code()}")
        Log.d(TAG, "Response message: ${response.message()}")
        Log.d(TAG, "Response headers: ${response.headers()}")

        try {
            val responseBody = response.body()
            Log.d(TAG, "Response body: $responseBody")
        } catch (e: Exception) {
            Log.e(TAG, "Error reading response body", e)
        }

        try {
            val errorBody = response.errorBody()?.string()
            Log.d(TAG, "Error body: $errorBody")
        } catch (e: Exception) {
            Log.e(TAG, "Error reading error body", e)
        }
    }
}