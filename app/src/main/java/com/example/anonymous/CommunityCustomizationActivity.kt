package com.example.anonymous

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.example.anonymous.datastore.*
import com.example.anonymous.ui.theme.AnonymousTheme
import kotlinx.coroutines.launch

class CommunityCustomizationActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            AnonymousTheme {
                CommunityCustomizationScreen()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CommunityCustomizationScreen() {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    // Get current settings
    val communitySettingsFlow = getCommunityCustomizationSettings(context)
    val communitySettings by communitySettingsFlow.collectAsState(initial = CommunityCustomizationSettings())

    var postColor by remember { mutableStateOf(communitySettings.postCardColor) }
    var textSize by remember { mutableStateOf(communitySettings.textSize) }
    var showImages by remember { mutableStateOf(communitySettings.showImages) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Community Customization") },
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
            Text("Post Card Color", style = MaterialTheme.typography.titleMedium)
            ColorPicker(
                selectedColor = postColor,
                onColorSelected = { postColor = it }
            )

            Spacer(modifier = Modifier.height(16.dp))

            Text("Text Size: $textSize", style = MaterialTheme.typography.titleMedium)
            Slider(
                value = textSize.toFloat(),
                onValueChange = { textSize = it.toInt() },
                valueRange = 10f..20f,
                steps = 10,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(16.dp))

            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Checkbox(
                    checked = showImages,
                    onCheckedChange = { showImages = it }
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text("Show images in posts", style = MaterialTheme.typography.bodyLarge)
            }

            Spacer(modifier = Modifier.height(32.dp))

            Button(
                onClick = {
                    coroutineScope.launch {
                        saveCommunityCustomizationSettings(
                            context,
                            CommunityCustomizationSettings(
                                postCardColor = postColor,
                                textSize = textSize,
                                showImages = showImages
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