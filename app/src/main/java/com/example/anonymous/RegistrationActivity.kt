package com.example.anonymous

import android.util.Log
import android.widget.Toast
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
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import com.example.anonymous.i2p.I2pdDaemon
import com.example.anonymous.i2p.I2pdJNI

private const val TAG = "RegistrationScreen"

@Composable
fun RegistrationScreen(
    navController: NavHostController,
    viewModel: RegistrationViewModel = viewModel()
) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsState()

    LaunchedEffect(Unit) {
        try {
            val abi = I2pdJNI.getABICompiledWith()
            Log.i("TEST", "Native ABI: $abi")
        } catch (e: Exception) {
            Log.e("TEST", "Native load failed!", e)
        }
    }

    // Load any pre-existing identity once
    LaunchedEffect(Unit) {
        viewModel.loadExistingIdentityIfAny(context)
    }

    // Wire the daemon state listener
    val daemon = remember { I2pdDaemon.getInstance(context) }
    DisposableEffect(daemon) {
        val listener = object : I2pdDaemon.DaemonStateListener {
            override fun onStateChanged(state: I2pdDaemon.DaemonState) {
                viewModel.onDaemonStateChanged(state)
            }
            override fun onError(error: String) {
                viewModel.onDaemonError(error)
            }
        }
        daemon.addListener(listener)
        onDispose { daemon.removeListener(listener) }
    }

    // Navigate to main when identity becomes ready
    LaunchedEffect(uiState.isIdentityReady) {
        if (uiState.isIdentityReady) {
            Toast.makeText(context, "Identity created!", Toast.LENGTH_SHORT).show()
            // CRITICAL: Navigate to main WITHOUT killing this composable's process
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
                // Identity ready - show QR
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
                Button(onClick = {
                    navController.navigate("main") {
                        popUpTo("registration") { inclusive = true }
                    }
                }) {
                    Icon(Icons.Default.ArrowForward, contentDescription = "Continue")
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Continue")
                }
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = "Your Anonymous identity is ready!\nShare this QR so others can reach you.",
                    fontSize = 12.sp,
                    textAlign = TextAlign.Center,
                    color = Color.Gray
                )

            } else {
                // Creation UI
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