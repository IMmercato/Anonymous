package com.example.anonymous.network

import android.content.Context
import android.util.Log
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

                Log.d(TAG, "Sending message to: $receiverId")
                Log.d(TAG, "Message content: $content")
                Log.d(TAG, "Session token: ${sessionToken.take(10)}...")

                val query = """
                    mutation SendMessage(${'$'}receiverId: ID!, ${'$'}content: String!, ${'$'}encryptedContent: String!) {
                        sendMessage(
                            receiverId: ${'$'}receiverId,
                            content: ${'$'}content,
                            encryptedContent: ${'$'}encryptedContent
                        ) {
                            id
                            content
                            encryptedContent
                            sender {
                                id
                            }
                            receiver {
                                id
                            }
                            isRead
                            createdAt
                        }
                    }
                """.trimIndent()

                val variables = mapOf(
                    "receiverId" to receiverId,
                    "content" to content,
                    "encryptedContent" to content
                )

                Log.d(TAG, "GraphQL Query: $query")
                Log.d(TAG, "Variables: $variables")

                val request = GraphQLRequest(query, variables)
                val response: Response<GraphQLResponse<SendMessageData>> = service.sendMessage(request)

                logResponseDetails(response)

                if (response.isSuccessful && response.body()?.errors.isNullOrEmpty()) {
                    Log.d(TAG, "Message sent successfully")
                    true
                } else {
                    val errorBody = response.errorBody()?.string()
                    Log.e(TAG, "Failed to send message: $errorBody")
                    false
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error sending message", e)
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

                val query = """
                    query GetMessages {
                        getMessages(receiverId: "$userId") {
                            id
                            content
                            encryptedContent
                            sender {
                                id
                            }
                            receiver {
                                id
                            }
                            isRead
                            createdAt
                        }
                    }
                """.trimIndent()

                val request = GraphQLRequest(query)
                val response: Response<GraphQLResponse<GetMessagesData>> = service.getMessages(request)

                if (response.isSuccessful && response.body()?.errors.isNullOrEmpty()) {
                    response.body()?.data?.getMessages?.map { messageResponse ->
                        Message(
                            id = messageResponse.id,
                            content = messageResponse.content,
                            encryptedContent = messageResponse.encryptedContent,
                            senderId = messageResponse.sender?.id ?: "",
                            receiverId = messageResponse.receiver?.id ?: "",
                            timestamp = parseTimestamp(messageResponse.createdAt),
                            isRead = messageResponse.isRead
                        )
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