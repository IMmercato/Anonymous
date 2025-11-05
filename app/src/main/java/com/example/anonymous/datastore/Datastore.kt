package com.example.anonymous.datastore

import android.content.Context
import android.graphics.Color.parseColor
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb

val Context.chatDataStore: DataStore<Preferences> by preferencesDataStore(name = "chat_settings")
val Context.communityDataStore: DataStore<Preferences> by preferencesDataStore(name = "community_settings")

// Chat customization settings data class
data class ChatCustomizationSettings(
    val sentBubbleColor: Color = Color(0xFF4CAF50),
    val receivedBubbleColor: Color = Color(0xFF2196F3),
    val isSentRightAligned: Boolean = true
)

// Community customization settings data class
data class CommunityCustomizationSettings(
    val postCardColor: Color = Color(0xFFF5F5F5),
    val textSize: Int = 14,
    val showImages: Boolean = true
)

// Chat settings keys
private val SENT_BUBBLE_COLOR = stringPreferencesKey("sent_bubble_color")
private val RECEIVED_BUBBLE_COLOR = stringPreferencesKey("received_bubble_color")
private val IS_SENT_RIGHT_ALIGNED = booleanPreferencesKey("is_sent_right_aligned")

// Community settings keys
private val POST_CARD_COLOR = stringPreferencesKey("post_card_color")
private val TEXT_SIZE = intPreferencesKey("text_size")
private val SHOW_IMAGES = booleanPreferencesKey("show_images")

// Proper color conversion using toArgb()
private fun Color.toHexString(): String {
    return String.format("#%08X", this.toArgb())
}

// Function to safely parse color using toArgb() conversion
private fun safeParseColor(colorString: String): Color {
    return try {
        // Handle different color string formats
        val cleanColor = when {
            colorString.startsWith("#") -> colorString
            colorString.startsWith("0x") -> "#${colorString.substring(2)}"
            else -> "#$colorString"
        }

        // Parse the color string to integer and create Color
        val colorInt = parseColor(cleanColor)
        Color(colorInt)
    } catch (e: Exception) {
        // Return appropriate defaults based on expected color
        when {
            colorString.contains("4CAF50", ignoreCase = true) -> Color(0xFF4CAF50) // Green
            colorString.contains("2196F3", ignoreCase = true) -> Color(0xFF2196F3) // Blue
            colorString.contains("F5F5F5", ignoreCase = true) -> Color(0xFFF5F5F5) // Light Gray
            else -> Color(0xFF4CAF50) // Default fallback
        }
    }
}

// Chat settings functions
fun getChatCustomizationSettings(context: Context): Flow<ChatCustomizationSettings> {
    return context.chatDataStore.data.map { preferences ->
        ChatCustomizationSettings(
            sentBubbleColor = safeParseColor(preferences[SENT_BUBBLE_COLOR] ?: "#FF4CAF50"),
            receivedBubbleColor = safeParseColor(preferences[RECEIVED_BUBBLE_COLOR] ?: "#FF2196F3"),
            isSentRightAligned = preferences[IS_SENT_RIGHT_ALIGNED] ?: true
        )
    }
}

suspend fun saveChatCustomizationSettings(
    context: Context,
    settings: ChatCustomizationSettings
) {
    context.chatDataStore.edit { preferences ->
        preferences[SENT_BUBBLE_COLOR] = settings.sentBubbleColor.toHexString()
        preferences[RECEIVED_BUBBLE_COLOR] = settings.receivedBubbleColor.toHexString()
        preferences[IS_SENT_RIGHT_ALIGNED] = settings.isSentRightAligned
    }
}

// Community settings functions
fun getCommunityCustomizationSettings(context: Context): Flow<CommunityCustomizationSettings> {
    return context.communityDataStore.data.map { preferences ->
        CommunityCustomizationSettings(
            postCardColor = safeParseColor(preferences[POST_CARD_COLOR] ?: "#FFF5F5F5"),
            textSize = preferences[TEXT_SIZE] ?: 14,
            showImages = preferences[SHOW_IMAGES] ?: true
        )
    }
}

suspend fun saveCommunityCustomizationSettings(
    context: Context,
    settings: CommunityCustomizationSettings
) {
    context.communityDataStore.edit { preferences ->
        preferences[POST_CARD_COLOR] = settings.postCardColor.toHexString()
        preferences[TEXT_SIZE] = settings.textSize
        preferences[SHOW_IMAGES] = settings.showImages
    }
}