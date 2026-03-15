package com.example.anonymous

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresApi
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.example.anonymous.controller.Controller
import com.example.anonymous.datastore.ChatCustomizationSettings
import com.example.anonymous.datastore.CommunityCustomizationSettings
import com.example.anonymous.datastore.getChatCustomizationSettings
import com.example.anonymous.datastore.getCommunityCustomizationSettings
import com.example.anonymous.i2p.I2pdService
import com.example.anonymous.i2p.SAMClient
import com.example.anonymous.messaging.MessageManager
import com.example.anonymous.network.model.Contact
import com.example.anonymous.ui.theme.AnonymousTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.core.net.toUri
import com.example.anonymous.network.model.Community

class MainActivity : ComponentActivity() {

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            Log.i("MainActivity", "Notification permission granted")
        } else {
            Log.w("MainActivity", "Notification permission DENIED — foreground service may be killed by Android")
        }
        startI2pdService()
    }

    @RequiresApi(Build.VERSION_CODES.VANILLA_ICE_CREAM)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.isFrameRatePowerSavingsBalanced = false
        enableEdgeToEdge()

        requestBatteryOptimizationExemption()
        requestNotificationPermissionThenStartService()

        setContent {
            AnonymousTheme {
                val navController = rememberNavController()
                NavHost(navController = navController, startDestination = "splash") {
                    composable("splash") { SplashScreen(navController) }
                    composable("registration") { RegistrationScreen(navController) }
                    composable("main") { MainScreen(navController) }
                }
            }
        }
    }

    private fun requestBatteryOptimizationExemption() {
        try {
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                Log.i("MainActivity", "Requesting battery optimization exemption...")
                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = "package:$packageName".toUri()
                }
                startActivity(intent)
            } else {
                Log.i("MainActivity", "Battery optimization already exempted ✓")
            }
        } catch (e: Exception) {
            Log.w("MainActivity", "Could not request battery exemption: ${e.message}")
        }
    }

    private fun requestNotificationPermissionThenStartService() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                this, Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED

            if (granted) {
                Log.i("MainActivity", "Notification permission already granted")
                startI2pdService()
            } else {
                Log.i("MainActivity", "Requesting notification permission...")
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        } else {
            startI2pdService()
        }
    }

    private fun startI2pdService() {
        Log.i("MainActivity", "Starting I2pdService from MainActivity")
        I2pdService.start(this)
    }

    override fun onStop() {
        super.onStop()
        Log.i("MainActivity", "onStop - NOT stopping I2P")
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.i("MainActivity", "onDestroy - NOT stopping I2P")
    }
}

