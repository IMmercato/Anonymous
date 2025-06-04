package com.example.anonymous

import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.anonymous.controller.Controller
import com.example.anonymous.database.Database
import com.example.anonymous.database.UserData
import com.example.anonymous.database.UserRepository
import com.example.anonymous.network.GraphQLRequest
import com.example.anonymous.ui.theme.AnonymousTheme
import kotlinx.coroutines.*
import android.util.Base64
import android.view.WindowManager
import androidx.core.view.WindowCompat
import com.example.anonymous.network.GraphQLService
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.util.UUID

@OptIn(DelicateCoroutinesApi::class)
class RegistrationActivity : ComponentActivity() {
    private lateinit var userRepository: UserRepository
    private lateinit var graphQLService: GraphQLService

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.setFlags(
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        )

        // Launch an IO coroutine to initialize your datasource
        GlobalScope.launch(Dispatchers.IO) {
            try {
                val db = Database.dataSource
                // Now that the datasource is established, you might want to update UI
                withContext(Dispatchers.Main) {
                    // Update your UI safely here if needed
                }
            } catch (e: Exception) {
                e.printStackTrace()
                // Optionally, report the error on the main thread.
            }
        }

        setContent {
            AnonymousTheme {
                RegistrationScreen()
            }
        }
    }

    @Composable
    fun RegistrationScreen() {
        val context = LocalContext.current
        val identityFileName = "qr_identity.png"
        var identityBitmap by remember { mutableStateOf<Bitmap?>(null) }
        val coroutineScope = rememberCoroutineScope()

        // Load stored identity (if exists)
        LaunchedEffect(Unit) {
            identityBitmap = Controller.checkIdentityExists(context, identityFileName)
        }

        // Only generate a new identity if one does not exist
        val newUUID = remember { if (identityBitmap == null) UUID.randomUUID() else null }
        val keyPair = remember { newUUID?.let { generateRSAKeyPair() } }

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
                    Spacer(modifier = Modifier.height(16.dp))
                    FloatingActionButton(
                        onClick = {
                            context.startActivity(Intent(context, LoginActivity::class.java))
                        }
                    ) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "Your anonymous identity already exists.\nKeep your QR code safe!",
                        fontSize = 10.sp,
                        textAlign = TextAlign.Center
                    )
                } else {
                    Button(onClick = {
                        coroutineScope.launch {
                            val uuidString = newUUID.toString()
                            val publicKeyString = keyPair?.public?.encoded?.let {
                                Base64.encodeToString(it, Base64.NO_WRAP)
                            } ?: ""

                            val fingerprint = keyPair?.public?.encoded?.let { getPublicKeyFingerprint(it) } ?: ""

                            registerUserWithBackend(uuidString, publicKeyString)

                            val payload = """{"uuid":"$uuidString", "fp":"$fingerprint"}"""
                            val bitmap = Controller.generateQRCode(payload)

                            if (bitmap != null) {
                                withContext(Dispatchers.IO) {
                                    Controller.saveBitmapToInternalStorage(context, bitmap, identityFileName)
                                    Controller.saveBitmapToGallery(context, bitmap, identityFileName)
                                }
                                identityBitmap = bitmap
                            }
                        }
                    }) {
                        Text("Be Anonymous!")
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "This is your unique identity on Anonymous.\nWe will save a copy on your device – please keep it safe!",
                        fontSize = 10.sp,
                        textAlign = TextAlign.Center
                    )
                }
            }
        }
    }

    fun registerUserWithBackend(uuid: String, publicKey: String) {
        GlobalScope.launch(Dispatchers.IO) {
            try {
                val userData = UserData(id = 0, uuid = uuid)
                userRepository.createUser(userData)

                val mutation = """
                    mutation registerUser(${'$'}input: RegisterUserInput!){
                        registerUser(input: ${'$'}input) {
                            id
                            uuid
                            publicKey
                        }
                    }
                """.trimIndent()

                val variables = mapOf(
                    "input" to mapOf(
                        "uuid" to uuid,
                        "publicKey" to publicKey
                    )
                )

                val requestBody = GraphQLRequest(query = mutation, variables = variables)

                val response = graphQLService.sendMutation(requestBody)

                if (!response.isSuccessful) {
                    println("GraphQL request failed: ${response.errorBody()?.string()}")
                }

            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun generateRSAKeyPair(alias: String = "registration_key"): KeyPair? {
        return try {
            val keyPairGenerator = KeyPairGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_RSA,
                "AndroidKeyStore"
            )

            val spec = KeyGenParameterSpec.Builder(
                alias,
                KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_ENCRYPT
            )
                .setDigests(KeyProperties.DIGEST_SHA256)
                .setSignaturePaddings(KeyProperties.SIGNATURE_PADDING_RSA_PKCS1)
                .build()

            keyPairGenerator.initialize(spec)
            keyPairGenerator.generateKeyPair()
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    fun getPublicKeyFingerprint(publicKey: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(publicKey)
        return digest.joinToString("") { "%02x".format(it) }
    }
}