package com.example.anonymous

import android.content.Intent
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Face
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp

@Composable
fun SettingsScreen() {
    val context = LocalContext.current

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
            Text(
                text = "Hi Anonymous!",
                modifier = Modifier.padding(16.dp),
                style = MaterialTheme.typography.titleMedium
            )
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