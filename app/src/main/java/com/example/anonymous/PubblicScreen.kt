package com.example.anonymous

import android.content.Context
import android.net.Uri
import android.os.Looper
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts.GetContent
import androidx.annotation.OptIn
import coil.compose.rememberAsyncImagePainter
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.transformer.Transformer
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.launch
import java.io.File

/**
 * Helper suspend function that uses Media3 Transformer to perform a simple transformation.
 * In this example, we “trim” the video from 0ms to 10,000ms.
 * (Note: Depending on the Transformer version the API might require slight adjustments.)
 */

@OptIn(UnstableApi::class)
suspend fun transformVideo(inputUri: Uri, context: Context): Uri {
    // Create a temporary file to store the transformed (trimmed) video.
    val outputFile = File(context.cacheDir, "trimmed_video_${System.currentTimeMillis()}.mp4")

    // Build a Transformer with an explicit Looper.
    val transformer = Transformer.Builder(context)
        .setLooper(Looper.myLooper() ?: Looper.getMainLooper())
        .build()

    // Configure clipping using MediaItem.ClippingConfiguration.
    val mediaItem = MediaItem.fromUri(inputUri).buildUpon()
        .setClippingConfiguration(
            MediaItem.ClippingConfiguration.Builder()
                .setStartPositionMs(0)      // start at 0ms
                .setEndPositionMs(10_000)     // end at 10 seconds (10,000ms)
                .build()
        )
        .build()

    try {
        // Call startTransformation on the transformer instance.
        // This is a suspend function, so ensure you’re calling it on a coroutine.
        transformer.start(mediaItem, outputFile.absolutePath)
    } catch (e: Exception) {
        e.printStackTrace()
        return inputUri
    }

    return Uri.fromFile(outputFile)
}

@kotlin.OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PubblicScreen() {
    // State variables.
    var selectedMediaUri by remember { mutableStateOf<Uri?>(null) }
    var showEditor by remember { mutableStateOf(false) }
    var isImage by remember { mutableStateOf(false) } // true if media is an image.
    var postedMediaUrl by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current

    // Launchers to pick image or video.
    val imagePickerLauncher = rememberLauncherForActivityResult(contract = GetContent()) { uri: Uri? ->
        uri?.let {
            selectedMediaUri = it
            val type = context.contentResolver.getType(it)
            isImage = type?.startsWith("image") == true
        }
    }
    val videoPickerLauncher = rememberLauncherForActivityResult(contract = GetContent()) { uri: Uri? ->
        uri?.let {
            selectedMediaUri = it
            val type = context.contentResolver.getType(it)
            isImage = (type?.startsWith("image") == true) // false if video
        }
    }

    // Wrap the entire UI in a MaterialTheme with a Scaffold.
    MaterialTheme {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("Media Editor", style = MaterialTheme.typography.titleLarge) },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.primary)
                )
            },
            content = { paddingValues ->
                Box(modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)) {
                    when {
                        // If a transformed/posted media URL is available, show the posting screen.
                        postedMediaUrl != null -> {
                            MediaPostScreen(mediaUrl = postedMediaUrl!!)
                        }
                        // Show the appropriate editor based on media type.
                        showEditor && selectedMediaUri != null -> {
                            if (isImage) {
                                ImageEditor(
                                    selectedImageUri = selectedMediaUri!!,
                                    onPost = { uri ->
                                        postedMediaUrl = uri.toString()
                                    },
                                    onCancel = {
                                        showEditor = false
                                        selectedMediaUri = null
                                    }
                                )
                            } else {
                                VideoEditor(
                                    selectedVideoUri = selectedMediaUri!!,
                                    onPost = { uri ->
                                        postedMediaUrl = uri.toString()
                                    },
                                    onCancel = {
                                        showEditor = false
                                        selectedMediaUri = null
                                    }
                                )
                            }
                        }
                        // Otherwise, show the initial media picker screen.
                        else -> {
                            Column(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(24.dp),
                                verticalArrangement = Arrangement.Center
                            ) {
                                Text(
                                    "Select a media file to edit",
                                    fontSize = 22.sp,
                                    style = MaterialTheme.typography.titleMedium
                                )
                                Spacer(modifier = Modifier.height(20.dp))
                                Row {
                                    Button(
                                        onClick = { imagePickerLauncher.launch("image/*") },
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Text("Select Image")
                                    }
                                    Spacer(modifier = Modifier.width(16.dp))
                                    Button(
                                        onClick = { videoPickerLauncher.launch("video/*") },
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Text("Select Video")
                                    }
                                }
                                Spacer(modifier = Modifier.height(16.dp))
                                selectedMediaUri?.let { uri ->
                                    Text(
                                        "Selected: $uri",
                                        fontSize = 14.sp,
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                    Spacer(modifier = Modifier.height(16.dp))
                                    Button(
                                        onClick = { showEditor = true },
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Text("Edit Media")
                                    }
                                }
                            }
                        }
                    }
                }
            }
        )
    }
}

