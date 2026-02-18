package com.example.anonymous

import android.util.Log
import android.widget.Toast
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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.anonymous.datastore.ChatCustomizationSettings
import com.example.anonymous.i2p.I2pdDaemon
import com.example.anonymous.messaging.MessageManager
import com.example.anonymous.network.GraphQLCryptoService
import com.example.anonymous.network.model.Message
import com.example.anonymous.repository.ContactRepository
import com.example.anonymous.repository.MessageRepository
import com.example.anonymous.utils.PrefsHelper
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import androidx.compose.runtime.collectAsState
import com.example.anonymous.messaging.OfflineMessageManager

sealed class IconType(val action: (String) -> String) {
    data class Vector(val icon: ImageVector, val vectorAction: (String) -> String) : IconType(vectorAction)
    data class Drawable(val resId: Int, val drawableAction: (String) -> String) : IconType(drawableAction)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    contactId: String,
    contactName: String,
    customizationSettings: ChatCustomizationSettings,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val messageRepository = remember { MessageRepository(context) }
    val messageManager = remember { MessageManager.getInstance(context) }
    val offlineManager = remember { OfflineMessageManager.getInstance(context) }
    val i2pdDaemon = remember { I2pdDaemon.getInstance(context) }
    val contactRepository = remember { ContactRepository.getInstance(context) }

    val currentUserId = remember { contactRepository.getMyIdentity()?.b32Address ?: PrefsHelper.getUserUuid(context) ?: "" }

