package com.example.anonymous

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.util.Base64
import android.util.Log
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FloatingActionButton
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
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
        private const val KEY_ALIAS_PREFIX = "anonymous_identity_"
        private const val IDENTITY_FILE_NAME = "qr_identity.png"
        private const val QR_FORMAT_VERSION = 1
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.setFlags(
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        )

        setContent {
            AnonymousTheme {
                RegistrationScreen()
            }
        }
    }

    @Composable
    fun RegistrationScreen() {
        val context = LocalContext.current
        val coroutineScope = rememberCoroutineScope()

        // I2P
        val i2pdDaemon = remember { I2pdDaemon.getInstance(context) }
        val samClient = remember { SAMClient.getInstance() }
        val contactRepository = remember { ContactRepository.getInstance(context) }
        var i2pState by remember { mutableStateOf(I2pdDaemon.DaemonState.STOPPED) }
        var statusText by remember { mutableStateOf("") }
        var myB32 by remember { mutableStateOf<String?>(null) }

        var identityBitmap by remember { mutableStateOf<Bitmap?>(null) }
        var isLoading by remember { mutableStateOf(false) }

        // Load and validate existing identity
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

        LaunchedEffect(isLoading) {
            if (!isLoading) return@LaunchedEffect

            i2pdDaemon.addListener(object : I2pdDaemon.DaemonStateListener {
                override fun onStateChanged(state: I2pdDaemon.DaemonState) {
                    i2pState = state
                    statusText = when (state) {
                        I2pdDaemon.DaemonState.STARTING -> "Starting I2P daemon..."
                        I2pdDaemon.DaemonState.BUILDING_TUNNELS -> "Building tunnels..."
                        I2pdDaemon.DaemonState.READY -> "I2P ready! Creating identity..."
                        I2pdDaemon.DaemonState.ERROR -> "I2P error - please retry."
                        else -> "Initialising..."
                    }
                    when (state) {
                        I2pdDaemon.DaemonState.READY -> coroutineScope.launch {
                            createIdentity(
                                context = context,
                                samClient = samClient,
                                contactRepository = contactRepository,
                                onStatus = { statusText = it },
                                onSuccess = { bmp, b32 ->
                                    identityBitmap = bmp
                                    myB32 = b32
                                    isLoading = false
                                },
                                onError = { msg ->
                                    statusText = msg
                                    isLoading = false
                                    Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                                }
                            )
                        }
                        I2pdDaemon.DaemonState.ERROR -> isLoading = false
                        else -> {}
                    }
                }

                override fun onError(error: String) {
                    statusText = "Error: $error"
                    isLoading = true
                }
            })
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
                            text  = "I2P: ${i2pState.name}",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.Gray
                        )
                    }

                    identityBitmap != null -> {
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
                                context.startActivity(
                                    Intent(context, MainActivity::class.java)
                                )
                                finish()
                            }
                        ) {
                            Icon(Icons.Default.ArrowForward, contentDescription = "Continue")
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        Button(
                            onClick = {
                                coroutineScope.launch {
                                    withContext(Dispatchers.IO) {
                                        context.deleteFile(IDENTITY_FILE_NAME)
                                        runCatching {
                                            val ks = KeyStore.getInstance("AndroidKeyStore").also {
                                                it.load(null)
                                            }
                                            PrefsHelper.getKeyAlias(context)?.let { ks.deleteEntry(it) }
                                        }.onFailure { Log.w(TAG, "Keystore cleanup error", it) }
                                    }
                                    identityBitmap = null
                                    myB32 = null
                                    isLoading = true
                                    statusText = "Starting I2P daemon..."
                                    i2pdDaemon.start()
                                }
                            }
                        ) {
                            Icon(Icons.Default.Refresh, contentDescription = null)
                            Spacer(modifier = Modifier.height(4.dp))
                            Text("Create New Identity")
                        }

                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = "Your Anonymous identity is ready!\nShare this QR so others can reach you.",
                            fontSize = 12.sp,
                            textAlign = TextAlign.Center,
                            color = Color.Gray
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Keep your QR safe - it is your public reachable address.",
                            fontSize = 10.sp,
                            textAlign = TextAlign.Center,
                            color = Color.Gray
                        )
                    }

                    else -> {
                        Text(
                            text = "Create your Anonymous identity.\nYou keys never left this device.",
                            style = MaterialTheme.typography.bodyMedium,
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(24.dp))
                        Button(
                            onClick = {
                                isLoading = true
                                statusText = "Stating I2P daemon..."
                                i2pdDaemon.start()
                            }
                        ) {
                            Text("Be Anonymous!")
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text      = "Your private key stays on this device — it is never shared.",
                            fontSize  = 10.sp,
                            textAlign = TextAlign.Center,
                            color     = Color.Gray
                        )
                    }
                }
            }
        }
    }

    private suspend fun createIdentity(
        context: Context,
        samClient: SAMClient,
        contactRepository: ContactRepository,
        onStatus: (String) -> Unit,
        onSuccess: (Bitmap, String) -> Unit,
        onError: (String) -> Unit
    ) {
        try {
            onStatus("Connecting to SAM bridge...")
            if (!samClient.connect()) throw Exception("Could not reach SAM bridge")

            onStatus("Creating I2P destination...")
            val sessionResult = samClient.createStreamSession()
            if (sessionResult.isFailure) {
                throw Exception("Session error: ${sessionResult.exceptionOrNull()?.message}")
            }
            val session = sessionResult.getOrThrow()

            onStatus("Generating encryption keys...")
            val ecdhKeyPair = CryptoManager.generateECDHKeyPair()
            val ecPublicB64 = Base64.encodeToString(ecdhKeyPair.public.encoded, Base64.NO_WRAP)

            onStatus("Saving identity...")
            CryptoManager.saveKeyPair(context, session.b32Address, ecdhKeyPair)
            PrefsHelper.saveKeyAlias(context, session.b32Address)

            val identity = ContactRepository.MyIdentity(
                b32Address = session.b32Address,
                publicKey = session.destination,
                privateKeyEncrypted = ecPublicB64
            )
            contactRepository.saveMyIdentity(identity)
            
            onStatus("Generate QR code...")
            val qrContent = I2pQRUtils.generateQRContent(
                b32Address = session.b32Address,
                i2pDestination = session.destination,
                ecPublicKey = ecPublicB64
            )
            val bitmap = withContext(Dispatchers.IO) { Controller.generateQRCode(qrContent) } ?: throw Exception("QR render failed")
            
            withContext(Dispatchers.IO) {
                Controller.saveBitmapToInternalStorage(context, bitmap, IDENTITY_FILE_NAME)
                Controller.saveBitmapToGallery(context, bitmap, IDENTITY_FILE_NAME)
            }

            Toast.makeText(context, "Identity created!", Toast.LENGTH_SHORT).show()
            onSuccess(bitmap, session.b32Address)
        } catch (e: Exception) {
            Log.e(TAG, "Identity creation failed", e)
            onError(e.message ?: "Unknown error")
        }
    }
}