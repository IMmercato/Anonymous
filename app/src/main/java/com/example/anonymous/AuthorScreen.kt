package com.example.anonymous

import android.annotation.SuppressLint
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.consumeAllChanges
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp

data class Media(
    val views: Int,
    val video: String
)

@SuppressLint("ClickableViewAccessibility")
@Composable
fun AuthorScreen(onBack: () -> Unit) {
    var info by remember { mutableStateOf(false) }
    var infomedia = listOf(
        Media(
            views = 10,
            video = "hello"
        ),
        Media(
            views = 1000,
            video = "f*ck"
        )
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectHorizontalDragGestures { change, dragAmount ->
                    if (dragAmount > 100f) {
                        onBack()
                        change.consumeAllChanges()
                    }
                }
            }
            .padding(16.dp)
    ) {
        Box(
            modifier = Modifier
                .height(200.dp)
                .align(Alignment.CenterHorizontally)
                .blur(
                    radiusX = 4.dp,
                    radiusY = 4.dp,
                    edgeTreatment = BlurredEdgeTreatment(RoundedCornerShape(50.dp))
                )
        ) {
            Image(
                painter = painterResource(R.drawable.anonymous),
                contentDescription = "Anonymous",
            )
        }

        Card(
            modifier = Modifier
                .padding(top = 24.dp)
                .fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .padding(vertical = 16.dp)
                    .fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                StatItem(title = "Posts", value = "1")
                StatItem(title = "Followers", value = "100")
                StatItem(title = "Friends", value = "10")
            }
        }

        LazyColumn {
            items(infomedia) {media ->
                Box(
                    modifier = Modifier.size(width = 100.dp, height = 250.dp).background(color = Color.Red)
                ) {
                    Text("${media.views}")
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(media.video)
                }
            }
        }

        if (info) {
            AlertDialog(
                onDismissRequest = { info = false },
                text = { Text("This user has a private account. Learn more in settings.") },
                confirmButton = {
                    TextButton(onClick = { info = false }) {
                        Text("OK")
                    }
                }
            )
        }
    }
}

@Composable
fun StatItem(title: String, value: String) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(text = value, style = MaterialTheme.typography.titleLarge)
        Text(text = title, style = MaterialTheme.typography.labelMedium)
    }
}