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
import java.security.SecureRandom
import java.security.spec.X509EncodedKeySpec
import javax.crypto.spec.SecretKeySpec

class GraphQLCryptoService(private val context: Context) {
    private val TAG = "GraphQLCryptoService"
    private val contactRepository = ContactRepository(context)
    private val myUserId = PrefsHelper.getUserUuid(context) ?: throw IllegalStateException("No user ID found")

    companion object {
        private const val MAX_MESSAGE_SIZE = 1024 * 4
        private const val PADDING_BLOCK_SIZE = 256
    }

    suspend fun sendEncryptedMessage(receiverId: String, content: String): EncryptedMessageData {
        return try {
            // Get receiver's public key from contact
            val receiverContact = getContact(receiverId)
            if (receiverContact == null || receiverContact.publicKey.isBlank()) {
                throw IllegalStateException("No public key found for contact: $receiverId")
            }

            // Decode receiver's public key
            val receiverPublicKey = decodePublicKey(receiverContact.publicKey)

            // Add Padding to hide message original length
            val paddedContent = addPadding(content)
            Log.d(TAG, "Original content: ${content.length} chars, Padded: ${paddedContent.length} chars")

            // Check key type and handle accordingly
            when (receiverPublicKey.algorithm) {
                "RSA" -> encryptWithRSA(receiverId, receiverPublicKey, paddedContent)
                "EC" -> encryptWithECDH(receiverId, receiverPublicKey, paddedContent)
                else -> throw IllegalStateException("Unsupported key algorithm: ${receiverPublicKey.algorithm}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Encryption failed for receiver $receiverId", e)
            throw e
        }
    }

    private suspend fun encryptWithECDH(receiverId: String, receiverPublicKey: PublicKey, content: String): EncryptedMessageData {

        val myKeyPair = CryptoManager.getUserKeyPair(context)
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
                Log.w(TAG, "Message from $senderId was not sent to me ($myUserId), skipping decryption.")
                throw IllegalStateException("Message not intended for current user")
            }

            val senderContact = getContact(senderId)
            if (senderContact == null || senderContact.publicKey.isBlank()) {
                Log.e(TAG, "No public key found for sender: $senderId. Cannot determine encryption method.")
                throw IllegalStateException("No public key found for sender: $senderId")
            }

            val senderPublicKey = decodePublicKey(senderContact.publicKey)

            val decryptedContent = when (senderPublicKey.algorithm) {
                "RSA" -> {
                    Log.d(TAG, "Attempting RSA decryption for message from $senderId (sender has RSA key)")
                    val encryptedAesKeyBytes = Base64.decode(encryptedMessage.dhPublicKey, Base64.NO_WRAP)
                    val myKeyPair = CryptoManager.getUserKeyPair(context)
                        ?: throw IllegalStateException("No RSA key pair found for receiver: $myUserId")
                    val aesKeyBytes = CryptoManager.decryptWithRSA(encryptedAesKeyBytes, myKeyPair.private)
                    val aesKey = SecretKeySpec(aesKeyBytes, "AES")
                    val encryptedData = CryptoManager.EncryptedMessage(
                        v = encryptedMessage.version,
                        iv = encryptedMessage.iv,
                        ct = encryptedMessage.encryptedContent,
                        authTag = encryptedMessage.authTag
                    )
                    Log.d(TAG, "RSA decryption successful for message from $senderId")
                    CryptoManager.decryptMessage(encryptedData, aesKey)
                }

                "EC" -> {
                    Log.d(TAG, "Attempting ECDH decryption for message from $senderId (sender has EC key)")
                    val senderEphemeralPublicKey = decodePublicKey(encryptedMessage.dhPublicKey)
                    val myKeyPair = CryptoManager.getUserKeyPair(context)
                        ?: throw IllegalStateException("No EC key pair found for receiver: $myUserId")
                    val sharedSecret = CryptoManager.performECDH(myKeyPair.private, senderEphemeralPublicKey)
                    val salt = "anonymous-salt".toByteArray()
                    val info = "anonymous-aes-key".toByteArray()
                    val aesKey = CryptoManager.deriveAESKey(sharedSecret, salt, info)
                    val encryptedData = CryptoManager.EncryptedMessage(
                        v = encryptedMessage.version,
                        iv = encryptedMessage.iv,
                        ct = encryptedMessage.encryptedContent,
                        authTag = encryptedMessage.authTag
                    )
                    Log.d(TAG, "ECDH decryption successful for message from $senderId")
                    CryptoManager.decryptMessage(encryptedData, aesKey)
                }

                else -> {
                    Log.e(TAG, "Unsupported key algorithm for sender ${senderPublicKey.algorithm}")
                    throw IllegalStateException("Unsupported key algorithm: ${senderPublicKey.algorithm}")
                }
            }

            // Remove Padding after Decryption
            val unpaddedContent = removePadding(decryptedContent)
            Log.d(TAG, "${senderPublicKey.algorithm} decryption successful for message from $senderId")
            unpaddedContent
        } catch (e: Exception) {
            Log.e(TAG, "Decryption failed for sender ${encryptedMessage.senderId}", e)
            throw e
        }
    }


    private fun addPadding(message: String): String {
        val messageBytes = message.toByteArray(Charsets.UTF_8)

        if (messageBytes.size > MAX_MESSAGE_SIZE) {
            throw IllegalArgumentException("Message too large. Max size $MAX_MESSAGE_SIZE bytes")
        }

        val targetSize = ((messageBytes.size + PADDING_BLOCK_SIZE - 1) / PADDING_BLOCK_SIZE) * PADDING_BLOCK_SIZE

        val paddedBytes = ByteArray(targetSize)

        System.arraycopy(messageBytes, 0, paddedBytes, 0, messageBytes.size)

        val random = SecureRandom()
        for (i in messageBytes.size until targetSize) {
            paddedBytes[i] = random.nextInt(256).toByte()
        }

        val result = ByteArray(targetSize + 4)
        result[0] = (messageBytes.size shr 24).toByte()
        result[1] = (messageBytes.size shr 16).toByte()
        result[2] = (messageBytes.size shr 8).toByte()
        result[3] = messageBytes.size.toByte()

        System.arraycopy(paddedBytes, 0, result, 4, paddedBytes.size)

        return  Base64.encodeToString(result, Base64.NO_WRAP)
    }

    private fun removePadding(paddedMessage: String): String {
        val decodeBytes = Base64.decode(paddedMessage, Base64.NO_WRAP)

        if (decodeBytes.size < 4) {
            throw IllegalArgumentException("Invalid padded message format")
        }

        val originalLength = ((decodeBytes[0].toInt() and 0xFF) shl 24) or
                ((decodeBytes[1].toInt() and 0xFF) shl 16) or
                ((decodeBytes[2].toInt() and 0xFF) shl 8) or
                (decodeBytes[3].toInt() and 0xFF)

        if (originalLength < 0 || originalLength > MAX_MESSAGE_SIZE) {
            throw IllegalArgumentException("Invalid original length: $originalLength")
        }

        val originalMessageBytes = ByteArray(originalLength)
        System.arraycopy(decodeBytes, 4, originalMessageBytes, 0, originalLength)

        return  String(originalMessageBytes, Charsets.UTF_8)
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