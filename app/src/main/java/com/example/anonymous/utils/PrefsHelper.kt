package com.example.anonymous.utils

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.annotation.RequiresApi
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.PublicKey
import java.security.SecureRandom
import java.security.spec.ECGenParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec

object PrefsHelper {

    private const val PREFS_NAME = "anonymous_prefs"
    private const val KEYSTORE_PROVIDER = "AndroidKeyStore"

    // Key names
    private const val KEY_KEY_ALIAS = "key_alias"
    private const val KEY_SESSION_TOKEN = "session_token"
    private const val KEY_USER_UUID = "user_uuid"
    private const val KEY_PUBLIC_KEY = "public_key"
    private const val KEY_LAST_REGISTRATION_TIME = "last_registration_time"
    private const val KEY_LAST_LOGIN_TIME = "last_login_time"
    private const val KEY_IS_LOGGED_IN = "is_logged_in"

    // Suffix used when storing fallback EC keys in EncryptedSharedPreferences
    private const val EC_PRIV_SUFFIX = "_ec_private"
    private const val EC_PUB_SUFFIX  = "_ec_public"

    // --- Secure SharedPreferences ---
    private fun getSharedPreferences(context: Context): SharedPreferences {
        val keyGenParameterSpec = KeyGenParameterSpec.Builder(
            MasterKey.DEFAULT_MASTER_KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build()

        val masterKey = MasterKey.Builder(context)
            .setKeyGenParameterSpec(keyGenParameterSpec)
            .build()

        return EncryptedSharedPreferences.create(
            context,
            PREFS_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    fun getSecurePrefs(context: Context): SharedPreferences = getSharedPreferences(context)

    // --- SharedPreferences extension ---
    private inline fun SharedPreferences.edit(operation: SharedPreferences.Editor.() -> Unit) {
        val editor = edit()
        editor.operation()
        editor.apply()
    }

    // ECDH Key Pair — used for message encryption/decryption
    fun generateOrGetECDHKeyPair(context: Context, alias: String): KeyPair {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            generateOrGetECDHKeyPairHardware(alias)
        } else {
            generateOrGetECDHKeyPairSoftware(context, alias)
        }
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private fun generateOrGetECDHKeyPairHardware(alias: String): KeyPair {
        val ks = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
        if (ks.containsAlias(alias)) {
            // Private key reference — bytes stay in the TEE, never leave
            val privateKey = ks.getKey(alias, null) as PrivateKey
            val publicKey  = ks.getCertificate(alias).publicKey
            return KeyPair(publicKey, privateKey)
        }

        val kpg  = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, KEYSTORE_PROVIDER)
        val spec = KeyGenParameterSpec.Builder(
            alias,
            KeyProperties.PURPOSE_AGREE_KEY   // enables KeyAgreement("ECDH") in hardware
        )
            .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
            .build()

        kpg.initialize(spec)
        return kpg.generateKeyPair()
        // From this point the private key object is a hardware-bound reference only.
        // calling .encoded on it returns null — it cannot be exported.
    }

    private fun generateOrGetECDHKeyPairSoftware(context: Context, alias: String): KeyPair {
        val prefs   = getSharedPreferences(context)
        val privB64 = prefs.getString("$alias$EC_PRIV_SUFFIX", null)
        val pubB64  = prefs.getString("$alias$EC_PUB_SUFFIX",  null)

        if (privB64 != null && pubB64 != null) {
            val factory = KeyFactory.getInstance("EC")
            val pubKey  = factory.generatePublic(X509EncodedKeySpec(Base64.decode(pubB64,  Base64.NO_WRAP)))
            val privKey = factory.generatePrivate(PKCS8EncodedKeySpec(Base64.decode(privB64, Base64.NO_WRAP)))
            return KeyPair(pubKey, privKey)
        }

        // Generate fresh EC key pair in software
        val kpg     = KeyPairGenerator.getInstance("EC")
        kpg.initialize(ECGenParameterSpec("secp256r1"), SecureRandom())
        val keyPair = kpg.generateKeyPair()

        // Persist in EncryptedSharedPreferences (AES-256-GCM, master key in AndroidKeyStore)
        prefs.edit {
            putString("$alias$EC_PUB_SUFFIX",  Base64.encodeToString(keyPair.public.encoded,  Base64.NO_WRAP))
            putString("$alias$EC_PRIV_SUFFIX", Base64.encodeToString(keyPair.private.encoded, Base64.NO_WRAP))
        }
        return keyPair
    }

    /**
     * Return the EC public key bytes for the given alias regardless of API level.
     * Safe to call on any API — the public key is always exportable.
     */
    fun getECPublicKeyBytes(context: Context, alias: String): ByteArray? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val ks = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
            ks.getCertificate(alias)?.publicKey?.encoded
        } else {
            val b64 = getSharedPreferences(context).getString("$alias$EC_PUB_SUFFIX", null)
                ?: return null
            Base64.decode(b64, Base64.NO_WRAP)
        }
    }

