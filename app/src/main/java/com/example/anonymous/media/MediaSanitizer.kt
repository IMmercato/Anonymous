package com.example.anonymous.media

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.util.Log
import java.io.ByteArrayOutputStream

/**
 * Strips all metadata from media bytes before they leave the device.
 *
 * ┌─────────────────┬──────────────────────────────────────────────────────────┐
 * │ Format          │ What is stripped                                         │
 * ├─────────────────┼──────────────────────────────────────────────────────────┤
 * │ JPEG / PNG /    │ Everything — we decode to a raw Bitmap (pixel data only) │
 * │ WebP / BMP …    │ then re-encode as WebP. EXIF, XMP, ICC, IPTC, comments,  │
 * │                 │ GPS, device info, thumbnails — all gone.                 │
 * ├─────────────────┼──────────────────────────────────────────────────────────┤
 * │ GIF             │ Comment extensions (0xFE) and Application extensions     │
 * │                 │ (0xFF) are removed, EXCEPT the Netscape 2.0 block which  │
 * │                 │ carries the animation loop count and must be preserved.  │
 * │                 │ Graphic Control Extensions (0xF9) are kept — they carry  │
 * │                 │ per-frame delay and disposal method (animation data).    │
 * └─────────────────┴──────────────────────────────────────────────────────────┘
 */

object MediaSanitizer {
    private const val TAG = "MediaSanitizer"

    fun sanitize(raw: ByteArray, mimeType: String): Pair<ByteArray, String>? {
        return if (mimeType == "image/gif") {
            val stripped = stripGifMetadata(raw) ?: return null
            Pair(stripped, "image/gif")
        } else {
            rasterToWebP(raw)
        }
    }

    // decode -> raw Bitmap -> re-encode as WebP
    private fun rasterToWebP(raw: ByteArray): Pair<ByteArray, String>? {
        val bitmap = BitmapFactory.decodeByteArray(raw, 0, raw.size) ?: run {
            Log.e(TAG, "rasterToWebP: BitmapFactory returned null")
            return null
        }

        val out = ByteArrayOutputStream()
        val format = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) Bitmap.CompressFormat.WEBP_LOSSY
        else Bitmap.CompressFormat.WEBP

