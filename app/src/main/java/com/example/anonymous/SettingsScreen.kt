package com.example.anonymous

import android.content.Intent
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

@Composable
fun SettingsScreen() {
    val context = LocalContext.current
    IconButton(onClick = {
        context.startActivity(Intent(context, LoginActivity::class.java))
    }) {
        Icon(Icons.Default.Settings, contentDescription = "Settings")
    }
    IconButton(onClick = {
        context.startActivity(Intent(context, RegistrationActivity::class.java))
    }) {
        Icon(Icons.Default.Person, contentDescription = "Registration")
    }
}