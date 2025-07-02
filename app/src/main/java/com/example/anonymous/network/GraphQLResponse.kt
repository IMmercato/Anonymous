package com.example.anonymous.network

data class GraphQLResponse<T>(
    val data: T?,
    val errors: List<GraphQLError>? = null
)

data class GraphQLError(
    val message: String
)