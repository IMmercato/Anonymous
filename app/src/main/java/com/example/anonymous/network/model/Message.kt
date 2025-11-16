package com.example.anonymous.network.model

import kotlinx.serialization.Serializable

@Serializable
data class Message(
    val id: String,
    val content: String, // This will be the *decrypted* content for UI display
    val encryptedContent: String, // This is the *encrypted* content stored
    val senderId: String,
    val receiverId: String,
    val timestamp: Long,
    val isRead: Boolean,
    val iv: String? = null, // For AES-GCM
    val authTag: String = "", // For AES-GCM, defaulting to empty string
    val version: Int = 1, // Default version
    val dhPublicKey: String? = null // For ECDH key exchange
)