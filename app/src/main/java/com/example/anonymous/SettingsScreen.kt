package com.example.anonymous

import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.example.anonymous.network.GraphQLRequest
import com.example.anonymous.network.GraphQLService
import com.example.anonymous.network.QueryBuilder
import kotlinx.coroutines.launch

class TrustScoreShape : Shape {
    override fun createOutline(
        size: Size,
        layoutDirection: LayoutDirection,
        density: Density
    ): Outline {
        val path = Path().apply {
            moveTo(0f, 0f) // Top center
            lineTo(0f, size.height)     // Bottom left
            lineTo(size.width, size.height) // Bottom right
            lineTo(size.width, 0f)
            close()
        }
        return Outline.Generic(path)
    }
}

@Composable
fun SettingsScreen() {
    val context = LocalContext.current
    var showTrustScore by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Hi Anonymous!",
                        style = MaterialTheme.typography.titleMedium
                    )
                    Spacer(modifier = Modifier.weight(1f))
                    Icon(Icons.Rounded.CheckCircle, null, modifier = Modifier
                        .clickable { showTrustScore = !showTrustScore }
                        .padding(8.dp))
                }
                if (showTrustScore) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Box(
                        modifier = Modifier
                            .padding(8.dp)
                            .size(200.dp, 50.dp)
                            .clip(TrustScoreShape())
                            .background(
                                Brush.horizontalGradient(listOf(Color.Red, Color.Yellow, Color.Green)),
                                shape = RoundedCornerShape(16.dp)
                            )
                    ) {
                        IconButton(onClick = {
                            Toast.makeText(context, "Trust Score", Toast.LENGTH_SHORT).show()
                        }) {
                            Icon(Icons.Default.KeyboardArrowUp, null)
                        }
                    }
                }
            }
        }

        SettingsCard(
            title = "Account Settings",
            icon = Icons.Default.Settings,
            description = "Manage login preferences",
            onClick = { context.startActivity(Intent(context, LoginActivity::class.java)) }
        )

        SettingsCard(
            title = "Registration",
            icon = Icons.Default.Person,
            description = "Sign up for an account",
            onClick = { context.startActivity(Intent(context, RegistrationActivity::class.java)) }
        )

        SettingsCard(
            title = "Chat Customization",
            icon = Icons.Default.Email,
            description = "Customize your chat experience",
            onClick = { context.startActivity(Intent(context, ChatCustomizationActivity::class.java)) }
        )

        SettingsCard(
            title = "Community Customization",
            icon = Icons.Default.Face,
            description = "Personalize your community settings",
            onClick = { context.startActivity(Intent(context, CommunityCustomizationActivity::class.java)) }
        )
        CreateUserButton()
    }
}

@Composable
fun SettingsCard(title: String, icon: ImageVector, description: String, onClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
        onClick = onClick
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(imageVector = icon, contentDescription = title, modifier = Modifier.size(32.dp))
            Spacer(modifier = Modifier.width(12.dp))
            Column {
                Text(text = title, style = MaterialTheme.typography.titleMedium)
                Text(text = description, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

// User creating by clicking a button and responding with the status of the request
@Composable
fun CreateUserButton() {
    val coroutineScope = rememberCoroutineScope()
    val context = LocalContext.current

    IconButton(onClick = {
        val request = GraphQLRequest( query = QueryBuilder.createUser("pubKey"))

        coroutineScope.launch {
            val service = GraphQLService.create()
            val response = service.createUser(request)

            if (response.isSuccessful) {
                val user = response.body()?.data?.createUser
                if (user != null) {
                    Toast.makeText(context, "✅ Created User: ${user.id}", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(context, "⚠️ No user returned.", Toast.LENGTH_SHORT).show()
                }
            } else {
                val errors = response.body()?.errors
                Toast.makeText(context, "❌ GraphQL Error: ${errors?.joinToString { it.message }}", Toast.LENGTH_SHORT).show()
            }
        }
    }) {
        Icon(Icons.Default.Check, contentDescription = "Check the connection")
    }
}