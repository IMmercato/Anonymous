package com.example.anonymous

import android.Manifest
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.util.Log
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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import com.example.anonymous.controller.Controller
import com.example.anonymous.network.GraphQLRequest
import com.example.anonymous.network.GraphQLService
import com.example.anonymous.network.MessagePollingService
import com.example.anonymous.network.QueryBuilder
import com.example.anonymous.ui.theme.AnonymousTheme
import com.example.anonymous.utils.CryptoUtils
import com.example.anonymous.utils.JwtUtils
import com.example.anonymous.utils.PrefsHelper
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.PermissionState
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberPermissionState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
                LoginScreen()
            }
        }
    }
}

@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun LoginScreen() {
    val context = LocalContext.current
    val cameraPermissionState = rememberPermissionState(Manifest.permission.CAMERA)
    var selectedBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var decodedText by remember { mutableStateOf<String?>(null) }
    var showCameraScanner by remember { mutableStateOf(false) }
    var loginState by remember { mutableStateOf<LoginState>(LoginState.Idle) }
    val coroutineScope = rememberCoroutineScope()

    // Auto-detect and verify existing identity on start
    LaunchedEffect(Unit) {
        val existingJwt = PrefsHelper.getRegistrationJwt(context)
        if (existingJwt != null && JwtUtils.isJwtValid(existingJwt)) {
            Log.d("LoginActivity", "Auto-detected valid JWT, starting verification...")
            startLoginProcess(existingJwt, context, coroutineScope) { newState ->
                loginState = newState
            }
        } else if (existingJwt != null) {
            Log.d("LoginActivity", "Auto-detected expired JWT, cleaning up...")
            Controller.cleanupAllIdentityData(context, "qr_identity.png")
        }
    }

    val galleryLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let {
            selectedBitmap = Controller.uriToBitmap(context, it)
            val result = selectedBitmap?.let { bmp -> Controller.decodeQRCodeFromBitmap(bmp) }
            decodedText = result ?: "QR code not detected. Please try a different image."
            showCameraScanner = false

            // Start login process if JWT is detected
            if (result != null && JwtUtils.isJwtValid(result)) {
                startLoginProcess(result, context, coroutineScope) { newState ->
                    loginState = newState
                }
            } else if (result != null) {
                decodedText = "QR code contains expired JWT. Please register again."
            }
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
            when (loginState) {
                is LoginState.Idle -> {
                    LoginScannerUI(
                        cameraPermissionState = cameraPermissionState,
                        selectedBitmap = selectedBitmap,
                        decodedText = decodedText,
                        showCameraScanner = showCameraScanner,
                        galleryLauncher = galleryLauncher,
                        onCameraScanStart = {
                            selectedBitmap = null
                            decodedText = null
                            showCameraScanner = true
                        },
                        onScanned = { scannedJwt ->
                            if (JwtUtils.isJwtValid(scannedJwt)) {
                                startLoginProcess(scannedJwt, context, coroutineScope) { newState ->
                                    loginState = newState
                                }
                            } else {
                                decodedText = "Invalid or expired JWT. Please register again."
                            }
                        },
                        onError = { error ->
                            decodedText = error
                        }
                    )
                }
                is LoginState.Loading -> {
                    LoadingUI((loginState as LoginState.Loading).message)
                }
                is LoginState.Success -> {
                    val successState = loginState as LoginState.Success
                    SuccessUI(successState.sessionToken, context)
                }
                is LoginState.Error -> {
                    val errorState = loginState as LoginState.Error
                    ErrorUI(errorState.message, errorState.isRecoverable) {
                        loginState = LoginState.Idle
                        decodedText = null
                        selectedBitmap = null
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun LoginScannerUI(
    cameraPermissionState: PermissionState,
    selectedBitmap: Bitmap?,
    decodedText: String?,
    showCameraScanner: Boolean,
    galleryLauncher: androidx.activity.compose.ManagedActivityResultLauncher<String, Uri?>,
    onCameraScanStart: () -> Unit,
    onScanned: (String) -> Unit,
    onError: (String) -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text("Verify Your Identity", style = MaterialTheme.typography.titleLarge)
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "Scan your QR code to verify and extend your session",
            fontSize = 14.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 20.dp)
        )
        Spacer(modifier = Modifier.height(24.dp))

        Button(onClick = { galleryLauncher.launch("image/*") }) {
            Text("Select QR Code from Gallery")
        }
        Spacer(modifier = Modifier.height(8.dp))
        Button(onClick = {
            if (cameraPermissionState.status.isGranted) {
                onCameraScanStart()
            } else {
                cameraPermissionState.launchPermissionRequest()
            }
        }) {
            Text("Scan QR Code with Camera")
        }

        if (showCameraScanner && cameraPermissionState.status.isGranted) {
            Spacer(modifier = Modifier.height(16.dp))
            RealTimeQRScanner(
                onScanned = onScanned,
                onError = onError
            )
        }

        if (!showCameraScanner) {
            selectedBitmap?.let { bmp ->
                Spacer(modifier = Modifier.height(16.dp))
                Image(
                    bitmap = bmp.asImageBitmap(),
                    contentDescription = "Selected QR Code Image",
                    modifier = Modifier.size(200.dp)
                )
            }
        }

        decodedText?.let { text ->
            Spacer(modifier = Modifier.height(16.dp))
            if (text.contains("error", ignoreCase = true) || text.contains("invalid", ignoreCase = true)) {
                Icon(
                    imageVector = Icons.Default.Warning,
                    contentDescription = "Error",
                    tint = Color.Red,
                    modifier = Modifier.size(24.dp)
                )
            }
            Text(
                text = text,
                color = if (text.contains("error", ignoreCase = true)) Color.Red else Color.Black,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 16.dp)
            )
        }

        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = "Your QR code contains a temporary token that will be verified and extended",
            fontSize = 12.sp,
            color = Color.Gray,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 20.dp)
        )
    }
}

