package com.example.anonymous

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.anonymous.datastore.ChatCustomizationSettings
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// Data model for an individual chat message.
data class ChatMessageModel(
    val id: Long = System.currentTimeMillis(), // Unique ID based on timestamp.
    val content: String,
    val isSent: Boolean  // true for sent messages, false for received.
)

sealed class Icontype {
    data class Vector(val icon: ImageVector) : Icontype()
    data class  Drawable(val resId: Int) : Icontype()
}

// Composable function for the chat screen.
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    contactName: String,
    customizationSettings: ChatCustomizationSettings,
    onBack: () -> Unit
) {
    var message by remember { mutableStateOf("") }
    val messages = remember { mutableStateListOf(ChatMessageModel(content = "Hi", isSent = false)) }
    val selecteds = remember { mutableStateOf(setOf<Icontype>()) }
    val coroutineScope = rememberCoroutineScope()
    val iconList = listOf(
        Icontype.Vector(Icons.Default.Favorite),
        Icontype.Vector(Icons.Default.Face),
        Icontype.Vector(Icons.Default.PlayArrow),
        Icontype.Drawable(R.drawable.code_24px)
    )
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Chat with $contactName") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(16.dp)
                .imePadding(),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(messages) { msg ->
                    ChatMessage(message = msg, settings = customizationSettings)
                }
            }
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(8.dp),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(8.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .height(50.dp)
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                            .padding(bottom = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        iconList.forEach { icon ->
                            IconButton(onClick = {
                                selecteds.value = if (icon in selecteds.value) {
                                    selecteds.value - icon
                                } else {
                                    selecteds.value + icon
                                }
                            }) {
                                when (icon) {
                                    is Icontype.Vector -> Icon(
                                        imageVector = icon.icon,
                                        contentDescription = null,
                                        tint = if (icon in selecteds.value) Color.White else Color.DarkGray
                                    )
                                    is Icontype.Drawable -> Icon(
                                        painter = painterResource(id = icon.resId),
                                        contentDescription = null,
                                        tint = if (icon in selecteds.value) Color.White else Color.DarkGray
                                    )
                                }
                            }
                        }
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        BasicTextField(
                            value = message,
                            onValueChange = { message = it },
                            modifier = Modifier.weight(1f).padding(4.dp),
                            textStyle = MaterialTheme.typography.bodyMedium.copy(
                                color = MaterialTheme.colorScheme.onSurface
                            ),
                            decorationBox = { inner ->
                                Box {
                                    if (message.isEmpty()) {
                                        Text(
                                            "Type a massage...",
                                            style = MaterialTheme.typography.bodyMedium.copy(
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        )
                                    }
                                    inner()
                                }
                            }
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        IconButton(
                            onClick = {
                                if (message.isNotBlank()) {
                                    val sentMessage =
                                        ChatMessageModel(content = "You: $message", isSent = true)
                                    messages.add(sentMessage)

                                    coroutineScope.launch {
                                        delay(1000)
                                        val reply = ChatMessageModel(
                                            content = "$contactName: Got it!",
                                            isSent = false
                                        )
                                        messages.add(reply)
                                    }
                                    message = ""
                                }
                            }
                        ) {
                            Icon(Icons.Default.Send, contentDescription = "Send")
                        }
                    }
                }
            }
        }
    }
}

// Chat bubble composable
@Composable
fun ChatMessage(message: ChatMessageModel, settings: ChatCustomizationSettings) {
    val bubbleColor = if (message.isSent) settings.sentBubbleColor else settings.receivedBubbleColor
    val alignment = if (message.isSent && settings.isSentRightAligned) Alignment.CenterEnd else Alignment.CenterStart

    Box(
        modifier = Modifier.fillMaxWidth(),
        contentAlignment = alignment
    ) {
        Surface(
            shape = RoundedCornerShape(
                topStart = 12.dp,
                topEnd = 12.dp,
                bottomStart = if (message.isSent) 12.dp else 4.dp,
                bottomEnd = if (message.isSent) 4.dp else 12.dp
            ),
            color = bubbleColor,
            shadowElevation = 2.dp,
            modifier = Modifier.widthIn(min = 50.dp, max = 250.dp)
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
            ) {
                Text(
                    text = message.content,
                    style = MaterialTheme.typography.bodyLarge.copy(
                        color = Color.Black,
                        fontSize = 14.sp
                    ),
                    modifier = Modifier.padding(bottom = 4.dp)
                )

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.End,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "00:00",
                        style = MaterialTheme.typography.bodyMedium.copy(
                            color = Color.Gray,
                            fontSize = 10.sp
                        )
                    )
                    if (!message.isSent) {
                        Spacer(modifier = Modifier.width(4.dp))
                        LoadingDots()
                    }
                }
            }
        }
    }
}

@Composable
fun LoadingDots() {
    val infiniteTransition = rememberInfiniteTransition()
    val dotAlpha = List(3) { index ->
        infiniteTransition.animateFloat(
            initialValue = 0.3f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(500, delayMillis = index * 200, easing = LinearEasing),
                repeatMode = RepeatMode.Reverse
            )
        )
    }

    Row(
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        dotAlpha.forEach { alpha ->
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(Color.Gray.copy(alpha = alpha.value))
            )
        }
    }
}