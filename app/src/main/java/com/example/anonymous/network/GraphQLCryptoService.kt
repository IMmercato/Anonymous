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
import java.security.KeyStore
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec

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

            // Decode RSA public key
            val receiverPublicKey = decodePublicKey(receiverContact.publicKey)

            // Generate an ephemeral (one-time use) AES key
            val aesKey = generateEphemeralAESKey()

            // Encrypt the message with AES-GCM
            val encryptedData = CryptoManager.encryptMessage(content, aesKey)

            // Encrypt the AES key with RSA
            val encryptedAesKey = encryptWithRSA(aesKey.encoded, receiverPublicKey)

            EncryptedMessageData(
                encryptedContent = encryptedData.ct,
                iv = encryptedData.iv,
                authTag = encryptedData.authTag,
                version = encryptedData.v,
                dhPublicKey = Base64.encodeToString(encryptedAesKey, Base64.NO_WRAP)
            )
        } catch (e: Exception) {
            Log.e(TAG, "Encryption failed for receiver $receiverId", e)
            throw e
        }
    }

    suspend fun decryptMessage(encryptedMessage: EncryptedMessageData): String {
        return try {
            // Get our own RSA private key
            val myPrivateKey = getMyPrivateKey()

            // Decrypt the AES key using our private key
            val encryptedAesKeyBytes = Base64.decode(encryptedMessage.dhPublicKey, Base64.NO_WRAP)
            val aesKeyBytes = decryptWithRSA(encryptedAesKeyBytes, myPrivateKey)
            val aesKey = SecretKeySpec(aesKeyBytes, "AES")

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

    private fun generateEphemeralAESKey(): SecretKey {
        val keyBytes = ByteArray(32) // 256-bit
        SecureRandom().nextBytes(keyBytes)
        return SecretKeySpec(keyBytes, "AES")
    }

    private fun encryptWithRSA(data: ByteArray, publicKey: PublicKey): ByteArray {
        val cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding")
        cipher.init(Cipher.ENCRYPT_MODE, publicKey)
        return cipher.doFinal(data)
    }

    private fun decryptWithRSA(encryptedData: ByteArray, privateKey: PrivateKey): ByteArray {
        val cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding")
        cipher.init(Cipher.DECRYPT_MODE, privateKey)
        return cipher.doFinal(encryptedData)
    }

    private fun getMyPrivateKey(): PrivateKey {
        // Get your own private key from Android Keystore
        val alias = PrefsHelper.getKeyAlias(context) ?: throw IllegalStateException("No key alias found")
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        return ks.getKey(alias, null) as PrivateKey
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