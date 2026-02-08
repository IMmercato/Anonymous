package com.example.anonymous.i2p

import android.util.Log

/**
 * JNI Bridge
 */
object I2pdJNI {
    private const val TAG = "I2pdJNI"

    init {
        try {
            System.loadLibrary("i2pd")
            Log.i(TAG, "i2pd library loaded successfully")
        } catch (e: UnsatisfiedLinkError) {
            Log.e(TAG, "Failed to load i2pd library", e)
            throw RuntimeException("Cannot load i2pd native library", e)
        }
    }

    external fun getABICompiledWith(): String
    external fun startDaemon(): String  // Returns "ok" or error message
    external fun stopDaemon()
    external fun startAcceptingTunnels()
    external fun stopAcceptingTunnels()
    external fun reloadTunnelsConfigs()
    external fun setDataDir(dataDir: String)
    external fun setLanguage(language: String)
    external fun getTransitTunnelsCount(): Int
    external fun getWebConsAddr(): String
    external fun getDataDir(): String
    external fun getHTTPProxyState(): Boolean
    external fun getSOCKSProxyState(): Boolean
    external fun getBOBState(): Boolean
    external fun getSAMState(): Boolean
    external fun getI2CPState(): Boolean
    external fun onNetworkStateChanged(isConnected: Boolean)
}