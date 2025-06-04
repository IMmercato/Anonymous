package com.example.anonymous.network

data class GraphQLRequest(
    val query: String,
    val variables: Map<String, Any?>
)