@Composable
fun LoadingUI(message: String) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        CircularProgressIndicator()
        Spacer(modifier = Modifier.height(16.dp))
        Text(message, textAlign = TextAlign.Center)
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "Verifying your identity and extending session...",
            fontSize = 12.sp,
            color = Color.Gray
        )
    }
}

@Composable
fun SuccessUI(sessionToken: String, context: android.content.Context) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = Icons.AutoMirrored.Filled.ArrowForward,
            contentDescription = "Success",
            tint = Color.Green,
            modifier = Modifier.size(48.dp)
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text("Verification Successful!", style = MaterialTheme.typography.titleLarge)
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "Your session has been extended",
            fontSize = 14.sp,
            color = Color.Green
        )
        Spacer(modifier = Modifier.height(24.dp))
        Button(
            onClick = {
                MessagePollingService.startService(this)
                val intent = Intent(context, MainActivity::class.java)
                context.startActivity(intent)
                // Finish this activity to prevent going back to login
                if (context is android.app.Activity) {
                    context.finish()
                }
            }
        ) {
            Text("Continue to App")
        }
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = "You can now use the app with your extended session",
            fontSize = 12.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 20.dp)
        )
    }
}

@Composable
fun ErrorUI(message: String, isRecoverable: Boolean, onRetry: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = Icons.Default.Warning,
            contentDescription = "Error",
            tint = Color.Red,
            modifier = Modifier.size(48.dp)
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text("Verification Failed", style = MaterialTheme.typography.titleLarge)
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = message,
            color = Color.Red,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 16.dp)
        )
        Spacer(modifier = Modifier.height(24.dp))
        if (isRecoverable) {
            Button(onClick = onRetry) {
                Text("Try Again")
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "You can also try scanning your QR code again",
                fontSize = 12.sp,
                color = Color.Gray
            )
        } else {
            Text(
                text = "Please register again to create a new identity",
                fontSize = 12.sp,
                color = Color.Gray
            )
        }
    }
}

