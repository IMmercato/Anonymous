package com.example.anonymous

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.example.anonymous.controller.Controller
import com.example.anonymous.crypto.CryptoManager
import com.example.anonymous.i2p.I2pQRUtils
import com.example.anonymous.i2p.ParsedI2PIdentity
import com.example.anonymous.network.model.Contact
import com.example.anonymous.repository.ContactRepository
import kotlinx.coroutines.launch

@Composable
fun NewContactDialog(
    onDismiss: () -> Unit,
    onContactAdded: (Contact) -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val coroutineScope = rememberCoroutineScope()
    val contactRepository = remember { ContactRepository.getInstance(context) }

    var hasPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                    PackageManager.PERMISSION_GRANTED
        )
    }

    var scannedIdentity by remember { mutableStateOf<ParsedI2PIdentity?>(null) }
    var contactName by remember { mutableStateOf("") }
    var duplicateError by remember { mutableStateOf(false) }
    var scanError by remember { mutableStateOf<String?>(null) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasPermission = granted
        if (!granted) {
            onDismiss()
        }
    }

    LaunchedEffect(Unit) {
        if (!hasPermission) {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    if (!hasPermission) return

    if (scannedIdentity == null) {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("Scan QR Code") },
            text = {
                Column {
                    scanError?.let { error ->
                        Text(
                            text = error,
                            color = Color.Red,
                            modifier = Modifier.padding(bottom = 8.dp)
                        )
                    }
                    Box(modifier = Modifier.height(300.dp)) {
                        CameraQrScanner(
                            context = context,
                            lifecycleOwner = lifecycleOwner,
                            onQrDecoded = { text ->
                                val parsed = I2pQRUtils.parseQRContent(text)
                                when {
                                    parsed == null -> {
                                        scanError = "Invalid QR code - not an Anonymous identity."
                                    }
                                    else -> {
                                        contactName = "Contact ${parsed.b32Address.take(8)}"
                                        scannedIdentity = parsed
                                        scanError = null
                                    }
                                }
                            },
                            onError = {
                                scanError = "Camera error occurred"
                            }
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = onDismiss) {
                    Text("Cancel")
                }
            }
        )
        return
    }

    val identity = scannedIdentity!!

    AlertDialog(
        onDismissRequest = {
            scannedIdentity = null
            contactName = ""
            duplicateError = false
            scanError = null
        },
        title = { Text("Add Anonymous Contact") },
        text = {
            Column {
                Text(
                    text = "I2P address:",
                    fontSize = 12.sp,
                    color = Color.Gray
                )
                Text(
                    text = identity.b32Address,
                    fontSize = 12.sp
                )
                if (identity.i2pDestination != null) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Full destination included",
                        color = Color.Green,
                        fontSize = 11.sp
                    )
                }
                Spacer(modifier = Modifier.height(16.dp))
                TextField(
                    value = contactName,
                    onValueChange = { contactName = it },
                    label = { Text("Contact Name") },
                    singleLine = true
                )
                if (duplicateError) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("This contact is already in your address book.", color = Color.Red)
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = contactName.isNotBlank(),
                onClick = {
                    coroutineScope.launch {
                        val exists = contactRepository.contactExists(identity.b32Address)

                        val myB32 = contactRepository.getMyIdentity()?.b32Address
                        if (myB32.equals(identity.b32Address, ignoreCase = true)) {
                            scanError = "Cannot add yourself as a contact."
                            scannedIdentity = null
                            return@launch
                        }

                        if (exists) {
                            duplicateError = true
                            return@launch
                        }

                        val contact = Contact(
                            b32Address = identity.b32Address,
                            name = contactName.trim(),
                            publicKey = identity.ecPublicKey ?: identity.i2pDestination ?: "",
                            isVerified = false
                        )
                        contactRepository.addContact(contact)

                        val myIdentity = contactRepository.getMyIdentity()
                        if (myIdentity != null) {
                            val existing = CryptoManager.getContactKeyPair(context, myIdentity.b32Address)
                            if (existing == null) {
                                val myKeyPair = CryptoManager.generateECDHKeyPair()
                                CryptoManager.saveKeyPair(context, myIdentity.b32Address, myKeyPair)
                                Log.d("NewContactDialog", "Generated ECDH key pair for own identity")
                            }
                        }

                        onContactAdded(contact)
                        onDismiss()
                    }
                }
            ) {
                Text("Add Contact")
            }
        },
        dismissButton = {
            TextButton(
                onClick = {
                    scannedIdentity = null
                    contactName = ""
                    duplicateError = false
                    scanError = null
                }
            ) {
                Text("Scan Again")
            }
        }
    )
}

@Composable
private fun CameraQrScanner(
    context: Context,
    lifecycleOwner: androidx.lifecycle.LifecycleOwner,
    onQrDecoded: (String) -> Unit,
    onError: () -> Unit
) {
    val previewView = remember { PreviewView(context) }

    LaunchedEffect(Unit) {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        cameraProviderFuture.addListener({
            try {
                val cameraProvider = cameraProviderFuture.get()
                bindAnalysisUseCase(cameraProvider, lifecycleOwner, previewView, onQrDecoded)
            } catch (e: Exception) {
                onError()
            }
        }, ContextCompat.getMainExecutor(context))
    }

    AndroidView(
        factory = { previewView },
        modifier = Modifier.fillMaxSize()
    )
}

private fun bindAnalysisUseCase(
    cameraProvider: ProcessCameraProvider,
    lifecycleOwner: androidx.lifecycle.LifecycleOwner,
    previewView: PreviewView,
    onQrDecoded: (String) -> Unit
) {
    try {
        cameraProvider.unbindAll()

        val preview = Preview.Builder().build().also {
            it.setSurfaceProvider(previewView.surfaceProvider)
        }

        val analyzer = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()
            .also { analysis ->
                analysis.setAnalyzer(ContextCompat.getMainExecutor(previewView.context)) { imageProxy ->
                    try {
                        val bitmap = Controller.imageProxyToBitmap(imageProxy)
                        if (bitmap != null) {
                            val text = Controller.decodeQRCodeFromBitmap(bitmap)
                            if (text != null) {
                                onQrDecoded(text)
                                cameraProvider.unbindAll()
                            }
                        }
                    } catch (e: Exception) {
                        // Continue scanning on error
                    } finally {
                        imageProxy.close()
                    }
                }
            }

        cameraProvider.bindToLifecycle(
            lifecycleOwner,
            CameraSelector.DEFAULT_BACK_CAMERA,
            preview,
            analyzer
        )
    } catch (e: Exception) {
        // Handle camera binding errors
    }
}