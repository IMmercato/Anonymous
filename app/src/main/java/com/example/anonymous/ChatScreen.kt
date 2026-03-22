package com.example.anonymous

import android.net.Uri
import android.os.Build
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.AttachFile
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
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.anonymous.datastore.ChatCustomizationSettings
import com.example.anonymous.i2p.I2pdDaemon
import com.example.anonymous.messaging.MessageManager
import com.example.anonymous.messaging.OfflineMessageManager
import com.example.anonymous.network.model.Message
import com.example.anonymous.repository.ContactRepository
import com.example.anonymous.utils.PrefsHelper
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextDecoration
import coil.ImageLoader
import coil.compose.AsyncImage
import coil.decode.GifDecoder
import coil.decode.ImageDecoderDecoder
import coil.request.ImageRequest
import com.example.anonymous.media.MediaChunkManager
import com.example.anonymous.media.MediaLoadState
import com.example.anonymous.repository.MessageRepository
import com.example.anonymous.utils.LinkifyText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// Editor
enum class EditorFormat(
    val prefix: String,
    val suffix: String,
    val placeholder: String = ""
) {
    BOLD(prefix = "**", suffix = "**", placeholder = "bold"),
    ITALIC(prefix = "_", suffix = "_", placeholder = "italic"),
    STRIKETHROUGH(prefix = "~~", suffix = "~~", placeholder = "strikethrough"),
    INLINE_CODE(prefix = "`", suffix = "`", placeholder = "code"),
    CODE_BLOCK(prefix = "```\n", suffix = "\n```", placeholder = "code here")
}

fun applyFormat(value: TextFieldValue, format: EditorFormat): TextFieldValue {
    val text = value.text
    val selection = value.selection
    val start = selection.min
    val end = selection.max
    val hasSelection = !selection.collapsed

    val alreadyWrapped = hasSelection
            && start >= format.prefix.length
            && end + format.suffix.length <= text.length
            && text.substring(start - format.prefix.length, start) == format.prefix
            && text.substring(end, end + format.suffix.length) == format.suffix

    return when {
        alreadyWrapped -> {
            val unwrapped = text.removeRange(end, end + format.suffix.length).removeRange(start - format.prefix.length, start)
            val newStart = start - format.prefix.length
            value.copy(text = unwrapped, selection = TextRange(newStart, newStart + (end -start)))
        }
        hasSelection -> {
            val inner = text.substring(start, end)
            val newText = text.replaceRange(start, end, "${format.prefix}$inner${format.suffix}")
            val selStart = start + inner.length
            val selEnd = selStart + inner.length
            value.copy(text = newText, selection = TextRange(selStart, selEnd))
        }
        else -> {
            val insert  = "${format.prefix}${format.placeholder}${format.suffix}"
            val newText = text.replaceRange(start, start, insert)
            val selStart = start + format.prefix.length
            val selEnd   = selStart + format.placeholder.length
            value.copy(text = newText, selection = TextRange(selStart, selEnd))
        }
    }
}

private fun String.isCodeBlock() = startsWith("```") && endsWith("```") && length > 6

