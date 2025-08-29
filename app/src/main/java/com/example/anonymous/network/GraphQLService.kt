package com.example.anonymous.network

import android.content.Context
import com.example.anonymous.network.model.*
import com.example.anonymous.utils.PrefsHelper
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Body
import retrofit2.http.Headers
import retrofit2.http.POST
import java.util.concurrent.TimeUnit

interface GraphQLService {
    @Headers("Content-Type: application/json")
    @POST("/graphql")
    suspend fun sendMessage(
        @Body request: GraphQLRequest
    ): Response<GraphQLResponse<SendMessageData>>

    @Headers("Content-Type: application/json")
    @POST("/graphql")
    suspend fun getMessages(
        @Body request: GraphQLRequest
    ): Response<GraphQLResponse<GetMessagesData>>

    @Headers("Content-Type: application/json")
    @POST("/graphql")
    suspend fun registerUser(
        @Body request: GraphQLRequest
    ): Response<GraphQLResponse<RegisterUserData>>

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

    @Headers("Content-Type: application/json")
    @POST("/graphql")
    suspend fun executeQuery(
        @Body request: GraphQLRequest
    ): Response<GraphQLResponse<Any>>

    companion object {
        private const val BASE_URL = "https://immercato.hackclub.app/"

        fun create(context: Context): GraphQLService {
            val loggingInterceptor = HttpLoggingInterceptor().apply {
                level = HttpLoggingInterceptor.Level.BODY
            }

            // Create an interceptor to add the auth token
            val authInterceptor = Interceptor { chain ->
                val originalRequest = chain.request()

                // Get the session token from SharedPreferences
                val sessionToken = PrefsHelper.getSessionToken(context)

                val newRequest = if (!sessionToken.isNullOrEmpty()) {
                    // Add Authorization header if token exists
                    originalRequest.newBuilder()
                        .header("Authorization", "Bearer $sessionToken")
                        .header("Content-Type", "application/json")
                        .method(originalRequest.method, originalRequest.body)
                        .build()
                } else {
                    // Just add Content-Type if no token
                    originalRequest.newBuilder()
                        .header("Content-Type", "application/json")
                        .method(originalRequest.method, originalRequest.body)
                        .build()
                }

                chain.proceed(newRequest)
            }

            val client = OkHttpClient.Builder()
                .addInterceptor(loggingInterceptor)
                .addInterceptor(authInterceptor) // Add the auth interceptor
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .build()

            return Retrofit.Builder()
                .baseUrl(BASE_URL)
                .client(client)
                .addConverterFactory(GsonConverterFactory.create())
                .build()
                .create(GraphQLService::class.java)
        }
    }
}