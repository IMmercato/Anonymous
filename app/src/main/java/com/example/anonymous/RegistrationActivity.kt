package com.example.anonymous

import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
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
import com.example.anonymous.network.GraphQLRequest
import com.example.anonymous.network.GraphQLService
import com.example.anonymous.network.QueryBuilder
import com.example.anonymous.network.model.RegisterUserData
import com.example.anonymous.ui.theme.AnonymousTheme
import com.example.anonymous.utils.JwtUtils
import com.example.anonymous.utils.PrefsHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.util.UUID

class RegistrationActivity : ComponentActivity() {

    companion object {
        private const val TAG = "RegistrationActivity"
        private const val KEY_ALIAS_PREFIX = "anonymous_identity_"
        private const val IDENTITY_FILE_NAME = "qr_identity.png"
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
        var identityBitmap by remember { mutableStateOf<Bitmap?>(null) }
        var isLoading by remember { mutableStateOf(false) }
        var timeRemaining by remember { mutableStateOf<String?>(null) }
        val coroutineScope = rememberCoroutineScope()

        // Load and validate existing identity
        LaunchedEffect(Unit) {
            identityBitmap = Controller.checkValidIdentityExists(context, IDENTITY_FILE_NAME)
            identityBitmap?.let {
                val jwt = PrefsHelper.getRegistrationJwt(context)
                jwt?.let {
                    val remaining = JwtUtils.getJwtTimeRemaining(jwt)
                    timeRemaining = JwtUtils.formatTimeRemaining(remaining)
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

                if (identityBitmap != null) {
                    Image(
                        bitmap = identityBitmap!!.asImageBitmap(),
                        contentDescription = "QR Code for your identity",
                        modifier = Modifier.size(256.dp)
                    )

                    timeRemaining?.let { remaining ->
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Expires in: $remaining",
                            color = if (remaining.contains("seconds")) Color.Red else Color.Green,
                            fontSize = 12.sp
                        )
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    // Continue to login button
                    FloatingActionButton(
                        onClick = {
                            context.startActivity(Intent(context, LoginActivity::class.java))
                        }
                    ) {
                        Icon(Icons.Default.ArrowForward, contentDescription = "Continue to Verify")
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    // Re-register button (if JWT is expired or about to expire)
                    Button(
                        onClick = {
                            coroutineScope.launch {
                                Controller.cleanupAllIdentityData(context, IDENTITY_FILE_NAME)
                                identityBitmap = null
                                timeRemaining = null
                            }
                        }
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = "Create New Identity")
                        Spacer(modifier = Modifier.height(4.dp))
                        Text("Create New Identity")
                    }

                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "Your anonymous identity already exists.\nKeep your QR code safe!",
                        fontSize = 10.sp,
                        textAlign = TextAlign.Center
                    )
                } else {
                    Button(
                        onClick = {
                            if (isLoading) return@Button
                            isLoading = true

                            coroutineScope.launch {
                                try {
                                    // Generate RSA key pair
                                    val keyAlias = KEY_ALIAS_PREFIX + UUID.randomUUID().toString()
                                    val keyPair = generateRSAKeyPair(keyAlias)
                                    val publicKeyBytes = keyPair?.public?.encoded
                                    val publicKeyString = publicKeyBytes?.let {
                                        Base64.encodeToString(it, Base64.NO_WRAP)
                                    } ?: throw Exception("Failed to generate key pair")

                                    Log.d(TAG, "Generated public key: ${publicKeyString.take(50)}...")
                                    Log.d(TAG, "Using key alias: $keyAlias")

                                    // Register with server
                                    val request = GraphQLRequest(
                                        query = QueryBuilder.registerUser(publicKeyString)
                                    )

                                    val service = GraphQLService.create()
                                    val response = service.registerUser(request)

                                    if (response.isSuccessful) {
                                        val authPayload = response.body()?.data?.registerUser
                                        if (authPayload != null && authPayload.jwt.isNotEmpty()) {
                                            Log.d(TAG, "Received JWT successfully")

                                            // Validate JWT before proceeding
                                            if (!JwtUtils.isJwtValid(authPayload.jwt)) {
                                                throw Exception("Server returned invalid JWT")
                                            }

                                            // Generate QR code with JWT
                                            val bitmap = Controller.generateQRCode(authPayload.jwt)

                                            if (bitmap != null) {
                                                withContext(Dispatchers.IO) {
                                                    // Save key alias and JWT to SharedPreferences
                                                    PrefsHelper.saveKeyAlias(context, keyAlias)
                                                    PrefsHelper.saveRegistrationJwt(context, authPayload.jwt)

                                                    // Save QR code with validation
                                                    Controller.saveBitmapToInternalStorage(
                                                        context,
                                                        bitmap,
                                                        IDENTITY_FILE_NAME
                                                    )
                                                    Controller.saveBitmapToGallery(
                                                        context,
                                                        bitmap,
                                                        IDENTITY_FILE_NAME
                                                    )
                                                }
                                                identityBitmap = bitmap
                                                val remaining = JwtUtils.getJwtTimeRemaining(authPayload.jwt)
                                                timeRemaining = JwtUtils.formatTimeRemaining(remaining)

                                                Toast.makeText(
                                                    context,
                                                    "✅ Registration successful!",
                                                    Toast.LENGTH_SHORT
                                                ).show()
                                            }
                                        } else {
                                            throw Exception("Server returned invalid response")
                                        }
                                    } else {
                                        val errorBody = response.errorBody()?.string()
                                        Log.e(TAG, "Registration failed: $errorBody")
                                        throw Exception("Registration failed: ${errorBody ?: "Unknown error"}")
                                    }
                                } catch (e: Exception) {
                                    Log.e(TAG, "Error during registration", e)
                                    Toast.makeText(
                                        context,
                                        "❌ Registration error: ${e.message}",
                                        Toast.LENGTH_LONG
                                    ).show()
                                } finally {
                                    isLoading = false
                                }
                            }
                        },
                        enabled = !isLoading
                    ) {
                        Text(if (isLoading) "Creating identity..." else "Be Anonymous!")
                    }

                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "This will create your unique anonymous identity.\nYour private key stays on your device - never shared!",
                        fontSize = 10.sp,
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "Already have an Identity?",
                        color = Color.Cyan,
                        modifier = Modifier.clickable {
                            context.startActivity(Intent(context, LoginActivity::class.java))
                        }
                    )
                }
            }
        }
    }

    private fun generateRSAKeyPair(alias: String): KeyPair? {
        return try {
            val keyPairGenerator = KeyPairGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_RSA,
                "AndroidKeyStore"
            )

            val spec = KeyGenParameterSpec.Builder(
                alias,
                KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY
            )
                .setDigests(KeyProperties.DIGEST_SHA256)
                .setSignaturePaddings(KeyProperties.SIGNATURE_PADDING_RSA_PKCS1)
                .setKeySize(2048)
                .setUserAuthenticationRequired(false)
                .build()

            keyPairGenerator.initialize(spec)
            keyPairGenerator.generateKeyPair()
        } catch (e: Exception) {
            Log.e(TAG, "Key generation failed", e)
            null
        }
    }
}