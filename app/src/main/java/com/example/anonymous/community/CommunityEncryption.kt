package com.example.anonymous.community

import android.util.Base64
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

object CommunityEncryption {
    private const val ALGORITHM = "AES/GCM/NoPadding"
    private const val KEY_ALGO = "AES"
    private const val IV_LENGTH = 12        // 96-bit IV for GCM
    private const val TAG_LENGTH = 128      // 128-bit auth tag

    // Generate a fresh random 256-bit group key
    fun generateGroupKey(): ByteArray = ByteArray(32).also { SecureRandom().nextBytes(it) }

    // Encrypt plaintext with groupKey
    fun encrypt(plaintext: String, groupKey: ByteArray): String {
        val iv = ByteArray(IV_LENGTH).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance(ALGORITHM)
        cipher.init(
            Cipher.ENCRYPT_MODE,
            SecretKeySpec(groupKey, KEY_ALGO),
            GCMParameterSpec(TAG_LENGTH, iv)
        )
        val ciphertext = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(iv + ciphertext, Base64.NO_WRAP)
    }

    // Decrypt payload from encrypt()
    fun decrypt(base64Payload: String ,groupKey: ByteArray): String {
        val combined = Base64.decode(base64Payload, Base64.NO_WRAP)
        val iv = combined.copyOfRange(0, IV_LENGTH)
        val ciphertext = combined.copyOfRange(IV_LENGTH, combined.size)

        val cipher = Cipher.getInstance(ALGORITHM)
        cipher.init(
            Cipher.DECRYPT_MODE,
            SecretKeySpec(groupKey, KEY_ALGO),
            GCMParameterSpec(TAG_LENGTH, iv)
        )
        return String(cipher.doFinal(ciphertext), Charsets.UTF_8)
    }
}