    /**
     * Delete ECDH key for the given alias from wherever it was stored.
     */
    fun deleteECDHKeyPair(context: Context, alias: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val ks = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
            if (ks.containsAlias(alias)) ks.deleteEntry(alias)
        }
        // Always clean up the software-fallback slots too (handles migration)
        getSharedPreferences(context).edit {
            remove("$alias$EC_PUB_SUFFIX")
            remove("$alias$EC_PRIV_SUFFIX")
        }
    }

    // --- RSA Signing Key Pair (identity signing only — NOT used for message encryption) ---
    fun generateOrGetSigningKeyPair(alias: String): KeyPair {
        val ks = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
        if (ks.containsAlias(alias)) {
            val privateKey = ks.getKey(alias, null) as PrivateKey
            val publicKey  = ks.getCertificate(alias).publicKey
            return KeyPair(publicKey, privateKey)
        }
        val kpg  = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_RSA, KEYSTORE_PROVIDER)
        val spec = KeyGenParameterSpec.Builder(
            alias,
            KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY
        )
            .setDigests(KeyProperties.DIGEST_SHA256, KeyProperties.DIGEST_SHA512)
            .setSignaturePaddings(KeyProperties.SIGNATURE_PADDING_RSA_PKCS1)
            .setKeySize(2048)
            .build()
        kpg.initialize(spec)
        return kpg.generateKeyPair()
    }

    fun getSigningPublicKey(alias: String): PublicKey? {
        val ks = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
        return if (ks.containsAlias(alias)) ks.getCertificate(alias).publicKey else null
    }

    fun deleteSigningKeyPair(alias: String) {
        val ks = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
        if (ks.containsAlias(alias)) ks.deleteEntry(alias)
    }

    // --- Key alias ---
    fun saveKeyAlias(context: Context, alias: String) {
        getSharedPreferences(context).edit { putString(KEY_KEY_ALIAS, alias) }
    }

    fun getKeyAlias(context: Context): String? =
        getSharedPreferences(context).getString(KEY_KEY_ALIAS, null)

    fun clearKeyAlias(context: Context) {
        getSharedPreferences(context).edit { remove(KEY_KEY_ALIAS) }
    }

    // --- Session token ---
    fun saveSessionToken(context: Context, token: String) {
        getSharedPreferences(context).edit { putString(KEY_SESSION_TOKEN, token) }
    }

    fun getSessionToken(context: Context): String? =
        getSharedPreferences(context).getString(KEY_SESSION_TOKEN, null)

    fun clearSessionToken(context: Context) {
        getSharedPreferences(context).edit { remove(KEY_SESSION_TOKEN) }
    }

    // --- User UUID ---
    fun saveUserUuid(context: Context, uuid: String) {
        getSharedPreferences(context).edit { putString(KEY_USER_UUID, uuid) }
    }

    fun getUserUuid(context: Context): String? =
        getSharedPreferences(context).getString(KEY_USER_UUID, null)

    fun clearUserUuid(context: Context) {
        getSharedPreferences(context).edit { remove(KEY_USER_UUID) }
    }

    // --- Public key (Base64 string form for QR / sharing) ---
    fun savePublicKey(context: Context, publicKey: String) {
        getSharedPreferences(context).edit { putString(KEY_PUBLIC_KEY, publicKey) }
    }

    fun getPublicKey(context: Context): String? =
        getSharedPreferences(context).getString(KEY_PUBLIC_KEY, null)

    fun clearPublicKey(context: Context) {
        getSharedPreferences(context).edit { remove(KEY_PUBLIC_KEY) }
    }

    // --- Timestamps ---
    fun saveLastRegistrationTime(context: Context, timestamp: Long) {
        getSharedPreferences(context).edit { putLong(KEY_LAST_REGISTRATION_TIME, timestamp) }
    }

    fun getLastRegistrationTime(context: Context): Long =
        getSharedPreferences(context).getLong(KEY_LAST_REGISTRATION_TIME, 0L)

    fun saveLastLoginTime(context: Context, timestamp: Long) {
        getSharedPreferences(context).edit { putLong(KEY_LAST_LOGIN_TIME, timestamp) }
    }

    fun getLastLoginTime(context: Context): Long =
        getSharedPreferences(context).getLong(KEY_LAST_LOGIN_TIME, 0L)

    // --- Login state ---
    fun setLoggedIn(context: Context, isLoggedIn: Boolean) {
        getSharedPreferences(context).edit { putBoolean(KEY_IS_LOGGED_IN, isLoggedIn) }
    }

    fun isLoggedIn(context: Context): Boolean =
        getSharedPreferences(context).getBoolean(KEY_IS_LOGGED_IN, false)

    // --- Cleanup ---
    fun clearAllAuthData(context: Context) {
        getSharedPreferences(context).edit {
            remove(KEY_KEY_ALIAS)
            remove(KEY_SESSION_TOKEN)
            remove(KEY_USER_UUID)
            remove(KEY_PUBLIC_KEY)
            remove(KEY_IS_LOGGED_IN)
        }
    }

    fun clearAll(context: Context) {
        getSharedPreferences(context).edit { clear() }
    }

    // --- Registration checks ---
    fun hasCompletedRegistration(context: Context): Boolean {
        val prefs = getSharedPreferences(context)
        return prefs.contains(KEY_KEY_ALIAS) && prefs.contains(KEY_USER_UUID)
    }

    // --- Debugging ---
    fun getAllStoredData(context: Context): Map<String, *> =
        getSharedPreferences(context).all

    // --- Migration ---
    fun migrateData(context: Context, oldPrefsName: String) {
        val oldPrefs = context.getSharedPreferences(oldPrefsName, Context.MODE_PRIVATE)
        val newPrefs = getSharedPreferences(context)

        newPrefs.edit {
            oldPrefs.all.forEach { (key, value) ->
                when (value) {
                    is String  -> putString(key, value)
                    is Int     -> putInt(key, value)
                    is Long    -> putLong(key, value)
                    is Float   -> putFloat(key, value)
                    is Boolean -> putBoolean(key, value)
                }
            }
        }
        oldPrefs.edit().clear().apply()
    }
}