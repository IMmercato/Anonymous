package com.example.anonymous.network

import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.scalars.ScalarsConverterFactory
import retrofit2.http.Body
import retrofit2.http.Headers
import retrofit2.http.POST

interface GraphQLService {
    @Headers("Content-Type: application/json")
    @POST("/graphql") // Adjust this endpoint according to your server's configuration
    suspend fun sendMutation(@Body requestBody: GraphQLRequest): Response<String>

    companion object {
        private const val BASE_URL = "https://immercato.hackclub.app"

        fun create(): GraphQLService {
            return Retrofit.Builder()
                .baseUrl(BASE_URL)
                .addConverterFactory(ScalarsConverterFactory.create())
                .build()
                .create(GraphQLService::class.java)
        }
    }
}