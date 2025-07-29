package com.example.anonymous

import android.content.Intent
import android.widget.Toast
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/*class TrustScoreShape : Shape {
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
}*/

@Composable
fun SettingsScreen() {
    val context = LocalContext.current
    var showTrustScore by remember { mutableStateOf(false) }
    var trustScore by remember { mutableStateOf(0.72f) }

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

                    TrustScoreBar(
                        score = trustScore,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(50.dp)
                            .padding(8.dp),
                        onClick = {
                            Toast
                                .makeText(context, "Trust Score details", Toast.LENGTH_SHORT)
                                .show()
                        },
                        gradientColors = listOf(Color(0xFF1E1F26), Color(0xFF434750), Color(0xFF009B77)),
                        cornerRadius = 16.dp
                    )
                    /*Box(
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
                    }*/
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
    }
}

@Composable
fun TrustScoreBar(
    score: Float,
    modifier: Modifier = Modifier,
    onClick: () -> Unit = {},
    gradientColors: List<Color> = listOf(
        Color(0xFF1E1F26), Color(0xFF434750), Color(0xFF009B77)
    ),
    height: Dp = 24.dp,
    cornerRadius: Dp = 12.dp
) {
    // Animate the fill fraction
    val animatedFraction by animateFloatAsState(
        targetValue = score.coerceIn(0f, 1f),
        animationSpec = tween(durationMillis = 800, easing = FastOutSlowInEasing)
    )

    Box(
        modifier = modifier
            .height(height)
            .fillMaxWidth()
            .clip(RoundedCornerShape(cornerRadius))
            .background(Color.LightGray.copy(alpha = 0.3f))
            .clickable(
                onClick = onClick,
                indication = ripple(bounded = true, radius = height),
                interactionSource = remember { MutableInteractionSource() }
            )
    ) {
        // Gradient fill
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .fillMaxWidth(animatedFraction)
                .background(
                    brush = Brush.horizontalGradient(gradientColors),
                    shape = RoundedCornerShape(cornerRadius)
                )
        )

        // Percentage text
        Text(
            text = "${(animatedFraction * 100).roundToInt()}%",
            modifier = Modifier.align(Alignment.Center),
            style = MaterialTheme.typography.bodyMedium.copy(color = Color.White),
            maxLines = 1
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