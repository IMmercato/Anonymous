package com.example.anonymous.i2p.config

import android.content.Context
import android.util.Log
import java.io.File

object I2pdConfig {

    private const val TAG = "I2pdConfig"

    fun copyCertificates(context: Context) {
        val certificatesDest = File(context.filesDir, "i2pd/certificates")
        certificatesDest.mkdirs()

        val assets = context.assets

        val topLevel: Array<String>? = try {
            assets.list("certificates")
        } catch (e: Exception) {
            Log.w(TAG, "No 'certificates' asset folder found: ${e.message}")
            null
        }

        if (topLevel.isNullOrEmpty()) {
            Log.w(TAG, "certificates/ asset folder is empty - daemon may fail to reseed!")
            return
        }

        var copied = 0
        var skipped = 0

        fun copyAsset(assetPath: String, destFile: File) {
            val children: Array<String>? = try { assets.list(assetPath) } catch (e: Exception) { null }

            if (children.isNullOrEmpty()) {
                if (!destFile.exists()) {
                    try {
                        assets.open(assetPath).use { inputStream ->
                            destFile.outputStream().use { outputStream -> inputStream.copyTo(outputStream) }
                        }
                        copied++
                        Log.d(TAG, "Copied: $assetPath")
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to copy $assetPath: ${e.message}")
                    }
                } else {
                    skipped++
                }
            } else {
                destFile.mkdirs()
                children.forEach { child ->
                    copyAsset("$assetPath/$child", File(destFile, child))
                }
            }
        }

        topLevel.forEach { entry ->
            copyAsset("certificates/$entry", File(certificatesDest, entry))
        }

        Log.i(TAG, "Certificates ready: $copied copied, $skipped already present")
        Log.i(TAG, "Cert tree root: ${certificatesDest.absolutePath}")
        certificatesDest.walkTopDown().filter { it.isFile }.forEach { f ->
            Log.d(TAG, "  cert file: ${f.relativeTo(certificatesDest)}")
        }
    }

    fun copyCABundle(context: Context) {
        val destFile = File(context.filesDir, "i2pd/cacert.pem")
        if (!destFile.exists()) {
            try {
                context.assets.open("cacert.pem").use { inputStream ->
                    destFile.outputStream().use { outputStream ->
                        inputStream.copyTo(outputStream)
                    }
                }
                Log.i(TAG, "Copied cacert.pem to ${destFile.absolutePath}")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to copy cacert.pem: ${e.message}")
            }
        } else {
            Log.d(TAG, "cacert.pem already exists, skipping copy")
        }
    }

    fun generateConfig(context: Context): String {
        val dataDir = File(context.filesDir, "i2pd")

        return buildString {
            appendLine("# i2pd configuration (Android embedded)")
            appendLine("# datadir is set via JNI setDataDir() - do not add it here")
            appendLine()
            appendLine("log = file")
            appendLine("logfile = ${dataDir.absolutePath}/i2pd.log")
            appendLine("loglevel = debug")
            appendLine("logclftime = true")
            appendLine()
            appendLine("daemon = false")
            appendLine("notransit = true")
            appendLine("floodfill = false")
            appendLine("bandwidth = L")
            appendLine("ipv4 = true")
            appendLine("ipv6 = false")
            appendLine()
            appendLine("[sam]")
            appendLine("enabled = true")
            appendLine("address = 127.0.0.1")
            appendLine("port = 7656")
            appendLine("portudp = 7655")
            appendLine()
            appendLine("[limits]")
            appendLine("transittunnels = 0")
            appendLine("openfiles = 128")
            appendLine()
            appendLine("[ssu2]")
            appendLine("enabled = true")
            appendLine("published = false")
            appendLine()
            appendLine("[ntcp2]")
            appendLine("enabled = true")
            appendLine("published = false")
            appendLine("port = 0")
            appendLine()
            appendLine("[exploratory]")
            appendLine("inbound.length = 2")
            appendLine("inbound.quantity = 3")
            appendLine("outbound.length = 2")
            appendLine("outbound.quantity = 3")
            appendLine()
            appendLine("[reseed]")
            appendLine("verify = true")
            appendLine("threshold = 25")
            appendLine("urls = https://reseed.diva.exchange/,https://reseed2.i2p.net/,https://reseed-pl.i2pd.xyz/,https://i2p.novg.net/,https://reseed.memcpy.io/,https://reseed.i2pgit.org/")
            appendLine()
            appendLine("[http]")
            appendLine("enabled = false")
            appendLine()
            appendLine("[httpproxy]")
            appendLine("enabled = false")
            appendLine()
            appendLine("[socksproxy]")
            appendLine("enabled = false")
            appendLine()
            appendLine("[bob]")
            appendLine("enabled = false")
            appendLine()
            appendLine("[i2cp]")
            appendLine("enabled = false")
            appendLine()
            appendLine("[i2pcontrol]")
            appendLine("enabled = false")
            appendLine()
            appendLine("[precomputation]")
            appendLine("elgamal = false")
            appendLine()
            appendLine("[persist]")
            appendLine("profiles = false")
            appendLine("addressbook = true")
            appendLine()
            appendLine("[upnp]")
            appendLine("enabled = false")
        }
    }

    fun writeConfig(context: Context): File {
        val dataDir = File(context.filesDir, "i2pd")
        if (!dataDir.exists()) {
            dataDir.mkdirs()
            Log.i(TAG, "Created data dir: ${dataDir.absolutePath}")
        }

        listOf("netDb", "addressbook", "certificates", "tunnels.d", "destinations").forEach { subdir ->
            File(dataDir, subdir).mkdirs()
        }

        val configFile = File(dataDir, "i2pd.conf")
        val configText = generateConfig(context)
        configFile.writeText(configText)
        Log.i(TAG, "Wrote config to: ${configFile.absolutePath} (${configText.length} bytes)")

        return configFile
    }
}