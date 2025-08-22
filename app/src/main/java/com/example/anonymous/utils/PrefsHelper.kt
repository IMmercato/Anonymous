package com.example.anonymous.utils

import android.content.Context
import android.content.SharedPreferences

object PrefsHelper {

    private const val PREFS_NAME = "anonymous_prefs"

    // Key names for stored values
    private const val KEY_KEY_ALIAS = "key_alias"
    private const val KEY_REGISTRATION_JWT = "registration_jwt"
    private const val KEY_SESSION_TOKEN = "session_token"
    private const val KEY_USER_UUID = "user_uuid"
    private const val KEY_PUBLIC_KEY = "public_key"
    private const val KEY_LAST_REGISTRATION_TIME = "last_registration_time"
    private const val KEY_LAST_LOGIN_TIME = "last_login_time"
    private const val KEY_IS_LOGGED_IN = "is_logged_in"

    private fun getSharedPreferences(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    // Key alias management
    fun saveKeyAlias(context: Context, alias: String) {
        getSharedPreferences(context).edit().putString(KEY_KEY_ALIAS, alias).apply()
    }

    fun getKeyAlias(context: Context): String? {
        return getSharedPreferences(context).getString(KEY_KEY_ALIAS, null)
    }

    fun clearKeyAlias(context: Context) {
        getSharedPreferences(context).edit().remove(KEY_KEY_ALIAS).apply()
    }

    // Registration JWT management
    fun saveRegistrationJwt(context: Context, jwt: String) {
        getSharedPreferences(context).edit().putString(KEY_REGISTRATION_JWT, jwt).apply()
    }

    fun getRegistrationJwt(context: Context): String? {
        return getSharedPreferences(context).getString(KEY_REGISTRATION_JWT, null)
    }

    fun clearRegistrationJwt(context: Context) {
        getSharedPreferences(context).edit().remove(KEY_REGISTRATION_JWT).apply()
    }

    // Session token management
    fun saveSessionToken(context: Context, token: String) {
        getSharedPreferences(context).edit().putString(KEY_SESSION_TOKEN, token).apply()
    }

    fun getSessionToken(context: Context): String? {
        return getSharedPreferences(context).getString(KEY_SESSION_TOKEN, null)
    }

    fun clearSessionToken(context: Context) {
        getSharedPreferences(context).edit().remove(KEY_SESSION_TOKEN).apply()
    }

    // User UUID management
    fun saveUserUuid(context: Context, uuid: String) {
        getSharedPreferences(context).edit().putString(KEY_USER_UUID, uuid).apply()
    }

    fun getUserUuid(context: Context): String? {
        return getSharedPreferences(context).getString(KEY_USER_UUID, null)
    }

    fun clearUserUuid(context: Context) {
        getSharedPreferences(context).edit().remove(KEY_USER_UUID).apply()
    }

    // Public key management
    fun savePublicKey(context: Context, publicKey: String) {
        getSharedPreferences(context).edit().putString(KEY_PUBLIC_KEY, publicKey).apply()
    }

    fun getPublicKey(context: Context): String? {
        return getSharedPreferences(context).getString(KEY_PUBLIC_KEY, null)
    }

    fun clearPublicKey(context: Context) {
        getSharedPreferences(context).edit().remove(KEY_PUBLIC_KEY).apply()
    }

    // Timestamp management
    fun saveLastRegistrationTime(context: Context, timestamp: Long) {
        getSharedPreferences(context).edit().putLong(KEY_LAST_REGISTRATION_TIME, timestamp).apply()
    }

    fun getLastRegistrationTime(context: Context): Long {
        return getSharedPreferences(context).getLong(KEY_LAST_REGISTRATION_TIME, 0L)
    }

    fun saveLastLoginTime(context: Context, timestamp: Long) {
        getSharedPreferences(context).edit().putLong(KEY_LAST_LOGIN_TIME, timestamp).apply()
    }

    fun getLastLoginTime(context: Context): Long {
        return getSharedPreferences(context).getLong(KEY_LAST_LOGIN_TIME, 0L)
    }

    // Login state management
    fun setLoggedIn(context: Context, isLoggedIn: Boolean) {
        getSharedPreferences(context).edit().putBoolean(KEY_IS_LOGGED_IN, isLoggedIn).apply()
    }

    fun isLoggedIn(context: Context): Boolean {
        return getSharedPreferences(context).getBoolean(KEY_IS_LOGGED_IN, false)
    }

    // Comprehensive cleanup methods
    fun clearAllAuthData(context: Context) {
        getSharedPreferences(context).edit()
            .remove(KEY_KEY_ALIAS)
            .remove(KEY_REGISTRATION_JWT)
            .remove(KEY_SESSION_TOKEN)
            .remove(KEY_USER_UUID)
            .remove(KEY_PUBLIC_KEY)
            .remove(KEY_IS_LOGGED_IN)
            .apply()
    }

    fun clearAll(context: Context) {
        getSharedPreferences(context).edit().clear().apply()
    }

    // Utility methods to check if user has completed registration
    fun hasCompletedRegistration(context: Context): Boolean {
        val prefs = getSharedPreferences(context)
        return prefs.contains(KEY_KEY_ALIAS) &&
                prefs.contains(KEY_REGISTRATION_JWT) &&
                prefs.contains(KEY_USER_UUID)
    }

    // Check if registration JWT is still valid
    fun isRegistrationValid(context: Context): Boolean {
        val jwt = getRegistrationJwt(context)
        return jwt != null && JwtUtils.isJwtValid(jwt)
    }

    // Get time remaining for registration JWT
    fun getRegistrationTimeRemaining(context: Context): Long {
        val jwt = getRegistrationJwt(context)
        return if (jwt != null) JwtUtils.getJwtTimeRemaining(jwt) else 0L
    }

    // Check if session token is valid (basic check - not expired)
    fun isSessionValid(context: Context): Boolean {
        val token = getSessionToken(context)
        return token != null && JwtUtils.isJwtValid(token)
    }

    // Get all stored data for debugging purposes
    fun getAllStoredData(context: Context): Map<String, *> {
        return getSharedPreferences(context).all
    }

    // Migration helper (if you need to change storage format in future)
    fun migrateData(context: Context, oldPrefsName: String) {
        val oldPrefs = context.getSharedPreferences(oldPrefsName, Context.MODE_PRIVATE)
        val newPrefs = getSharedPreferences(context)
        val editor = newPrefs.edit()

        oldPrefs.all.forEach { (key, value) ->
            when (value) {
                is String -> editor.putString(key, value)
                is Int -> editor.putInt(key, value)
                is Long -> editor.putLong(key, value)
                is Float -> editor.putFloat(key, value)
                is Boolean -> editor.putBoolean(key, value)
            }
        }
        editor.apply()

        // Clear old preferences after migration
        oldPrefs.edit().clear().apply()
    }
}