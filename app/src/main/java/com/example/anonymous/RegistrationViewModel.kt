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
import java.net.Socket

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
        private const val SAM_READY_TIMEOUT_MS = 600_000L  // 10 minutes
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
                it.copy(
                    isLoading = true,
                    errorMessage = null,
                    statusText = "Starting I2P service..."
                )
            }

            try {
                val appContext = context.applicationContext
                val samClient = SAMClient.getInstance()
                val contactRepository = ContactRepository.getInstance(appContext)

                // Step 1: Start the foreground service (separate process)
                Log.i(TAG, "STEP 1: Starting I2pdService...")
                withContext(Dispatchers.Main) {
                    I2pdService.start(appContext)
                }

                // Step 2: Wait for SAM port to become reachable
                Log.i(TAG, "STEP 2: Waiting for SAM bridge...")
                _uiState.update { it.copy(statusText = "Connecting to I2P network...") }

                val samReady = withContext(Dispatchers.IO) {
                    waitForSamReady(timeoutMs = SAM_READY_TIMEOUT_MS)
                }

                if (!samReady) {
                    throw IllegalStateException(
                        "I2P did not become ready within ${SAM_READY_TIMEOUT_MS / 1000}s."
                    )
                }

                // Step 3: Connect to SAM
                Log.i(TAG, "STEP 3: Connecting to SAM...")
                _uiState.update { it.copy(statusText = "Creating identity...") }

                if (!samClient.isConnected.value) {
                    val samConnected = withContext(Dispatchers.IO) { samClient.connect() }
                    if (!samConnected) throw IllegalStateException("SAM bridge connection failed")
                }

                // Step 4: Create SAM stream session
                Log.i(TAG, "STEP 4: Creating SAM stream session...")
                val session = withContext(Dispatchers.IO) {
                    samClient.createStreamSession().getOrThrow()
                }
                Log.i(TAG, "Session b32: ${session.b32Address}")

                // Step 5: Generate ECDH key pair
                Log.i(TAG, "STEP 5: Generating ECDH keys...")
                val ecdhKeyPair = withContext(Dispatchers.IO) {
                    CryptoManager.generateECDHKeyPair()
                }
                val ecPublicB64 = Base64.encodeToString(ecdhKeyPair.public.encoded, Base64.NO_WRAP)

                // Step 6: Persist identity
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

                // Step 7: Generate QR code
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
                        autoNavigate = false,
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

    private suspend fun waitForSamReady(timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        var attempt = 0
        while (System.currentTimeMillis() < deadline) {
            attempt++
            if (isSamPortOpen()) {
                Log.i(TAG, "SAM port ready after $attempt attempts")
                return true
            }

            val elapsedSec = attempt * 2
            val statusText = when {
                elapsedSec < 30  -> "Connecting to I2P network..."
                elapsedSec < 120 -> "Downloading network database (~${elapsedSec}s).\nFirst launch takes a few minutes."
                elapsedSec < 300 -> "Reseeding network database (~${elapsedSec / 60}min).\nPlease keep the app open."
                else             -> "Still reseeding... (~${elapsedSec / 60}min elapsed).\nThis is normal on first launch."
            }
            _uiState.update { it.copy(statusText = statusText) }

            delay(2000)
        }
        Log.e(TAG, "SAM port not ready after $timeoutMs ms")
        return false
    }

    private fun isSamPortOpen(): Boolean {
        return try {
            Socket("127.0.0.1", 7656).use { it.isConnected }
        } catch (_: Exception) {
            false
        }
    }
}