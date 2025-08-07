package com.example.anonymous.model

import kotlinx.serialization.Serializable

@Serializable
data class Message(
    val id: String,
    val content: String,
    val encryptedContent: String,
    val senderId: String,
    val receiverId: String,
    val timestamp: Long,
    val isRead: Boolean = false
)