private fun connectionStatusInfo(connectionStatus: I2pdDaemon.DaemonState, isConnected: Boolean): Pair<String, Color> = when(connectionStatus) {
    I2pdDaemon.DaemonState.Idle -> "I2P Idle" to Color.Gray
    I2pdDaemon.DaemonState.Starting -> "Starting I2P..." to Color.Yellow
    I2pdDaemon.DaemonState.WaitingForNetwork -> "Waiting for network..." to Color.Yellow
    I2pdDaemon.DaemonState.BuildingTunnels -> "Building tunnels..." to Color.Yellow
    I2pdDaemon.DaemonState.Reseeding -> "Reseeding..." to Color.Yellow
    I2pdDaemon.DaemonState.Ready -> if (isConnected) "Connected" to Color.Green else "Connecting..." to Color.Yellow
    is I2pdDaemon.DaemonState.Error -> if (connectionStatus.isPermanent) "I2P Error (Permanent)" to Color.Red else "I2P Error" to Color.Red
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
    val contactRepository = remember { ContactRepository.getInstance(context) }
    val mediaManager = remember { MediaChunkManager.getInstance(context) }

    val currentUserId = remember { contactRepository.getMyIdentity()?.b32Address ?: PrefsHelper.getUserUuid(context) ?: "" }

    var messages by remember { mutableStateOf(emptyList<Message>()) }
    // TextField
    var fieldValue by remember { mutableStateOf(TextFieldValue("")) }
    var isLoading by remember { mutableStateOf(false) }
    var selectedUri by remember { mutableStateOf<Uri?>(null) }
    val coroutineScope = rememberCoroutineScope()

    val connectionState by messageManager.connectionState.collectAsState()
    val isConnected = connectionState == MessageManager.ConnectionState.Connected
    val connectionStatus = when (connectionState) {
        MessageManager.ConnectionState.Connected -> I2pdDaemon.DaemonState.Ready
        MessageManager.ConnectionState.Connecting -> I2pdDaemon.DaemonState.Starting
        MessageManager.ConnectionState.Error -> I2pdDaemon.DaemonState.Error("Session error", false)
        MessageManager.ConnectionState.Disconnected -> I2pdDaemon.DaemonState.Idle
    }

    val gifLoader = remember(context.applicationContext) {
        ImageLoader.Builder(context.applicationContext)
            .components {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) add(ImageDecoderDecoder.Factory())
                else add(GifDecoder.Factory())
            }
            .build()
    }

    val mediaPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri -> selectedUri = uri }

    // Load messages on start
    LaunchedEffect(contactId) {
        messages = withContext(Dispatchers.IO) { messageRepository.getMessagesForContact(contactId) }
    }

    // Listen for incoming I2P messages
    LaunchedEffect(messageManager, contactId) {
        messageManager.incomingMessages.collect { newMessage ->
            if (newMessage.senderId == contactId || newMessage.receiverId == contactId) {
                messages = withContext(Dispatchers.IO) { messageRepository.getMessagesForContact(contactId) }
            }
        }
    }

    // Listen for message status updates
    LaunchedEffect(messageManager, contactId) {
        messageManager.messageStatus.collect { status ->
            if (status.status == MessageManager.Status.SENT || status.status == MessageManager.Status.DELIVERED) {
                messages = withContext(Dispatchers.IO) {
                    messageRepository.getMessagesForContact(contactId)
                }
            }
        }
    }

    val (statusText, statusColor) = connectionStatusInfo(connectionStatus, isConnected)

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Chat with $contactName")
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
                items(messages, key = { it.id }) { msg ->
                    ChatMessage(
                        message = msg,
                        settings = customizationSettings,
                        currentUserId = currentUserId,
                        isCode = msg.content.startsWith("```") && msg.content.endsWith("```"),
                        mediaManager = mediaManager,
                        imageLoader = gifLoader
                    )
                }
            }

            // Selected image preview
            selectedUri?.let { uri ->
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(120.dp)
                        .padding(horizontal = 8.dp)
                ) {
                    AsyncImage(
                        model = uri,
                        contentDescription = "Selected image preview",
                        modifier = Modifier
                            .fillMaxSize()
                            .clip(RoundedCornerShape(8.dp)),
                        contentScale = ContentScale.Crop
                    )
                    TextButton(
                        onClick = { selectedUri = null },
                        modifier = Modifier.align(Alignment.TopEnd)
                    ) { Text("X", color = Color.White) }
                }
                Spacer(modifier = Modifier.height(4.dp))
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
                    EditorToolbar(
                        fieldValue = fieldValue,
                        onValueChange = { fieldValue = it }
                    )

                    Spacer(modifier = Modifier.height(4.dp))

                    val isCodeBlock = fieldValue.text.isCodeBlock()

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { mediaPicker.launch("image/*") }) {
                            Icon(Icons.Default.AttachFile, contentDescription = "Attach", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }

                        BasicTextField(
                            value = fieldValue,
                            onValueChange = { fieldValue = it },
                            modifier = Modifier
                                .weight(1f)
                                .padding(4.dp)
                                .background(
                                    color = if (isCodeBlock) MaterialTheme.colorScheme.surfaceVariant
                                    else Color.Transparent,
                                    shape = RoundedCornerShape(8.dp)
                                )
                                .padding(8.dp),
                            textStyle = MaterialTheme.typography.bodyMedium.copy(
                                color = MaterialTheme.colorScheme.onSurface,
                                fontFamily = if (isCodeBlock) FontFamily.Monospace else FontFamily.Default
                            ),
                            decorationBox = { inner ->
                                Box {
                                    if (fieldValue.text.isEmpty()) {
                                        Text("Type a message…",
                                            style = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurfaceVariant))
                                    }
                                    inner()
                                }
                            }
                        )

                        Spacer(modifier = Modifier.width(8.dp))

                        IconButton(
                            onClick = {
                                val text = fieldValue.text.trim()
                                val uri = selectedUri
                                if (!isLoading && (text.isNotBlank() || uri != null)) {
                                    if (uri != null && !isConnected) {
                                        Toast.makeText(context, "Not connected yet", Toast.LENGTH_SHORT).show()
                                        return@IconButton
                                    }
                                    isLoading = true
                                    coroutineScope.launch {
                                        try {
                                            if (uri != null) {
                                                val result = messageManager.sendMedia(contactId, uri, text)
                                                if (result.isSuccess) {
                                                    messages = withContext(Dispatchers.IO) { messageRepository.getMessagesForContact(contactId) }
                                                    fieldValue = TextFieldValue("")
                                                    selectedUri = null
                                                    Log.d("ChatScreen", "Media sent")
                                                } else {
                                                    Log.w("ChatScreen", "sendMedia failed: ${result.exceptionOrNull()?.message}")
                                                    Toast.makeText(context, "Failed to send image", Toast.LENGTH_SHORT).show()
                                                }
                                            } else {
                                                val result = messageManager.sendMessage(contactId, text)
                                                if (result.isSuccess) {
                                                    messages = withContext(Dispatchers.IO) { messageRepository.getMessagesForContact(contactId) }
                                                    fieldValue = TextFieldValue("")
                                                    Log.d("ChatScreen", "Message sent via I2P")
                                                } else {
                                                    Log.w("ChatScreen", "I2P failed, queuing: ${result.exceptionOrNull()?.message}")
                                                    val queuedId = offlineManager.queueMessage(contactId, text)
                                                    Log.d("ChatScreen", "Queued: $queuedId")
                                                    fieldValue = TextFieldValue("")
                                                    Toast.makeText(context, "Message Queued", Toast.LENGTH_SHORT).show()
                                                }
                                            }
                                        } catch (e: Exception) {
                                            Log.e("ChatScreen", "Error sending", e)
                                        } finally {
                                            isLoading = false
                                        }
                                    }
                                }
                            },
                            enabled = !isLoading && (fieldValue.text.isNotBlank() || selectedUri != null)
                        ) {
                            if (isLoading) CircularProgressIndicator(modifier = Modifier.size(24.dp))
                            else Icon(Icons.Default.Send, contentDescription = "Send")
                        }
                    }
                }
            }
        }
    }
}

