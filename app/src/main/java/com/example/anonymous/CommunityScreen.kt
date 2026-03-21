package com.example.anonymous

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.os.Build
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts.GetContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.ImageLoader
import coil.compose.AsyncImage
import coil.decode.GifDecoder
import coil.decode.ImageDecoderDecoder
import coil.request.ImageRequest
import com.example.anonymous.community.CommunityEncryption
import com.example.anonymous.community.CommunityHostService
import com.example.anonymous.community.CommunityInvite
import com.example.anonymous.community.CommunityMemberClient
import com.example.anonymous.datastore.CommunityCustomizationSettings
import com.example.anonymous.media.MediaChunkManager
import com.example.anonymous.media.MediaLoadState
import com.example.anonymous.media.MediaProtocol
import com.example.anonymous.network.model.Community
import com.example.anonymous.network.model.CommunityMessage
import com.example.anonymous.network.model.CommunityPacket
import com.example.anonymous.repository.CommunityRepository
import com.example.anonymous.repository.ContactRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.text.SimpleDateFormat
import java.util.Base64
import java.util.Date
import java.util.Locale

// Simple model for a Post.
data class Post(
    val text: String,
    val mediaUrl: String? = null
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CommunityScreen(
    community: Community,
    onBack: () -> Unit,
    customization: CommunityCustomizationSettings
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val communityRepo = remember { CommunityRepository.getInstance(context) }
    val contactRepo = remember { ContactRepository.getInstance(context) }
    val mediaManager = remember { MediaChunkManager.getInstance(context) }

    val myB32 = remember { contactRepo.getMyIdentity()?.b32Address ?: "" }
    val myName = remember { if (myB32.isNotEmpty()) myB32.take(12) else "Unknown" }
    val groupKey = remember(community.groupKeyBase64) { Base64.getDecoder().decode(community.groupKeyBase64) }
    val json = remember { Json { ignoreUnknownKeys = true } }

    val messages by communityRepo.getMessagesFlow(community.b32Address).collectAsState(initial = emptyList())

    var messageText by remember { mutableStateOf("") }
    var isConnected by remember { mutableStateOf(false) }
    var showShareDialog by remember { mutableStateOf(false) }
    var isSending by remember { mutableStateOf(false) }
    var selectedUri by remember { mutableStateOf<Uri?>(null) }

    val listState = rememberLazyListState()

    val mediaPicker = rememberLauncherForActivityResult(GetContent()) { uri: Uri? -> selectedUri = uri }

    // Create client once
    val client = remember {
        if (!community.isCreator) {
            CommunityMemberClient(
                community = community,
                myB32 = myB32,
                myName = myName,
                onMessage = { msg ->
                    coroutineScope.launch { communityRepo.addMessage(msg) }
                },
                onConnectionStateChanged = { connected -> isConnected = connected }
            )
        } else null
    }

    // For demo purposes, we pre-add some posts.
    /*
    val posts = remember {
        mutableStateListOf<Post>().apply {
            add(
                Post(
                    text = "Welcome to ${community.name}!",
                    mediaUrl = "https://e7.pngegg.com/pngimages/847/922/png-clipart-anonymous-logo-security-hacker-graphics-anonymous-white-logo-thumbnail.png"
                )
            )
            add(Post(text = "Here's an interesting post without media."))
            add(
                Post(
                    text = "Check out this cool gif:",
                    mediaUrl = "https://gifdb.com/images/high/forget-about-it-will-smith-i6trzp9b14ybnus6.gif"
                )
            )
        }
    }
     */
    // Build an ImageLoader that supports GIFs.
    val gifLoader = remember {
        ImageLoader.Builder(context)
            .components {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    add(ImageDecoderDecoder.Factory())
                } else {
                    add(GifDecoder.Factory())
                }
            }
            .build()
    }

    // Community Host
    LaunchedEffect(community.isCreator) {
        if (community.isCreator) isConnected = true
    }

    // Clean up member client
    DisposableEffect(community.b32Address, community.isCreator) {
        if (community.isCreator) CommunityHostService.start(context, community.b32Address)
        else client?.connect(context)
        onDispose {
            if (!community.isCreator) client?.disconnect()
        }
    }

    // Scroll to bottom on new message
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.size - 1)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(community.name)
                        Text(
                            text = if (isConnected) "Connected" else "Connecting...",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (isConnected) Color(0xFF4CAF50) else Color(0xFFFFC107)
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { showShareDialog = true }) {
                        Icon(Icons.Default.Share, contentDescription = "Share Invite")
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .imePadding()
        ) {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(horizontal = 8.dp),
                contentPadding = PaddingValues(vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                items(messages, key = { it.id }) { msg->
                    CommunityMessageBubble(
                        message = msg,
                        isOwn = msg.senderB32 == myB32,
                        bubbleColor = customization.postCardColor,
                        textSize = customization.textSize,
                        mediaManager = mediaManager,
                        imageLoader = gifLoader,
                        onRequestMedia = { mediaId ->
                            if (!community.isCreator) client?.pullMissingChunks(mediaId)
                            else Log.d("CommunityScreen", "Host cannot pull chunks (no client)")
                        }
                    )
                }
            }

            // Selected media preview
            selectedUri?.let { uri ->
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(120.dp)
                        .padding(horizontal = 8.dp)
                ) {
                    AsyncImage(
                        model = uri,
                        contentDescription = "Selected media preview",
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
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = { mediaPicker.launch("image/*") }) {
                        Icon(Icons.Default.AttachFile, contentDescription = "Attach image", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }

                    BasicTextField(
                        value = messageText,
                        onValueChange = { messageText = it },
                        modifier = Modifier
                            .weight(1f)
                            .padding(4.dp),
                        textStyle = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface),
                        decorationBox = { inner ->
                            Box {
                                if (messageText.isEmpty()) {
                                    Text("Message ${community.name}...", style = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurfaceVariant))
                                }
                                inner()
                            }
                        }
                    )

                    Spacer(modifier = Modifier.width(8.dp))

                    IconButton(
                        onClick = {
                            val text = messageText.trim()
                            val uri = selectedUri
                            if ((text.isNotEmpty() || uri != null) && !isSending) {
                                isSending = true

                                if (community.isCreator) {
                                    val sink = CommunityHostService.localPacketSink
                                    if (sink == null) {
                                        Toast.makeText(context, "Host service not ready", Toast.LENGTH_SHORT).show()
                                        isSending = false
                                        return@IconButton
                                    }
                                    if (uri != null) {
                                        // Host media
                                        coroutineScope.launch {
                                            try {
                                                val result = withContext(Dispatchers.IO) { mediaManager.prepareMedia(uri) }
                                                if (result == null) {
                                                    Toast.makeText(context, "Failed to sen image - too large after compression (max 1 MB)", Toast.LENGTH_SHORT).show()
                                                    return@launch
                                                }
                                                val (meta, chunks) = result
                                                val ts = System.currentTimeMillis()

                                                withContext(Dispatchers.IO) {
                                                    sink(json.encodeToString(CommunityPacket(
                                                        type = MediaProtocol.TYPE_MEDIA_META,
                                                        senderB32 = myB32,
                                                        senderName = myName,
                                                        timestamp = ts,
                                                        meta = meta
                                                    )))
                                                    val encrypted = CommunityEncryption.encrypt(text.ifBlank { "media" }, groupKey)
                                                    sink(json.encodeToString(CommunityPacket(
                                                        type = MediaProtocol.TYPE_MSG,
                                                        senderB32 = myB32,
                                                        senderName = myName,
                                                        payload = encrypted,
                                                        timestamp = ts,
                                                        mediaId = meta.mediaId
                                                    )))
                                                    chunks.forEachIndexed { index, chunkData ->
                                                        sink(json.encodeToString(CommunityPacket(
                                                            type = MediaProtocol.TYPE_MEDIA_CHUNK,
                                                            senderB32 = myB32,
                                                            mediaId = meta.mediaId,
                                                            chunkIndex = index,
                                                            chunkData = chunkData,
                                                            timestamp = ts
                                                        )))
                                                    }
                                                }
                                                communityRepo.addMessage(CommunityMessage(
                                                    id = "${myB32.take(8)}_$ts",
                                                    senderB32 = myB32,
                                                    senderName = myName,
                                                    content = text.ifBlank { "media" },
                                                    timestamp = ts,
                                                    communityB32 = community.b32Address,
                                                    mediaId = meta.mediaId
                                                ))
                                                selectedUri = null
                                                messageText = ""
                                            } finally {
                                                isSending = false
                                            }
                                        }
                                    } else if (text.isNotEmpty()) {
                                        // Host text
                                        coroutineScope.launch {
                                            try {
                                                val ts = System.currentTimeMillis()
                                                withContext(Dispatchers.IO) {
                                                    val encrypted = CommunityEncryption.encrypt(text, groupKey)
                                                    sink(json.encodeToString(CommunityPacket(
                                                        type = MediaProtocol.TYPE_MSG,
                                                        senderB32 = myB32,
                                                        senderName = myName,
                                                        payload = encrypted,
                                                        timestamp = ts
                                                    )))
                                                }
                                                communityRepo.addMessage(CommunityMessage(
                                                    id = "${myB32.take(8)}_$ts",
                                                    senderB32 = myB32,
                                                    senderName = myName,
                                                    content = text,
                                                    timestamp = ts,
                                                    communityB32 = community.b32Address
                                                ))
                                                messageText = ""
                                            } finally {
                                                isSending = false
                                            }
                                        }
                                    } else {
                                        isSending = false
                                    }
                                } else {
                                    // Member
                                    if (uri != null) {
                                        if (!isConnected) {
                                            Toast.makeText(context, "Not connected to community yet", Toast.LENGTH_SHORT).show()
                                            selectedUri = null
                                            isSending = false
                                            return@IconButton
                                        }
                                        val ts = System.currentTimeMillis()
                                        val mediaId = client?.sendMedia(uri, text, ts)
                                        if (mediaId != null) {
                                            coroutineScope.launch {
                                                communityRepo.addMessage(CommunityMessage(
                                                    id = "${myB32.take(8)}_$ts",
                                                    senderB32 = myB32,
                                                    senderName = myName,
                                                    content = text.ifBlank { "media" },
                                                    timestamp = ts,
                                                    communityB32 = community.b32Address,
                                                    mediaId = mediaId
                                                ))
                                            }
                                        } else {
                                            Toast.makeText(context, "Failed to send image — too large after compression (max 1 MB)", Toast.LENGTH_SHORT).show()
                                        }
                                        selectedUri = null
                                    } else if (text.isNotEmpty()) {
                                        val ts = System.currentTimeMillis()
                                        client?.sendMessage(text, ts)
                                        coroutineScope.launch {
                                            communityRepo.addMessage(CommunityMessage(
                                                id = "${myB32.take(8)}_$ts",
                                                senderB32 = myB32,
                                                senderName = myName,
                                                content = text,
                                                timestamp = ts,
                                                communityB32 = community.b32Address
                                            ))
                                        }
                                    }
                                    isSending = false
                                    messageText = ""
                                }
                            }
                        },
                        enabled = (messageText.isNotBlank() || selectedUri != null) && !isSending
                    ) {
                        if (isSending) CircularProgressIndicator(modifier = Modifier.size(20.dp))
                        else Icon(Icons.Default.Send, contentDescription = "Send")
                    }
                }
            }
        }
    }

    if (showShareDialog) {
        val invite = remember(community) { CommunityInvite.generateInviteUri(community) }

        AlertDialog(
            onDismissRequest = { showShareDialog = false },
            title = {
                Column {
                    Text("Share this invite link with people you want to add:", style = MaterialTheme.typography.bodyMedium)
                    Spacer(modifier = Modifier.height(12.dp))
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text(
                            text = invite,
                            modifier = Modifier.padding(12.dp),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (!community.isCreator) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            "Note: only the community creator should share invites.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setPrimaryClip(ClipData.newPlainText("Community Invite", invite))
                        Toast.makeText(context, "Invite link copied!", Toast.LENGTH_SHORT).show()
                        showShareDialog = false
                    }
                ) { Text("Copy Link") }
            },
            dismissButton = {
                TextButton(onClick = { showShareDialog = false }) { Text("Cancel") }
            }
        )
    }
}

