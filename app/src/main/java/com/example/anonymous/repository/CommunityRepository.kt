package com.example.anonymous.repository

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.anonymous.network.model.Community
import com.example.anonymous.network.model.CommunityMessage
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json

val Context.communityDataStore: DataStore<Preferences> by preferencesDataStore(name = "communities")

class CommunityRepository private constructor(private val context: Context) {

    companion object {
        @Volatile private var instance: CommunityRepository? = null

        fun getInstance(context: Context): CommunityRepository {
            return instance ?: synchronized(this) { instance ?: CommunityRepository(context.applicationContext).also { instance = it } }
        }
    }

    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }

    private val COMMUNITIES_KEY = stringPreferencesKey("communities_list")
    private val MESSAGES_KEY    = stringPreferencesKey("community_messages_list")


    suspend fun save(community: Community) {
        val current = getAllCommunities().toMutableList()
        val idx = current.indexOfFirst { it.b32Address.equals(community.b32Address, ignoreCase = true) }
        if (idx != -1) current[idx] = community else current.add(community)
        saveCommunities(current)
    }

    suspend fun delete(b32Address: String) {
        val current = getAllCommunities().filter { !it.b32Address.equals(b32Address, ignoreCase = true) }
        saveCommunities(current)
        val messages = getAllMessages().filter { !it.communityB32.equals(b32Address, ignoreCase = true) }
        saveMessages(messages)
    }

    suspend fun getByB32(b32Address: String): Community? {
        return getAllCommunities().find { it.b32Address.equals(b32Address, ignoreCase = true) }
    }

    suspend fun communityExists(b32Address: String): Boolean = getByB32(b32Address) != null

    fun getCommunitiesFlow(): Flow<List<Community>> {
        return context.communityDataStore.data.map { preferences ->
            preferences[COMMUNITIES_KEY]?.let { json.decodeFromString<List<Community>>(it) } ?: emptyList()
        }
    }

    private suspend fun getAllCommunities(): List<Community> =
        getCommunitiesFlow().first()

    private suspend fun saveCommunities(communities: List<Community>) {
        context.communityDataStore.edit { preferences ->
            preferences[COMMUNITIES_KEY] = json.encodeToString(
                communities.distinctBy { it.b32Address.lowercase() }
            )
        }
    }


    // Community message storage

    suspend fun addMessage(message: CommunityMessage) {
        val current = getAllMessages().toMutableList()
        if (current.none { it.id == message.id }) {
            current.add(message)
            saveMessages(current)
        }
    }

    suspend fun addMessages(messages: List<CommunityMessage>) {
        val current = getAllMessages().toMutableList()
        val newOnes = messages.filter { new -> current.none { it.id == new.id } }
        if (newOnes.isNotEmpty()) {
            current.addAll(newOnes)
            saveMessages(current)
        }
    }

    suspend fun getMessagesForCommunity(communityB32: String): List<CommunityMessage> {
        return getAllMessages()
            .filter { it.communityB32.equals(communityB32, ignoreCase = true) }
            .sortedBy { it.timestamp }
    }

    fun getMessagesFlow(communityB32: String): Flow<List<CommunityMessage>> {
        return context.communityDataStore.data.map { preferences ->
            preferences[MESSAGES_KEY]
                ?.let { json.decodeFromString<List<CommunityMessage>>(it) }
                ?.filter { it.communityB32.equals(communityB32, ignoreCase = true) }
                ?.sortedBy { it.timestamp }
                ?: emptyList()
        }
    }

    private suspend fun getAllMessages(): List<CommunityMessage> {
        return context.communityDataStore.data.map { preferences ->
            preferences[MESSAGES_KEY]?.let { json.decodeFromString<List<CommunityMessage>>(it) } ?: emptyList()
        }.first()
    }

    private suspend fun saveMessages(messages: List<CommunityMessage>) {
        context.communityDataStore.edit { preferences ->
            preferences[MESSAGES_KEY] = json.encodeToString(messages.distinctBy { it.id })
        }
    }
}