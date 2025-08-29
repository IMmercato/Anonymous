package com.example.anonymous.network

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.localbroadcastmanager.content.LocalBroadcastManager

class MessageReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            "NEW_MESSAGE_RECEIVED" -> {
                // Handle new message notification
                // You can refresh your UI here
            }
        }
    }

    companion object {
        fun register(context: Context, receiver: MessageReceiver) {
            val filter = IntentFilter().apply {
                addAction("NEW_MESSAGE_RECEIVED")
            }
            LocalBroadcastManager.getInstance(context).registerReceiver(receiver, filter)
        }

        fun unregister(context: Context, receiver: MessageReceiver) {
            LocalBroadcastManager.getInstance(context).unregisterReceiver(receiver)
        }

        fun sendNewMessageBroadcast(context: Context) {
            val intent = Intent("NEW_MESSAGE_RECEIVED")
            LocalBroadcastManager.getInstance(context).sendBroadcast(intent)
        }
    }
}