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

    // Check if the user has an existing QR identity.
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

    // Generate a QR code.
    fun generateQRCode(content: String, width: Int = 512, height: Int = 512): Bitmap? {
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

    // Save QR code to internal storage.
    fun saveBitmapToInternalStorage(context: Context, bitmap: Bitmap, fileName: String): File? {
        return try {
            val imageFile = File(context.filesDir, fileName)
            FileOutputStream(imageFile).use { fos ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, fos)
            }
            Log.d(TAG, "Saved QR code to internal storage.")
            imageFile
        } catch (e: Exception) {
            Log.e(TAG, "Error saving QR code internally.", e)
            null
        }
    }

    // Save QR code to the gallery.
    fun saveBitmapToGallery(context: Context, bitmap: Bitmap, fileName: String): Uri? {
        return try {
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
            Log.d(TAG, "Saved QR code to gallery.")
            imageUri
        } catch (e: Exception) {
            Log.e(TAG, "Error saving QR code to gallery.", e)
            null
        }
    }

    // Decode QR code from bitmap.
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
            Log.d(TAG, "Decoded QR code successfully: ${result.text}")
            result.text
        } catch (e: NotFoundException) {
            Log.e(TAG, "QR Code not found")
            null
        } catch (e: ReaderException) {
            Log.e(TAG, "QR code decoding failed.", e)
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

    // Gallery selection: convert a given URI to Bitmap.
    fun uriToBitmap(context: Context, uri: Uri): Bitmap? {
        return try {
            MediaStore.Images.Media.getBitmap(context.contentResolver, uri)
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }
}