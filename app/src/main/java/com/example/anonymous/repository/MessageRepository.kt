package com.example.anonymous.repository

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.anonymous.ChatMessageModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

val Context.messagesDataStore: DataStore<Preferences> by preferencesDataStore(name = "messages")

class MessageRepository(private val context: Context) {
    private val MESSAGES_KEY = stringPreferencesKey("messages_list")
    private val json = Json { encodeDefaults = true }

    suspend fun addMessage(message: ChatMessageModel) {
        val currentMessages = getAllMessages().toMutableList()
        currentMessages.add(message)
        saveMessages(currentMessages)
    }

    fun getMessagesForContact(contactId: String): Flow<List<ChatMessageModel>> {
        return context.messagesDataStore.data.map { preferences ->
            preferences[MESSAGES_KEY]?.let { jsonString ->
                json.decodeFromString<List<ChatMessageModel>>(jsonString)
                    .filter { it.isSent || !it.isSent } // Filter by contact logic will be handled in ChatScreen
            } ?: emptyList()
        }
    }

    private suspend fun getAllMessages(): List<ChatMessageModel> {
        return context.messagesDataStore.data.map { preferences ->
            preferences[MESSAGES_KEY]?.let { jsonString ->
                json.decodeFromString<List<ChatMessageModel>>(jsonString)
            } ?: emptyList()
        }.first()
    }

    private suspend fun saveMessages(messages: List<ChatMessageModel>) {
        context.messagesDataStore.edit { preferences ->
            preferences[MESSAGES_KEY] = json.encodeToString(messages)
        }
    }
}