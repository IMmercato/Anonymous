package com.example.anonymous

import android.app.Application
import android.content.Context
import android.util.Log

class AnonymousApp : Application() {

    companion object {
        private const val TAG = "AnonymousApp"
    }

    override fun onCreate() {
        super.onCreate()

        val pid = android.os.Process.myPid()
        val prefs = getSharedPreferences("i2p_process", Context.MODE_PRIVATE)
        val lastPid = prefs.getInt("last_pid", -1)
        val lastStartTime = prefs.getLong("last_start_time", 0)
        val now = System.currentTimeMillis()

        val isNewProcess = lastPid != pid
        val isRapidRestart = (now - lastStartTime) < 5000 && lastPid == pid

        if (isNewProcess) {
            Log.w(TAG, "NEW PROCESS DETECTED: $lastPid -> $pid")
        } else if (isRapidRestart) {
            Log.w(TAG, "RAPID RESTART DETECTED - possible crash")
        } else {
            Log.i(TAG, "Same process startup (pid=$pid, uptime=${now - lastStartTime}ms)")
        }

        prefs.edit()
            .putInt("last_pid", pid)
            .putLong("last_start_time", now)
            .apply()
    }

    override fun onTerminate() {
        Log.i(TAG, "AnonymousApp terminating")
        super.onTerminate()
    }

    override fun onLowMemory() {
        super.onLowMemory()
        Log.w(TAG, "Low memory warning")
    }
}