package com.example.anonymous

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.util.Base64
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.anonymous.controller.Controller
import com.example.anonymous.crypto.CryptoManager
import com.example.anonymous.i2p.I2pQRUtils
import com.example.anonymous.i2p.I2pdDaemon
import com.example.anonymous.i2p.SAMClient
import com.example.anonymous.repository.ContactRepository
import com.example.anonymous.ui.theme.AnonymousTheme
import com.example.anonymous.utils.PrefsHelper
import kotlinx.coroutines.*
import java.security.KeyStore

class RegistrationActivity : ComponentActivity() {

    companion object {
        private const val TAG = "RegistrationActivity"
        private const val IDENTITY_FILE_NAME = "qr_identity.png"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            AnonymousTheme {
                RegistrationScreen()
            }
        }
    }

    @Composable
    fun RegistrationScreen() {
        val context = LocalContext.current
        val scope = rememberCoroutineScope()

        val i2pdDaemon = remember { I2pdDaemon.getInstance(context) }
        val samClient = remember { SAMClient.getInstance() }
        val contactRepository = remember { ContactRepository.getInstance(context) }

        var identityBitmap by remember { mutableStateOf<Bitmap?>(null) }
        var myB32 by remember { mutableStateOf<String?>(null) }
        var isLoading by remember { mutableStateOf(false) }
        var statusText by remember { mutableStateOf("") }
        var errorMessage by remember { mutableStateOf<String?>(null) }

        // Check for existing identity
        LaunchedEffect(Unit) {
            Log.i(TAG, "Checking for existing identity...")
            val existingIdentity = contactRepository.getMyIdentity()
            if (existingIdentity != null) {
                Log.i(TAG, "Found existing identity: ${existingIdentity.b32Address}")
                myB32 = existingIdentity.b32Address
                val qrContent = I2pQRUtils.generateQRContent(
                    b32Address = existingIdentity.b32Address,
                    i2pDestination = existingIdentity.publicKey,
                    ecPublicKey = existingIdentity.privateKeyEncrypted.takeIf { it.isNotBlank() }
                )
                identityBitmap = withContext(Dispatchers.IO) {
                    Controller.generateQRCode(qrContent)
                }
                Log.i(TAG, "QR code generated from existing identity")
            } else {
                Log.i(TAG, "No existing identity found")
            }
        }

        // Setup daemon listener
        DisposableEffect(i2pdDaemon) {
            val listener = object : I2pdDaemon.DaemonStateListener {
                override fun onStateChanged(state: I2pdDaemon.DaemonState) {
                    Log.d(TAG, "Daemon state changed: $state")
                    statusText = when (state) {
                        I2pdDaemon.DaemonState.STARTING -> "Starting I2P..."
                        I2pdDaemon.DaemonState.BUILDING_TUNNELS -> "Building tunnels..."
                        I2pdDaemon.DaemonState.WAITING_FOR_NETWORK -> "Waiting for network..."
                        I2pdDaemon.DaemonState.READY -> "I2P Ready!"
                        else -> state.name
                    }
                }

                override fun onError(error: String) {
                    Log.e(TAG, "Daemon error: $error")
                    errorMessage = error
                    isLoading = false
                }
            }

            i2pdDaemon.addListener(listener)
            onDispose {
                Log.d(TAG, "Removing daemon listener")
                i2pdDaemon.removeListener(listener)
            }
        }

        // Create identity function
        suspend fun doCreateIdentity() {
            Log.i(TAG, ">>> doCreateIdentity() starting <<<")
            isLoading = true
            errorMessage = null
            statusText = "Initializing I2P network..."

            try {
                // Step 1: Start I2P daemon
                Log.d(TAG, "Step 1: Starting I2P daemon...")
                val started = withContext(Dispatchers.IO) {
                    i2pdDaemon.start()
                }
                Log.i(TAG, "i2pdDaemon.start() returned: $started")

                if (!started) {
                    throw IllegalStateException("Failed to start I2P daemon")
                }

                // Step 2: Wait for SAM to be ready
                statusText = "Waiting for I2P to be ready..."
                Log.d(TAG, "Step 2: Waiting for I2P ready...")
                val ready = withContext(Dispatchers.IO) {
                    i2pdDaemon.waitForReady(timeoutMs = 120000)
                }
                Log.i(TAG, "i2pdDaemon.waitForReady() returned: $ready")

                if (!ready) {
                    throw IllegalStateException("I2P failed to become ready")
                }

                // Step 3: Connect to SAM
                statusText = "Creating identity..."
                Log.d(TAG, "Step 3: Connecting to SAM...")
                val samConnected = withContext(Dispatchers.IO) {
                    samClient.connect()
                }
                Log.i(TAG, "samClient.connect() returned: $samConnected")

                if (!samConnected) {
                    throw IllegalStateException("Failed to connect to SAM")
                }

                // Step 4: Create session (this gives us the b32 address)
                Log.d(TAG, "Step 4: Creating SAM session...")
                val sessionResult = withContext(Dispatchers.IO) {
                    samClient.createStreamSession()
                }
                Log.i(TAG, "samClient.createStreamSession() returned: ${sessionResult.isSuccess}")

                if (sessionResult.isFailure) {
                    throw IllegalStateException("Failed to create session: ${sessionResult.exceptionOrNull()?.message}")
                }

                val session = sessionResult.getOrThrow()
                Log.i(TAG, "Session created: b32=${session.b32Address}")

                // Step 5: Generate encryption keys
                Log.d(TAG, "Step 5: Generating ECDH keys...")
                val ecdhKeyPair = withContext(Dispatchers.IO) {
                    CryptoManager.generateECDHKeyPair()
                }
                val ecPublicB64 = Base64.encodeToString(ecdhKeyPair.public.encoded, Base64.NO_WRAP)
                Log.i(TAG, "ECDH keys generated")

                // Step 6: Save identity
                Log.d(TAG, "Step 6: Saving identity...")
                withContext(Dispatchers.IO) {
                    CryptoManager.saveKeyPair(context, session.b32Address, ecdhKeyPair)
                    PrefsHelper.saveKeyAlias(context, session.b32Address)
                }

                val identity = ContactRepository.MyIdentity(
                    b32Address = session.b32Address,
                    publicKey = session.destination,
                    privateKeyEncrypted = ecPublicB64
                )
                contactRepository.saveMyIdentity(identity)
                Log.i(TAG, "Identity saved to repository")

                // Step 7: Generate QR code
                statusText = "Generating QR code..."
                Log.d(TAG, "Step 7: Generating QR code...")
                val qrContent = I2pQRUtils.generateQRContent(
                    b32Address = session.b32Address,
                    i2pDestination = session.destination,
                    ecPublicKey = ecPublicB64
                )

                val bitmap = withContext(Dispatchers.IO) {
                    Controller.generateQRCode(qrContent)
                } ?: throw IllegalStateException("QR generation failed")
                Log.i(TAG, "QR code generated")

                withContext(Dispatchers.IO) {
                    Controller.saveBitmapToInternalStorage(context, bitmap, IDENTITY_FILE_NAME)
                    Controller.saveBitmapToGallery(context, bitmap, IDENTITY_FILE_NAME)
                }
                Log.i(TAG, "QR code saved")

                identityBitmap = bitmap
                myB32 = session.b32Address
                isLoading = false

                Log.i(TAG, ">>> Identity creation COMPLETE <<<")
                Toast.makeText(context, "Identity created!", Toast.LENGTH_SHORT).show()

            } catch (e: Exception) {
                Log.e(TAG, "!!! Identity creation FAILED !!!", e)
                errorMessage = e.message ?: "Unknown error"
                isLoading = false
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
                Text("Welcome to Anonymous!", style = MaterialTheme.typography.titleLarge)
                Spacer(modifier = Modifier.height(16.dp))

                // ── Identity ready: just show QR and continue ──────────────
                if (identityBitmap != null) {
                    Log.d(TAG, "UI: Showing existing identity QR")
                    Image(
                        bitmap = identityBitmap!!.asImageBitmap(),
                        contentDescription = "Your Anonymous Identity QR Code",
                        modifier = Modifier.size(256.dp)
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    myB32?.let { b32 ->
                        Text(
                            text = b32,
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.Gray,
                            textAlign = TextAlign.Center
                        )
                    }
                    Spacer(modifier = Modifier.height(16.dp))

                    Button(
                        onClick = {
                            Log.i(TAG, "Continue button clicked, going to MainActivity")
                            context.startActivity(Intent(context, MainActivity::class.java))
                            finish()
                        }
                    ) {
                        Icon(Icons.Default.ArrowForward, contentDescription = "Continue")
                    }

                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "Your Anonymous identity is ready!\nShare this QR so others can reach you.",
                        fontSize = 12.sp,
                        textAlign = TextAlign.Center,
                        color = Color.Gray
                    )

                    // ── No identity yet: create new one ────────────────────────
                } else {
                    Log.d(TAG, "UI: No identity, showing creation UI")
                    when {
                        isLoading -> {
                            CircularProgressIndicator()
                            Spacer(modifier = Modifier.height(16.dp))
                            Text(
                                text = statusText,
                                style = MaterialTheme.typography.bodyMedium,
                                textAlign = TextAlign.Center
                            )
                        }
                        errorMessage != null -> {
                            Text(
                                text = "Error: $errorMessage",
                                color = Color.Red,
                                textAlign = TextAlign.Center
                            )
                        }
                        else -> {
                            Text(
                                text = "Create your Anonymous identity.\nYour keys never leave this device.",
                                style = MaterialTheme.typography.bodyMedium,
                                textAlign = TextAlign.Center
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(24.dp))

                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                            .padding(horizontal = 16.dp)
                            .alpha(if (isLoading) 0.6f else 1f)
                            .clickable(
                                enabled = !isLoading,
                                indication = null,
                                interactionSource = remember { MutableInteractionSource() },
                                role = Role.Button,
                                onClick = {
                                    Log.i(TAG, "Be Anonymous! button clicked")
                                    scope.launch {
                                        doCreateIdentity()
                                    }
                                }
                            ),
                        shape = MaterialTheme.shapes.medium,
                        color = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            if (isLoading) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(22.dp),
                                    strokeWidth = 2.5.dp,
                                    color = MaterialTheme.colorScheme.onPrimary
                                )
                            } else {
                                Text(
                                    text = if (errorMessage != null) "Retry" else "Be Anonymous!",
                                    style = MaterialTheme.typography.bodyLarge
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Your private key stays on this device — it is never shared.",
                        fontSize = 10.sp,
                        textAlign = TextAlign.Center,
                        color = Color.Gray
                    )
                }
            }
        }
    }

    private suspend fun cleanupIdentity(context: Context) {
        Log.w(TAG, "cleanupIdentity() called - this should only happen on full app reset!")
        try {
            context.deleteFile(IDENTITY_FILE_NAME)
            val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            PrefsHelper.getKeyAlias(context)?.let { alias ->
                ks.deleteEntry(alias)
            }
            PrefsHelper.clearAll(context)
            ContactRepository.getInstance(context).clearIdentity()
            Log.i(TAG, "Identity data cleaned up")
        } catch (e: Exception) {
            Log.w(TAG, "Cleanup error", e)
        }
    }
}