@Composable
private fun CommunityMessageBubble(
    message: CommunityMessage,
    isOwn: Boolean,
    bubbleColor: Color,
    textSize: Int,
    mediaManager: MediaChunkManager,
    imageLoader: ImageLoader,
    onRequestMedia: (String) -> Unit
) {
    val alignment = if (isOwn) Alignment.CenterEnd else Alignment.CenterStart
    val ownBubble = MaterialTheme.colorScheme.primary
    val color = if (isOwn) ownBubble else bubbleColor

    Box(
        modifier = Modifier.fillMaxWidth(),
        contentAlignment = alignment
    ) {
        Column(
            modifier = Modifier.widthIn(max = 280.dp),
            horizontalAlignment = if (isOwn) Alignment.End else Alignment.Start
        ) {
            if (!isOwn) {
                Text(
                    text = message.senderName,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(start = 4.dp, bottom = 2.dp)
                )
            }
            Surface(
                shape = RoundedCornerShape(
                    topStart = 12.dp, topEnd = 12.dp,
                    bottomStart = if (isOwn) 12.dp else 4.dp,
                    bottomEnd = if (isOwn) 4.dp else 12.dp
                ),
                color = color,
                shadowElevation = 2.dp
            ) {
                Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                    message.mediaId?.let { mediaId ->
                        MediaAttachment(
                            mediaId = mediaId,
                            mediaManager = mediaManager,
                            imageLoader = imageLoader,
                            onRequestMedia = onRequestMedia
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                    }

                    if (message.content.isNotBlank() && message.content != "media") {
                        Text(
                            text = message.content,
                            fontSize = textSize.sp,
                            color = if (isOwn) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                    }

                    Text(
                        text = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(message.timestamp)),
                        style = MaterialTheme.typography.bodySmall.copy(fontSize = 10.sp),
                        color = if (isOwn) MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.7f) else Color.Gray,
                        modifier = Modifier.align(Alignment.End)
                    )
                }
            }
        }
    }
}

@Composable
private fun MediaAttachment(
    mediaId: String,
    mediaManager: MediaChunkManager,
    imageLoader: ImageLoader,
    onRequestMedia: (String) -> Unit
) {
    val state by mediaManager.stateFor(mediaId).collectAsState()

    Box(
        modifier = Modifier
            .fillMaxSize()
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
                    Text(
                        text = "${s.received}/${s.meta.chunkCount} chunks",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            is MediaLoadState.Pending -> {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                    modifier            = Modifier
                        .fillMaxSize()
                        .clickable { onRequestMedia(mediaId) }
                        .padding(16.dp)
                ) {
                    Icon(
                        Icons.Default.CloudDownload,
                        contentDescription = "Download image",
                        modifier           = Modifier.size(32.dp),
                        tint               = MaterialTheme.colorScheme.primary
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text      = "Tap to load image\n(${s.meta.totalSize / 1024} KB)",
                        style     = MaterialTheme.typography.bodySmall,
                        color     = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                }
            }
            MediaLoadState.Unknown -> {
                CircularProgressIndicator(modifier = Modifier.size(28.dp))
            }
            MediaLoadState.Error -> {
                Text(
                    text  = "Failed to load image",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}