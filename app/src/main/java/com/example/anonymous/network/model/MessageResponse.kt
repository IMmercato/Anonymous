package com.example.anonymous.network.model

data class MessageResponse(
    val id: String,
    val content: String,
    val encryptedContent: String,
    val sender: User?,
    val receiver: User?,
    val isRead: Boolean,
    val createdAt: String
)