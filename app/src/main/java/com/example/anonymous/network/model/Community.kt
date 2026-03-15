package com.example.anonymous.network.model

import kotlinx.serialization.Serializable

@Serializable
data class Community(
    val b32Address: String,
    val name: String,
    val samPrivateKey: String? = null,
    val groupKeyBase64: String,         // AES-256 shared key
    val isCreator: Boolean = false,
    val createdAt: Long = System.currentTimeMillis()
)

@Serializable
data class CommunityMessage(
    val id: String,
    val senderB32: String,
    val senderName: String,
    val content: String,
    val timestamp: Long = System.currentTimeMillis(),
    val communityB32: String
)

@Serializable
data class CommunityPacket(
    val type: String,
    val senderB32: String,
    val senderName: String,
    val payload: String,
    val timestamp: Long
)
