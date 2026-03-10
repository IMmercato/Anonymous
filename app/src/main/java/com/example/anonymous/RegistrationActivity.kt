package com.example.anonymous

import android.content.Context
import android.graphics.Bitmap
import android.util.Base64
import android.util.Log
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
import androidx.core.content.edit
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import com.example.anonymous.controller.Controller
import com.example.anonymous.i2p.I2pQRUtils
import com.example.anonymous.i2p.I2pdService
import com.example.anonymous.i2p.SAMClient
import com.example.anonymous.repository.ContactRepository
import com.example.anonymous.utils.PrefsHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val TAG = "RegistrationScreen"

data class RegistrationUiState(
    val isLoading: Boolean = false,
    val statusText: String = "",
    val errorMessage: String? = null,
    val identityBitmap: Bitmap? = null,
    val myB32: String? = null,
    val isIdentityReady: Boolean = false,
    val autoNavigate: Boolean = false
)

class RegistrationViewModel : ViewModel() {
    companion object {
        private const val TAG = "RegistrationViewModel"
        private const val IDENTITY_FILE_NAME = "qr_identity.png"
        private const val SAM_READY_TIMEOUT_MS = 600_000L // 10 minutes

        private const val PREFS_NAME = "i2p_identity"
        private const val KEY_PRIV   = "sam_priv_key"
        private const val KEY_PUB    = "sam_pub_key"

        private const val SESSION_CREATE_MAX_RETRIES = 5
        private const val SESSION_CREATE_RETRY_DELAY_MS = 3_000L
    }

    private val _uiState = MutableStateFlow(RegistrationUiState())
    val uiState: StateFlow<RegistrationUiState> = _uiState.asStateFlow()

