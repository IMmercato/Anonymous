package com.example.anonymous.network

import android.content.Context
import com.example.anonymous.crypto.CryptoManager
import com.example.anonymous.network.model.Contact
import com.example.anonymous.repository.ContactRepository
import kotlinx.coroutines.flow.first
import java.security.PublicKey
import android.util.Base64
import android.util.Log
import com.example.anonymous.utils.PrefsHelper
import java.security.KeyFactory
import java.security.KeyFactory.getInstance
import java.security.spec.X509EncodedKeySpec

class GraphQLCryptoService(private val context: Context) {
    private val TAG = "GraphQLCryptoService"
    private val contactRepository = ContactRepository(context)

    suspend fun sendEncryptedMessage(receiverId: String, content: String): EncryptedMessageData {
        return try {
            // Get receiver's public key
            val receiverContact = getContact(receiverId)
            if (receiverContact == null || receiverContact.publicKey.isBlank()) {
                throw IllegalStateException("No public key found for contact: $receiverId")
            }

            // Generate or get key pair for this receiver
            var keyPair = CryptoManager.getKeyPair(context, receiverId)
            if (keyPair == null) {
                keyPair = CryptoManager.generateECDHKeyPair()
                CryptoManager.saveKeyPair(context, receiverId, keyPair)
            }

            // Get receiver's public key
            val receiverPublicKey = decodePublicKey(receiverContact.publicKey)

            // Perform ECDH key exchange
            val sharedSecret = CryptoManager.performECDH(keyPair.private, receiverPublicKey)

            // Derive AES key
            val salt = ("dm:$receiverId").toByteArray(Charsets.UTF_8)
            val currentUserId = PrefsHelper.getUserUuid(context) ?: ""
            val info = ("msg:$currentUserId->$receiverId").toByteArray(Charsets.UTF_8)
            val aesKey = CryptoManager.deriveAESKey(sharedSecret, salt, info)

            // Encrypt the message
            val encryptedData = CryptoManager.encryptMessage(content, aesKey)

            EncryptedMessageData(
                encryptedContent = encryptedData.ct,
                iv = encryptedData.iv,
                authTag = encryptedData.authTag,
                version = encryptedData.v,
                dhPublicKey = Base64.encodeToString(keyPair.public.encoded, Base64.NO_WRAP)
            )
        } catch (e: Exception) {
            Log.e(TAG, "Encryption failed for receiver $receiverId", e)
            throw e
        }
    }

    suspend fun decryptMessage(encryptedMessage: EncryptedMessageData): String {
        return try {
            // Get sender's contact to retrieve their public key
            val senderContact = getContact(encryptedMessage.senderId)
            if (senderContact == null ||  senderContact.publicKey.isBlank()) {
                throw IllegalStateException("No public key found for sender: ${encryptedMessage.senderId}")
            }

            // Get key pair for this sender
            val keyPair= CryptoManager.getKeyPair(context, encryptedMessage.senderId)
                ?: throw IllegalStateException("No key pair found for sender: ${encryptedMessage.senderId}")

            // Get sender's public key
            val senderPublicKey = decodePublicKey(senderContact.publicKey)

            // Perform ECDH key exchange
            val sharedSecret = CryptoManager.performECDH(keyPair.private, senderPublicKey)

            // Derive AES key
            val salt = ("dm:${encryptedMessage.senderId}").toByteArray(Charsets.UTF_8)
            val currentUserId = PrefsHelper.getUserUuid(context) ?: ""
            val info = ("msg:${encryptedMessage.senderId}->$currentUserId").toByteArray(Charsets.UTF_8)
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
            Log.d(TAG, "Contact public key (first 50 chars): ${contact.publicKey.take(50)}...")
        } else {
            Log.d(TAG, "Contact not found: $contactId")
        }

        return contact
    }

    private fun decodePublicKey(encodedKey: String): PublicKey {
        Log.d(TAG, "Decoding public key, encoded length: ${encodedKey.length}")

        val keyBytes = Base64.decode(encodedKey, Base64.NO_WRAP)
        Log.d(TAG, "Decoded key bytes length: ${keyBytes.size}")

        // Try different key formats
        return try {
            val keySpec = X509EncodedKeySpec(keyBytes)
            val keyFactory = KeyFactory.getInstance("EC")
            val publicKey = keyFactory.generatePublic(keySpec)
            Log.d(TAG, "Successfully decoded as EC key")
            Log.d(TAG, "Decoded key algorithm: ${publicKey.algorithm}")
            Log.d(TAG, "Decoded key format: ${publicKey.format}")
            publicKey
        } catch (ecException: Exception) {
            Log.d(TAG, "EC decoding failed: ${ecException.message}")

            // Try RSA as fallback
            try {
                val keySpec = X509EncodedKeySpec(keyBytes)
                val keyFactory = KeyFactory.getInstance("RSA")
                val publicKey = keyFactory.generatePublic(keySpec)
                Log.d(TAG, "Successfully decoded as RSA key")
                publicKey
            } catch (rsaException: Exception) {
                Log.e(TAG, "RSA decoding also failed: ${rsaException.message}")
                throw IllegalArgumentException("Failed to decode public key in any supported format", rsaException)
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