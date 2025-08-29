package com.example.anonymous.network

import android.content.Context
import androidx.compose.foundation.layout.ColumnScope
import androidx.lifecycle.LifecycleService
import com.example.anonymous.utils.PrefsHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MessagePollingService : LifecycleService() {
    private var pollingJob: Job? = null
    private lateinit var messageService: GraphQLMessageService

    override fun onCreate() {
        super.onCreate()
        messageService = GraphQLMessageService(this)
        startPolling()
    }

    override fun onDestroy() {
        stopPolling()
        super.onDestroy()
    }

    private fun startPolling() {
        pollingJob = CoroutineScope(Dispatchers.IO).launch {
            while (true) {
                try {
                    val userId = PrefsHelper.getUserUuid(this@MessagePollingService)
                    if (!userId.isNullOrEmpty()) {
                        val messages = messageService.fetchMessages()
                        // Notify app about new messages (you can use LocalBroadcastManager or LiveData)
                        if (messages.isNotEmpty()) {
                            // Send broadcast or update local database
                        }
                    }
                    delay(10000) // Poll every 10 seconds
                } catch (e: Exception) {
                    delay(30000) // Wait longer on error
                }
            }
        }
    }

    private fun stopPolling() {
        pollingJob?.cancel()
    }

    companion object {
        fun startService(context: ColumnScope) {
            // Start the polling service
            // val intent = Intent(context, MessagePollingService::class.java)
            // context.startService(intent)
        }

        fun stopService(context: Context) {
            // Stop the polling service
            // val intent = Intent(context, MessagePollingService::class.java)
            // context.stopService(intent)
        }
    }
}