package com.example.anonymous.crypto

import android.content.Context
import android.util.Base64
import android.util.Log
import com.example.anonymous.utils.PrefsHelper
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.PublicKey
import java.security.SecureRandom
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

object CryptoManager {
    private const val TAG = "CryptoManager"
    private const val AES_KEY_SIZE_BYTES = 32
    private const val GCM_TAG_LENGTH_BITS = 128
    private const val GCM_IV_LENGTH = 12
    private const val FORMAT_VERSION = 1

    data class EncryptedMessage(
        val v: Int,              // version
        val iv: String,          // base64
        val ct: String,          // base64 (ciphertext || tag)
        val authTag: String,     // base64 authentication tag
        val aad: String? = null  // optional base64
    )

    // Generate ECDH P-256 key pair (use X25519 later)
    fun generateECDHKeyPair(): KeyPair {
        val kpg = KeyPairGenerator.getInstance("EC")
        val ecSpec = java.security.spec.ECGenParameterSpec("secp256r1") // Use named curve
        kpg.initialize(ecSpec, SecureRandom())
        return kpg.generateKeyPair()
    }

    fun generateRSAKeyPair(): KeyPair {
        val kpg = KeyPairGenerator.getInstance("RSA")
        kpg.initialize(2048, SecureRandom())
        return kpg.generateKeyPair()
    }

    fun performECDH(myPrivateKey: PrivateKey, theirPublicKey: PublicKey): ByteArray {
        val ka = KeyAgreement.getInstance("ECDH")
        ka.init(myPrivateKey)
        ka.doPhase(theirPublicKey, true)
        return ka.generateSecret()
    }

    // Proper HKDF-SHA256 (extract + expand)
    fun hkdfSha256(sharedSecret: ByteArray, salt: ByteArray, info: ByteArray, length: Int = AES_KEY_SIZE_BYTES): ByteArray {
        // Extract
        val macExtract = Mac.getInstance("HmacSHA256")
        macExtract.init(SecretKeySpec(salt, "HmacSHA256"))
        val prk = macExtract.doFinal(sharedSecret)

        // Expand
        var t = ByteArray(0)
        var okm = ByteArray(0)
        var counter = 1
        while (okm.size < length) {
            val macExpand = Mac.getInstance("HmacSHA256")
            macExpand.init(SecretKeySpec(prk, "HmacSHA256"))
            macExpand.update(t)
            macExpand.update(info)
            macExpand.update(counter.toByte())
            t = macExpand.doFinal()
            okm += t
            counter++
        }
        return okm.copyOf(length)
    }

    fun deriveAESKey(sharedSecret: ByteArray, salt: ByteArray, info: ByteArray): SecretKey {
        val keyBytes = hkdfSha256(sharedSecret, salt, info, AES_KEY_SIZE_BYTES)
        return SecretKeySpec(keyBytes, "AES")
    }

