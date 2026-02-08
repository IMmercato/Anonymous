package com.example.anonymous.i2p.config

import android.content.Context
import java.io.File

object I2pdConfig {

    /**
    * Generate Client-Only i2pd.conf
    */
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
            
            ## Service configuration
            [http]
            enabled = true
            address = 127.0.0.1
            port = 7070
            
            ## SAM
            [sam]
            enabled = true
            address = 127.0.0.1
            port = 7656
            portudp = 7656
            singlethread = false
            
            ## Disable transit tunnels (client-only mode)
            notransit = true
            
            ## Disable floodfill mode
            floodfill = false
            
            ## Bandwidth limits
            bandwidth = L       # L = Limited, 0 = Unlimited
            share = 0
            
            ## Transit tunnel limits
            [limits]
            transittunnels = 0
            coresize = 1
            openfiles = 256
            ntcphard = 64
            ntcpsoft = 16
            ntcphreads = 1
            
            ## Network Protocols
            ipv4 = true
            ipv6 = true
            
            ## SSU (UDP transport)
            ssu = true
            ssu.enabled = true
            
            ## NTCP (TCP transport)
            ntcp = false
            
            ## NTCP2 (moder TCP transport)
            ntcp2.enabled = true
            ntcp2.published = false
            ntcp2.port = 0
            
            ## Exploratory Tunnels
            [exploratory]
            inbound.length = 2
            inbound.quantity = 2
            outbound.length = 2
            outbound.quantity = 2
            
            ## Tunnel settings
            [tunnels]
            ## Crypto
            crypto.tagtimeout = 2400
            
            ## HTTP Console
            [http]
            enabled = true      # DEBUG
            address = 127.0.0.1
            port = 7070
            auth = true
            user = admin
            pass =
            stricthreaders = true
            hostname = localhost
            webroot = /
            
            ## Reseed
            [reseed]
            verify = true
            threshold = 25
            urls = https://reseed.i2p-projekt.de/,https://i2p.mooo.com/netDb/,https://reseed.memcpy.io/,https://reseed-fr.i2pd.xyz/,https://reseed.onion.im/
            
            ## AddressBook
            [addressbook]
            defaulturl = http://shx5vqsw7usdaunyzr2qmes2fq37oumybpudrd4jjj4e4vk4uusa.b32.i2p/hosts.txt
            subscription = http://stats.i2p/cgi-bin/newhosts.txt,http://i2p-projekt.i2p/hosts.txt
            
            ## UPNP
            [upnp]
            enabled = true
            name = I2Pd
            
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
            elgamal = true
            
            ## Performance & Persistence
            [persist]
            profiles = true
            
            ## CPU
            cpuext = true
            
            ## Trust
            [trust]
            enabled = false
            family =
            hidden = false
        """.trimIndent()
    }

    fun writeConfig(context: Context): File {
        val configDir = File(context.filesDir, "i2pd")
        if (!configDir.exists()) {
            configDir.mkdirs()
        }

        val configFile = File(configDir, "i2pd.conf")
        configFile.writeText(generateConfig(context))

        return configFile
    }
}