// Editor Toolbar
@Composable
private fun EditorToolbar(
   fieldValue: TextFieldValue,
   onValueChange: (TextFieldValue) -> Unit
) {
    val text = fieldValue.text
    val select = fieldValue.selection

    fun isActive(format: EditorFormat): Boolean {
        if (select.collapsed) return false
        val s = select.min; val e = select.max
        return s >= format.prefix.length
                && e + format.suffix.length <= text.length
                && text.substring(s - format.prefix.length, s) == format.prefix
                && text.substring(e, e + format.suffix.length) == format.suffix
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        EditorTextButton(label = "B", active = isActive(EditorFormat.BOLD), fontWeight = FontWeight.Bold) {
            onValueChange(applyFormat(fieldValue, EditorFormat.BOLD))
        }
        EditorTextButton(label = "I", active = isActive(EditorFormat.ITALIC), fontStyle = FontStyle.Italic) {
            onValueChange(applyFormat(fieldValue, EditorFormat.ITALIC))
        }
        EditorTextButton(label = "S", active = isActive(EditorFormat.STRIKETHROUGH), textDecoration = TextDecoration.LineThrough) {
            onValueChange(applyFormat(fieldValue, EditorFormat.STRIKETHROUGH))
        }

        Box(modifier = Modifier.width(1.dp).height(20.dp).background(MaterialTheme.colorScheme.outlineVariant))

        EditorIconButton(
            resId = R.drawable.code_24px,
            active = isActive(EditorFormat.INLINE_CODE),
            contentDescription = "Inline code"
        ) { onValueChange(applyFormat(fieldValue, EditorFormat.INLINE_CODE)) }

        EditorTextButton(
            label = "</>",
            active = text.isCodeBlock()
        ) { onValueChange(applyFormat(fieldValue, EditorFormat.CODE_BLOCK)) }
    }
}

