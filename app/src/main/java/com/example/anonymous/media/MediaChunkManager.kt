package com.example.anonymous.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

class MediaChunkManager private constructor(private val context: Context) {
    companion object {
        private const val TAG = "MediaChunkManager"

        @Volatile private var instance: MediaChunkManager? = null

        fun getInstance(context: Context): MediaChunkManager = instance ?: synchronized(this) {
            instance ?: MediaChunkManager(context.applicationContext).also { instance = it }
        }
    }

    private val cacheDir = File(context.cacheDir, "media_chunks").also { it.mkdirs() }

    // mediaId -> nullable chunk array
    private val reassemblyBuffers = ConcurrentHashMap<String, Array<ByteArray?>>()

    // mediaId -> metadata
    private val metaStore = ConcurrentHashMap<String, MediaMeta>()

    private val _stateFlows = ConcurrentHashMap<String, MutableStateFlow<MediaLoadState>>()

    fun stateFor(mediaId: String): StateFlow<MediaLoadState> = _stateFlows.getOrPut(mediaId) {
        val cached = getCachedFile(mediaId)
        val meta = metaStore[mediaId]
        if (cached != null && meta != null) {
            MutableStateFlow(MediaLoadState.Ready(cached, meta.mimeType))
        } else if (meta != null) {
            MutableStateFlow(MediaLoadState.Pending(meta))
        } else {
            MutableStateFlow(MediaLoadState.Unknown)
        }
    }.asStateFlow()

    private fun emit(mediaId: String, state: MediaLoadState) {
        _stateFlows.getOrPut(mediaId) { MutableStateFlow(state) }.value = state
    }

    // Compress uri -> SHA-256 hash -> split into16 KB chunks
    fun prepareMedia(uri: Uri): Pair<MediaMeta, List<String>>? {
        return try {
            val (bytes, mime) = compressUri(uri) ?: return null
            val hash   = sha256Hex(bytes)
            val chunks = bytes.splitIntoChunks(MediaProtocol.CHUNK_SIZE)

            val meta = MediaMeta(
                mediaId = hash,
                mimeType = mime,
                totalSize = bytes.size,
                chunkCount = chunks.size
            )

            metaStore[hash] = meta
            writeCacheFile(hash, bytes)
            emit(hash, MediaLoadState.Ready(getCachedFile(hash)!!, mime))

            val encoded = chunks.map { Base64.encodeToString(it, Base64.NO_WRAP) }
            Pair(meta, encoded)
        } catch (e : Exception) {
            Log.e(TAG, "prepareMedia failed: ${e.message}", e)
            null
        }
    }

    // Incoming MEDIA_META
    fun registerMeta(meta: MediaMeta) {
        metaStore[meta.mediaId] = meta

        // Already cached?
        val cached = getCachedFile(meta.mediaId)
        if (cached != null) {
            emit(meta.mediaId, MediaLoadState.Ready(cached, meta.mimeType))
            return
        }

        reassemblyBuffers.getOrPut(meta.mediaId) { arrayOfNulls(meta.chunkCount) }
        emit(meta.mediaId, MediaLoadState.Pending(meta))
        Log.d(TAG, "Registered meta for ${meta.mediaId} (${meta.chunkCount} chunks, ${meta.totalSize} B)")
    }

    // On MEDIA_CHUNK
    fun onChunkReceived(mediaId: String, chunkIndex: Int, base64Data: String): File? {
        // Already complete?
        val existing = getCachedFile(mediaId)
        if (existing != null) return existing

        val meta = metaStore[mediaId] ?: run {
            Log.w(TAG, "onChunkReceived: no meta for $mediaId - buffering impossible, drop chunk $chunkIndex")
            return null
        }
        val buffer = reassemblyBuffers.getOrPut(mediaId) { arrayOfNulls(meta.chunkCount) }

        if (chunkIndex < 0 || chunkIndex >= buffer.size) {
            Log.w(TAG, "onChunkReceived: chunk $chunkIndex out of range for $mediaId")
            return null
        }

        // Decode & store
        if (buffer[chunkIndex] == null) {
            buffer[chunkIndex] = Base64.decode(base64Data, Base64.NO_WRAP)
        }

        val received = buffer.count { it != null }
        emit(mediaId, MediaLoadState.Loading(meta, received))
        Log.v(TAG, " [$mediaId] chunk $chunkIndex received ($received/${meta.chunkCount})")

        if (received < meta.chunkCount) return null

        // Reassemble
        val out = ByteArrayOutputStream(meta.totalSize)
        buffer.forEach { chunk -> out.write(chunk) }
        val assembled = out.toByteArray()

        if (sha256Hex(assembled) != mediaId) {
            Log.e(TAG, "Hash mismatch for $mediaId")
            reassemblyBuffers.remove(mediaId)
            emit(mediaId, MediaLoadState.Error)
            return null
        }

        val file = writeCacheFile(mediaId, assembled)
        reassemblyBuffers.remove(mediaId)
        emit(mediaId, MediaLoadState.Ready(file, meta.mimeType))
        Log.i(TAG, "Media $mediaId fully assembled (${assembled.size} B)")

        return file
    }

