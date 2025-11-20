package com.example.anonymous.repository

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.anonymous.network.GraphQLCryptoService
import com.example.anonymous.network.model.Message
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import android.util.Log
import com.example.anonymous.network.EncryptedMessageData
import com.example.anonymous.utils.PrefsHelper

val Context.messagesDataStore: DataStore<Preferences> by preferencesDataStore(name = "messages")

class MessageRepository(private val context: Context) {
    private val MESSAGES_KEY = stringPreferencesKey("messages_list")
    private val json = Json { encodeDefaults = true }

    suspend fun addMessage(message: Message) {
        val currentMessages = getAllMessages().toMutableList()
        if (currentMessages.none { it.id == message.id }) {
            currentMessages.add(message)
            saveMessages(currentMessages)
        }
    }

    suspend fun addMessages(messages: List<Message>) {
        val currentMessages = getAllMessages().toMutableList()
        val newMessages = messages.filter { newMessage ->
            currentMessages.none { it.id == newMessage.id }
        }
        if (newMessages.isNotEmpty()) {
            currentMessages.addAll(newMessages)
            saveMessages(currentMessages)
        }
    }

    suspend fun getMessagesForContactDecrypted(contactId: String, cryptoService: GraphQLCryptoService): List<Message> {
        val myUserId = PrefsHelper.getUserUuid(context) ?: return emptyList()
        val allMessages = getAllMessages()
        val contactMessages = allMessages.filter {msg ->
            val isFromContact = msg.senderId == contactId
            val isToContact = msg.receiverId == contactId
            val isRelevant = isFromContact || isToContact
            isRelevant
        }.sortedBy { it.timestamp }

        Log.d("MessageRepository", "Filtered contactMessages size: ${contactMessages.size}")

        val result = contactMessages.mapNotNull { storedMsg ->
            try {
                val isOutgoing = (storedMsg.senderId == myUserId)
                val isFromContact = (storedMsg.senderId == contactId)
                val isToMe = (storedMsg.receiverId == myUserId)
                val isToContact = (storedMsg.receiverId == contactId)

                Log.v("MessageRepository", "Processing msg ${storedMsg.id}: isOutgoing=$isOutgoing, isFromContact=$isFromContact, isToMe=$isToMe, isToContact=$isToContact")

                if (isOutgoing) { // Message I sent TO the contact
                    Log.d("MessageRepository", "Loading outgoing message for UI: ${storedMsg.id}")
                    storedMsg.copy(content = storedMsg.content)
                } else if (isToMe && isFromContact) { // Message sent TO me FROM the contact (incoming)
                    Log.d("MessageRepository", "Decrypting incoming message for UI: ${storedMsg.id}")
                    if (storedMsg.encryptedContent.isNotEmpty()) {
                        val decryptedContent = cryptoService.decryptMessage(
                            EncryptedMessageData(
                                encryptedContent = storedMsg.encryptedContent,
                                iv = storedMsg.iv ?: "",
                                authTag = storedMsg.authTag,
                                version = storedMsg.version,
                                dhPublicKey = storedMsg.dhPublicKey ?: "",
                                senderId = storedMsg.senderId
                            ),
                            storedMsg.senderId,
                            storedMsg.receiverId
                        )
                        storedMsg.copy(content = decryptedContent)
                    } else {
                        Log.w("MessageRepository", "Incoming message ${storedMsg.id} has no encryptedContent, cannot decrypt.")
                        null // Filter out invalid incoming messages
                    }
                } else {
                    Log.w("MessageRepository", "Message ${storedMsg.id} does not match outgoing or incoming criteria for contact $contactId. senderId: ${storedMsg.senderId}, receiverId: ${storedMsg.receiverId}")
                    null // Filter out unexpected messages
                }
            } catch (e: Exception) {
                Log.e("MessageRepository", "Failed to process message ${storedMsg.id} (sender: ${storedMsg.senderId}, receiver: ${storedMsg.receiverId})", e)
                null // Filter out messages that cause errors during processing
            }
        }

        Log.d("MessageRepository", "Final decrypted messages list size: ${result.size}")
        return result
    }

    suspend fun getMessagesForContact(contactId: String): List<Message> {
        return getAllMessages().filter {
            it.senderId == contactId || it.receiverId == contactId
        }.sortedBy { it.timestamp }
    }

    private suspend fun getAllMessages(): List<Message> {
        return context.messagesDataStore.data.map { preferences ->
            preferences[MESSAGES_KEY]?.let { jsonString ->
                json.decodeFromString<List<Message>>(jsonString)
            } ?: emptyList()
        }.first()
    }

    private suspend fun saveMessages(messages: List<Message>) {
        context.messagesDataStore.edit { preferences ->
            preferences[MESSAGES_KEY] = json.encodeToString(messages.distinctBy { it.id })
        }
    }
}