package com.example.anonymous.repository

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.anonymous.i2p.SAMClient
import com.example.anonymous.network.model.Contact
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.net.URI

val Context.contactsDataStore: DataStore<Preferences> by preferencesDataStore(name = "contacts")

class ContactRepository(private val context: Context) {
    companion object {
        @Volatile
        private var instance: ContactRepository? = null

        fun getInstance(context: Context): ContactRepository {
            return instance ?: synchronized(this) {
                instance ?: ContactRepository(context.applicationContext).also {
                    instance = it
                }
            }
        }
    }

    private val CONTACTS_KEY = stringPreferencesKey("contacts_list")
    private val MY_IDENTITY_KEY = stringPreferencesKey("my_identity")
    private val json = Json { encodeDefaults = true }

    /**
     * Trust levels for "Web of Trust"
     */
    enum class TrustLevel {
        UNVERIFIED,         // Added but not verified
        DIRECT_SCAN,        // Verified by scanning QR in person
        DIRECT_SHARE,       // Verified by direct b32 share
        FRIEND_OF_FRIEND,   // Verified through mutual friend
        NETWORK,            // Verified through network of trust
        BLOCKED             // Explicitly blocked
    }

    @Serializable
    data class MyIdentity(
        val b32Address: String,
        val publicKey: String,
        val privateKeyEncrypted: String,
        val createdAt: Long = System.currentTimeMillis()
    )

    data class ParsedIdentity(
        val b32Address: String,
        val publicKey: String?
    )

    suspend fun addContact(contact: Contact) {
        val currentContacts = getAllContacts().toMutableList()
        // Prevent duplicates
        if (currentContacts.any {
            it.b32Address.equals(contact.b32Address, ignoreCase = true)
            }) {
            return
        }
        currentContacts.add(contact)
        saveContacts(currentContacts)
    }

    suspend fun deleteContact(b32Address: String) {
        val currentContacts = getAllContacts().filter {
            !it.b32Address.equals(b32Address, ignoreCase = true)
        }
        saveContacts(currentContacts)
    }

    suspend fun updateContact(updatedContact: Contact) {
        val currentContacts = getAllContacts().toMutableList()
        val index = currentContacts.indexOfFirst {
            it.b32Address.equals(updatedContact.b32Address, ignoreCase = true)
        }
        if (index != -1) {
            currentContacts[index] = updatedContact
            saveContacts(currentContacts)
        }
    }

    suspend fun contactExists(b32Address: String): Boolean {
        return getAllContacts().any { it.b32Address.equals(b32Address, ignoreCase = true) }
    }

    fun getContactsFlow(): Flow<List<Contact>> {
        return context.contactsDataStore.data.map { preferences ->
            preferences[CONTACTS_KEY]?.let { jsonString ->
                json.decodeFromString<List<Contact>>(jsonString)
            } ?: emptyList()
        }
    }

    private suspend fun getAllContacts(): List<Contact> {
        return getContactsFlow().first()
    }

    private suspend fun saveContacts(contacts: List<Contact>) {
        context.contactsDataStore.edit { preferences ->
            preferences[CONTACTS_KEY] = json.encodeToString(contacts)
        }
    }

    /**
     * Contact verifying reaching it via SAM on I2P
     */
    suspend fun verifyContact(b32Address: String): Result<Contact> = withContext(Dispatchers.IO) {
        try {
            val contact = getContactByB32(b32Address) ?: return@withContext Result.failure(IllegalArgumentException("Contact not found"))

            val sam = SAMClient.getInstance()
            val lookupResult = sam.namingLookup(b32Address)

            if (lookupResult.isSuccess) {
                val updated = contact.copy(isVerified = true)
                updateContact(updated)
                Result.success(updated)
            } else {
                Result.failure(Exception("Contact not reachable on I2P"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getContactByB32(b32Address: String): Contact? {
        return getAllContacts().find {
            it.b32Address.equals(b32Address, ignoreCase = true)
        }
    }

    suspend fun getVerifiedContacts(): List<Contact> {
        return getAllContacts().filter { it.isVerified }
    }

    fun getMyIdentity(): MyIdentity? {
        return runBlocking {
            context.contactsDataStore.data.map { preferences ->
                preferences[MY_IDENTITY_KEY]?.let { jsonString ->
                    json.decodeFromString<MyIdentity>(jsonString)
                }
            }.first()
        }
    }

    suspend fun saveMyIdentity(identity: MyIdentity) {
        context.contactsDataStore.edit { preferences ->
            preferences[MY_IDENTITY_KEY] = json.encodeToString(identity)
        }
    }

    suspend fun clearIdentity() {
        context.contactsDataStore.edit { preferences ->
            preferences.remove(MY_IDENTITY_KEY)
        }
    }

    /**
     * QR format: i2p://{b32Address}.b32.i2p?pk={publickey}
     */
    fun generateQRContent(): String {
        val identity = runBlocking { getMyIdentity() } ?: return ""
        return "i2p://${identity.b32Address}?pk=${identity.publicKey}"
    }

    // i2p://{b32}.b32.i2p?pk={key} or {b32}.b32.i2p
    fun parseQRContent(content: String): Result<ParsedIdentity> {
        return try {
            when {
                content.startsWith("i2p://") -> {
                    val uri = URI(content)
                    val b32 = uri.host ?: return Result.failure(IllegalArgumentException("No b32 in QR"))
                    val publicKey = uri.getQueryParam("pk")

                    if (!isValidB32(b32)) {
                        return Result.failure(IllegalArgumentException("Invalid b32 address"))
                    }

                    Result.success(ParsedIdentity(b32, publicKey))
                }

                isValidB32(content) -> Result.success(ParsedIdentity(content, null))
                else -> Result.failure(IllegalArgumentException("Invalid QR format"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun isValidB32(b32: String): Boolean {
        val normalized = b32.lowercase()
        if (!normalized.endsWith(".b32.i2p")) return false
        val prefix = normalized.removeSuffix(".b32.i2p")
        return prefix.length >= 52 && prefix.all { it in 'a'..'z' || it in '2'..'7' }
    }

    private fun java.net.URI.getQueryParam(name: String): String? {
        return query?.split("&")
            ?.map { it.split("=", limit = 2) }
            ?.find { it.getOrNull(0) == name }
            ?.getOrNull(1)
    }
}