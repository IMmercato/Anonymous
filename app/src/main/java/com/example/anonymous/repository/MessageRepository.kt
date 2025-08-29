package com.example.anonymous.repository

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.anonymous.network.model.Message
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

val Context.messagesDataStore: DataStore<Preferences> by preferencesDataStore(name = "messages")

class MessageRepository(private val context: Context) {
    private val MESSAGES_KEY = stringPreferencesKey("messages_list")
    private val json = Json { encodeDefaults = true }

    suspend fun addMessage(message: Message) {
        val currentMessages = getAllMessages().toMutableList()
        currentMessages.add(message)
        saveMessages(currentMessages)
    }

    fun getMessagesForContact(contactId: String): Flow<List<Message>> {
        return context.messagesDataStore.data.map { preferences ->
            preferences[MESSAGES_KEY]?.let { jsonString ->
                json.decodeFromString<List<Message>>(jsonString)
                    .filter { it.senderId == contactId || it.receiverId == contactId }
            } ?: emptyList()
        }
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
            preferences[MESSAGES_KEY] = json.encodeToString(messages)
        }
    }
}