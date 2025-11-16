package com.example.anonymous.network.model

data class GraphQLSubscriptionMessage(
    val id: String? = null,
    val type: String,
    val payload: Map<String, Any>? = null
)

data class UserResponse(
    val id: String
)