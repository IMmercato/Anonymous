package com.example.anonymous.network

import android.content.Context
import android.util.Base64
import android.util.Log
import com.example.anonymous.crypto.CryptoManager
import com.example.anonymous.network.model.Contact
import com.example.anonymous.repository.ContactRepository
import com.example.anonymous.utils.PrefsHelper
import kotlinx.coroutines.flow.first
import java.security.KeyFactory
import java.security.PublicKey
import java.security.spec.X509EncodedKeySpec
import javax.crypto.spec.SecretKeySpec

class GraphQLCryptoService(private val context: Context) {
    private val TAG = "GraphQLCryptoService"
    private val contactRepository = ContactRepository(context)
    private val myUserId = PrefsHelper.getUserUuid(context) ?: throw IllegalStateException("No user ID found")

    suspend fun sendEncryptedMessage(receiverId: String, content: String): EncryptedMessageData {
        return try {
            // Get receiver's public key from contact
            val receiverContact = getContact(receiverId)
            if (receiverContact == null || receiverContact.publicKey.isBlank()) {
                throw IllegalStateException("No public key found for contact: $receiverId")
            }

            // Decode receiver's public key
            val receiverPublicKey = decodePublicKey(receiverContact.publicKey)

            // Check key type and handle accordingly
            when (receiverPublicKey.algorithm) {
                "RSA" -> encryptWithRSA(receiverId, receiverPublicKey, content)
                "EC" -> encryptWithECDH(receiverId, receiverPublicKey, content)
                else -> throw IllegalStateException("Unsupported key algorithm: ${receiverPublicKey.algorithm}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Encryption failed for receiver $receiverId", e)
            throw e
        }
    }

    private suspend fun encryptWithECDH(receiverId: String, receiverPublicKey: PublicKey, content: String): EncryptedMessageData {

        val myKeyPair = CryptoManager.getKeyPair(context, myUserId)
            ?: throw IllegalStateException("No ECDH key pair found for user: $myUserId")

        // Perform ECDH key agreement to get shared secret
        val sharedSecret = CryptoManager.performECDH(myKeyPair.private, receiverPublicKey)

        // Derive AES key using HKDF
        val salt = "anonymous-salt".toByteArray() // Use a fixed salt or derive from something
        val info = "anonymous-aes-key".toByteArray()
        val aesKey = CryptoManager.deriveAESKey(sharedSecret, salt, info)

        // Encrypt the message with AES-GCM
        val encryptedData = CryptoManager.encryptMessage(content, aesKey)

        return EncryptedMessageData(
            encryptedContent = encryptedData.ct,
            iv = encryptedData.iv,
            authTag = encryptedData.authTag,
            version = encryptedData.v,
            dhPublicKey = Base64.encodeToString(myKeyPair.public.encoded, Base64.NO_WRAP)
        )
    }

    private suspend fun encryptWithRSA(receiverId: String, receiverPublicKey: PublicKey, content: String): EncryptedMessageData {
        // Generate an ephemeral AES key for this message
        val aesKey = CryptoManager.generateEphemeralAESKey()

        // Encrypt the message content with the ephemeral AES key
        val encryptedContentData = CryptoManager.encryptMessage(content, aesKey)

        // Encrypt the ephemeral AES key with the RECEIVER'S PUBLIC KEY
        val encryptedAesKeyBytes = CryptoManager.encryptWithRSA(aesKey.encoded, receiverPublicKey)

        // The encrypted AES key goes into the dhPublicKey field for RSA
        return EncryptedMessageData(
            encryptedContent = encryptedContentData.ct,
            iv = encryptedContentData.iv,
            authTag = encryptedContentData.authTag,
            version = encryptedContentData.v,
            dhPublicKey = Base64.encodeToString(encryptedAesKeyBytes, Base64.NO_WRAP)   // Encrypted AES key
        )
    }

    suspend fun decryptMessage(encryptedMessage: EncryptedMessageData, senderId: String, receiverId: String): String {
        return try {
            if (receiverId != myUserId) {
                Log.w(TAG, "RSA: Message from $senderId was not sent to me ($myUserId), skipping decryption.")
                throw IllegalStateException("Message not intended for current user")
            }

            val myKeyPair = CryptoManager.getKeyPair(context, myUserId)
                ?: throw IllegalStateException("No key pair found for sender: $myUserId")

            // --- RSA Decryption Path ---
            if (myKeyPair.private.algorithm == "RSA") {
                Log.d(TAG, "Attempting RSA decryption for message from $senderId")

                val encryptedAesKeyBytes = Base64.decode(encryptedMessage.dhPublicKey, Base64.NO_WRAP)
                val aesKeyBytes = CryptoManager.decryptWithRSA(encryptedAesKeyBytes, myKeyPair.private)
                val aesKey = SecretKeySpec(aesKeyBytes, "AES")

                val encryptedData = CryptoManager.EncryptedMessage(
                    v = encryptedMessage.version,
                    iv = encryptedMessage.iv,
                    ct = encryptedMessage.encryptedContent,
                    authTag = encryptedMessage.authTag
                )

                val decryptedContent = CryptoManager.decryptMessage(encryptedData, aesKey)
                Log.d(TAG, "RSA decryption successful fro message from $senderId")
                return decryptedContent
            } else {    // ECDH
                Log.d(TAG, "Attempting ECDH decryption fro message from $senderId")
                // Decode sender's public key from the message
                val senderEphemeralPublicKey = decodePublicKey(encryptedMessage.dhPublicKey)

                // Perform ECDH key agreement to get shared secret
                val sharedSecret = CryptoManager.performECDH(myKeyPair.private, senderEphemeralPublicKey)

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
            }
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

    fun decodePublicKey(encodedKey: String): PublicKey {
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
}

data class  EncryptedMessageData(
    val encryptedContent: String,
    val iv: String,
    val authTag: String,
    val version: Int,
    val dhPublicKey : String,   // RSA: Encrypted AES key, ECDH: Sender's ephemeral public key
    val senderId: String = ""
)