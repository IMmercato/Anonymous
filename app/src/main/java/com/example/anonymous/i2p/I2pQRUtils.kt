package com.example.anonymous.i2p

import android.util.Log
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder

data class ParsedI2PIdentity(
    val b32Address: String,
    val i2pDestination: String?,        // Full base64 I2P destination
    val ecPublicKey: String?,           // Base64 ECDH public key
    val version: Int
)

object I2pQRUtils {
    private const val TAG = "I2PQRUtils"
    private const val SCHEME = "anonymous://"
    private const val CURRENT_VERSION = 1

    fun generateQRContent(b32Address: String, i2pDestination: String, ecPublicKey: String? = null): String = buildString {
        append(SCHEME)
        append(b32Address)
        append("?i2p_dest=")
        append(URLEncoder.encode(i2pDestination, "UTF-8"))
        ecPublicKey?.let {
            append("&ec_key=")
            append(URLEncoder.encode(it, "UTF-8"))
        }
        append("&v=")
        append(CURRENT_VERSION)
    }

    fun parseQRContent(content: String): ParsedI2PIdentity? = try {
        when {
            content.startsWith(SCHEME) -> parseFullFormat(content)
            isValidB32(content) -> ParsedI2PIdentity(content, null, null, CURRENT_VERSION)
            else -> null
        }
    } catch (e: Exception) {
        Log.e(TAG, "Failed to parse QR content: ${e.message}", e)
        null
    }

    private fun parseFullFormat(content: String): ParsedI2PIdentity? {
        val uri = URI(content)
        val b32 = uri.host ?: return null
        if (!isValidB32(b32)) return null

        val params: Map<String, String> = uri.query
            ?.split("&")
            ?.mapNotNull { pair->
                val parts = pair.split("=", limit = 2)
                if (parts.size == 2) parts[0] to URLDecoder.decode(parts[1], "UTF-8")
                else null
            }
            ?.toMap()
            ?: emptyMap()

        return ParsedI2PIdentity(
            b32Address = b32,
            i2pDestination = params["i2p_dest"]?.takeIf { it.isNotBlank() },
            ecPublicKey = params["ec_key"]?.takeIf { it.isNotBlank() },
            version = params["v"]?.toIntOrNull() ?: CURRENT_VERSION
        )
    }

    // B32 Address: <52+ base32 chars>.b32.i2p
    fun isValidB32(b32: String): Boolean {
        val lower = b32.lowercase()
        if (!lower.endsWith(".b32.i2p")) return false
        val prefix = lower.removeSuffix(".b32.i2p")
        return prefix.length >= 52 && prefix.all { it in 'a'..'z' || it in '2'..'7' }
    }
}