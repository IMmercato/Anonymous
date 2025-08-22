package com.example.anonymous.controller

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import com.example.anonymous.utils.JwtUtils
import com.google.zxing.BinaryBitmap
import com.google.zxing.EncodeHintType
import com.google.zxing.NotFoundException
import com.google.zxing.ReaderException
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.RGBLuminanceSource
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream

object Controller {
    private const val TAG = "Controller"

    fun checkIdentityExists(context: Context, identityFileName: String): Bitmap? {
        val identityFile = File(context.filesDir, identityFileName)
        return if (identityFile.exists()) {
            Log.d(TAG, "Identity exists.")
            BitmapFactory.decodeFile(identityFile.absolutePath)
        } else {
            Log.d(TAG, "No identity found.")
            null
        }
    }

    // Check if the user has a valid existing QR identity
    fun checkValidIdentityExists(context: Context, identityFileName: String): Bitmap? {
        val identityFile = File(context.filesDir, identityFileName)
        if (!identityFile.exists()) {
            Log.d(TAG, "No identity file found.")
            return null
        }

        val bitmap = BitmapFactory.decodeFile(identityFile.absolutePath)
        if (bitmap == null) {
            Log.d(TAG, "Failed to decode identity bitmap.")
            return null
        }

        // Extract JWT from QR code and validate it
        val jwt = decodeQRCodeFromBitmap(bitmap)
        if (jwt == null || !JwtUtils.isJwtValid(jwt)) {
            Log.d(TAG, "QR code contains invalid or expired JWT. Cleaning up...")
            cleanupInvalidIdentity(context, identityFileName)
            return null
        }

        Log.d(TAG, "Valid identity exists.")
        return bitmap
    }

    // Cleanup invalid identity files
    private fun cleanupInvalidIdentity(context: Context, identityFileName: String) {
        try {
            // Delete internal storage file
            val identityFile = File(context.filesDir, identityFileName)
            if (identityFile.exists()) {
                identityFile.delete()
                Log.d(TAG, "Deleted invalid identity file from internal storage.")
            }

            // Clear stored preferences
            val prefs = context.getSharedPreferences("anonymous_prefs", Context.MODE_PRIVATE)
            prefs.edit().remove("key_alias").remove("registration_jwt").apply()
            Log.d(TAG, "Cleared invalid identity from preferences.")

            // TODO: Optionally notify server to cleanup user data
            // This would require additional API call

        } catch (e: Exception) {
            Log.e(TAG, "Error during identity cleanup", e)
        }
    }

