package com.example.anonymous

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material3.Icon
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import com.example.anonymous.ui.theme.AnonymousTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
    val selectedTab = remember { mutableStateOf(Icons.Default.Home) }
    var showCustomHome by remember { mutableStateOf(false) }
    var accountIcon by remember { mutableStateOf(Icons.Default.Person) }
    var iconScale by remember { mutableStateOf(1f) }
    val animatedIconScale by animateFloatAsState(targetValue = iconScale)
    val coroutineScope = rememberCoroutineScope()
    // Chat or Community
    var selectedContact by remember { mutableStateOf<String?>(null) }
    var selectedCommunity by remember { mutableStateOf<CommunityInfo?>(null) }

    if (selectedCommunity != null) {
        CommunityScreen(
            community = selectedCommunity!!,
            onBack = { selectedCommunity = null }
        )
    }
    else if (selectedContact != null) {
        ChatScreen(contactName = selectedContact!!, onBack = { selectedContact = null })
    }
    else {
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
                    Icons.Default.PlayArrow -> XScreen()
                    Icons.Default.Person -> SettingsScreen()
                }
            }
        }
    }
}