    fun loadExistingIdentityIfAny(context: Context) {
        if (_uiState.value.identityBitmap != null || _uiState.value.isLoading) return

        viewModelScope.launch {
            try {
                val contactRepository = ContactRepository.getInstance(context)
                val existingIdentity = withContext(Dispatchers.IO) {
                    contactRepository.getMyIdentity()
                }

                if (existingIdentity != null) {
                    Log.i(TAG, "Existing identity found: ${existingIdentity.b32Address}")
                    val qrContent = I2pQRUtils.generateQRContent(
                        b32Address = existingIdentity.b32Address,
                        i2pDestination = existingIdentity.publicKey,
                        ecPublicKey = existingIdentity.privateKeyEncrypted.takeIf { it.isNotBlank() }
                    )
                    val bitmap = withContext(Dispatchers.IO) {
                        Controller.generateQRCode(qrContent)
                    }
                    _uiState.update {
                        it.copy(
                            identityBitmap = bitmap,
                            myB32 = existingIdentity.b32Address,
                            isIdentityReady = true,
                            autoNavigate = true
                        )
                    }
                } else {
                    Log.i(TAG, "No existing identity")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error loading existing identity", e)
            }
        }
    }

    fun createIdentity(context: Context) {
        if (_uiState.value.isLoading) {
            Log.w(TAG, "Already loading, ignoring duplicate call")
            return
        }

        viewModelScope.launch {
            _uiState.update {
                it.copy(isLoading = true, errorMessage = null, statusText = "Starting I2P service...")
            }

            try {
                val appContext = context.applicationContext
                val samClient = SAMClient.getInstance()
                val contactRepository = ContactRepository.getInstance(appContext)

                Log.i(TAG, "STEP 1: Starting I2pdService...")
                withContext(Dispatchers.Main) { I2pdService.start(appContext) }

                Log.i(TAG, "STEP 2: Waiting for stable SAM bridge...")
                _uiState.update { it.copy(statusText = "Connecting to I2P network...") }
                val samReady = withContext(Dispatchers.IO) {
                    waitForSamStable(SAM_READY_TIMEOUT_MS, samClient)
                }
                if (!samReady) throw IllegalStateException(
                    "I2P did not become ready within ${SAM_READY_TIMEOUT_MS / 1000}s."
                )

                Log.i(TAG, "STEP 3: SAM bridge confirmed stable")
                _uiState.update { it.copy(statusText = "Creating identity...") }

                Log.i(TAG, "STEP 4: Generating persistent SAM destination...")
                val (pub, priv) = withContext(Dispatchers.IO) {
                    samClient.generateDestination().getOrThrow()
                }

                Log.i(TAG, "STEP 5: Creating SAM stream session with persistent key (with retry)...")
                val session = withContext(Dispatchers.IO) {
                    createSessionWithRetry(samClient, priv)
                } ?: throw IllegalStateException("Failed to create SAM session after $SESSION_CREATE_MAX_RETRIES retries")

                // Only save keys to prefs after session is successfully created
                appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    .edit {
                        putString(KEY_PRIV, priv)
                        putString(KEY_PUB, pub)
                    }
                Log.i(TAG, "STEP 5: Session created and keys saved. b32: ${session.b32Address}")

                Log.i(TAG, "STEP 6: Generating ECDH keys...")
                val alias = session.b32Address
                withContext(Dispatchers.IO) {
                    PrefsHelper.generateOrGetECDHKeyPair(context, alias)
                }
                val ecPublicBytes = PrefsHelper.getECPublicKeyBytes(context, alias)
                    ?: throw IllegalStateException("Could not read own EC public key after generation")
                val ecPublicB64 = Base64.encodeToString(ecPublicBytes, Base64.NO_WRAP)

                Log.i(TAG, "STEP 7: Saving identity to ContactRepository...")
                withContext(Dispatchers.IO) {
                    PrefsHelper.saveKeyAlias(appContext, alias)
                }
                contactRepository.saveMyIdentity(
                    ContactRepository.MyIdentity(
                        b32Address = session.b32Address,
                        publicKey = session.destination,    // PUB destination (shared with peers)
                        privateKeyEncrypted = ecPublicB64   // EC public key (for ECDH encryption)
                    )
                )

                Log.i(TAG, "STEP 8: Generating QR code...")
                _uiState.update { it.copy(statusText = "Generating QR code...") }
                val qrContent = I2pQRUtils.generateQRContent(
                    b32Address = session.b32Address,
                    i2pDestination = session.destination,
                    ecPublicKey = ecPublicB64
                )
                val bitmap = withContext(Dispatchers.IO) {
                    Controller.generateQRCode(qrContent)
                } ?: throw IllegalStateException("QR generation failed")

                withContext(Dispatchers.IO) {
                    Controller.saveBitmapToInternalStorage(appContext, bitmap, IDENTITY_FILE_NAME)
                    Controller.saveBitmapToGallery(appContext, bitmap, IDENTITY_FILE_NAME)
                }

                Log.i(TAG, ">>> createIdentity() SUCCESS <<<")
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        identityBitmap = bitmap,
                        myB32 = session.b32Address,
                        isIdentityReady = true,
                        autoNavigate = false,
                        statusText = "Identity created!"
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "!!! createIdentity() FAILED !!!", e)
                _uiState.update {
                    it.copy(isLoading = false, errorMessage = e.message ?: "Unknown error")
                }
            }
        }
    }

    private suspend fun createSessionWithRetry(
        samClient: SAMClient,
        privKey: String
    ): SAMClient.SAMSession? {
        repeat(SESSION_CREATE_MAX_RETRIES) { attempt ->
            Log.i(TAG, "SESSION CREATE attempt ${attempt + 1}/$SESSION_CREATE_MAX_RETRIES")
            val result = samClient.createStreamSession(savedPrivateKey = privKey)
            if (result.isSuccess) {
                return result.getOrThrow()
            }
            val err = result.exceptionOrNull()
            Log.w(TAG, "Session create attempt ${attempt + 1} failed: ${err?.message}")

            if (attempt < SESSION_CREATE_MAX_RETRIES - 1) {
                _uiState.update {
                    it.copy(statusText = "Retrying session creation (${attempt + 2}/$SESSION_CREATE_MAX_RETRIES)...")
                }
                delay(SESSION_CREATE_RETRY_DELAY_MS)

                // Wait for SAM to be responsive again before retrying
                val samBack = waitForSamStable(30_000L, samClient)
                if (!samBack) {
                    Log.w(TAG, "SAM did not recover before retry ${attempt + 2}, trying anyway")
                }
            }
        }
        return null
    }