@Composable
fun SplashScreen(navController: NavHostController) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var showLogo by remember { mutableStateOf(true) }
    var showSlogan by remember { mutableStateOf(false) }
    var showText by remember { mutableStateOf(false) }
    var showButton by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        delay(1500)
        showLogo = false
        delay(1000)
        showSlogan = true
        delay(500)
        showText = true
        delay(1000)
        showButton = true
    }

    LaunchedEffect(showButton) {
        if (showButton) {
            delay(500)
            val identityFileName = "qr_identity.png"
            val identityBitmap = withContext(Dispatchers.IO) {
                Controller.checkIdentityExists(context, identityFileName)
            }

            if (identityBitmap != null) {
                Log.i("SplashScreen", "Identity found, going to main")
                navController.navigate("main") {
                    popUpTo("splash") { inclusive = true }
                }
            } else {
                Log.i("SplashScreen", "No identity, going to registration")
                navController.navigate("registration") {
                    popUpTo("splash") { inclusive = true }
                }
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        AnimatedVisibility(
            visible = showLogo,
            exit = fadeOut(animationSpec = tween(800)),
            modifier = Modifier.align(Alignment.Center)
        ) {
            Text(
                text = "Anonymous",
                style = MaterialTheme.typography.headlineLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
        }

        Column(
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(
                    start = 16.dp,
                    top = 16.dp + WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
                )
        ) {
            AnimatedVisibility(
                visible = showSlogan,
                enter = fadeIn(animationSpec = tween(1000)) + scaleIn(initialScale = 0.9f)
            ) {
                Text(
                    text = "Your Identity,\nYour Secret",
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            AnimatedVisibility(
                visible = showText,
                enter = fadeIn(animationSpec = tween(1000))
            ) {
                Text(
                    text = "Register now and embrace anonymity.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        }

        AnimatedVisibility(
            visible = showButton,
            enter = fadeIn(animationSpec = tween(1000)),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(
                    bottom = 16.dp + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
                )
        ) {
            Box(
                modifier = Modifier
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) { /* handled by LaunchedEffect auto-navigate */ }
            ) {
                Text(
                    text = "Get Started",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(navController: NavHostController) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    val samClient = remember { SAMClient.getInstance() }
    val messageManager = remember { MessageManager.getInstance(context) }

    var connectionError by remember { mutableStateOf<String?>(null) }
    var selectedContact by remember { mutableStateOf<Contact?>(null) }
    var selectedCommunity by remember { mutableStateOf<Community?>(null) }
    var selectedTab = remember { mutableStateOf(Icons.Default.Home) }
    var showCustomHome by remember { mutableStateOf(false) }
    var showingAuthor by remember { mutableStateOf(false) }
    var accountIcon by remember { mutableStateOf(Icons.Default.Person) }
    var iconScale by remember { mutableStateOf(1f) }
    val animatedIconScale by animateFloatAsState(
        targetValue = iconScale,
        animationSpec = tween(200),
        label = "iconScale"
    )

    val chatCustomizationSettings by getChatCustomizationSettings(context)
        .collectAsState(initial = ChatCustomizationSettings())
    val communityCustomizationSettings by getCommunityCustomizationSettings(context)
        .collectAsState(initial = CommunityCustomizationSettings())

    var isConnecting by remember { mutableStateOf(true) }
    var retryKey by remember { mutableStateOf(0) }

    var hasConnectedOnce by remember { mutableStateOf(false) }

    LaunchedEffect(retryKey) {
        isConnecting = true
        connectionError = null

        val existingSession = samClient.getActiveSessions().lastOrNull()
        if (existingSession != null) {
            Log.i("MainScreen", "Reusing registration session: ${existingSession.id}")
            messageManager.startListening(existingSession.id)
            return@LaunchedEffect
        }

        val prefs = context.getSharedPreferences("i2p_identity", Context.MODE_PRIVATE)
        val savedPrivateKey = prefs.getString("sam_priv_key", null)
        if (savedPrivateKey == null) {
            connectionError = "No identity found — please re-register."
            isConnecting = false
            return@LaunchedEffect
        }

        var attempts = 0
        while (attempts < 60) {
            delay(2000)
            val ok = withContext(Dispatchers.IO) {
                try { samClient.connect() } catch (_: Exception) { false }
            }
            if (ok) break
            attempts++
        }
        if (attempts >= 60) {
            connectionError = "I2P not ready after 2 minutes"
            isConnecting = false
            return@LaunchedEffect
        }

        val session = withContext(Dispatchers.IO) {
            samClient.createStreamSession(savedPrivateKey = savedPrivateKey).getOrNull()
        }
        if (session == null) {
            connectionError = "Failed to restore I2P session"
            isConnecting = false
            return@LaunchedEffect
        }

        Log.i("MainScreen", "Recreated session after i2pd restart: ${session.id}")
        messageManager.startListening(session.id)
    }

    val connectionState by messageManager.connectionState.collectAsState()
    LaunchedEffect(connectionState) {
        when (connectionState) {
            MessageManager.ConnectionState.Connected -> {
                isConnecting = false
                connectionError = null
            }
            MessageManager.ConnectionState.Disconnected -> {
                if (hasConnectedOnce) {
                    connectionError = "I2P connection lost"
                    isConnecting = false
                }
            }
            MessageManager.ConnectionState.Error -> {
                connectionError = "I2P session lost — tap Retry"
                isConnecting = false
            }
            else -> {}
        }
    }

    if (isConnecting) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator()
                Spacer(modifier = Modifier.height(16.dp))
                Text("Connecting to I2P network...", style = MaterialTheme.typography.bodyMedium)
                connectionError?.let {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("Error: $it", color = Color.Red, fontSize = 12.sp)
                }
            }
        }
    } else if (connectionError != null) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Failed to connect", color = Color.Red)
                Spacer(modifier = Modifier.height(8.dp))
                Button(onClick = {
                    retryKey++
                }) {
                    Text("Retry")
                }
            }
        }
    } else {
        if (selectedCommunity != null) {
            CommunityScreen(
                community = selectedCommunity!!,
                onBack = { selectedCommunity = null },
                customization = communityCustomizationSettings
            )
        } else if (selectedContact != null) {
            ChatScreen(
                contactId = selectedContact!!.b32Address,
                contactName = selectedContact!!.name,
                customizationSettings = chatCustomizationSettings,
                onBack = { selectedContact = null }
            )
        } else {
            Scaffold(
                modifier = Modifier.fillMaxSize(),
                topBar = {
                    TopAppBar(
                        title = { Text("Anonymous") },
                        actions = {
                            IconButton(onClick = {
                                iconScale = 1.2f
                                coroutineScope.launch {
                                    delay(200)
                                    iconScale = 1f
                                }
                                if (accountIcon == Icons.Default.Person) {
                                    showCustomHome = true
                                    accountIcon = Icons.Default.Face
                                } else {
                                    showCustomHome = false
                                    accountIcon = Icons.Default.Person
                                }
                            }) {
                                Icon(imageVector = accountIcon, contentDescription = "Account",
                                    modifier = Modifier.scale(animatedIconScale))
                            }
                        }
                    )
                },
                bottomBar = {
                    BottomAppBar {
                        IconButton(onClick = { selectedTab.value = Icons.Default.Home },
                            modifier = Modifier.weight(1f)) {
                            Icon(Icons.Default.Home, contentDescription = "Home",
                                modifier = Modifier.size(26.dp),
                                tint = if (selectedTab.value == Icons.Default.Home) Color.White else Color.DarkGray)
                        }
                        IconButton(onClick = { selectedTab.value = Icons.Default.Search },
                            modifier = Modifier.weight(1f)) {
                            Icon(Icons.Default.Search, contentDescription = "Search",
                                modifier = Modifier.size(26.dp),
                                tint = if (selectedTab.value == Icons.Default.Search) Color.White else Color.DarkGray)
                        }
                        Box(modifier = Modifier.weight(1f).padding(16.dp),
                            contentAlignment = Alignment.Center) {
                            IconButton(onClick = { selectedTab.value = Icons.Default.Add }) {
                                Icon(Icons.Default.Add, contentDescription = "Add",
                                    tint = if (selectedTab.value == Icons.Default.Add) Color.White else Color.Gray)
                            }
                        }
                        IconButton(onClick = { selectedTab.value = Icons.Default.PlayArrow },
                            modifier = Modifier.weight(1f)) {
                            Icon(Icons.Default.PlayArrow, contentDescription = "User",
                                modifier = Modifier.size(26.dp),
                                tint = if (selectedTab.value == Icons.Default.PlayArrow) Color.White else Color.DarkGray)
                        }
                        IconButton(onClick = { selectedTab.value = Icons.Default.Person },
                            modifier = Modifier.weight(1f)) {
                            Icon(Icons.Default.Person, contentDescription = "Settings",
                                modifier = Modifier.size(26.dp),
                                tint = if (selectedTab.value == Icons.Default.Person) Color.White else Color.DarkGray)
                        }
                    }
                }
            ) { innerPadding ->
                Column(
                    modifier = Modifier.fillMaxSize().padding(innerPadding),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    when (selectedTab.value) {
                        Icons.Default.Home -> HomeScreen(
                            isCommunity = showCustomHome,
                            onOpenChat = { contact -> selectedContact = contact },
                            onOpenCommunity = { community -> selectedCommunity = community }
                        )
                        Icons.Default.Search -> SearchScreen()
                        Icons.Default.Add -> PubblicScreen()
                        Icons.Default.PlayArrow -> {
                            if (showingAuthor) {
                                AuthorScreen(onBack = { showingAuthor = false })
                            } else {
                                XScreen(onSwipeRight = { showingAuthor = true })
                            }
                        }
                        Icons.Default.Person -> SettingsScreen()
                    }
                }
            }
        }
    }
}