@Composable
fun RealTimeQRScanner(onScanned: (String) -> Unit, onError: (String) -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val previewView = remember { PreviewView(context) }

    Box(modifier = Modifier
        .size(300.dp)
        .padding(8.dp)) {
        AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
    }

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
                onError("Camera initialization failed: ${exc.message}")
            }
        }, ContextCompat.getMainExecutor(context))
    }
}

private fun startLoginProcess(
    jwt: String,
    context: android.content.Context,
    coroutineScope: kotlinx.coroutines.CoroutineScope,
    onStateChange: (LoginState) -> Unit
) {
    onStateChange(LoginState.Loading("Verifying JWT..."))

    coroutineScope.launch {
        try {
            // Step 1: Request nonce from server
            onStateChange(LoginState.Loading("Requesting verification challenge..."))
            val nonce = requestNonceFromServer(context, jwt)

            // Step 2: Extract UUID from JWT
            val uuid = JwtUtils.extractUuidFromJwt(jwt)
            if (uuid == null) {
                throw Exception("Invalid JWT: No UUID found")
            }

            // Step 3: Sign the nonce with private key
            onStateChange(LoginState.Loading("Signing verification challenge..."))
            val keyAlias = PrefsHelper.getKeyAlias(context)
            if (keyAlias == null) {
                throw Exception("No private key found. Please register again.")
            }

            val signature = CryptoUtils.signDataWithAlias(nonce, keyAlias)
            if (signature == null) {
                throw Exception("Failed to sign verification challenge")
            }

            // Step 4: Complete login with signature to get extended session
            onStateChange(LoginState.Loading("Completing verification..."))
            val sessionToken = completeLogin(context ,uuid, signature)

            // Step 5: Save extended session
            PrefsHelper.saveSessionToken(context, sessionToken)
            PrefsHelper.setLoggedIn(context, true)
            PrefsHelper.saveUserUuid(context, uuid)

            onStateChange(LoginState.Success(sessionToken))

        } catch (e: Exception) {
            Log.e("LoginActivity", "Verification failed", e)
            val isRecoverable = !e.message?.contains("register again", ignoreCase = true)!! ?: true
            onStateChange(LoginState.Error(e.message ?: "Unknown error during verification", isRecoverable))
        }
    }
}

private suspend fun requestNonceFromServer(context: Context, jwt: String): String {
    return withContext(Dispatchers.IO) {
        val service = GraphQLService.create(context)
        val request = GraphQLRequest(query = QueryBuilder.loginWithJwt(jwt))
        val response = service.loginWithJwt(request)

        if (response.isSuccessful) {
            val noncePayload = response.body()?.data?.loginWithJwt
            if (noncePayload != null && noncePayload.nonce.isNotEmpty()) {
                return@withContext noncePayload.nonce
            } else {
                throw Exception("Server returned invalid verification challenge")
            }
        } else {
            val errorBody = response.errorBody()?.string()
            throw Exception("Verification challenge request failed: ${errorBody ?: "Unknown error"}")
        }
    }
}

private suspend fun completeLogin(context: Context, uuid: String, signature: String): String {
    return withContext(Dispatchers.IO) {
        val service = GraphQLService.create(context)
        val request = GraphQLRequest(query = QueryBuilder.completeLogin(uuid, signature))
        val response = service.completeLogin(request)

        if (response.isSuccessful) {
            val sessionPayload = response.body()?.data?.completeLogin
            if (sessionPayload != null && sessionPayload.token.isNotEmpty()) {
                return@withContext sessionPayload.token
            } else {
                throw Exception("Server returned invalid session response")
            }
        } else {
            val errorBody = response.errorBody()?.string()
            throw Exception("Verification completion failed: ${errorBody ?: "Unknown error"}")
        }
    }
}

sealed class LoginState {
    object Idle : LoginState()
    data class Loading(val message: String) : LoginState()
    data class Success(val sessionToken: String) : LoginState()
    data class Error(val message: String, val isRecoverable: Boolean) : LoginState()
}