package com.example.anonymous

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import com.example.anonymous.ui.theme.AnonymousTheme

class ProvaActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContent {
            AnonymousTheme {
                ProvaScreen()
            }
        }
    }
}

@Composable
fun ProvaScreen() {
    val context = LocalContext.current
    Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            DisplayButtons()
        }
    }
}

@Composable
fun DisplayButtons() {
    val context = LocalContext.current

    Button(
        onClick = {
            Toast.makeText(context, "HELLO!", Toast.LENGTH_SHORT).show()
        }
    ) {
        Text("Hello World!")
    }

    Spacer(modifier = Modifier.height(16.dp))

    Button(
        onClick = {
            val intent = Intent(context, RegistrationActivity::class.java)
            context.startActivity(intent)
        }
    ) {
        Text("Register")
    }
}