package com.example.anonymous

import android.Manifest
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import com.example.anonymous.controller.Controller
import com.example.anonymous.ui.theme.AnonymousTheme
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberPermissionState

class LoginActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.setFlags(
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        )
        setContent {
            AnonymousTheme {
                QRScannerScreen()
            }
        }
    }
}

@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun QRScannerScreen() {
    val context = LocalContext.current
    val identityFileName = "qr_identity.png"
    val cameraPermissionState = rememberPermissionState(Manifest.permission.CAMERA)
    var identityBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var selectedBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var decodedText by remember { mutableStateOf<String?>(null) }
    var showCameraScanner by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        identityBitmap = Controller.checkIdentityExists(context, identityFileName)
    }

    val galleryLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let {
            selectedBitmap = Controller.uriToBitmap(context, it)
            val result = selectedBitmap?.let { bmp -> Controller.decodeQRCodeFromBitmap(bmp) }
            decodedText = result ?: "QR code not detected. Please try a different image."
            showCameraScanner = false
        }
    }

    Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(16.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            if (identityBitmap != null) {
                Text("Already Logged in!", style = MaterialTheme.typography.titleLarge)
                Spacer(modifier = Modifier.height(16.dp))
                Image(
                    bitmap = identityBitmap!!.asImageBitmap(),
                    contentDescription = "Already Logged in!",
                    modifier = Modifier.size(256.dp)
                )
                Spacer(modifier = Modifier.height(16.dp))
                FloatingActionButton(
                    onClick = {
                        val intent = Intent(context, MainActivity::class.java)
                        context.startActivity(intent)
                    }
                ) {
                    Icon(imageVector = Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                }
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = "Your anonymous identity already exists.\nKeep your QR code safe!",
                    fontSize = 10.sp,
                    modifier = Modifier.padding(20.dp),
                    textAlign = TextAlign.Center
                )
            } else {
                Button(onClick = { galleryLauncher.launch("image/*") }) {
                    Text("Select QR Code from Gallery")
                }
                Spacer(modifier = Modifier.height(8.dp))
                Button(onClick = {
                    if (cameraPermissionState.status.isGranted) {
                        selectedBitmap = null
                        decodedText = null
                        showCameraScanner = true
                    } else {
                        cameraPermissionState.launchPermissionRequest()
                    }
                }) {
                    Text("Scan QR Code with Camera")
                }

                if (showCameraScanner && cameraPermissionState.status.isGranted) {
                    RealTimeQRScanner(
                        onScanned = { scanned ->
                            decodedText = scanned
                            showCameraScanner = false
                        },
                        onError = { error ->
                            decodedText = error
                        }
                    )
                }

                if (!showCameraScanner) {
                    selectedBitmap?.let { bmp ->
                        Spacer(modifier = Modifier.height(16.dp))
                        Image(bitmap = bmp.asImageBitmap(), contentDescription = "Selected QR Code Image")
                    }
                }
                decodedText?.let { text ->
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(text = text)
                }
            }
        }
    }
}

@Composable
fun RealTimeQRScanner(onScanned: (String) -> Unit, onError: (String) -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val previewView = remember { PreviewView(context) }

    AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())

    LaunchedEffect(Unit) {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()
            val preview = Preview.Builder().build().apply {
                setSurfaceProvider(previewView.surfaceProvider)
            }
            val imageAnalysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()

            imageAnalysis.setAnalyzer(ContextCompat.getMainExecutor(context)) { imageProxy ->
                val bitmap = Controller.imageProxyToBitmap(imageProxy)
                if (bitmap != null) {
                    val result = Controller.decodeQRCodeFromBitmap(bitmap)
                    if (result != null) {
                        onScanned(result)
                        cameraProvider.unbindAll()
                    }
                }
                imageProxy.close()
            }

            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(lifecycleOwner, cameraSelector, preview, imageAnalysis)
            } catch (exc: Exception) {
                onError("Camera initialization failed")
            }
        }, ContextCompat.getMainExecutor(context))
    }
}