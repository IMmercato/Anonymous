package com.example.anonymous

import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts.GetContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.ImageLoader
import coil.compose.AsyncImage
import coil.decode.GifDecoder
import coil.decode.ImageDecoderDecoder
import coil.request.ImageRequest

data class CommunityInfo(
    val name: String,
    val description: String,
    val members: Int
)

data class Post(
    val text: String,
    val mediaUrl: String? = null
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CommunityScreen(
    community: CommunityInfo,
    onBack: () -> Unit
) {
    var post by remember { mutableStateOf("") }
    var selectedMediaUri by remember { mutableStateOf<Uri?>(null) }
    var showPostDialog by remember { mutableStateOf(false) }

    // Posts list is maintained as a mutable state list.
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

    // Create a GIF-enabled ImageLoader as per the recommended approach.
    val context = LocalContext.current
    val gifEnabledLoader = remember {
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

    val mediaPickerLauncher = rememberLauncherForActivityResult(contract = GetContent()) { uri: Uri? ->
        selectedMediaUri = uri
    }

    // State for the LazyColumn (optional, for further advanced interactions).
    val listState = rememberLazyListState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(community.name) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { showPostDialog = true },
                modifier = Modifier.padding(bottom = 16.dp, end = 16.dp)
            ) {
                Icon(Icons.Rounded.Add, contentDescription = "New Post!")
            }
        }
    ) { innerPadding ->
        Box(modifier = Modifier.fillMaxSize()) {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(horizontal = 16.dp),
                contentPadding = PaddingValues(vertical = 16.dp)
            ) {
                // Sticky header for the CommunityInfo.
                stickyHeader {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.surface)
                            .padding(16.dp)
                    ) {
                        Text(
                            text = community.description,
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Members: ${community.members}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
                // List of posts.
                items(posts) { post ->
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                            .clickable { /* Optionally handle post clicks */ },
                        colors = CardDefaults.elevatedCardColors(
                            containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                        ),
                        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
                    ) {
                        Column(modifier = Modifier.fillMaxWidth()) {
                            // Display media if available.
                            post.mediaUrl?.let { url ->
                                AsyncImage(
                                    model = ImageRequest.Builder(context)
                                        .data(url)
                                        .crossfade(true)
                                        .build(),
                                    imageLoader = gifEnabledLoader,
                                    contentDescription = "Post media",
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(200.dp),
                                    contentScale = ContentScale.Crop
                                )
                            }
                            Text(
                                text = post.text,
                                modifier = Modifier.padding(16.dp),
                                fontSize = 16.sp,
                                style = MaterialTheme.typography.bodyLarge
                            )
                        }
                    }
                }
            }

            // New post dialog.
            if (showPostDialog) {
                AlertDialog(
                    onDismissRequest = {
                        showPostDialog = false
                        post = ""
                        selectedMediaUri = null
                    },
                    title = { Text("New Post") },
                    text = {
                        Column(modifier = Modifier.fillMaxWidth()) {
                            // Text input for the post.
                            TextField(
                                value = post,
                                onValueChange = { post = it },
                                label = { Text("Enter your post") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            // Button to select an image from the gallery.
                            Button(
                                onClick = { mediaPickerLauncher.launch("image/*") },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("Select Image")
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                            // Preview of the selected image.
                            selectedMediaUri?.let { uri ->
                                AsyncImage(
                                    model = uri,
                                    contentDescription = "Selected image preview",
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(200.dp),
                                    contentScale = ContentScale.Crop
                                )
                            }
                        }
                    },
                    confirmButton = {
                        TextButton(
                            onClick = {
                                if (post.isNotBlank() || selectedMediaUri != null) {
                                    posts.add(
                                        Post(
                                            text = post,
                                            mediaUrl = selectedMediaUri?.toString()
                                        )
                                    )
                                    Toast.makeText(context, "Post Added!", Toast.LENGTH_SHORT).show()
                                    post = ""
                                    selectedMediaUri = null
                                    showPostDialog = false
                                }
                            }
                        ) {
                            Text("Post")
                        }
                    },
                    dismissButton = {
                        TextButton(
                            onClick = {
                                showPostDialog = false
                                post = ""
                                selectedMediaUri = null
                            }
                        ) {
                            Text("Cancel")
                        }
                    }
                )
            }
        }
    }
}