    var messages by remember { mutableStateOf(emptyList<Message>()) }
    var message by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(false) }
    var isCodeFormat by remember { mutableStateOf(false) }
    val coroutineScope = rememberCoroutineScope()
    var connectionStatus by remember {
        mutableStateOf<I2pdDaemon.DaemonState>(I2pdDaemon.DaemonState.STOPPED)
    }
    var isInitializing by remember { mutableStateOf(false) }

    val iconList = listOf(
        IconType.Vector(Icons.Default.Favorite, { text -> "❤️ $text ❤️" }),
        IconType.Vector(Icons.Default.Face, { text -> "😊 $text 😊" }),
        IconType.Vector(Icons.Default.PlayArrow, { text -> "▶️ $text" }),
        IconType.Drawable(R.drawable.code_24px, { text ->
            if (text.startsWith("```") && text.endsWith("```")) {
                text.removeSurrounding("```")
            } else {
                "```\n$text\n```"
            }
        })
    )

    // Initialize I2P daemon and messaging
    LaunchedEffect(Unit) {
        if (!i2pdDaemon.isRunning() && !isInitializing) {
            isInitializing = true
            i2pdDaemon.start()
        }

        i2pdDaemon.addListener(object : I2pdDaemon.DaemonStateListener {
            override fun onStateChanged(state: I2pdDaemon.DaemonState) {
                connectionStatus = state
                if (state == I2pdDaemon.DaemonState.READY) {
                    coroutineScope.launch {
                        val success = messageManager.initialize()
                        if (success) {
                            Log.i("ChatScreen", "MessageManger initialized successfully")
                        } else {
                            Log.e("ChatScreen", "Failed to initialize MessageManager")
                        }
                    }
                }
            }

            override fun onError(error: String) {
                Log.e("ChatScreen", "I2P Error: $error")
                connectionStatus = I2pdDaemon.DaemonState.ERROR
            }
        })
    }

    // Load messages on start
    LaunchedEffect(contactId) {
        val cryptoService = GraphQLCryptoService(context)
        val localMessagesDecrypted = messageRepository.getMessagesForContactDecrypted(contactId, cryptoService)
        messages = localMessagesDecrypted
    }

    // Listen for incoming I2P messages
    LaunchedEffect(messageManager) {
        messageManager.incomingMessages.collect { newMessage ->
            try {
                messageRepository.addMessage(newMessage)
                val cryptoService = GraphQLCryptoService(context)
                val updatedMessages = messageRepository.getMessagesForContactDecrypted(contactId, cryptoService)
                messages = updatedMessages
            } catch (e: Exception) {
                Log.e("ChatScreen", "Error processing new message", e)
            }
        }
    }

    // Listen for message status updates
    LaunchedEffect(messageManager) {
        messageManager.messageStatus.collect { status ->
            Log.d("ChatScreen", "Message ${status.messageId} status: ${status.status}")
        }
    }

    // Cleanup on exit
    DisposableEffect(Unit) {
        onDispose {
            Log.d("ChatScreen", "Leaving chat with $contactId")
        }
    }

    @Composable
    fun getConnectionStatusText(): Pair<String, Color> {
        return when (connectionStatus) {
            I2pdDaemon.DaemonState.STOPPED -> "I2P Stopped" to Color.Gray
            I2pdDaemon.DaemonState.STARTING -> "Starting I2P..." to Color.Yellow
            I2pdDaemon.DaemonState.WAITING_FOR_NETWORK -> "Waiting for network..." to Color.Yellow
            I2pdDaemon.DaemonState.BUILDING_TUNNELS -> "Building tunnels..." to Color.Yellow
            I2pdDaemon.DaemonState.READY -> {
                if (messageManager.connectionState.collectAsState().value == MessageManager.ConnectionState.Connected) {
                    "Connected (I2P)" to Color.Green
                } else {
                    "Connecting..." to Color.Yellow
                }
            }
            I2pdDaemon.DaemonState.ERROR -> "I2P Error" to Color.Red
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Chat with $contactName")
                        val (statusText, statusColor) = getConnectionStatusText()
                        Text(
                            text = statusText,
                            style = MaterialTheme.typography.bodySmall,
                            color = statusColor
                        )
                    }
                },
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
                verticalArrangement = Arrangement.spacedBy(10.dp),
                reverseLayout = true
            ) {
                items(messages.reversed()) { msg ->
                    ChatMessage(
                        message = msg,
                        settings = customizationSettings,
                        currentUserId = currentUserId,
                        isCode = msg.content.startsWith("```") && msg.content.endsWith("```")
                    )
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
                                message = icon.action(message)
                                if (icon is IconType.Drawable && icon.resId == R.drawable.code_24px) {
                                    isCodeFormat = !isCodeFormat
                                }
                            }) {
                                when (icon) {
                                    is IconType.Vector -> Icon(
                                        imageVector = icon.icon,
                                        contentDescription = null,
                                        tint = if (message.contains(icon.icon.name)) Color.White else Color.DarkGray
                                    )
                                    is IconType.Drawable -> Icon(
                                        painter = painterResource(id = icon.resId),
                                        contentDescription = null,
                                        tint = if (isCodeFormat) Color.White else Color.DarkGray
                                    )
                                }
                            }
                        }
                    }

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        BasicTextField(
                            value = message,
                            onValueChange = { message = it },
                            modifier = Modifier
                                .weight(1f)
                                .padding(4.dp)
                                .background(
                                    if (isCodeFormat) Color.LightGray.copy(alpha = 0.2f)
                                    else Color.Transparent,
                                    RoundedCornerShape(8.dp)
                                )
                                .padding(8.dp),
                            textStyle = MaterialTheme.typography.bodyMedium.copy(
                                color = MaterialTheme.colorScheme.onSurface,
                                fontFamily = if (isCodeFormat) MaterialTheme.typography.bodyMedium.fontFamily else null
                            ),
                            decorationBox = { inner ->
                                Box {
                                    if (message.isEmpty()) {
                                        Text(
                                            if (isCodeFormat) "Type your code..." else "Type a message...",
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
                                if (message.isNotBlank() && !isLoading) {
                                    isLoading = true
                                    coroutineScope.launch {
                                        try {
                                            val result = messageManager.sendMessage(contactId, message)

                                            if (result.isSuccess) {
                                                val cryptoService = GraphQLCryptoService(context)
                                                val updatedMessages = messageRepository.getMessagesForContactDecrypted(contactId, cryptoService)
                                                messages = updatedMessages
                                                message = ""
                                                isCodeFormat = false
                                                Log.d("ChatScreen", "Message sent successfully via I2P")
                                            } else {
                                                Log.w("ChatScreen", "I2P sen failed, queuing for offline: ${result.exceptionOrNull()?.message}")
                                                val queuedId = offlineManager.queueMessage(contactId, message)
                                                Log.d("ChatScreen", "Message queued for offline delivery: $queuedId")
                                                message = ""
                                                isCodeFormat = false
                                                Toast.makeText(context, "Message Queued", Toast.LENGTH_SHORT).show()
                                            }
                                        } catch (e: Exception) {
                                            Log.e("ChatScreen", "Error sending message", e)
                                        } finally {
                                            isLoading = false
                                        }
                                    }
                                }
                            },
                            enabled = !isLoading && message.isNotBlank()
                        ) {
                            if (isLoading) {
                                CircularProgressIndicator(modifier = Modifier.size(24.dp))
                            } else {
                                Icon(Icons.Default.Send, contentDescription = "Send")
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun ChatMessage(
    message: Message,
    settings: ChatCustomizationSettings,
    currentUserId: String,
    isCode: Boolean = false
) {
    val isSent = message.senderId == currentUserId
    val bubbleColor = if (isSent) settings.sentBubbleColor else settings.receivedBubbleColor
    val alignment = if (isSent && settings.isSentRightAligned) Alignment.CenterEnd else Alignment.CenterStart

    Box(
        modifier = Modifier.fillMaxWidth(),
        contentAlignment = alignment
    ) {
        Surface(
            shape = RoundedCornerShape(
                topStart = 12.dp,
                topEnd = 12.dp,
                bottomStart = if (isSent) 12.dp else 4.dp,
                bottomEnd = if (isSent) 4.dp else 12.dp
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
                        color = if (isCode) Color.White else Color.Black,
                        fontSize = if (isCode) 12.sp else 14.sp,
                        fontFamily = if (isCode) MaterialTheme.typography.bodyMedium.fontFamily else null
                    ),
                    modifier = Modifier
                        .padding(bottom = 4.dp)
                        .background(
                            if (isCode) Color.DarkGray.copy(alpha = 0.8f) else Color.Transparent,
                            RoundedCornerShape(4.dp)
                        )
                        .padding(if (isCode) 8.dp else 0.dp)
                )

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.End,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = SimpleDateFormat("HH:mm", Locale.getDefault())
                            .format(Date(message.timestamp)),
                        style = MaterialTheme.typography.bodyMedium.copy(
                            color = Color.Gray,
                            fontSize = 10.sp
                        )
                    )
                    if (!isSent) {
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