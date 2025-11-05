package com.example.anonymous

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.example.anonymous.datastore.*
import com.example.anonymous.ui.theme.AnonymousTheme
import kotlinx.coroutines.launch

class ChatCustomizationActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            AnonymousTheme {
                ChatCustomizationScreen()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatCustomizationScreen() {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

// Get current settings
    val chatSettingsFlow = getChatCustomizationSettings(context)
    val chatSettings by chatSettingsFlow.collectAsState(initial = ChatCustomizationSettings())

    var sentColor by remember(chatSettings) { mutableStateOf(chatSettings.sentBubbleColor) }
    var receivedColor by remember(chatSettings) { mutableStateOf(chatSettings.receivedBubbleColor) }
    var rightAlign by remember(chatSettings) { mutableStateOf(chatSettings.isSentRightAligned) }

    // Local state for not saved UI
    var localSentColor by remember { mutableStateOf(sentColor) }
    var localReceivedColor by remember { mutableStateOf(receivedColor) }
    var localRightAlign by remember { mutableStateOf(rightAlign) }

    // Update local state when values update
    LaunchedEffect(chatSettings) {
        localSentColor = chatSettings.sentBubbleColor
        localReceivedColor = chatSettings.receivedBubbleColor
        localRightAlign = chatSettings.isSentRightAligned
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Chat Customization") },
                navigationIcon = {
                    IconButton(onClick = { (context as android.app.Activity).finish() }) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Text("Sent Message Color", style = MaterialTheme.typography.titleMedium)
            ColorPicker(
                selectedColor = localSentColor,
                onColorSelected = { localSentColor = it }
            )

            Spacer(modifier = Modifier.height(16.dp))

            Text("Received Message Color", style = MaterialTheme.typography.titleMedium)
            ColorPicker(
                selectedColor = localReceivedColor,
                onColorSelected = { localReceivedColor = it }
            )

            Spacer(modifier = Modifier.height(16.dp))

            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Checkbox(
                    checked = localRightAlign,
                    onCheckedChange = { localRightAlign = it }
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text("Right-align sent messages", style = MaterialTheme.typography.bodyLarge)
            }

            Spacer(modifier = Modifier.height(32.dp))

            Button(
                onClick = {
                    coroutineScope.launch {
                        saveChatCustomizationSettings(
                            context,
                            ChatCustomizationSettings(
                                sentBubbleColor = localSentColor,
                                receivedBubbleColor = localReceivedColor,
                                isSentRightAligned = localRightAlign
                            )
                        )
                        (context as android.app.Activity).finish()
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Save Settings")
            }
        }
    }
}

@Composable
fun ColorPicker(selectedColor: Color, onColorSelected: (Color) -> Unit) {
    val colors = listOf(
        Color(0xFF4CAF50), // Green
        Color(0xFF2196F3), // Blue
        Color(0xFF9C27B0), // Purple
        Color(0xFFFF9800), // Orange
        Color(0xFFF44336), // Red
        Color(0xFF607D8B), // Gray
        Color(0xFFFFEB3B), // Yellow
        Color(0xFF00BCD4), // Cyan
        Color(0xFF8BC34A), // Light Green
        Color(0xFFFF5722), // Deep Orange
        Color(0xFF795548), // Brown
        Color(0xFF9E9E9E)  // Grey
    )

    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        items(colors) { color ->
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .background(color, CircleShape)
                    .clickable { onColorSelected(color) }
                    .border(
                        width = 2.dp,
                        color = if (color == selectedColor) MaterialTheme.colorScheme.primary else Color.Transparent,
                        shape = CircleShape
                    ),
                contentAlignment = Alignment.Center
            ) {
                if (color == selectedColor) {
                    Icon(
                        imageVector = Icons.Default.Check,
                        contentDescription = "Selected",
                        tint = if (color == Color(0xFFFFEB3B)) Color.Black else Color.White
                    )
                }
            }
        }
    }
}