package com.example.anonymous.datastore

import android.content.Context
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

// Function to safely parse color
private fun safeParseColor(colorString: String): Color {
    return try {
        Color(android.graphics.Color.parseColor(colorString))
    } catch (e: IllegalArgumentException) {
        Color(0xFF4CAF50) // Default color if parsing fails
    }
}

// Chat settings functions
fun getChatCustomizationSettings(context: Context): Flow<ChatCustomizationSettings> {
    return context.chatDataStore.data.map { preferences ->
        ChatCustomizationSettings(
            sentBubbleColor = safeParseColor(preferences[SENT_BUBBLE_COLOR] ?: "#4CAF50"),
            receivedBubbleColor = safeParseColor(preferences[RECEIVED_BUBBLE_COLOR] ?: "#2196F3"),
            isSentRightAligned = preferences[IS_SENT_RIGHT_ALIGNED] ?: true
        )
    }
}

suspend fun saveChatCustomizationSettings(
    context: Context,
    settings: ChatCustomizationSettings
) {
    context.chatDataStore.edit { preferences ->
        preferences[SENT_BUBBLE_COLOR] = settings.sentBubbleColor.toString()
        preferences[RECEIVED_BUBBLE_COLOR] = settings.receivedBubbleColor.toString()
        preferences[IS_SENT_RIGHT_ALIGNED] = settings.isSentRightAligned
    }
}

// Community settings functions
fun getCommunityCustomizationSettings(context: Context): Flow<CommunityCustomizationSettings> {
    return context.communityDataStore.data.map { preferences ->
        CommunityCustomizationSettings(
            postCardColor = safeParseColor(preferences[POST_CARD_COLOR] ?: "#F5F5F5"),
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
        preferences[POST_CARD_COLOR] = settings.postCardColor.toString()
        preferences[TEXT_SIZE] = settings.textSize
        preferences[SHOW_IMAGES] = settings.showImages
    }
}