package com.example.anonymous.network.model

import kotlinx.serialization.Serializable

@Serializable
data class Message(
    val id: String,
    val content: String,
    val encryptedContent: String,
    val senderId: String,
    val receiverId: String,
    val timestamp: Long,
    val isRead: Boolean,
    val iv: String? = null, // For AES-GCM
    val dhPublicKey: String? = null // For ECDH key exchange
)