@Composable
fun ImageEditor(
    selectedImageUri: Uri,
    onPost: (Uri) -> Unit,
    onCancel: () -> Unit
) {
    val context = LocalContext.current
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp)
    ) {
        Text("Image Editor", style = MaterialTheme.typography.headlineSmall)
        Spacer(modifier = Modifier.height(20.dp))
        // Display the image inside a Card for a neat look.
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .height(320.dp)
        ) {
            Image(
                painter = rememberAsyncImagePainter(selectedImageUri),
                contentDescription = "Editable Image",
                modifier = Modifier.fillMaxSize()
            )
        }
        Spacer(modifier = Modifier.height(20.dp))
        Button(onClick = {
            // Simulate an editing operation, e.g., adding text.
            Toast.makeText(context, "Simulated: Text added", Toast.LENGTH_SHORT).show()
        }) {
            Text("Add Text")
        }
        Spacer(modifier = Modifier.height(20.dp))
        Row {
            Button(
                onClick = { onPost(selectedImageUri) },
                modifier = Modifier.weight(1f)
            ) {
                Text("Post Edited Image")
            }
            Spacer(modifier = Modifier.width(12.dp))
            Button(
                onClick = onCancel,
                modifier = Modifier.weight(1f)
            ) {
                Text("Cancel")
            }
        }
    }
}

@Composable
fun VideoEditor(
    selectedVideoUri: Uri,
    onPost: (Uri) -> Unit,
    onCancel: () -> Unit
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    var isProcessing by remember { mutableStateOf(false) }

    // Create an ExoPlayer instance for previewing the selected video.
    val player = remember {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(selectedVideoUri))
            prepare()
            playWhenReady = true
        }
    }

    DisposableEffect(key1 = selectedVideoUri) {
        onDispose { player.release() }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp)
    ) {
        Text("Video Editor", style = MaterialTheme.typography.headlineSmall)
        Spacer(modifier = Modifier.height(20.dp))
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .height(320.dp)
        ) {
            // Display video preview inside an AndroidView that shows the PlayerView.
            AndroidView(
                factory = { ctx ->
                    PlayerView(ctx).apply {
                        this.player = player
                        useController = true
                        layoutParams = android.view.ViewGroup.LayoutParams(
                            android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                            with(density) { 320.dp.toPx() }.toInt()
                        )
                    }
                },
                modifier = Modifier.fillMaxWidth()
            )
        }
        Spacer(modifier = Modifier.height(20.dp))
        Row {
            Button(
                onClick = {
                    // Start processing the video with Media3 Transformer.
                    scope.launch {
                        isProcessing = true
                        val transformedUri = transformVideo(selectedVideoUri, context)
                        isProcessing = false
                        onPost(transformedUri)
                    }
                },
                modifier = Modifier.weight(1f),
                enabled = !isProcessing
            ) {
                Text(if (isProcessing) "Processing..." else "Post Edited Video")
            }
            Spacer(modifier = Modifier.width(12.dp))
            Button(
                onClick = onCancel,
                modifier = Modifier.weight(1f),
                enabled = !isProcessing
            ) {
                Text("Cancel")
            }
        }
    }
}

@Composable
fun MediaPostScreen(mediaUrl: String) {
    val context = LocalContext.current
    if (mediaUrl.contains("video") || mediaUrl.endsWith(".mp4")) {
        val player = remember {
            ExoPlayer.Builder(context).build().apply {
                setMediaItem(MediaItem.fromUri(mediaUrl))
                prepare()
                playWhenReady = true
            }
        }
        DisposableEffect(mediaUrl) {
            onDispose { player.release() }
        }
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    this.player = player
                    useController = true
                    layoutParams = android.view.ViewGroup.LayoutParams(
                        android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                        android.view.ViewGroup.LayoutParams.MATCH_PARENT
                    )
                }
            },
            modifier = Modifier.fillMaxSize()
        )
    } else {
        Image(
            painter = rememberAsyncImagePainter(mediaUrl),
            contentDescription = "Posted Media",
            modifier = Modifier.fillMaxSize()
        )
    }
}