        bitmap.compress(format, MediaProtocol.WEBP_QUALITY, out)
        return Pair(out.toByteArray(), "image/webp")
    }

    /**
     * GIF block layout (GIF89a spec):
     *   [Header 6B][Logical Screen Descriptor 7B][Global Color Table?]
     *   ( [Extension Introducer 0x21][Label][Sub-blocks…]
     *   | [Image Descriptor 0x2C][…][Local Color Table?][Image Data]
     *   | [Trailer 0x3B] ) *
     *
     * Extension labels:
     *   0xF9  Graphic Control   — keep  (frame timing / disposal)
     *   0xFF  Application       — keep ONLY "NETSCAPE2.0" (loop count)
     *                           — drop everything else (XMP, ICCRGBG1012, …)
     *   0xFE  Comment           — drop  (free-text, can contain author/GPS/tool)
     *   0x01  Plain Text        — drop  (rarely used, can embed text metadata)
     */
    private fun stripGifMetadata(raw: ByteArray): ByteArray? {
        if (raw.size < 13) return null
        val header = String(raw, 0, 6)
        if (header != "GIF87a" && header != "GIF89a") {
            Log.e(TAG, "stripGifMetadata: not a GIF")
            return null
        }

        val out = ByteArrayOutputStream(raw.size)
        var pos = 0

        // 1. Header (6 bytes)
        out.write(raw, pos, 6); pos += 6

        // 2. Logical Screen Descriptor (7 bytes)
        if (pos + 7 > raw.size) return null
        val flags = raw[pos + 4].toInt() and 0xFF
        val hasGCT = (flags and 0x80) != 0
        val gctSize = if (hasGCT) 3 * (1 shl ((flags and 0x07) + 1)) else 0
        out.write(raw, pos, 7); pos += 7

        // 3. Global Color Table
        if (hasGCT) {
            if (pos + gctSize > raw.size) return null
            out.write(raw, pos, gctSize); pos += gctSize
        }

        // 4. Blocks
        while (pos < raw.size) {
            val blockByte = raw[pos].toInt() and 0xFF

            when (blockByte) {
                // Trailer
                0x3B -> {
                    out.write(blockByte)
                    break
                }
                // Image Descriptor
                0x2C -> {
                    pos = copyImageDescriptorBlock(raw, pos, out) ?: return null
                }
                // Extension Introducer
                0x21 -> {
                    if (pos + 1 >= raw.size) return null
                    val label = raw[pos + 1].toInt() and 0xFF
                    pos = when (label) {
                        0xF9 -> copyExtensionBlock(raw, pos, out)   // Graphic Control
                        0xFF -> copyAppNetscapeExt(raw, pos, out)   // App extension
                        else -> skipExtensionBlock(raw, pos)        // Comment
                    } ?: return null
                }
                else -> {
                    Log.w(TAG, "stripGifMetadata: unexpected block type 0x${blockByte.toString(16)} at $pos")
                    pos++
                }
            }
        }

        val result = out.toByteArray()
        val saved = raw.size - result.size
        if (saved > 0) Log.d(TAG, "GIF: stripped $saved bytes of metadata")
        return result
    }

    private fun copyExtensionBlock(raw: ByteArray, start: Int, out: ByteArrayOutputStream): Int? {
        var pos = start
        if (pos + 2 > raw.size) return null
        out.write(raw[pos].toInt())     // 0x21
        out.write(raw[pos + 1].toInt()) // label
        pos += 2
        pos = copySubBlocks(raw, pos, out) ?: return null
        return pos
    }

    // Application extension: "NETSCAPE2.0"
    private fun copyAppNetscapeExt(raw: ByteArray, start: Int, out: ByteArrayOutputStream): Int? {
        var pos = start
        // Minimum: 0x21 0xFF 0x0B
        if (pos + 14 > raw.size) return skipExtensionBlock(raw, pos)
        // Block size: 0x0B (11)
        val blockSize = raw[pos + 2].toInt() and 0xFF
        if (blockSize == 0x0B) {
            val appId = String(raw, pos + 3, 11, Charsets.US_ASCII)
            if (appId == "NETSCAPE2.0" || appId == "ANIMEXTS1.0") {
                return copyExtensionBlock(raw, pos, out)
            }
        }
        // Not NetScape
        return skipExtensionBlock(raw, pos)
    }

    // Copy sub-block chain
    private fun copySubBlocks(raw: ByteArray, start: Int, out: ByteArrayOutputStream): Int? {
        var pos = start
        while (pos < raw.size) {
            val size = raw[pos].toInt() and 0xFF
            if (pos + 1 + size > raw.size) return null
            out.write(size)
            if (size == 0) { pos++; break }
            out.write(raw, pos + 1, size)
            pos += 1 + size
        }
        return pos
    }

    private fun copyImageDescriptorBlock(raw: ByteArray, start: Int, out: ByteArrayOutputStream): Int? {
        var pos = start
        // Image Descriptor: 10 bytes (0x2C + 9)
        if (pos + 10 > raw.size) return null
        val descFlags = raw[pos+ 9].toInt() and 0xFF
        val hasLCT = (descFlags and 0x80) != 0
        val lctSize = if (hasLCT) 3 * (1 shl ((descFlags and 0x07) + 1)) else 0

        out.write(raw, pos, 10); pos += 10

        // Local Color Table
        if (hasLCT) {
            if (pos + lctSize > raw.size) return null
            out.write(raw, pos, lctSize); pos += lctSize
        }

        // LZW minimum code size (1 byte)
        if (pos >= raw.size) return null
        out.write(raw[pos].toInt()); pos++

        // Image data sub-blocks
        pos = copySubBlocks(raw, pos, out) ?: return null
        return pos
    }

    private fun skipExtensionBlock(raw: ByteArray, start: Int): Int? {
        var pos = start + 2     // skip 0x21 + label
        while (pos < raw.size) {
            val size = raw[pos].toInt() and 0xFF
            pos += 1 + size
            if (size == 0) break
        }
        return pos
    }
}