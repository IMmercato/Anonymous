package com.example.anonymous.network

import android.content.Context
import android.util.Log
import com.example.anonymous.crypto.CryptoManager
import com.example.anonymous.network.model.*
import com.example.anonymous.utils.PrefsHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import retrofit2.Response

class GraphQLMessageService(private val context: Context) {
    private val TAG = "GraphQLMessageService"
    private val service = GraphQLService.create(context)

    suspend fun sendMessage(receiverId: String, content: String): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                val sessionToken = PrefsHelper.getSessionToken(context)
                if (sessionToken.isNullOrEmpty()) {
                    Log.e(TAG, "No session token found")
                    return@withContext false
                }

                val cryptoService = GraphQLCryptoService(context)
                val encryptedData = cryptoService.sendEncryptedMessage(receiverId, content)

                Log.d(TAG, "Sending ENCRYPTED message to: $receiverId")
                Log.d(TAG, "Original content: $content")
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
                    val cryptoService = GraphQLCryptoService(context)

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

                            val decryptedContent = cryptoService.decryptMessage(encryptedData)

                            Message(
                                id = messageResponse.id,
                                content = decryptedContent, // Store decrypted content locally
                                encryptedContent = messageResponse.encryptedContent,
                                senderId = messageResponse.sender?.id ?: "",
                                receiverId = messageResponse.receiver?.id ?: "",
                                timestamp = parseTimestamp(messageResponse.createdAt),
                                isRead = messageResponse.isRead,
                                iv = messageResponse.iv,
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
                val allMessages = fetchMessages()
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
            // Simple timestamp parser - adjust based on your server's format
            if (timestampString.isNotEmpty()) {
                // If it's an ISO string, you might need a proper parser
                System.currentTimeMillis()
            } else {
                System.currentTimeMillis()
            }
        } catch (e: Exception) {
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