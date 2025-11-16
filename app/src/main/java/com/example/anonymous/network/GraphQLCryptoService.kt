package com.example.anonymous.network

import android.content.Context
import android.util.Base64
import android.util.Log
import com.example.anonymous.crypto.CryptoManager
import com.example.anonymous.network.model.Contact
import com.example.anonymous.repository.ContactRepository
import kotlinx.coroutines.flow.first
import java.security.KeyFactory
import java.security.PublicKey
import java.security.spec.X509EncodedKeySpec

class GraphQLCryptoService(private val context: Context) {
    private val TAG = "GraphQLCryptoService"
    private val contactRepository = ContactRepository(context)

    suspend fun sendEncryptedMessage(receiverId: String, content: String): EncryptedMessageData {
        return try {
            // Get OUR key pair for this contact (not the receiver's public key)
            val myKeyPair = CryptoManager.getKeyPair(context, receiverId)
            if (myKeyPair == null) {
                throw IllegalStateException("No key pair found for contact: $receiverId")
            }

            // Get receiver's public key from contact
            val receiverContact = getContact(receiverId)
            if (receiverContact == null || receiverContact.publicKey.isBlank()) {
                throw IllegalStateException("No public key found for contact: $receiverId")
            }

            // Decode receiver's public key
            val receiverPublicKey = decodePublicKey(receiverContact.publicKey)

            // Perform ECDH key agreement to get shared secret
            val sharedSecret = CryptoManager.performECDH(myKeyPair.private, receiverPublicKey)

            // Derive AES key using HKDF
            val salt = "anonymous-salt".toByteArray() // Use a fixed salt or derive from something
            val info = "anonymous-aes-key".toByteArray()
            val aesKey = CryptoManager.deriveAESKey(sharedSecret, salt, info)

            // Encrypt the message with AES-GCM
            val encryptedData = CryptoManager.encryptMessage(content, aesKey)

            EncryptedMessageData(
                encryptedContent = encryptedData.ct,
                iv = encryptedData.iv,
                authTag = encryptedData.authTag,
                version = encryptedData.v,
                dhPublicKey = Base64.encodeToString(myKeyPair.public.encoded, Base64.NO_WRAP)
            )
        } catch (e: Exception) {
            Log.e(TAG, "Encryption failed for receiver $receiverId", e)
            throw e
        }
    }

    suspend fun decryptMessage(encryptedMessage: EncryptedMessageData): String {
        return try {
            // Get OUR key pair for this contact
            val myKeyPair = CryptoManager.getKeyPair(context, encryptedMessage.senderId)
            if (myKeyPair == null) {
                throw IllegalStateException("No key pair found for sender: ${encryptedMessage.senderId}")
            }
            // Decode sender's public key from the message
            val senderPublicKey = decodePublicKey(encryptedMessage.dhPublicKey)

            // Perform ECDH key agreement to get shared secret
            val sharedSecret = CryptoManager.performECDH(myKeyPair.private, senderPublicKey)

            // Derive AES key using HKDF (same parameters as encryption)
            val salt = "anonymous-salt".toByteArray()
            val info = "anonymous-aes-key".toByteArray()
            val aesKey = CryptoManager.deriveAESKey(sharedSecret, salt, info)

            val encryptedData = CryptoManager.EncryptedMessage(
                v = encryptedMessage.version,
                iv = encryptedMessage.iv,
                ct = encryptedMessage.encryptedContent,
                authTag = encryptedMessage.authTag
            )

            CryptoManager.decryptMessage(encryptedData, aesKey)

        } catch (e: Exception) {
            Log.e(TAG, "Decryption failed for sender ${encryptedMessage.senderId}", e)
            throw e
        }
    }

    private suspend fun getContact(contactId: String): Contact? {
        val contacts = contactRepository.getContactsFlow().first()
        val contact = contacts.find { it.uuid == contactId }

        if (contact != null) {
            Log.d(TAG, "Found contact: ${contact.uuid}")
            Log.d(TAG, "Contact public key length: ${contact.publicKey.length}")
        } else {
            Log.d(TAG, "Contact not found: $contactId")
        }

        return contact
    }

    private fun decodePublicKey(encodedKey: String): PublicKey {
        Log.d(TAG, "Decoding public key, encoded length: ${encodedKey.length}")

        val keyBytes = Base64.decode(encodedKey, Base64.NO_WRAP)
        Log.d(TAG, "Decoded key bytes length: ${keyBytes.size}")

        val keySpec = X509EncodedKeySpec(keyBytes)

        return try {
            val keyFactory = KeyFactory.getInstance("EC")
            val publicKey = keyFactory.generatePublic(keySpec)
            Log.d(TAG, "Successfully decoded as EC key")
            Log.d(TAG, "Decoded key algorithm: ${publicKey.algorithm}")
            Log.d(TAG, "Decoded key format: ${publicKey.format}")
            publicKey
        } catch (e: Exception) {
            Log.d(TAG, "Failed to decode as EC key, trying RSA: ${e.message}")
            try {
                val keyFactory = KeyFactory.getInstance("RSA")
                val publicKey = keyFactory.generatePublic(keySpec)
                Log.d(TAG, "Successfully decoded as RSA key")
                Log.d(TAG, "Decoded key algorithm: ${publicKey.algorithm}")
                Log.d(TAG, "Decoded key format: ${publicKey.format}")
                publicKey
            } catch (e2: Exception) {
                Log.e(TAG, "Failed to decode public keyt as both EC and RSA", e2)
                throw e2
            }
        }
    }

    suspend fun initializeKeyPairForContact(contactId: String, contactPublicKey: String) {
        val existingKeyPair = CryptoManager.getKeyPair(context, contactId)
        if (existingKeyPair == null) {
            // Generate new key pair for this contact
            val keyPair = CryptoManager.generateECDHKeyPair()
            CryptoManager.saveKeyPair(context, contactId, keyPair)
            Log.d(TAG, "Generated new key pair for contact: $contactId")
        } else {
            Log.d(TAG, "Key pair already exists for contact: $contactId")
        }
    }

    suspend fun debugContactSetup(contactId: String) {
        val contact = getContact(contactId)
        val keyPair = CryptoManager.getKeyPair(context, contactId)

        Log.d(TAG, "=== CONTACT DEBUG: $contactId ===")
        Log.d(TAG, "Contact exists: ${contact != null}")
        Log.d(TAG, "Contact public key present: ${contact?.publicKey?.isNotEmpty() == true}")
        Log.d(TAG, "Contact public key length: ${contact?.publicKey?.length}")
        Log.d(TAG, "Our key pair exists: ${keyPair != null}")

        if (contact != null && keyPair != null) {
            try {
                val theirPublicKey = decodePublicKey(contact.publicKey)
                val sharedSecret = CryptoManager.performECDH(keyPair.private, theirPublicKey)
                Log.d(TAG, "ECDH test successful - shared secret length: ${sharedSecret.size}")
            } catch (e: Exception) {
                Log.e(TAG, "ECDH test FAILED: ${e.message}")
            }
        }
    }
}

data class  EncryptedMessageData(
    val encryptedContent: String,
    val iv: String,
    val authTag: String,
    val version: Int,
    val dhPublicKey : String,
    val senderId: String = ""
)