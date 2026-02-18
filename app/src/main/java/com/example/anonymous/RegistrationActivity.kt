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
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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

        // Use lifecycleScope which is tied to Activity lifecycle
        // BUT the daemon runs in its own thread so it survives config changes
        val coroutineScope = rememberCoroutineScope()

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
            val existingIdentity = contactRepository.getMyIdentity()
            if (existingIdentity != null) {
                myB32 = existingIdentity.b32Address
                val qrContent = I2pQRUtils.generateQRContent(
                    b32Address = existingIdentity.b32Address,
                    i2pDestination = existingIdentity.publicKey,
                    ecPublicKey = existingIdentity.privateKeyEncrypted.takeIf { it.isNotBlank() }
                )
                identityBitmap = withContext(Dispatchers.IO) {
                    Controller.generateQRCode(qrContent)
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
                Text("Welcome to Anonymous!", style = MaterialTheme.typography.titleLarge)
                Spacer(modifier = Modifier.height(16.dp))

                when {
                    isLoading -> {
                        CircularProgressIndicator()
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = statusText,
                            style = MaterialTheme.typography.bodyMedium,
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "I2P: ${i2pdDaemon.getState().name}",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.Gray
                        )
                    }

                    errorMessage != null -> {
                        Text(
                            text = "Error: $errorMessage",
                            color = Color.Red,
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Button(
                            onClick = {
                                errorMessage = null
                                isLoading = true
                                statusText = "Retrying..."
                                coroutineScope.launch {
                                    createIdentityWithRetry(
                                        context, i2pdDaemon, samClient, contactRepository,
                                        onStatus = { statusText = it },
                                        onSuccess = { bmp, b32 ->
                                            identityBitmap = bmp
                                            myB32 = b32
                                            isLoading = false
                                        },
                                        onError = { msg ->
                                            errorMessage = msg
                                            isLoading = false
                                        }
                                    )
                                }
                            }
                        ) {
                            Text("Retry")
                        }
                    }

                    identityBitmap != null -> {
                        // Show QR Code
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
                                textAlign = TextAlign.Center,
                                modifier = Modifier.padding(horizontal = 24.dp)
                            )
                        }
                        Spacer(modifier = Modifier.height(16.dp))

                        FloatingActionButton(
                            onClick = {
                                context.startActivity(Intent(context, MainActivity::class.java))
                                finish()
                            }
                        ) {
                            Icon(Icons.Default.ArrowForward, contentDescription = "Continue")
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        // Create new identity button
                        Box(
                            modifier = Modifier
                                .padding(horizontal = 16.dp)
                                .clip(MaterialTheme.shapes.small)
                                .background(MaterialTheme.colorScheme.secondary)
                                .clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null
                                ) {
                                    coroutineScope.launch {
                                        withContext(Dispatchers.IO) {
                                            cleanupIdentity(context)
                                        }
                                        identityBitmap = null
                                        myB32 = null
                                        isLoading = true
                                        statusText = "Creating new identity..."

                                        createIdentityWithRetry(
                                            context, i2pdDaemon, samClient, contactRepository,
                                            onStatus = { statusText = it },
                                            onSuccess = { bmp, b32 ->
                                                identityBitmap = bmp
                                                myB32 = b32
                                                isLoading = false
                                            },
                                            onError = { msg ->
                                                errorMessage = msg
                                                isLoading = false
                                            }
                                        )
                                    }
                                }
                                .padding(horizontal = 16.dp, vertical = 8.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.Center
                            ) {
                                Icon(
                                    Icons.Default.Refresh,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSecondary
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    "Create New Identity",
                                    color = MaterialTheme.colorScheme.onSecondary
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = "Your Anonymous identity is ready!\nShare this QR so others can reach you.",
                            fontSize = 12.sp,
                            textAlign = TextAlign.Center,
                            color = Color.Gray
                        )
                    }

                    else -> {
                        // Initial state - no identity
                        Text(
                            text = "Create your Anonymous identity.\nYour keys never leave this device.",
                            style = MaterialTheme.typography.bodyMedium,
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(24.dp))
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(48.dp)
                                .padding(horizontal = 16.dp)
                                .clip(MaterialTheme.shapes.medium)
                                .background(MaterialTheme.colorScheme.primary)
                                .clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null
                                ) {
                                    isLoading = true
                                    statusText = "Starting I2P daemon..."
                                    coroutineScope.launch {
                                        createIdentityWithRetry(
                                            context, i2pdDaemon, samClient, contactRepository,
                                            onStatus = { statusText = it },
                                            onSuccess = { bmp, b32 ->
                                                identityBitmap = bmp
                                                myB32 = b32
                                                isLoading = false
                                            },
                                            onError = { msg ->
                                                errorMessage = msg
                                                isLoading = false
                                            }
                                        )
                                    }
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "Be Anonymous!",
                                color = MaterialTheme.colorScheme.onPrimary
                            )
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
    }

    private suspend fun createIdentityWithRetry(
        context: Context,
        i2pdDaemon: I2pdDaemon,
        samClient: SAMClient,
        contactRepository: ContactRepository,
        onStatus: (String) -> Unit,
        onSuccess: (Bitmap, String) -> Unit,
        onError: (String) -> Unit
    ) {
        try {
            // Step 1: Start daemon if not running
            if (!i2pdDaemon.isRunning()) {
                onStatus("Starting I2P network...")
                val started = i2pdDaemon.start()
                if (!started) {
                    throw Exception("Failed to start I2P daemon")
                }
            }

            // Step 2: Wait for SAM ready
            onStatus("Building I2P tunnels...")
            val ready = i2pdDaemon.waitForReady(timeoutMs = 60000)
            if (!ready) {
                throw Exception("I2P failed to become ready")
            }

            // Step 3: Connect SAM and create session
            onStatus("Creating your identity...")

            if (!samClient.connect()) {
                throw Exception("Failed to connect to SAM bridge")
            }

            val sessionResult = samClient.createStreamSession()
            if (sessionResult.isFailure) {
                throw Exception("Failed to create I2P session: ${sessionResult.exceptionOrNull()?.message}")
            }
            val session = sessionResult.getOrThrow()

            // Step 4: Generate encryption keys
            onStatus("Generating encryption keys...")
            val ecdhKeyPair = CryptoManager.generateECDHKeyPair()
            val ecPublicB64 = Base64.encodeToString(ecdhKeyPair.public.encoded, Base64.NO_WRAP)

            // Step 5: Save everything
            onStatus("Saving identity...")
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

            // Step 6: Generate QR
            onStatus("Generating QR code...")
            val qrContent = I2pQRUtils.generateQRContent(
                b32Address = session.b32Address,
                i2pDestination = session.destination,
                ecPublicKey = ecPublicB64
            )

            val bitmap = withContext(Dispatchers.IO) {
                Controller.generateQRCode(qrContent)
            } ?: throw Exception("QR generation failed")

            // Save QR to storage
            withContext(Dispatchers.IO) {
                Controller.saveBitmapToInternalStorage(context, bitmap, IDENTITY_FILE_NAME)
                Controller.saveBitmapToGallery(context, bitmap, IDENTITY_FILE_NAME)
            }

            withContext(Dispatchers.Main) {
                Toast.makeText(context, "Identity created!", Toast.LENGTH_SHORT).show()
            }

            onSuccess(bitmap, session.b32Address)

        } catch (e: Exception) {
            Log.e(TAG, "Identity creation failed", e)
            onError(e.message ?: "Unknown error")
        }
    }

    private suspend fun cleanupIdentity(context: Context) {
        try {
            context.deleteFile(IDENTITY_FILE_NAME)

            val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            PrefsHelper.getKeyAlias(context)?.let { alias ->
                ks.deleteEntry(alias)
            }

            PrefsHelper.clearAll(context)
            ContactRepository.getInstance(context).clearIdentity()
        } catch (e: Exception) {
            Log.w(TAG, "Cleanup error", e)
        }
    }
}