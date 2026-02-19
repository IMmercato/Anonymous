package com.example.anonymous

import android.content.Context
import android.graphics.Bitmap
import android.util.Base64
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.anonymous.controller.Controller
import com.example.anonymous.crypto.CryptoManager
import com.example.anonymous.i2p.I2pQRUtils
import com.example.anonymous.i2p.I2pdDaemon import com.example.anonymous.i2p.I2pdService
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

data class RegistrationUiState(
    val isLoading: Boolean = false,
    val statusText: String = "",
    val errorMessage: String? = null,
    val identityBitmap: Bitmap? = null,
    val myB32: String? = null,
    val isIdentityReady: Boolean = false
)

class RegistrationViewModel : ViewModel() {
    companion object {
        private const val TAG = "RegistrationViewModel"
        private const val IDENTITY_FILE_NAME = "qr_identity.png"
        private const val DAEMON_READY_TIMEOUT_MS = 300_000L
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
                            isIdentityReady = true
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
                it.copy(
                    isLoading = true,
                    errorMessage = null,
                    statusText = "Initializing I2P network..."
                )
            }

            try {
                val appContext = context.applicationContext
                val daemon = I2pdDaemon.getInstance(appContext)
                val samClient = SAMClient.getInstance()
                val contactRepository = ContactRepository.getInstance(appContext)

                // If daemon crashed previously, tell user to restart
                if (daemon.getState() == I2pdDaemon.DaemonState.ERROR) {
                    _uiState.update {
                        it.copy(isLoading = false, errorMessage = "I2P crashed — please restart the app")
                    }
                    return@launch
                }

                // If already fully ready, skip straight to identity creation
                if (daemon.isReady()) {
                    Log.i(TAG, "Daemon already ready, skipping wait")
                    createSessionAndIdentity(appContext, samClient, contactRepository)
                    return@launch
                }

                // STEP 1: Tell the foreground Service to start the daemon.
                // The Service owns daemon.start() — we NEVER call it from here.
                // Using startForegroundService ensures it runs even when app is backgrounded.
                Log.i(TAG, "STEP 1: Starting I2pdService...")
                withContext(Dispatchers.Main) {
                    I2pdService.start(appContext)
                }

                // STEP 2: Wait for the daemon to become ready.
                // Give the service a moment to actually call daemon.start() before we poll.
                Log.i(TAG, "STEP 2: Waiting for daemon to become ready...")
                _uiState.update { it.copy(statusText = "Connecting to I2P network...") }

                val ready = withContext(Dispatchers.IO) {
                    daemon.waitForReady(timeoutMs = DAEMON_READY_TIMEOUT_MS)
                }

                Log.i(TAG, "waitForReady() = $ready")
                if (!ready) {
                    throw IllegalStateException(
                        "I2P did not become ready within ${DAEMON_READY_TIMEOUT_MS / 1000}s. " +
                                "Check your network connection."
                    )
                }

                createSessionAndIdentity(appContext, samClient, contactRepository)

            } catch (e: Exception) {
                Log.e(TAG, "!!! createIdentity() FAILED !!!", e)
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        errorMessage = e.message ?: "Unknown error"
                    )
                }
            }
        }
    }

    private suspend fun createSessionAndIdentity(
        appContext: Context,
        samClient: SAMClient,
        contactRepository: ContactRepository
    ) {
        try {
            // STEP 3: Connect to SAM bridge
            Log.i(TAG, "STEP 3: Connecting to SAM...")
            _uiState.update { it.copy(statusText = "Creating identity...") }

            if (!samClient.isConnected.value) {
                val samConnected = withContext(Dispatchers.IO) { samClient.connect() }
                Log.i(TAG, "samClient.connect() = $samConnected")
                if (!samConnected) throw IllegalStateException("SAM bridge connection failed")
            } else {
                Log.i(TAG, "SAM already connected, reusing")
            }

            // STEP 4: Create stream session (this gives us our I2P identity)
            Log.i(TAG, "STEP 4: Creating SAM stream session...")
            val session = withContext(Dispatchers.IO) {
                samClient.createStreamSession().getOrThrow()
            }
            Log.i(TAG, "Session b32: ${session.b32Address}")

            // STEP 5: Generate ECDH key pair for end-to-end encryption
            Log.i(TAG, "STEP 5: Generating ECDH keys...")
            val ecdhKeyPair = withContext(Dispatchers.IO) {
                CryptoManager.generateECDHKeyPair()
            }
            val ecPublicB64 = Base64.encodeToString(ecdhKeyPair.public.encoded, Base64.NO_WRAP)

            // STEP 6: Persist identity to storage
            Log.i(TAG, "STEP 6: Saving identity...")
            withContext(Dispatchers.IO) {
                CryptoManager.saveKeyPair(appContext, session.b32Address, ecdhKeyPair)
                PrefsHelper.saveKeyAlias(appContext, session.b32Address)
            }
            contactRepository.saveMyIdentity(
                ContactRepository.MyIdentity(
                    b32Address = session.b32Address,
                    publicKey = session.destination,
                    privateKeyEncrypted = ecPublicB64
                )
            )

            // STEP 7: Generate QR code
            Log.i(TAG, "STEP 7: Generating QR code...")
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
                    statusText = "Identity created!"
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in createSessionAndIdentity", e)
            throw e
        }
    }

    fun onDaemonStateChanged(state: I2pdDaemon.DaemonState) {
        if (!_uiState.value.isLoading) return
        val text = when (state) {
            I2pdDaemon.DaemonState.STARTING            -> "Starting I2P..."
            I2pdDaemon.DaemonState.BUILDING_TUNNELS    -> "Building tunnels..."
            I2pdDaemon.DaemonState.WAITING_FOR_NETWORK -> "Waiting for network..."
            I2pdDaemon.DaemonState.RESEEDING           -> "Reseeding (first-time setup)..."
            I2pdDaemon.DaemonState.READY               -> "I2P Ready!"
            else                                       -> state.name
        }
        _uiState.update { it.copy(statusText = text) }
    }

    fun onDaemonError(error: String) {
        Log.e(TAG, "Daemon error: $error")
        _uiState.update { it.copy(isLoading = false, errorMessage = error) }
    }
}