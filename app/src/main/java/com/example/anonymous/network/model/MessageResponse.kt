package com.example.anonymous.network.model

data class MessageResponse(
    val id: String,
    val encryptedContent: String,
    val iv: String,
    val authTag: String,
    val version: Int,
    val dhPublicKey: String,
    val sender: User?,
    val receiver: User?,
    val isRead: Boolean,
    val createdAt: String
)