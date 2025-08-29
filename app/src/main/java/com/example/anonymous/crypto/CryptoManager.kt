package com.example.anonymous.crypto

import android.content.Context
import android.util.Base64
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.PublicKey
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

object CryptoManager {
    private const val TAG = "CryptoManager"
    private const val AES_KEY_SIZE = 256
    private const val GCM_TAG_LENGTH = 128
    private const val GCM_IV_LENGTH = 12

    // Generate ECDH key pair
    fun generateECDHKeyPair(): KeyPair {
        val keyPairGenerator = KeyPairGenerator.getInstance("EC")
        keyPairGenerator.initialize(256)
        return keyPairGenerator.generateKeyPair()
    }

    // Perform ECDH key exchange
    fun performECDH(myPrivateKey: PrivateKey, theirPublicKey: PublicKey): ByteArray {
        val keyAgreement = KeyAgreement.getInstance("ECDH")
        keyAgreement.init(myPrivateKey)
        keyAgreement.doPhase(theirPublicKey, true)
        return keyAgreement.generateSecret()
    }

    // Derive AES key from shared secret
    fun deriveAESKey(sharedSecret: ByteArray, salt: ByteArray = ByteArray(0)): SecretKey {
        // Simple HKDF-like derivation (in production, use proper HKDF)
        val derivedKey = sharedSecret.copyOf(32) // Take first 32 bytes for AES-256
        return SecretKeySpec(derivedKey, "AES")
    }

    // Encrypt message with AES-GCM
    fun encryptMessage(message: String, key: SecretKey): EncryptedMessage {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val iv = ByteArray(GCM_IV_LENGTH).apply {
            SecureRandom().nextBytes(this)
        }

        val parameterSpec = GCMParameterSpec(GCM_TAG_LENGTH, iv)
        cipher.init(Cipher.ENCRYPT_MODE, key, parameterSpec)

        val cipherText = cipher.doFinal(message.toByteArray(Charsets.UTF_8))

        return EncryptedMessage(
            iv = Base64.encodeToString(iv, Base64.NO_WRAP),
            ciphertext = Base64.encodeToString(cipherText, Base64.NO_WRAP)
        )
    }

    // Decrypt message with AES-GCM
    fun decryptMessage(encryptedMessage: EncryptedMessage, key: SecretKey): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val iv = Base64.decode(encryptedMessage.iv, Base64.NO_WRAP)
        val cipherText = Base64.decode(encryptedMessage.ciphertext, Base64.NO_WRAP)

        val parameterSpec = GCMParameterSpec(GCM_TAG_LENGTH, iv)
        cipher.init(Cipher.DECRYPT_MODE, key, parameterSpec)

        val decryptedBytes = cipher.doFinal(cipherText)
        return String(decryptedBytes, Charsets.UTF_8)
    }

    data class EncryptedMessage(
        val iv: String,
        val ciphertext: String
    )

    // Generate key pair for a contact session
    suspend fun generateSessionKeyPair(context: Context, contactId: String): KeyPair {
        val keyPair = generateECDHKeyPair()
        saveKeyPair(context, contactId, keyPair)
        return keyPair
    }

    fun saveKeyPair(context: Context, contactId: String, keyPair: KeyPair) {
        val prefs = context.getSharedPreferences("crypto_keys", Context.MODE_PRIVATE)
        val publicKeyBytes = keyPair.public.encoded
        val privateKeyBytes = keyPair.private.encoded

        prefs.edit()
            .putString("${contactId}_public", Base64.encodeToString(publicKeyBytes, Base64.NO_WRAP))
            .putString("${contactId}_private", Base64.encodeToString(privateKeyBytes, Base64.NO_WRAP))
            .apply()
    }

    fun getKeyPair(context: Context, contactId: String): KeyPair? {
        val prefs = context.getSharedPreferences("crypto_keys", Context.MODE_PRIVATE)
        val publicKeyStr = prefs.getString("${contactId}_public", null)
        val privateKeyStr = prefs.getString("${contactId}_private", null)

        return if (publicKeyStr != null && privateKeyStr != null) {
            val publicKeyBytes = Base64.decode(publicKeyStr, Base64.NO_WRAP)
            val privateKeyBytes = Base64.decode(privateKeyStr, Base64.NO_WRAP)

            // Reconstruct key pair (simplified - in production use proper key factory)
            val publicKey = java.security.spec.X509EncodedKeySpec(publicKeyBytes).let {
                KeyFactory.getInstance("EC").generatePublic(it)
            }
            val privateKey = java.security.spec.PKCS8EncodedKeySpec(privateKeyBytes).let {
                KeyFactory.getInstance("EC").generatePrivate(it)
            }

            KeyPair(publicKey, privateKey)
        } else {
            null
        }
    }
}