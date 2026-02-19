package com.example.anonymous

import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
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
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.example.anonymous.controller.Controller
import com.example.anonymous.datastore.ChatCustomizationSettings
import com.example.anonymous.datastore.CommunityCustomizationSettings
import com.example.anonymous.datastore.getChatCustomizationSettings
import com.example.anonymous.datastore.getCommunityCustomizationSettings
import com.example.anonymous.i2p.I2pdDaemon
import com.example.anonymous.i2p.SAMClient
import com.example.anonymous.messaging.MessageManager
import com.example.anonymous.network.model.Contact
import com.example.anonymous.repository.ContactRepository
import com.example.anonymous.ui.theme.AnonymousTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    @RequiresApi(Build.VERSION_CODES.VANILLA_ICE_CREAM)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.isFrameRatePowerSavingsBalanced = false

        enableEdgeToEdge()
        setContent {
            AnonymousTheme {
                val navController = rememberNavController()

                NavHost(navController = navController, startDestination = "splash") {
                    composable("splash") {
                        SplashScreen(navController)
                    }
                    composable("registration") {
                        RegistrationScreen(navController)
                    }
                    composable("main") {
                        MainScreen(navController)
                    }
                }
            }
        }
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

    // Check identity and navigate automatically after animation
    LaunchedEffect(showButton) {
        if (showButton) {
            delay(500)
            // Check if identity exists
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
                    ) {
                        scope.launch {
                            val identityFileName = "qr_identity.png"
                            val identityBitmap = withContext(Dispatchers.IO) {
                                Controller.checkIdentityExists(context, identityFileName)
                            }

                            if (identityBitmap != null) {
                                navController.navigate("main") {
                                    popUpTo("splash") { inclusive = true }
                                }
                            } else {
                                navController.navigate("registration") {
                                    popUpTo("splash") { inclusive = true }
                                }
                            }
                        }
                    }
                    .padding(horizontal = 32.dp, vertical = 16.dp)
                    .background(MaterialTheme.colorScheme.primary, shape = MaterialTheme.shapes.medium)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.PlayArrow, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Enter")
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(navController: NavHostController) {
    val context = LocalContext.current

    // Retrieve customization settings from DataStore
    val chatSettingsFlow = getChatCustomizationSettings(context)
    val chatCustomizationSettings by chatSettingsFlow.collectAsState(
        initial = ChatCustomizationSettings()
    )
    val communitySettingsFlow = getCommunityCustomizationSettings(context)
    val communityCustomizationSettings by communitySettingsFlow.collectAsState(
        initial = CommunityCustomizationSettings()
    )

    val selectedTab = remember { mutableStateOf(Icons.Default.Home) }
    var showCustomHome by remember { mutableStateOf(false) }
    var accountIcon by remember { mutableStateOf(Icons.Default.Person) }
    var iconScale by remember { mutableStateOf(1f) }
    val animatedIconScale by animateFloatAsState(targetValue = iconScale)
    val coroutineScope = rememberCoroutineScope()
    var showingAuthor by remember { mutableStateOf(false) }

    // I2P State - reuse existing daemon from Registration
    val i2pdDaemon = remember { I2pdDaemon.getInstance(context) }
    val samClient = remember { SAMClient.getInstance() }
    val messageManager = remember { MessageManager.getInstance(context) }
    val contactRepository = remember { ContactRepository.getInstance(context) }

    var i2pState by remember { mutableStateOf(I2pdDaemon.DaemonState.STOPPED) }
    var isI2PReady by remember { mutableStateOf(false) }
    var showI2PInitializing by remember { mutableStateOf(false) }

    // State for navigation
    var selectedContact by remember { mutableStateOf<Contact?>(null) }
    var selectedCommunity by remember { mutableStateOf<CommunityInfo?>(null) }

    // Initialize messaging only - I2P already running from Registration
    LaunchedEffect(Unit) {
        Log.i("MainScreen", "Checking I2P status...")

        // Check if daemon is already ready (started in Registration)
        if (i2pdDaemon.isReady()) {
            Log.i("MainScreen", "I2P already ready from Registration")
            isI2PReady = true

            // Initialize messaging
            val msgInitSuccess = messageManager.initialize()
            if (msgInitSuccess) {
                val session = samClient.getActiveSessions().firstOrNull()
                session?.let {
                    messageManager.startListening(it.id)
                }
            }
        } else if (i2pdDaemon.isRunning()) {
            Log.i("MainScreen", "I2P running but not ready, waiting...")
            showI2PInitializing = true
            val ready = withContext(Dispatchers.IO) { i2pdDaemon.waitForReady() }
            isI2PReady = ready
            showI2PInitializing = false

            if (ready) {
                val msgInitSuccess = messageManager.initialize()
                if (msgInitSuccess) {
                    val session = samClient.getActiveSessions().firstOrNull()
                    session?.let { messageManager.startListening(it.id) }
                }
            }
        } else {
            Log.e("MainScreen", "I2P not running! Should have been started in Registration")
            // This shouldn't happen if flow is correct
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            Log.d("MainScreen", "MainScreen disposed but I2P keeps running")
        }
    }

    if (showI2PInitializing) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator()
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = "Connecting to I2P network...",
                    style = MaterialTheme.typography.bodyMedium
                )
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
                                Icon(
                                    imageVector = accountIcon,
                                    contentDescription = "Account",
                                    modifier = Modifier.scale(animatedIconScale)
                                )
                            }
                        }
                    )
                },
                bottomBar = {
                    BottomAppBar {
                        IconButton(
                            onClick = { selectedTab.value = Icons.Default.Home },
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(
                                Icons.Default.Home,
                                contentDescription = "Home",
                                modifier = Modifier.size(26.dp),
                                tint = if (selectedTab.value == Icons.Default.Home) Color.White else Color.DarkGray
                            )
                        }
                        IconButton(
                            onClick = { selectedTab.value = Icons.Default.Search },
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(
                                Icons.Default.Search,
                                contentDescription = "Search",
                                modifier = Modifier.size(26.dp),
                                tint = if (selectedTab.value == Icons.Default.Search) Color.White else Color.DarkGray
                            )
                        }
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .padding(16.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            IconButton(onClick = { selectedTab.value = Icons.Default.Add }) {
                                Icon(
                                    Icons.Default.Add,
                                    contentDescription = "Add",
                                    tint = if (selectedTab.value == Icons.Default.Add) Color.White else Color.Gray
                                )
                            }
                        }
                        IconButton(
                            onClick = { selectedTab.value = Icons.Default.PlayArrow },
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(
                                Icons.Default.PlayArrow,
                                contentDescription = "User",
                                modifier = Modifier.size(26.dp),
                                tint = if (selectedTab.value == Icons.Default.PlayArrow) Color.White else Color.DarkGray
                            )
                        }
                        IconButton(
                            onClick = { selectedTab.value = Icons.Default.Person },
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(
                                Icons.Default.Person,
                                contentDescription = "Settings",
                                modifier = Modifier.size(26.dp),
                                tint = if (selectedTab.value == Icons.Default.Person) Color.White else Color.DarkGray
                            )
                        }
                    }
                }
            ) { innerPadding ->
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding),
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