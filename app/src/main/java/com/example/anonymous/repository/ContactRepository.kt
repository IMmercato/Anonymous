package com.example.anonymous.repository

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.anonymous.network.model.Contact
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

val Context.contactsDataStore: DataStore<Preferences> by preferencesDataStore(name = "contacts")

class ContactRepository(private val context: Context) {
    private val CONTACTS_KEY = stringPreferencesKey("contacts_list")
    private val json = Json { encodeDefaults = true }

    suspend fun addContact(contact: Contact) {
        val currentContacts = getAllContacts().toMutableList()
        currentContacts.add(contact)
        saveContacts(currentContacts)
    }

    suspend fun deleteContact(contactId: String) {
        val currentContacts = getAllContacts().toMutableList()
        currentContacts.removeAll { it.uuid == contactId }
        saveContacts(currentContacts)
    }

    suspend fun updateContact(updatedContact: Contact) {
        val currentContacts = getAllContacts().toMutableList()
        val index = currentContacts.indexOfFirst { it.uuid == updatedContact.uuid }
        if (index != -1) {
            currentContacts[index] = updatedContact
            saveContacts(currentContacts)
        }
    }

    suspend fun contactExists(uuid: String): Boolean {
        return getAllContacts().any { it.uuid == uuid }
    }

    fun getContactsFlow(): Flow<List<Contact>> {
        return context.contactsDataStore.data.map { preferences ->
            preferences[CONTACTS_KEY]?.let { jsonString ->
                json.decodeFromString<List<Contact>>(jsonString)
            } ?: emptyList()
        }
    }

    private suspend fun getAllContacts(): List<Contact> {
        return context.contactsDataStore.data.map { preferences ->
            preferences[CONTACTS_KEY]?.let { jsonString ->
                json.decodeFromString<List<Contact>>(jsonString)
            } ?: emptyList()
        }.first()
    }

    private suspend fun saveContacts(contacts: List<Contact>) {
        context.contactsDataStore.edit { preferences ->
            preferences[CONTACTS_KEY] = json.encodeToString(contacts)
        }
    }
}