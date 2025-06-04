package com.example.anonymous.network

data class GraphQLResponse(
    val data: Map<String, Any>?, // Adjust based on actual API response
    val errors: List<String>?
)