    private suspend fun waitForSamStable(timeoutMs: Long, samClient: SAMClient): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        var attempt = 0
        while (System.currentTimeMillis() < deadline) {
            attempt++
            val firstOk = withContext(Dispatchers.IO) { samClient.connect() }
            if (!firstOk) {
                val elapsedSec = attempt * 2
                _uiState.update {
                    it.copy(statusText = when {
                        elapsedSec < 30  -> "Connecting to I2P network..."
                        elapsedSec < 120 -> "Downloading network database (~${elapsedSec}s).\nFirst launch takes a few minutes."
                        elapsedSec < 300 -> "Reseeding network database (~${elapsedSec / 60}min).\nPlease keep the app open."
                        else             -> "Still reseeding... (~${elapsedSec / 60}min elapsed).\nThis is normal on first launch."
                    })
                }
                delay(2000)
                continue
            }

            Log.i(TAG, "SAM responded (attempt $attempt), confirming stability in 3s...")
            _uiState.update { it.copy(statusText = "Verifying I2P stability...") }
            delay(3000)

            val secondOk = withContext(Dispatchers.IO) { samClient.connect() }
            if (secondOk) {
                Log.i(TAG, "SAM confirmed stable after $attempt attempts")
                return true
            }

            // SAM died in those 3s — i2pd is still cycling, keep waiting
            Log.w(TAG, "SAM was alive but died within 3s — i2pd still restarting, continuing to wait")
        }
        Log.e(TAG, "SAM bridge not stable after ${timeoutMs}ms")
        return false
    }
}

@Composable
fun RegistrationScreen(
    navController: NavHostController,
    viewModel: RegistrationViewModel = viewModel()
) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsState()

    LaunchedEffect(Unit) {
        viewModel.loadExistingIdentityIfAny(context)
    }

    LaunchedEffect(uiState.isIdentityReady, uiState.autoNavigate) {
        if (uiState.isIdentityReady && uiState.autoNavigate) {
            delay(16)
            navController.navigate("main") {
                popUpTo("registration") { inclusive = true }
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

            if (uiState.isIdentityReady && uiState.identityBitmap != null) {
                Image(
                    bitmap = uiState.identityBitmap!!.asImageBitmap(),
                    contentDescription = "Your Anonymous Identity QR Code",
                    modifier = Modifier.size(256.dp)
                )
                Spacer(modifier = Modifier.height(8.dp))
                uiState.myB32?.let { b32 ->
                    Text(
                        text = b32,
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.Gray,
                        textAlign = TextAlign.Center
                    )
                }
                Spacer(modifier = Modifier.height(16.dp))

                Surface(
                    modifier = Modifier
                        .wrapContentWidth()
                        .height(48.dp)
                        .clickable(
                            indication = null,
                            interactionSource = remember { MutableInteractionSource() },
                            role = Role.Button,
                            onClick = {
                                navController.navigate("main") {
                                    popUpTo("registration") { inclusive = true }
                                }
                            }
                        ),
                    shape = MaterialTheme.shapes.medium,
                    color = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 24.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.ArrowForward, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Continue", style = MaterialTheme.typography.bodyLarge)
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = "Your Anonymous identity is ready!\nShare this QR so others can reach you.",
                    fontSize = 12.sp,
                    textAlign = TextAlign.Center,
                    color = Color.Gray
                )
            } else {
                when {
                    uiState.isLoading -> {
                        CircularProgressIndicator()
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = uiState.statusText,
                            style = MaterialTheme.typography.bodyMedium,
                            textAlign = TextAlign.Center
                        )
                    }
                    uiState.errorMessage != null -> {
                        Text(
                            text = "Error: ${uiState.errorMessage}",
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
                        .alpha(if (uiState.isLoading) 0.6f else 1f)
                        .clickable(
                            enabled = !uiState.isLoading,
                            indication = null,
                            interactionSource = remember { MutableInteractionSource() },
                            role = Role.Button,
                            onClick = {
                                Log.i(TAG, "Be Anonymous! clicked")
                                viewModel.createIdentity(context)
                            }
                        ),
                    shape = MaterialTheme.shapes.medium,
                    color = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        if (uiState.isLoading) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(22.dp),
                                strokeWidth = 2.5.dp,
                                color = MaterialTheme.colorScheme.onPrimary
                            )
                        } else {
                            Text(
                                text = if (uiState.errorMessage != null) "Retry" else "Be Anonymous!",
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