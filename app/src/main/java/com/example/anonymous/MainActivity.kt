package com.example.anonymous

import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.background
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

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            AnonymousTheme {
                MainScreen()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen() {
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

    // I2P State
    val i2pdDaemon = remember { I2pdDaemon.getInstance(context) }
    val samClient = remember { SAMClient.getInstance() }
    val messageManager = remember { MessageManager.getInstance(context) }
    val contactRepository = remember { ContactRepository.getInstance(context) }

    var i2pState by remember { mutableStateOf(I2pdDaemon.DaemonState.STOPPED) }
    var isI2PReady by remember { mutableStateOf(false) }
    var showI2PInitializing by remember { mutableStateOf(false) }

    // Identity check
    val identityFileName = "qr_identity.png"
    var identityBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var isIdentityChecked by remember { mutableStateOf(false) }

    // State for navigation
    var selectedContact by remember { mutableStateOf<Contact?>(null) }
    var selectedCommunity by remember { mutableStateOf<CommunityInfo?>(null) }

    // Initialize I2P
    LaunchedEffect(Unit) {
        identityBitmap = Controller.checkIdentityExists(context, identityFileName)
        isIdentityChecked = true

        if (identityBitmap != null) {
            showI2PInitializing = true

            i2pdDaemon.addListener(object : I2pdDaemon.DaemonStateListener {
                override fun onStateChanged(state: I2pdDaemon.DaemonState) {
                    coroutineScope.launch(Dispatchers.Main) {
                        i2pState = state
                        when (state) {
                            I2pdDaemon.DaemonState.READY -> {
                                coroutineScope.launch {
                                    // Initialize SAM and MessageManger
                                    val samConnected = samClient.connect()
                                    if (samConnected) {
                                        val sessionResult = samClient.createStreamSession()
                                        if (sessionResult.isSuccess) {
                                            val session = sessionResult.getOrThrow()

                                            val existingIdentity = contactRepository.getMyIdentity()
                                            if (existingIdentity == null) {
                                                val identity = ContactRepository.MyIdentity(
                                                    b32Address = session.b32Address,
                                                    publicKey = session.destination,
                                                    privateKeyEncrypted = ""
                                                )
                                                contactRepository.saveMyIdentity(identity)
                                            }

                                            val msgInitSuccess = messageManager.initialize()
                                            isI2PReady = msgInitSuccess
                                            if (msgInitSuccess) {
                                                messageManager.startListening(session.id)
                                            }
                                        }
                                    }
                                    showI2PInitializing = false
                                }
                            }
                            I2pdDaemon.DaemonState.ERROR -> {
                                showI2PInitializing = false
                            }
                            else -> {}
                        }
                    }
                }

                override fun onError(error: String) {
                    showI2PInitializing = false
                }
            })

            i2pdDaemon.start()
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            Log.d("MainActivity", "MainActivity disposed but I2P keeps running")
        }
    }

    if (!isIdentityChecked || showI2PInitializing) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator()
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = if (!isIdentityChecked) "Checking identity..." else "Initializing I2P network...",
                    style = MaterialTheme.typography.bodyMedium
                )
                if (showI2PInitializing) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "State: ${i2pState.name}",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.Gray
                    )
                }
            }
        }
    } else {
        if (identityBitmap != null) {
            // User is logged in
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
        } else {
            Welcome()
        }
    }
}

@Composable
fun Welcome() {
    val context = LocalContext.current

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
            Button(onClick = {
                context.startActivity(Intent(context, RegistrationActivity::class.java))
            }) {
                Icon(imageVector = Icons.Default.PlayArrow, contentDescription = "Register")
                Spacer(modifier = Modifier.width(8.dp))
                Text("Register")
            }
        }
    }
}