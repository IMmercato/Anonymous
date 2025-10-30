package com.example.anonymous.utils

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.PublicKey

object PrefsHelper {

    private const val PREFS_NAME = "anonymous_prefs"

    // Key names
    private const val KEY_KEY_ALIAS = "key_alias"
    private const val KEY_REGISTRATION_JWT = "registration_jwt"
    private const val KEY_SESSION_TOKEN = "session_token"
    private const val KEY_USER_UUID = "user_uuid"
    private const val KEY_PUBLIC_KEY = "public_key"
    private const val KEY_LAST_REGISTRATION_TIME = "last_registration_time"
    private const val KEY_LAST_LOGIN_TIME = "last_login_time"
    private const val KEY_IS_LOGGED_IN = "is_logged_in"

    // --- Secure SharedPreferences ---
    private fun getSharedPreferences(context: Context): SharedPreferences {
        // Build a modern MasterKey using KeyGenParameterSpec (no deprecations)
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

    // --- Keystore ---
    private const val KEYSTORE_PROVIDER = "AndroidKeyStore"

    fun generateOrGetKeyPair(alias: String): KeyPair {
        val ks = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
        return if (ks.containsAlias(alias)) {
            val privateKey = ks.getKey(alias, null) as PrivateKey
            val publicKey = ks.getCertificate(alias).publicKey
            KeyPair(publicKey, privateKey)
        } else {
            val kpg = KeyPairGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_RSA, KEYSTORE_PROVIDER
            )
            val spec = KeyGenParameterSpec.Builder(
                alias,
                KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_DECRYPT
            )
                .setDigests(KeyProperties.DIGEST_SHA256, KeyProperties.DIGEST_SHA512)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_RSA_PKCS1)
                .build()

            kpg.initialize(spec)
            kpg.generateKeyPair()
        }
    }

    fun getPublicKey(alias: String): PublicKey? {
        val ks = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
        return if (ks.containsAlias(alias)) ks.getCertificate(alias).publicKey else null
    }

    fun deleteKeyPair(alias: String) {
        val ks = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
        if (ks.containsAlias(alias)) ks.deleteEntry(alias)
    }

    // --- SharedPreferences extension ---
    private inline fun SharedPreferences.edit(operation: SharedPreferences.Editor.() -> Unit) {
        val editor = edit()
        editor.operation()
        editor.apply()
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

    // --- Registration JWT ---
    fun saveRegistrationJwt(context: Context, jwt: String) {
        getSharedPreferences(context).edit { putString(KEY_REGISTRATION_JWT, jwt) }
    }

    fun getRegistrationJwt(context: Context): String? =
        getSharedPreferences(context).getString(KEY_REGISTRATION_JWT, null)

    fun clearRegistrationJwt(context: Context) {
        getSharedPreferences(context).edit { remove(KEY_REGISTRATION_JWT) }
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

    // --- Public key (string form, if needed) ---
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
            remove(KEY_REGISTRATION_JWT)
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
        return prefs.contains(KEY_KEY_ALIAS) &&
                prefs.contains(KEY_REGISTRATION_JWT) &&
                prefs.contains(KEY_USER_UUID)
    }

    fun isRegistrationValid(context: Context): Boolean {
        val jwt = getRegistrationJwt(context)
        return jwt != null && JwtUtils.isJwtValid(jwt)
    }

    fun getRegistrationTimeRemaining(context: Context): Long {
        val jwt = getRegistrationJwt(context)
        return if (jwt != null) JwtUtils.getJwtTimeRemaining(jwt) else 0L
    }

    fun isSessionValid(context: Context): Boolean {
        val token = getSessionToken(context)
        return token != null && JwtUtils.isJwtValid(token)
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
                    is String -> putString(key, value)
                    is Int -> putInt(key, value)
                    is Long -> putLong(key, value)
                    is Float -> putFloat(key, value)
                    is Boolean -> putBoolean(key, value)
                }
            }
        }

        oldPrefs.edit().clear().apply()
    }
}