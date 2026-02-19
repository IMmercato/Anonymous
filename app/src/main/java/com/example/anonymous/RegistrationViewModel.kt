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
import com.example.anonymous.i2p.I2pdDaemon
import com.example.anonymous.i2p.SAMClient
import com.example.anonymous.repository.ContactRepository
import com.example.anonymous.utils.PrefsHelper
import kotlinx.coroutines.Dispatchers
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
    }

    private val _uiState = MutableStateFlow(RegistrationUiState())
    val uiState: StateFlow<RegistrationUiState> = _uiState.asStateFlow()

    fun loadExistingIdentityIfAny(context: Context) {
        if (_uiState.value.identityBitmap != null || _uiState.value.isLoading) return

        viewModelScope.launch {
            val contactRepository = ContactRepository.getInstance(context)
            val existingIdentity = withContext(Dispatchers.IO) { contactRepository.getMyIdentity() }

            if (existingIdentity !=  null) {
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
        }
    }

    fun createIdentity(context: Context) {
        if (_uiState.value.isLoading) return

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

                // Step 1 - Start daemon
                Log.i(TAG, "STEP 1: Starting daemon...")
                val started = withContext(Dispatchers.IO) { daemon.start() }
                Log.i(TAG, "start() = $started")
                if (!started) throw IllegalStateException("Failed to start I2P daemon")

                // Step 2 - Wait for SAM port
                Log.i(TAG, "STEP 2: Waiting for ready...")
                _uiState.update { it.copy(statusText = "Waiting for I2P to be ready...") }
                val ready = withContext(Dispatchers.IO) { daemon.waitForReady(timeoutMs = 120_000) }
                Log.i(TAG, "waitForReady() = $ready")
                if (!ready) throw IllegalStateException("I2P timed out - check network")

                // Step 3 - Connect to SAM
                Log.i(TAG, "STEP 3: Connecting to SAM...")
                _uiState.update { it.copy(statusText = "Creating identity...") }
                val samConnected = withContext(Dispatchers.IO) { samClient.connect() }
                Log.i(TAG, "samClient.connect() = $samConnected")
                if (!samConnected) throw IllegalStateException("SAM bridge connection failed")

                // Step 4 - Create stream session
                Log.i(TAG, "STEP 4: Creating SAM session...")
                val session = withContext(Dispatchers.IO) {
                    samClient.createStreamSession().getOrThrow()
                }
                Log.i(TAG, "Session b32: ${session.b32Address}")

                // Step 5 - ECDH key pair: end-to-end
                Log.i(TAG, "STEP 5: Generating ECDH keys...")
                val ecdhKeyPair = withContext(Dispatchers.IO) {
                    CryptoManager.generateECDHKeyPair()
                }
                val ecPublicB64 = Base64.encodeToString(ecdhKeyPair.public.encoded, Base64.NO_WRAP)

                // Step 6 - Persist identity
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

                // Step 7 - Generate & save QR code
                Log.i(TAG, "STEP 7: Generating QR...")
                _uiState.update { it.copy(statusText = "Generating QR code...") }
                val qrContent = I2pQRUtils.generateQRContent(
                    b32Address = session.b32Address,
                    i2pDestination = session.destination,
                    ecPublicKey = ecPublicB64
                )
                val bitmap = withContext(Dispatchers.IO) {
                    Controller.generateQRCode(qrContent)
                } ?: throw java.lang.IllegalStateException("QR generation failed")

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

    fun onDaemonStateChanged(state: I2pdDaemon.DaemonState) {
        if (!_uiState.value.isLoading) return
        val text = when (state) {
            I2pdDaemon.DaemonState.STARTING             -> "Starting I2P..."
            I2pdDaemon.DaemonState.BUILDING_TUNNELS     -> "Building tunnels..."
            I2pdDaemon.DaemonState.WAITING_FOR_NETWORK  -> "Waiting for network..."
            I2pdDaemon.DaemonState.READY                -> "I2P Ready!"
            else                                        -> state.name
        }
        _uiState.update { it.copy(statusText = text) }
    }

    fun onDaemonError(error: String) {
        Log.e(TAG, "Daemon error: $error")
        _uiState.update { it.copy(isLoading = false, errorMessage = error) }
    }
}