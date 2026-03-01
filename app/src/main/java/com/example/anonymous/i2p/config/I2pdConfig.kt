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

        // Log the full cert tree so we can verify the structure i2pd expects
        Log.i(TAG, "Certificates ready: $copied copied, $skipped already present")
        Log.i(TAG, "Cert tree root: ${certificatesDest.absolutePath}")
        certificatesDest.walkTopDown().filter { it.isFile }.forEach { f ->
            Log.d(TAG, "  cert file: ${f.relativeTo(certificatesDest)}")
        }
    }

    fun generateConfig(context: Context): String {
        val dataDir = File(context.filesDir, "i2pd")

        return """
            # i2pd Mobile Configuration
            # NOTE: datadir is intentionally omitted — set via JNI setDataDir() instead.

            # Logging — debug level so we see output before logger fully inits
            log = file
            logfile = ${dataDir}/i2pd.log
            loglevel = debug
            logclftime = true

            # Daemon mode (false for embedded/JNI use)
            daemon = false

            # Network id — MAINNET (1)
            netid = 1

            # Client-only: do not route traffic for others
            notransit = true
            floodfill = false

            # Bandwidth
            bandwidth = L

            # Network protocols
            ipv4 = true
            ipv6 = false

            # SAM bridge
            [sam]
            enabled = true
            address = 127.0.0.1
            port = 7656
            portudp = 7655
            singlethread = false

            # Transit tunnel limits
            [limits]
            transittunnels = 0
            coresize = 4
            openfiles = 128
            ntcphard = 16
            ntcpsoft = 8

            # SSU2 (UDP transport)
            [ssu2]
            enabled = true
            published = false

            # NTCP2 (TCP transport)
            [ntcp2]
            enabled = true
            published = false
            port = 0

            # Exploratory tunnels
            [exploratory]
            inbound.length = 2
            inbound.quantity = 3
            outbound.length = 2
            outbound.quantity = 3

            # Crypto
            [crypto]
            tagtimeout = 2400

            # HTTP console
            [http]
            enabled = true
            address = 127.0.0.1
            port = 7070

            # Reseed
            [reseed]
            verify = true
            threshold = 25
            urls = https://reseed.i2p-projekt.de/,https://i2p.mooo.com/netDb/,https://reseed.memcpy.io/

            # AddressBook
            [addressbook]
            defaulturl = http://shx5vqsw7usdaunyzr2qmes2fq37oumybpudrd4jjj4e4vk4uusa.b32.i2p/hosts.txt
            subscriptions = http://stats.i2p/cgi-bin/newhosts.txt,http://i2p-projekt.i2p/hosts.txt

            # UPnP
            [upnp]
            enabled = false

            # Disabled services
            [httpproxy]
            enabled = false

            [socksproxy]
            enabled = false

            [bob]
            enabled = false

            [i2cp]
            enabled = false

            [i2pcontrol]
            enabled = false

            # Precomputation
            [precomputation]
            elgamal = false

            # Persistence
            [persist]
            profiles = false
            addressbook = true

            # Trust — family intentionally omitted (empty value causes parse errors)
            [trust]
            enabled = false
            hidden = false
        """.trimIndent()
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