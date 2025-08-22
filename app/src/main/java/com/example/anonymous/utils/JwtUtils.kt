package com.example.anonymous.utils

import android.util.Base64
import android.util.Log
import org.json.JSONObject
import java.nio.charset.StandardCharsets

object JwtUtils {
    private const val TAG = "JwtUtils"

    fun decodeJwtPayload(jwt: String): Map<String, Any> {
        return try {
            val parts = jwt.split(".")
            if (parts.size != 3) {
                throw IllegalArgumentException("Invalid JWT format: expected 3 parts, got ${parts.size}")
            }

            // Decode the payload (second part)
            val payloadJson = String(
                Base64.decode(parts[1], Base64.URL_SAFE or Base64.NO_PADDING),
                StandardCharsets.UTF_8
            )

            Log.d(TAG, "JWT Payload: $payloadJson")
            JSONObject(payloadJson).toMap()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to decode JWT", e)
            emptyMap()
        }
    }

    fun extractUuidFromJwt(jwt: String): String? {
        val payload = decodeJwtPayload(jwt)
        return payload["uuid"] as? String
    }

    fun extractPublicKeyFromJwt(jwt: String): String? {
        val payload = decodeJwtPayload(jwt)
        return payload["publicKey"] as? String
    }

    fun getJwtExpiration(jwt: String): Long {
        val payload = decodeJwtPayload(jwt)
        val exp = payload["exp"]
        return when (exp) {
            is Number -> exp.toLong() * 1000 // Convert seconds to milliseconds
            is String -> exp.toLongOrNull() ?: 0L
            else -> 0L
        }
    }

    fun getJwtIssuedAt(jwt: String): Long {
        val payload = decodeJwtPayload(jwt)
        val iat = payload["iat"]
        return when (iat) {
            is Number -> iat.toLong() * 1000
            is String -> iat.toLongOrNull() ?: 0L
            else -> 0L
        }
    }

    fun isJwtExpired(jwt: String): Boolean {
        val expiration = getJwtExpiration(jwt)
        if (expiration == 0L) {
            Log.w(TAG, "JWT has no expiration time, considering it expired for safety")
            return true
        }
        return expiration < System.currentTimeMillis()
    }

    fun isJwtValid(jwt: String): Boolean {
        if (jwt.isEmpty()) {
            Log.w(TAG, "Empty JWT")
            return false
        }

        if (jwt.split('.').size != 3) {
            Log.w(TAG, "Invalid JWT format")
            return false
        }

        val uuid = extractUuidFromJwt(jwt)
        if (uuid.isNullOrEmpty()) {
            Log.w(TAG, "JWT missing UUID")
            return false
        }

        val publicKey = extractPublicKeyFromJwt(jwt)
        if (publicKey.isNullOrEmpty()) {
            Log.w(TAG, "JWT missing public key")
            return false
        }

        if (isJwtExpired(jwt)) {
            Log.w(TAG, "JWT is expired")
            return false
        }

        Log.d(TAG, "JWT is valid for UUID: $uuid")
        return true
    }

    fun getJwtTimeRemaining(jwt: String): Long {
        val expiration = getJwtExpiration(jwt)
        if (expiration == 0L) return 0L
        return expiration - System.currentTimeMillis()
    }

    fun formatTimeRemaining(milliseconds: Long): String {
        val seconds = milliseconds / 1000
        val minutes = seconds / 60
        val hours = minutes / 60
        val days = hours / 24

        return when {
            days > 0 -> "$days days"
            hours > 0 -> "$hours hours"
            minutes > 0 -> "$minutes minutes"
            else -> "$seconds seconds"
        }
    }

    private fun JSONObject.toMap(): Map<String, Any> {
        val map = mutableMapOf<String, Any>()
        val keys = this.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val value = this.get(key)
            map[key] = value
        }
        return map
    }
}