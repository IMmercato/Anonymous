package com.example.anonymous.i2p

import android.util.Base64
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okio.IOException
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.io.PrintWriter
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.Socket
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

class SAMClient private constructor() {
    companion object {
        private const val TAG = "SAMClient"
        private const val SAM_HOST = "127.0.0.1"
        private const val SAM_PORT = 7656
        private const val SAM_VERSION = "3.1"
        private const val SIGNATURE_TYPE = "7"
        private const val HANDSHAKE_TIMEOUT_MS = 30_000

        @Volatile
        private var instance: SAMClient? = null

        fun getInstance(): SAMClient {
            return instance ?: synchronized(this) {
                instance ?: SAMClient().also {
                    instance = it
                }
            }
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val sessionCounter = AtomicInteger(0)
    private val activeSessions = ConcurrentHashMap<String, SAMSession>()
    private val _isConnected = MutableStateFlow(false)
    val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()

    data class SAMSession(
        val id: String,
        val destination: String,
        val b32Address: String,
        val style: SessionStyle,
        val sessionSocket: Socket,
        val sessionReader: BufferedReader,
        val sessionWriter: PrintWriter
    )

    enum class SessionStyle {
        STREAM,
        DATAGRAM,
        RAW
    }

    suspend fun connect(): Boolean = withContext(Dispatchers.IO) {
        try {
            val ok = openUtilityConnection { _, _ ->
                true
            }
            _isConnected.value = ok
            if (ok) Log.i(TAG, "SAM bridge reachable")
            ok
        } catch (e: Exception) {
            Log.e(TAG, "SAM bridge not reachable: ${e.message}")
            _isConnected.value = false
            false
        }
    }

    suspend fun createStreamSession(savedPrivateKey: String? = null): Result<SAMSession> = withContext(Dispatchers.IO) {
        createSessionInternal(
            style = "STREAM",
            sessionStyle = SessionStyle.STREAM,
            savedPrivateKey = savedPrivateKey,
            extraParams = ""
        )
    }

    suspend fun createDatagramSession(forwardPort: Int = 0): Result<SAMSession> = withContext(Dispatchers.IO) {
        val extra = if (forwardPort > 0) " PORT=$forwardPort" else ""
        createSessionInternal(
            style = "DATAGRAM",
            sessionStyle = SessionStyle.DATAGRAM,
            savedPrivateKey = null,
            extraParams = extra
        )
    }

    private fun createSessionInternal(style: String, sessionStyle: SessionStyle, savedPrivateKey: String?, extraParams: String): Result<SAMSession> {
        val sessionSocket: Socket
        val reader: BufferedReader
        val writer: PrintWriter

        try {
            sessionSocket = Socket(SAM_HOST, SAM_PORT)
            sessionSocket.soTimeout = HANDSHAKE_TIMEOUT_MS
            reader = BufferedReader(InputStreamReader(sessionSocket.getInputStream()))
            writer = PrintWriter(OutputStreamWriter(sessionSocket.getOutputStream()), true)
        } catch (e: Exception) {
            Log.e(TAG, "Cannot open session socket: ${e.message}")
            return Result.failure(e)
        }

        return try {
            writer.println("HELLO VERSION MIN=3.0 MAX=$SAM_VERSION")
            val helloResp = reader.readLine()
                ?: throw IOException("SAM closed during HELLO")
            if (!helloResp.startsWith("HELLO REPLY RESULT=OK")) {
                sessionSocket.close()
                return Result.failure(Exception("HELLO failed: $helloResp"))
            }

            val sessionId = "${style.lowercase()}_${sessionCounter.incrementAndGet()}_${System.currentTimeMillis()}"
            val destination = savedPrivateKey ?: "TRANSIENT"
            writer.println(
                "SESSION CREATE STYLE=$style ID=$sessionId " +
                        "DESTINATION=$destination SIGNATURE_TYPE=$SIGNATURE_TYPE$extraParams"
            )

            val response = reader.readLine()
                ?: throw IOException("SAM closed during SESSION CREATE")
            Log.d(TAG, "SESSION CREATE response: $response")

            if (!response.startsWith("SESSION STATUS RESULT=OK")) {
                sessionSocket.close()
                return Result.failure(Exception("SESSION CREATE failed: $response"))
            }

            sessionSocket.soTimeout = 0

            val returnedDestination = extractValue(response, "DESTINATION")
                ?: run {
                    sessionSocket.close()
                    return Result.failure(Exception("No DESTINATION in SESSION STATUS"))
                }

            val b32Address = destinationToB32(returnedDestination)

            val session = SAMSession(
                id = sessionId,
                destination = returnedDestination,
                b32Address = b32Address,
                style = sessionStyle,
                sessionSocket = sessionSocket,
                sessionReader = reader,
                sessionWriter = writer
            )

            activeSessions[sessionId] = session
            Log.i(TAG, "Created $style session: $sessionId b32: $b32Address")
            Result.success(session)
        } catch (e : Exception) {
            try {
                sessionSocket.close()
            } catch (_ : Exception) {}
            Log.e(TAG, "Failed to create $style session: ${e.message}")
            Result.failure(e)
        }
    }

    suspend fun connectToPeer(sessionId: String, peerB32: String): Result<Socket> = withContext(Dispatchers.IO) {
        try {
            val connSocket = Socket(SAM_HOST, SAM_PORT)
            connSocket.soTimeout = 30_000

            val reader = BufferedReader(InputStreamReader(connSocket.getInputStream()))
            val writer = PrintWriter(OutputStreamWriter(connSocket.getOutputStream()), true)

            writer.println("HELLO VERSION MIN=3.0 MAX=$SAM_VERSION")
            val helloResp = reader.readLine()
            if (!helloResp.startsWith("HELLO REPLY RESULT=OK")) {
                connSocket.close()
                return@withContext Result.failure(Exception("HELLO failed: $helloResp"))
            }

            writer.println("STREAM CONNECT ID=$sessionId DESTINATION=$peerB32 SILENT=false")
            val connectResp = reader.readLine()

            if (connectResp == null || !connectResp.startsWith("STREAM STATUS RESULT=OK")) {
                connSocket.close()
                return@withContext Result.failure(
                    Exception("STREAM CONNECT failed: $connectResp")
                )
            }

            connSocket.soTimeout = 0
            Log.i(TAG, "STREAM CONNECT to $peerB32 OK")
            Result.success(connSocket)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun acceptConnection(sessionId: String): Result<Pair<Socket, String>> = withContext(Dispatchers.IO) {
        try {
            val acceptSocket = Socket(SAM_HOST, SAM_PORT)
            acceptSocket.soTimeout = 0

            val reader = BufferedReader(InputStreamReader(acceptSocket.getInputStream()))
            val writer = PrintWriter(OutputStreamWriter(acceptSocket.getOutputStream()), true)

            writer.println("HELLO VERSION MIN=3.0 MAX=$SAM_VERSION")
            val helloResp = reader.readLine()
            if (!helloResp.startsWith("HELLO REPLY RESULT=OK")) {
                acceptSocket.close()
                return@withContext Result.failure(Exception("HELLO failed"))
            }

            writer.println("STREAM ACCEPT ID=$sessionId SILENT=false")
            val acceptResp = reader.readLine()

            if (acceptResp == null || !acceptResp.startsWith("STREAM STATUS RESULT=OK")) {
                acceptSocket.close()
                return@withContext Result.failure(Exception("STREAM ACCEPT failed: $acceptResp"))
            }

            val peerDest = reader.readLine()
                ?: throw IOException("Connection closed before peer dest line")

            Log.i(TAG, "Accepted connection from: ${peerDest.take(30)}...")
            Result.success(Pair(acceptSocket, peerDest))
        } catch (e: Exception) {
            Log.e(TAG, "acceptConnection($sessionId) failed: ${e.message}")
            Result.failure(e)
        }
    }

    suspend fun sendDatagram(
        sessionId: String,
        peerB32: String,
        data: ByteArray
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val session = activeSessions[sessionId]
                ?: return@withContext Result.failure(Exception("Session not found"))

            if (session.style != SessionStyle.DATAGRAM) {
                return@withContext Result.failure(Exception("Not a DATAGRAM session"))
            }

            val udpSocket = DatagramSocket()
            val header = "3.0 $sessionId $peerB32\n".toByteArray(Charsets.UTF_8)
            val packetData = header + data

            val packet = DatagramPacket(
                packetData,
                packetData.size,
                InetAddress.getByName(SAM_HOST),
                7655
            )

            udpSocket.send(packet)
            udpSocket.close()

            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun namingLookup(name: String): Result<String> = withContext(Dispatchers.IO) {
        try {
            openUtilityConnection { reader, writer ->
                writer.println("NAMING LOOKUP NAME=$name")
                val response = reader.readLine() ?: throw IOException("Connection closed")
                Log.d(TAG, "NAMING LOOKUP <- $response")

                if (response.startsWith("NAMING REPLY RESULT=OK")) {
                    val value = extractValue(response, "VALUE")
                        ?: throw Exception("No VALUE in NAMING REPLY")
                    Result.success(value)
                } else {
                    Result.failure(Exception("Lookup failed: $response"))
                }
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun generateDestination(): Result<Pair<String, String>> = withContext(Dispatchers.IO) {
        try {
            openUtilityConnection { reader, writer ->
                writer.println("DEST GENERATE SIGNATURE_TYPE=$SIGNATURE_TYPE")
                val response = reader.readLine() ?: throw IOException("Connetion closed")
                Log.d(TAG, "DEST GENERATE <- $response")

                if (!response.startsWith("DEST REPLY")) {
                    return@openUtilityConnection Result.failure(
                        Exception("DEST GENERATE failed: $response")
                    )
                }

                val public = extractValue(response, "PUB")
                val private = extractValue(response, "PRIV")

                if (public != null && private != null) {
                    Result.success(Pair(public, private))
                } else {
                    Result.failure(Exception("Incomplete DEST REPLY: $response"))
                }
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // SESSION MANAGEMENT

    fun getActiveSessions(): List<SAMSession> = activeSessions.values.toList()

    fun removeSession(sessionId: String) {
        val session = activeSessions.remove(sessionId)
        session?.let {
            try {
                it.sessionSocket.close()
            } catch (e : Exception) {}
            Log.d(TAG, "Removed and closed session $sessionId")
        }
    }

    fun disconnect() {
        activeSessions.values.forEach { session ->
            try {
                session.sessionSocket.close()
            } catch (e : Exception) {}
        }
        activeSessions.clear()
        _isConnected.value = false
        Log.i(TAG, "Disconnected - all sessions closed")
    }

    fun cleanup() {
        disconnect()
        scope.cancel()
    }

    // HELPERS

    private fun <T> openUtilityConnection(block: (BufferedReader, PrintWriter) -> T): T {
        Socket(SAM_HOST, SAM_PORT).use {socket ->
            socket.soTimeout = 30_000
            val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
            val writer = PrintWriter(OutputStreamWriter(socket.getOutputStream()), true)

            writer.println("HELLO VERSION MIN=3.0 MAX=$SAM_VERSION")
            val helloResponse = reader.readLine()
                ?: throw IOException("SAM closed during utility HELLO")
            if (!helloResponse.startsWith("HELLO REPLY RESULT=OK")) {
                throw Exception("SAM HELLO failed: $helloResponse")
            }

            return block(reader, writer)
        }
    }

    private fun extractValue(response: String, key: String): String? {
        val regex = "$key=(\\S+)".toRegex()
        return regex.find(response)?.groupValues?.get(1)
    }

    private fun destinationToB32(destination: String): String {
        val standardBase64 = destination.replace('-', '+').replace('~', '/')
        val allBytes = Base64.decode(standardBase64, Base64.DEFAULT)
        val pubBytes = extractPublicDestination(allBytes)
        val hash = MessageDigest.getInstance("SHA-256").digest(pubBytes)
        return "${encodeBase32(hash)}.b32.i2p"
    }

    private fun extractPublicDestination(keyBytes: ByteArray): ByteArray {
        // Minimum size of a valid b32 destination: 387 bytes = 256(crypto) + 128(signing) + 3(cert header)
        if (keyBytes.size <= 387) return keyBytes
        val certLength = ((keyBytes[385].toInt() and 0xFF) shl 8) or (keyBytes[386].toInt() and 0xFF)
        val totalDestSize = 387 + certLength
        return if (keyBytes.size > totalDestSize) keyBytes.copyOfRange(0, totalDestSize)
        else keyBytes
    }

    private fun encodeBase32(data: ByteArray): String {
        val alphabet = "abcdefghijklmnopqrstuvwxyz234567"
        val result = StringBuilder()

        var i = 0
        var bits = 0
        var value = 0

        while (i < data.size) {
            value = (value shl 8) or (data[i].toInt() and 0xFF)
            bits += 8

            while (bits >= 5) {
                result.append(alphabet[(value shr (bits - 5)) and 0x1F])
                bits -= 5
            }
            i++
        }

        if (bits > 0) {
            result.append(alphabet[(value shl (5 - bits)) and 0x1F])
        }

        return result.toString()
    }
}