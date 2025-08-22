package com.example.anonymous.network

import com.example.anonymous.network.model.CreateUserData
import com.example.anonymous.network.model.CompleteLoginData
import com.example.anonymous.network.model.LoginWithJwtData
import com.example.anonymous.network.model.RegisterUserData
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Body
import retrofit2.http.Headers
import retrofit2.http.POST

interface GraphQLService {
    @Headers("Content-Type: application/json")
    @POST("/graphql")
    suspend fun registerUser(
        @Body request: GraphQLRequest
    ): Response<GraphQLResponse<RegisterUserData>>

    @Headers("Content-Type: application/json")
    @POST("/graphql")
    suspend fun createUser(
        @Body request: GraphQLRequest
    ): Response<GraphQLResponse<CreateUserData>>

    @Headers("Content-Type: application/json")
    @POST("/graphql")
    suspend fun loginWithJwt(
        @Body request: GraphQLRequest
    ): Response<GraphQLResponse<LoginWithJwtData>>

    @Headers("Content-Type: application/json")
    @POST("/graphql")
    suspend fun completeLogin(
        @Body request: GraphQLRequest
    ): Response<GraphQLResponse<CompleteLoginData>>

    companion object {
        private const val BASE_URL = "https://immercato.hackclub.app/"

        fun create(): GraphQLService {
            return Retrofit.Builder()
                .baseUrl(BASE_URL)
                .addConverterFactory(GsonConverterFactory.create())
                .build()
                .create(GraphQLService::class.java)
        }
    }
}