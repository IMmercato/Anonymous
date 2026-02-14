package com.example.anonymous.network.model

import kotlinx.serialization.Serializable

@Serializable
data class Contact(
    val b32Address: String,             // I2P identity
    val name: String,
    val publicKey: String,
    val lastMessage: String? = null,
    val timestamp: Long? = null,
    val isVerified: Boolean = false
)