    // Generate a QR code with validation
    fun generateQRCode(content: String, width: Int = 512, height: Int = 512): Bitmap? {
        if (content.length > 2000) {
            Log.e(TAG, "QR code content too long: ${content.length} characters")
            return null
        }

        return try {
            val hints = hashMapOf<EncodeHintType, Int>().apply {
                put(EncodeHintType.MARGIN, 1)
            }
            val bitMatrix = QRCodeWriter().encode(content, com.google.zxing.BarcodeFormat.QR_CODE, width, height, hints)
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.RGB_565)
            for (x in 0 until width) {
                for (y in 0 until height) {
                    bitmap.setPixel(x, y, if (bitMatrix[x, y]) android.graphics.Color.BLACK else android.graphics.Color.WHITE)
                }
            }
            bitmap
        } catch (e: Exception) {
            Log.e(TAG, "QR code generation failed.", e)
            null
        }
    }

    // Save QR code to internal storage with validation
    fun saveBitmapToInternalStorage(context: Context, bitmap: Bitmap, fileName: String): File? {
        return try {
            // Validate the QR content before saving
            val jwt = decodeQRCodeFromBitmap(bitmap)
            if (jwt == null || !JwtUtils.isJwtValid(jwt)) {
                Log.e(TAG, "Cannot save invalid JWT QR code")
                return null
            }

            val imageFile = File(context.filesDir, fileName)
            FileOutputStream(imageFile).use { fos ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, fos)
            }
            Log.d(TAG, "Saved valid QR code to internal storage.")
            imageFile
        } catch (e: Exception) {
            Log.e(TAG, "Error saving QR code internally.", e)
            null
        }
    }

    // Save QR code to the gallery with validation
    fun saveBitmapToGallery(context: Context, bitmap: Bitmap, fileName: String): Uri? {
        return try {
            // Validate the QR content before saving
            val jwt = decodeQRCodeFromBitmap(bitmap)
            if (jwt == null || !JwtUtils.isJwtValid(jwt)) {
                Log.e(TAG, "Cannot save invalid JWT QR code to gallery")
                return null
            }

            val resolver = context.contentResolver
            val contentValues = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                put(MediaStore.MediaColumns.MIME_TYPE, "image/png")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + File.separator + "Anonymous")
                }
            }
            val imageUri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
            imageUri?.let { uri ->
                resolver.openOutputStream(uri)?.use { os ->
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, os)
                }
            }
            Log.d(TAG, "Saved valid QR code to gallery.")
            imageUri
        } catch (e: Exception) {
            Log.e(TAG, "Error saving QR code to gallery.", e)
            null
        }
    }

    // Decode QR code from bitmap with enhanced validation
    fun decodeQRCodeFromBitmap(bitmap: Bitmap): String? {
        return try {
            val width = bitmap.width
            val height = bitmap.height
            val pixels = IntArray(width * height)
            bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

            val source = RGBLuminanceSource(width, height, pixels)
            val binaryBitmap = BinaryBitmap(HybridBinarizer(source))
            val qrReader = QRCodeReader()
            val result = qrReader.decode(binaryBitmap)

            val decodedText = result.text
            Log.d(TAG, "Decoded QR code: ${decodedText.take(50)}...")

            // Basic JWT format validation
            if (decodedText.split('.').size != 3) {
                Log.e(TAG, "Decoded text is not a valid JWT format")
                return null
            }

            decodedText
        } catch (e: NotFoundException) {
            Log.e(TAG, "QR Code not found in image")
            null
        } catch (e: ReaderException) {
            Log.e(TAG, "QR code decoding failed.", e)
            null
        } catch (e: Exception) {
            Log.e(TAG, "Unexpected error during QR decoding", e)
            null
        }
    }

    // Convert ImageProxy to Bitmap (for CameraX real-time scanning)
    fun imageProxyToBitmap(imageProxy: androidx.camera.core.ImageProxy): Bitmap? {
        try {
            val yBuffer = imageProxy.planes[0].buffer
            val uBuffer = imageProxy.planes[1].buffer
            val vBuffer = imageProxy.planes[2].buffer

            val ySize = yBuffer.remaining()
            val uSize = uBuffer.remaining()
            val vSize = vBuffer.remaining()

            val nv21 = ByteArray(ySize + uSize + vSize)
            yBuffer.get(nv21, 0, ySize)
            // V and U are swapped
            vBuffer.get(nv21, ySize, vSize)
            uBuffer.get(nv21, ySize + vSize, uSize)

            val yuvImage = android.graphics.YuvImage(nv21, ImageFormat.NV21, imageProxy.width, imageProxy.height, null)
            val out = ByteArrayOutputStream()
            yuvImage.compressToJpeg(Rect(0, 0, imageProxy.width, imageProxy.height), 100, out)
            val imageBytes = out.toByteArray()

            return BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
        } catch (e: Exception) {
            Log.e(TAG, "Error converting image proxy to bitmap", e)
            return null
        }
    }

    // Gallery selection: convert a given URI to Bitmap
    fun uriToBitmap(context: Context, uri: Uri): Bitmap? {
        return try {
            MediaStore.Images.Media.getBitmap(context.contentResolver, uri)
        } catch (e: Exception) {
            Log.e(TAG, "Error converting URI to bitmap", e)
            null
        }
    }

    // Cleanup all identity data (for logout or re-registration)
    fun cleanupAllIdentityData(context: Context, identityFileName: String) {
        try {
            // Delete internal storage file
            val identityFile = File(context.filesDir, identityFileName)
            if (identityFile.exists()) {
                identityFile.delete()
                Log.d(TAG, "Deleted identity file from internal storage.")
            }

            // Clear all stored preferences
            val prefs = context.getSharedPreferences("anonymous_prefs", Context.MODE_PRIVATE)
            prefs.edit().clear().apply()
            Log.d(TAG, "Cleared all identity preferences.")

            // TODO: Optionally delete from gallery (more complex due to content provider permissions)

        } catch (e: Exception) {
            Log.e(TAG, "Error during complete identity cleanup", e)
        }
    }
}