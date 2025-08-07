package com.example.anonymous

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.*
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.example.anonymous.controller.Controller
import com.example.anonymous.model.Contact
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
    val contactRepository = remember { ContactRepository(context) }

    var hasPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                    PackageManager.PERMISSION_GRANTED
        )
    }

    var scannedUuid by remember { mutableStateOf<String?>(null) }
    var contactName by remember { mutableStateOf("") }

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

    if (hasPermission) {
        if (scannedUuid == null) {
            AlertDialog(
                onDismissRequest = onDismiss,
                title = { Text("Scan QR Code") },
                text = {
                    Box(modifier = Modifier.height(300.dp)) {
                        CameraQrScanner(
                            context = context,
                            lifecycleOwner = lifecycleOwner,
                            onQrDecoded = { uuid ->
                                scannedUuid = uuid
                            },
                            onError = { onDismiss() }
                        )
                    }
                },
                confirmButton = {
                    TextButton(onClick = onDismiss) {
                        Text("Cancel")
                    }
                }
            )
        } else {
            AlertDialog(
                onDismissRequest = {
                    scannedUuid = null
                    contactName = ""
                },
                title = { Text("Add Contact") },
                text = {
                    Column {
                        Text("Scanned ID: ${scannedUuid}")
                        Spacer(modifier = Modifier.height(16.dp))
                        TextField(
                            value = contactName,
                            onValueChange = { contactName = it },
                            label = { Text("Contact Name") }
                        )
                    }
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            scannedUuid?.let { uuid ->
                                val newContact = Contact(
                                    uuid = uuid,
                                    name = contactName,
                                    publicKey = "" // You'll need to fetch this from your API
                                )
                                coroutineScope.launch {
                                    contactRepository.addContact(newContact)
                                    onContactAdded(newContact)
                                }
                                onDismiss()
                            }
                        },
                        enabled = contactName.isNotBlank()
                    ) {
                        Text("Add")
                    }
                },
                dismissButton = {
                    TextButton(
                        onClick = {
                            scannedUuid = null
                            contactName = ""
                        }
                    ) {
                        Text("Cancel")
                    }
                }
            )
        }
    }
}

@Composable
private fun CameraQrScanner(
    context: Context,
    lifecycleOwner: androidx.lifecycle.LifecycleOwner,
    onQrDecoded: (String) -> Unit,
    onError: () -> Unit
) {
    // Compose wrapper for PreviewView
    val previewView = remember { PreviewView(context) }

    // Launch camera setup once
    LaunchedEffect(Unit) {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()
            bindAnalysisUseCase(cameraProvider, lifecycleOwner, previewView, onQrDecoded)
        }, ContextCompat.getMainExecutor(context))
    }

    // Show the camera preview in Compose
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
    // 1) Unbind any previous use cases
    cameraProvider.unbindAll()

    // 2) Preview
    val preview = Preview.Builder().build().also {
        it.setSurfaceProvider(previewView.surfaceProvider)
    }

    // 3) ImageAnalysis: backpressure = latest only
    val analyzer = ImageAnalysis.Builder()
        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
        .build()
        .also { analysis ->
            analysis.setAnalyzer(ContextCompat.getMainExecutor(previewView.context)) { imageProxy ->
                try {
                    // Convert frame to Bitmap
                    val bitmap = Controller.imageProxyToBitmap(imageProxy)
                    if (bitmap != null) {
                        // Decode QR
                        val text = Controller.decodeQRCodeFromBitmap(bitmap)
                        if (text != null) {
                            onQrDecoded(text)
                            // once decoded, you may want to unbind to stop camera
                            cameraProvider.unbindAll()
                        }
                    }
                } catch (e: Exception) {
                    // swallow or log
                } finally {
                    imageProxy.close()
                }
            }
        }

    // 4) Bind to lifecycle
    cameraProvider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analyzer)
}