@Composable
private fun EditorTextButton(
    label: String,
    active: Boolean,
    fontWeight: FontWeight = FontWeight.Normal,
    fontStyle: FontStyle = FontStyle.Normal,
    textDecoration: TextDecoration = TextDecoration.None,
    onClick: () -> Unit
) {
    val primary = MaterialTheme.colorScheme.primary
    val onSurface = MaterialTheme.colorScheme.onSurfaceVariant
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(6.dp),
        color = if (active) primary.copy(alpha = 0.15f) else Color.Transparent,
        modifier = Modifier.size(36.dp)
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontWeight = fontWeight,
                    fontStyle = fontStyle,
                    textDecoration = textDecoration,
                    color = if (active) primary else onSurface,
                    fontSize = 14.sp
                )
            )
        }
    }
}

@Composable
private fun EditorIconButton(
    resId: Int,
    active: Boolean,
    contentDescription: String,
    onClick: () -> Unit
) {
    val primary   = MaterialTheme.colorScheme.primary
    val onSurface = MaterialTheme.colorScheme.onSurfaceVariant
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(6.dp),
        color = if (active) primary.copy(alpha = 0.15f) else Color.Transparent,
        modifier = Modifier.size(36.dp)
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                painter = painterResource(id = resId),
                contentDescription = contentDescription,
                tint = if (active) primary else onSurface,
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

@Composable
fun ChatMessage(
    message: Message,
    settings: ChatCustomizationSettings,
    currentUserId: String,
    isCode: Boolean = false,
    mediaManager: MediaChunkManager? = null,
    imageLoader: ImageLoader? = null
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
                val mediaId = message.mediaId
                if (mediaId != null && mediaManager != null && imageLoader != null) {
                    MediaAttachment(mediaId = mediaId, mediaManager = mediaManager, imageLoader = imageLoader)
                    Spacer(modifier = Modifier.height(6.dp))
                }

                if (message.content.isNotBlank() && message.content != "media") {
                    LinkifyText(
                        text = message.content,
                    )
                }

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
private fun MediaAttachment(
    mediaId: String,
    mediaManager: MediaChunkManager,
    imageLoader: ImageLoader
) {
    val state by mediaManager.stateFor(mediaId).collectAsState()

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 80.dp, max = 220.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center
    ) {
        when (val s = state) {
            is MediaLoadState.Ready -> {
                AsyncImage(
                    model = ImageRequest.Builder(LocalContext.current)
                        .data(s.file)
                        .crossfade(true)
                        .build(),
                    imageLoader = imageLoader,
                    contentDescription = "Shared image",
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
            }
            is MediaLoadState.Loading -> {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                    modifier = Modifier.padding(12.dp)
                ) {
                    val progress = if (s.meta.chunkCount > 0) s.received.toFloat() / s.meta.chunkCount else 0f
                    CircularProgressIndicator(
                        progress = { progress },
                        modifier = Modifier.size(36.dp)
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text("${s.received}/${s.meta.chunkCount} chunks",  style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            is MediaLoadState.Pending, MediaLoadState.Unknown -> {
                CircularProgressIndicator(modifier = Modifier.size(28.dp))
            }
            MediaLoadState.Error -> {
                Text("Failed to load image", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
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