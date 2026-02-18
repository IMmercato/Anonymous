package com.example.anonymous.i2p.config

import android.content.Context
import java.io.File

object I2pdConfig {

    fun generateConfig(context: Context): String {
        val dataDir = File(context.filesDir, "i2pd")

        return """
            # i2pd Client-Only Configuration
            
            ## Data directory
            datadir = $dataDir
            
            ## Logging
            log = file
            logfile = $dataDir/i2pd.log
            loglevel = warn
            logclftime = false
            
            ## Daemon mode (false for embedded)
            daemon = false
            
            ## Network id
            netid = 2
            
            ## SAM bridge
            [sam]
            enabled = true
            address = 127.0.0.1
            port = 7656
            portudp = 7655
            singlethread = true
            
            ## Disable transit tunnels (client-only mode)
            notransit = true
            
            ## Disable floodfill mode
            floodfill = false
            
            ## Bandwidth limits
            bandwidth = L
            share = 0
            
            ## Transit tunnel limits - minimized threads
            [limits]
            transittunnels = 0
            coresize = 1
            openfiles = 128
            ntcphard = 32
            ntcpsoft = 8
            ntcphreads = 1
            
            ## Network Protocols
            ipv4 = true
            ipv6 = false
            
            ## SSU2 (UDP transport)
            [ssu2]
            enabled = true
            published = false
            
            ## NTCP2 (TCP transport)
            [ntcp2]
            enabled = true
            published = false
            port = 0
            
            ## Exploratory Tunnels - reduced for mobile
            [exploratory]
            inbound.length = 2
            inbound.quantity = 2
            outbound.length = 2
            outbound.quantity = 2
            
            ## Crypto
            [crypto]
            tagtimeout = 2400
            
            ## HTTP Console
            [http]
            enabled = false
            
            ## Reseed - disabled verify for faster startup
            [reseed]
            verify = false
            threshold = 25
            urls = https://reseed.i2p-projekt.de/,https://i2p.mooo.com/netDb/,https://reseed.memcpy.io/,https://reseed-fr.i2pd.xyz/,https://reseed.onion.im/
            
            ## AddressBook
            [addressbook]
            defaulturl = http://shx5vqsw7usdaunyzr2qmes2fq37oumybpudrd4jjj4e4vk4uusa.b32.i2p/hosts.txt
            subscription = http://stats.i2p/cgi-bin/newhosts.txt,http://i2p-projekt.i2p/hosts.txt
            
            ## UPnP
            [upnp]
            enabled = false
            
            ## Disabled Services
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
            
            ## Precomputation
            [precomputation]
            elgamal = false
            
            ## Persistence
            [persist]
            profiles = false
            
            ## CPU extensions - disabled for stability
            cpuext = false
            
            ## Trust
            [trust]
            enabled = false
            family =
            hidden = false
        """.trimIndent()
    }

    fun writeConfig(context: Context): File {
        val dataDir = File(context.filesDir, "i2pd")
        if (!dataDir.exists()) {
            dataDir.mkdirs()
        }

        listOf("netDb", "addressbook", "certificates", "tunnels.d").forEach {
            File(dataDir, it).mkdirs()
        }

        val configFile = File(dataDir, "i2pd.conf")
        configFile.writeText(generateConfig(context))

        return configFile
    }
}