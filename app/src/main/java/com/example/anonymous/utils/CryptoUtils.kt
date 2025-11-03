package com.example.anonymous.utils

import android.util.Base64
import android.util.Log
import java.security.KeyStore
import java.security.PrivateKey
import java.security.Signature

object CryptoUtils {
    private const val TAG = "CryptoUtils"

    fun getPrivateKey(alias: String): PrivateKey? {
        return try {
            val keyStore = KeyStore.getInstance("AndroidKeyStore")
            keyStore.load(null)
            val entry = keyStore.getEntry(alias, null) as? KeyStore.PrivateKeyEntry
            entry?.privateKey
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get private key", e)
            null
        }
    }

    fun signData(data: String, privateKey: PrivateKey): String? {
        return try {
            val signature = Signature.getInstance("SHA256withRSA")
            signature.initSign(privateKey)
            signature.update(data.toByteArray(Charsets.UTF_8))
            val signatureBytes = signature.sign()
            Base64.encodeToString(signatureBytes, Base64.NO_WRAP)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to sign data", e)
            null
        }
    }

    fun signDataWithAlias(data: String, alias: String): String? {
        val privateKey = getPrivateKey(alias)
        return privateKey?.let { signData(data, it) }
    }

    fun convertToSpkiFormat(publicKey: java.security.PublicKey): String {
        return try {
            val publicKeyBytes = publicKey.encoded
            Base64.encodeToString(publicKeyBytes, Base64.NO_WRAP)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to convert public key to SPKI format", e)
            throw e
        }
    }

    fun getPublicKeyBytes(publicKey: java.security.PublicKey): ByteArray {
        return publicKey.encoded
    }

    fun getPublicKeySpkiBase64(publicKey: java.security.PublicKey): String {
        return convertToSpkiFormat(publicKey)
    }

    // Utility function to extract public key from alias
    fun getPublicKeyFromAlias(alias: String): java.security.PublicKey? {
        return try {
            val keyStore = KeyStore.getInstance("AndroidKeyStore")
            keyStore.load(null)
            val entry = keyStore.getEntry(alias, null) as? KeyStore.PrivateKeyEntry
            entry?.certificate?.publicKey
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get public key from alias", e)
            null
        }
    }

    // Get public key in SPKI format from alias
    fun getPublicKeySpkiFromAlias(alias: String): String? {
        return try {
            val publicKey = getPublicKeyFromAlias(alias)
            publicKey?.let { convertToSpkiFormat(it) }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get SPKI public key from alias", e)
            null
        }
    }

    // Debugger
    fun doesKeyExist(alias: String): Boolean {
        return try {
            val keyStore = KeyStore.getInstance("AndroidKeyStore")
            keyStore.load(null)
            keyStore.containsAlias(alias)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to check if key exists", e)
            false
        }
    }
    fun debugKeyStoreStatus(): Map<String, Any> {
        return try {
            val keyStore = KeyStore.getInstance("AndroidKeyStore")
            keyStore.load(null)

            val aliases = mutableListOf<String>()
            val privateKeys = mutableListOf<String>()
            val enumeration = keyStore.aliases()

            while (enumeration.hasMoreElements()) {
                val alias = enumeration.nextElement()
                aliases.add(alias)

                if (keyStore.entryInstanceOf(alias, KeyStore.PrivateKeyEntry::class.java)) {
                    val privateKey = getPrivateKey(alias)
                    privateKeys.add("$alias: ${privateKey != null}")
                }
            }

            mapOf(
                "total_aliases" to aliases.size,
                "aliases" to aliases,
                "private_keys" to privateKeys
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to debug KeyStore status", e)
            emptyMap()
        }
    }
}