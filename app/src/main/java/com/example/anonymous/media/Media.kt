package com.example.anonymous.media

import kotlinx.serialization.Serializable
import java.io.File

object MediaProtocol {
    // Maximum payload bytes per chunk (16 KB -> ~22 KB base64 JSON line)
    const val CHUNK_SIZE = 16_384

    // Hard cap on compressed media size before chunking.
    const val MAX_MEDIA_BYTES = 1_048_576       // 1 MB

    // Maximum WebP dimension
    const val MAX_DIMENSION = 1280

    // WebP quality 0-100
    const val WEBP_QUALITY = 80

    // Packets type

    const val TYPE_MSG = "MSG"
    // Sender announces upcoming media
    const val TYPE_MEDIA_META = "MEDIA_META"
    // One 16 KB chunk of a media item
    const val TYPE_MEDIA_CHUNK = "MEDIA_CHUNK"
    const val TYPE_MEDIA_GET = "MEDIA_GET"
}

@Serializable
data class MediaMeta(
    // Hex-encoded SHA-256 of the full compressed byte array
    val mediaId: String,
    val mimeType: String,           // "image/webp" or "image/gif"
    val fileName: String? = null,
    val totalSize: Int,             // bytes after compression
    val chunkCount: Int,
    val width: Int? = null,
    val height: Int? = null
)

sealed class MediaLoadState {
    object Unknown: MediaLoadState()
    data class Pending(val meta: MediaMeta) : MediaLoadState()
    data class Loading(val meta: MediaMeta, val received: Int) : MediaLoadState()
    data class Ready(val file: File, val mimeType: String) : MediaLoadState()
    object Error : MediaLoadState()
}