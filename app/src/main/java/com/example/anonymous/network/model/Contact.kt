package com.example.anonymous.network.model

import kotlinx.serialization.Serializable

@Serializable
data class Contact(
    val uuid: String,
    val name: String,
    val publicKey: String,
    val lastMessage: String? = null,
    val timestamp: Long? = null
)