    fun getChunk(mediaId: String, chunkIndex: Int): String? {
        val file = getCachedFile(mediaId) ?: return null
        val bytes = file.readBytes()
        val start = chunkIndex * MediaProtocol.CHUNK_SIZE
        if (start >= bytes.size) return null
        val end = minOf(start + MediaProtocol.CHUNK_SIZE, bytes.size)
        return Base64.encodeToString(bytes.copyOfRange(start, end), Base64.NO_WRAP)
    }

    fun getMeta(mediaId: String): MediaMeta? = metaStore[mediaId]

    fun getMissingChunkIndices(mediaId: String): List<Int> {
        if (getCachedFile(mediaId) != null) return emptyList()
        val meta = metaStore[mediaId] ?: return emptyList()
        val buffer = reassemblyBuffers[mediaId] ?: return (0 until meta.chunkCount).toList()
        return buffer.indices.filter { buffer[it] == null }
    }

    fun getCachedFile(mediaId: String): File? {
        val f = File(cacheDir, mediaId)
        return if (f.exists() && f.length() > 0) f else null
    }

    // Pipeline read -> sanitize (strip all metadata) -> scale -> re-encode
    private fun compressUri(uri: Uri): CompressResult? {
        val mime = context.contentResolver.getType(uri) ?: "image/*"
        val raw = context.contentResolver.openInputStream(uri)?.readBytes() ?: return null

        // 1 - Sanitize: strip EXIF / XMP / ICC / comments / GPS / device info
        val (sanitized, outMime) = MediaSanitizer.sanitize(raw, mime) ?: run {
            Log.e(TAG, "compressUri: sanitize returned null for $mime")
            return null
        }
        val before = raw.size
        val after = sanitized.size
        Log.d(TAG, "Metadata stripped ${before}B -> ${after}B (saved ${before - after}B)")

        // 2 - GIF
        if (outMime == "image/gif") {
            return if (sanitized.size <= MediaProtocol.MAX_MEDIA_BYTES) {
                CompressResult(sanitized, outMime)
            } else {
                Log.w(TAG, "GIF still ${sanitized.size}B after strip")
                null
            }
        }

        // 3 - Raster
        val bitmap = BitmapFactory.decodeByteArray(sanitized, 0, sanitized.size) ?: return null
        val scaled = scaleBitmap(bitmap, MediaProtocol.MAX_DIMENSION)

        val out = ByteArrayOutputStream()
        val format = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) Bitmap.CompressFormat.WEBP_LOSSY
        else Bitmap.CompressFormat.WEBP

        scaled.compress(format, MediaProtocol.WEBP_QUALITY, out)
        val compressed = out.toByteArray()

        return if (compressed.size > MediaProtocol.MAX_MEDIA_BYTES) {
            Log.w(TAG, "Image still ${compressed.size}B after compression")
            null
        } else {
            CompressResult(compressed, "image/webp")
        }
    }

    private data class CompressResult(val bytes: ByteArray, val mime: String)

    private fun scaleBitmap(src: Bitmap, maxEdge: Int): Bitmap {
        val scale = minOf(maxEdge.toFloat() / src.width, maxEdge.toFloat() / src.height)
        return if (scale >= 1f) src
        else Bitmap.createScaledBitmap(src, (src.width * scale).toInt(), (src.height * scale).toInt(), true)
    }

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it) }

    private fun writeCacheFile(mediaId: String, bytes: ByteArray): File = File(cacheDir, mediaId).also { it.writeBytes(bytes) }

    private fun ByteArray.splitIntoChunks(size: Int): List<ByteArray> {
        val result = mutableListOf<ByteArray>()
        var offset = 0
        while (offset < this.size) {
            result.add(copyOfRange(offset, minOf(offset + size, this.size )))
            offset += size
        }
        return result
    }
}