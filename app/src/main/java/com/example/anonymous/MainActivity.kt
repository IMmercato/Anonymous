package com.example.anonymous

import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.BottomAppBar
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import com.example.anonymous.controller.Controller
import com.example.anonymous.datastore.ChatCustomizationSettings
import com.example.anonymous.datastore.CommunityCustomizationSettings
import com.example.anonymous.datastore.getChatCustomizationSettings
import com.example.anonymous.datastore.getCommunityCustomizationSettings
import com.example.anonymous.ui.theme.AnonymousTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Allow app drawing under system bars.
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.setFlags(
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        )
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

    // Retrieve customization settings from DataStore.
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

    val identityFileName = "qr_identity.png"
    var identityBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var isIdentityChecked by remember { mutableStateOf(false) }

    // Variables for selecting screens.
    var selectedContact by remember { mutableStateOf<String?>(null) }
    var selectedCommunity by remember { mutableStateOf<CommunityInfo?>(null) }

    // Asynchronous identity check.
    LaunchedEffect(Unit) {
        identityBitmap = Controller.checkIdentityExists(context, identityFileName)
        isIdentityChecked = true
    }

    if (!isIdentityChecked) {
        // Show a loading indicator until the identity check is complete.
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            CircularProgressIndicator()
        }
    } else {
        if (identityBitmap != null) {
            // User is logged in.
            if (selectedCommunity != null) {
                CommunityScreen(
                    community = selectedCommunity!!,
                    onBack = { selectedCommunity = null },
                    customization = communityCustomizationSettings
                )
            } else if (selectedContact != null) {
                ChatScreen(
                    contactName = selectedContact!!,
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
                                    // Toggle the custom home view.
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
                                    // 1) AuthorScreen hijacks the full area, onBack resets the flag
                                    AuthorScreen(onBack = { showingAuthor = false })
                                } else {
                                    // 2) XScreen now takes an onSwipeRight lambda
                                    XScreen(onSwipeRight = { showingAuthor = true })
                                }
                            }
                            Icons.Default.Person -> SettingsScreen()
                        }
                    }
                }
            }
        } else {
            // No user is logged in; show the registration screen.
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

        // 1. Centered Anonymous text
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

        // 2. Slogan and text at top-start with padding for status bar and start safe area
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

        // 3. Register button at bottom center with padding for navigation bar
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