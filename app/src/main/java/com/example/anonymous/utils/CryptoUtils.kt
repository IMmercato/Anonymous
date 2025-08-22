package com.example.anonymous.utils

import android.util.Base64
import android.util.Log
import java.security.KeyStore
import java.security.PrivateKey
import java.security.Signature

object CryptoUtils {

    fun getPrivateKey(alias: String): PrivateKey? {
        return try {
            val keyStore = KeyStore.getInstance("AndroidKeyStore")
            keyStore.load(null)
            val entry = keyStore.getEntry(alias, null) as? KeyStore.PrivateKeyEntry
            entry?.privateKey
        } catch (e: Exception) {
            Log.e("CryptoUtils", "Failed to get private key", e)
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
            Log.e("CryptoUtils", "Failed to sign data", e)
            null
        }
    }

    fun signDataWithAlias(data: String, alias: String): String? {
        val privateKey = getPrivateKey(alias)
        return privateKey?.let { signData(data, it) }
    }
}