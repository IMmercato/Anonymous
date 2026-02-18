package com.example.anonymous.i2p

import org.purplei2p.i2pd.I2PD_JNI

object I2pdJNI {
    fun setDataDir(dataDir: String)             = I2PD_JNI.setDataDir(dataDir)
    fun startDaemon(): String                   = I2PD_JNI.startDaemon()
    fun stopDaemon()                            = I2PD_JNI.stopDaemon()
    fun getSAMState(): Boolean                  = I2PD_JNI.getSAMState()
    fun getWebConsAddr(): String                = I2PD_JNI.getWebConsAddr()
    fun getDataDir(): String                    = I2PD_JNI.getDataDir()
    fun getABICompiledWith(): String            = I2PD_JNI.getABICompiledWith()
    fun getTransitTunnelsCount(): Int           = I2PD_JNI.getTransitTunnelsCount()
    fun getHTTPProxyState(): Boolean            = I2PD_JNI.getHTTPProxyState()
    fun getSOCKSProxyState(): Boolean           = I2PD_JNI.getSOCKSProxyState()
    fun getBOBState(): Boolean                  = I2PD_JNI.getBOBState()
    fun getI2CPState(): Boolean                 = I2PD_JNI.getI2CPState()
    fun onNetworkStateChanged(isConnected: Boolean) = I2PD_JNI.onNetworkStateChanged(isConnected)
    fun startAcceptingTunnels()                 = I2PD_JNI.startAcceptingTunnels()
    fun stopAcceptingTunnels()                  = I2PD_JNI.stopAcceptingTunnels()
    fun reloadTunnelsConfigs()                  = I2PD_JNI.reloadTunnelsConfigs()
    fun setLanguage(language: String)           = I2PD_JNI.setLanguage(language)
}