    fun encryptMessage(message: String, key: SecretKey, aad: ByteArray? = null): EncryptedMessage {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val iv = ByteArray(GCM_IV_LENGTH).also { SecureRandom().nextBytes(it) }
        val spec = GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv)
        cipher.init(Cipher.ENCRYPT_MODE, key, spec)
        if (aad != null) cipher.updateAAD(aad)
        val encrypted = cipher.doFinal(message.toByteArray(Charsets.UTF_8))
        val ctLength = encrypted.size - GCM_TAG_LENGTH_BITS / 8
        val ct = encrypted.copyOf(ctLength)
        val authTag = encrypted.copyOfRange(ctLength, encrypted.size)
        return EncryptedMessage(
            v = FORMAT_VERSION,
            iv = Base64.encodeToString(iv, Base64.NO_WRAP),
            ct = Base64.encodeToString(ct, Base64.NO_WRAP),
            authTag = Base64.encodeToString(authTag, Base64.NO_WRAP),
            aad = aad?.let { Base64.encodeToString(it, Base64.NO_WRAP) }
        )
    }

    fun decryptMessage(encrypted: EncryptedMessage, key: SecretKey): String {
        require(encrypted.v == FORMAT_VERSION) { "Unsupported ciphertext version: ${encrypted.v}" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val iv = Base64.decode(encrypted.iv, Base64.NO_WRAP)
        val ciphertext = Base64.decode(encrypted.ct, Base64.NO_WRAP)
        val authTag = Base64.decode(encrypted.authTag, Base64.NO_WRAP)
        val combined = ciphertext + authTag
        val spec = GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv)
        cipher.init(Cipher.DECRYPT_MODE, key, spec)
        encrypted.aad?.let { cipher.updateAAD(Base64.decode(it, Base64.NO_WRAP)) }
        val pt = cipher.doFinal(combined) // throws on tag failure
        return String(pt, Charsets.UTF_8)
    }

    // Store per-contact key pairs in EncryptedSharedPreferences, not plain SharedPreferences
    fun saveKeyPair(context: Context, contactId: String, keyPair: KeyPair) {
        val prefs = PrefsHelper.getSecurePrefs(context)

        // Use proper encoding for EC public keys - ensure X.509 format
        val pubB64 = Base64.encodeToString(keyPair.public.encoded, Base64.NO_WRAP)

        // For private key, use PKCS#8 format
        val privB64 = Base64.encodeToString(keyPair.private.encoded, Base64.NO_WRAP)

        prefs.edit()
            .putString("${contactId}_public", pubB64)
            .putString("${contactId}_private", privB64)
            .apply()

        Log.d(TAG, "Saved key pair for contact: $contactId")
        Log.d(TAG, "Public key format: ${keyPair.public.format}")
        Log.d(TAG, "Public key algorithm: ${keyPair.public.algorithm}")
    }

    fun getUserKeyPair(context: Context): KeyPair? {
        val alias = PrefsHelper.getKeyAlias(context) ?: run {
            Log.e(TAG, "No key alias found for the current user")
            return null
        }
        Log.d(TAG, "Attempting to load user's own key pair from Keystore using alias: $alias")

        return try {
            val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            val privateKeyEntry = keyStore.getEntry(alias, null) as? KeyStore.PrivateKeyEntry
            if (privateKeyEntry != null) {
                val publicKey = privateKeyEntry.certificate.publicKey
                val privateKey = privateKeyEntry.privateKey
                Log.d(TAG, "Successfully loaded user's own key pair from Keystore.")
                KeyPair(publicKey, privateKey)
            } else {
                Log.e(TAG, "No private key entry found in Keystore for alias: $alias")
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load user's own key pair from Keystore", e)
            null
        }
    }

    fun getContactPublicKey(context: Context, contactId: String): PublicKey? {
        val prefs = PrefsHelper.getSecurePrefs(context)
        val pubStr = prefs.getString("${contactId}_public", null)

        Log.d(TAG, "Attempting to load public key for contact: $contactId from EncryptedSharedPreferences")
        Log.d(TAG, "Found public key in prefs: ${pubStr != null}")

        if (pubStr == null) {
            Log.d(TAG, "No public key found for contact: $contactId")
            return null
        }

        try {
            val pubBytes = Base64.decode(pubStr, Base64.NO_WRAP)
            val keyFactory = KeyFactory.getInstance("RSA")
            val pubKey = keyFactory.generatePublic(X509EncodedKeySpec(pubBytes))
            Log.d(TAG, "Successfully loaded public key for contact: $contactId")
            return pubKey
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load public key from prefs fro $contactId", e)
            return null
        }
    }

    fun getContactKeyPair(context: Context, contactId: String): KeyPair? {
        val prefs = PrefsHelper.getSecurePrefs(context)
        val pubStr = prefs.getString("${contactId}_public", null)
        val privStr = prefs.getString("${contactId}_private", null)

        Log.d(TAG, "Attempting to load key pair for contact: $contactId from EncryptedSharedPreferences")
        Log.d(TAG, "Found public key in prefs: ${pubStr != null}")
        Log.d(TAG, "Found private key in prefs: ${privStr != null}")

        if (pubStr == null) {
            Log.d(TAG, "No public key found for contact: $contactId")
            return null
        }

        val ecFactory = KeyFactory.getInstance("EC")

        return if (privStr != null) {
            try {
                val pubKey = ecFactory.generatePublic(X509EncodedKeySpec(Base64.decode(pubStr, Base64.NO_WRAP)))
                val privKey = ecFactory.generatePrivate(PKCS8EncodedKeySpec(Base64.decode(privStr, Base64.NO_WRAP)))
                Log.d(TAG, "Loaded full EC key pair for: $contactId")
                KeyPair(pubKey, privKey)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load key pair for $contactId", e)
                null
            }
        } else {
            // Only public key on file — this is correct for remote contacts
            Log.d(TAG, "Only public key on file for $contactId (correct for remote contacts)")
            null
        }
    }

    fun getKeyPair(context: Context, contactId: String, isOwnKey: Boolean = false): KeyPair? {
        Log.d(TAG, "getKeyPair called for contactId: $contactId, isOwnKey: $isOwnKey")
        return if (isOwnKey) {
            getUserKeyPair(context)
        } else {
            getContactKeyPair(context, contactId)
        }
    }

    fun encryptWithRSA(data: ByteArray, publicKey: PublicKey): ByteArray {
        val cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding")
        cipher.init(Cipher.ENCRYPT_MODE, publicKey)
        return cipher.doFinal(data)
    }

    fun decryptWithRSA(encryptedData: ByteArray, privateKey: PrivateKey): ByteArray {
        val cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding")
        cipher.init(Cipher.DECRYPT_MODE, privateKey)
        return cipher.doFinal(encryptedData)
    }

    fun generateEphemeralAESKey(): SecretKey {
        val keyBytes = ByteArray(32)
        SecureRandom().nextBytes(keyBytes)
        return SecretKeySpec(keyBytes, "AES")
    }
}