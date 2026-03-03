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
    private var controlSocket: Socket? = null
    private var controlReader: BufferedReader? = null
    private var controlWriter: PrintWriter? = null
    private val _isConnected = MutableStateFlow(false)
    val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()

    data class SAMSession(
        val id: String,
        val destination: String,
        val b32Address: String,
        val style: SessionStyle,
        val socket: Socket? = null
    )

    enum class SessionStyle {
        STREAM,
        DATAGRAM,
        RAW
    }

    suspend fun connect(): Boolean = withContext(Dispatchers.IO) {
        try {
            if (_isConnected.value && controlSocket?.isConnected == true) {
                Log.d(TAG, "Already connected, reusing connection")
                return@withContext true
            }

            controlSocket = Socket(SAM_HOST, SAM_PORT)

            controlReader = BufferedReader(InputStreamReader(controlSocket!!.getInputStream()))
            controlWriter = PrintWriter(OutputStreamWriter(controlSocket!!.getOutputStream()), true)

            if (!helloHandshake()) {
                disconnect()
                return@withContext false
            }

            _isConnected.value = true
            Log.i(TAG, "Connected to SAM bridge")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to connect to SAM", e)
            false
        }
    }

    private fun helloHandshake(): Boolean {
        return try {
            sendCommand("HELLO VERSION MIN=3.0 MAX=$SAM_VERSION")
            val response = readResponse()

            if (response.startsWith("HELLO REPLY RESULT=OK")) {
                Log.d(TAG, "SAM handshake successful: $response")
                true
            } else {
                Log.e(TAG, "SAM handshake failed: $response")
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Handshake exception", e)
            false
        }
    }

    suspend fun createStreamSession(): Result<SAMSession> = withContext(Dispatchers.IO) {
        try {
            val sessionId = "stream_${sessionCounter.incrementAndGet()}_${System.currentTimeMillis()}"

            sendCommand("SESSION CREATE STYLE=STREAM ID=$sessionId DESTINATION=TRANSIENT SIGNATURE_TYPE=$SIGNATURE_TYPE")

            val response = readResponse()
            Log.d(TAG, "SESSION CREATE response: $response")

            if (response.startsWith("SESSION STATUS RESULT=OK")) {
                val destination = extractValue(response, "DESTINATION")
                    ?: return@withContext Result.failure(Exception("No destination in response"))

                val b32Address = destinationToB32(destination)

                val session = SAMSession(
                    id = sessionId,
                    destination = destination,
                    b32Address = b32Address,
                    style = SessionStyle.STREAM
                )

                activeSessions[sessionId] = session
                Log.i(TAG, "Created STREAM session: $sessionId, b32: $b32Address")

                Result.success(session)
            } else {
                Result.failure(Exception("SESSION CREATE failed: $response"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create session", e)
            Result.failure(e)
        }
    }

    suspend fun createDatagramSession(forwardPort: Int = 0): Result<SAMSession> = withContext(Dispatchers.IO) {
        try {
            val sessionId = "dgram_${sessionCounter.incrementAndGet()}_${System.currentTimeMillis()}"

            val portCmd = if (forwardPort > 0) " PORT=$forwardPort" else ""
            sendCommand("SESSION CREATE STYLE=DATAGRAM ID=$sessionId DESTINATION=TRANSIENT SIGNATURE_TYPE=$SIGNATURE_TYPE$portCmd")

            val response = readResponse()

            if (response.startsWith("SESSION STATUS RESULT=OK")) {
                val destination = extractValue(response, "DESTINATION")
                    ?: return@withContext Result.failure(Exception("No destination in response"))

                val b32Address = destinationToB32(destination)

                val session = SAMSession(
                    id = sessionId,
                    destination = destination,
                    b32Address = b32Address,
                    style = SessionStyle.DATAGRAM
                )

                activeSessions[sessionId] = session
                Log.i(TAG, "Created DATAGRAM session: $sessionId")

                Result.success(session)
            } else {
                Result.failure(Exception("SESSION CREATE failed: $response"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun connectToPeer(sessionId: String, peerB32: String): Result<Socket> = withContext(Dispatchers.IO) {
        try {
            val connSocket = Socket(SAM_HOST, SAM_PORT)
            connSocket.soTimeout = 60000

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

            if (connectResp.startsWith("STREAM STATUS RESULT=OK")) {
                Result.success(connSocket)
            } else {
                connSocket.close()
                Result.failure(Exception("STREAM CONNECT failed: $connectResp"))
            }
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

            if (acceptResp.startsWith("STREAM STATUS RESULT=OK")) {
                val peerDest = reader.readLine()
                Log.i(TAG, "Accepted connection from: $peerDest")
                Result.success(Pair(acceptSocket, peerDest))
            } else {
                acceptSocket.close()
                Result.failure(Exception("STREAM ACCEPT failed: $acceptResp"))
            }
        } catch (e: Exception) {
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
            sendCommand("NAMING LOOKUP NAME=$name")
            val response = readResponse()

            if (response.startsWith("NAMING REPLY RESULT=OK")) {
                val destination = extractValue(response, "VALUE")
                if (destination != null) {
                    Result.success(destination)
                } else {
                    Result.failure(Exception("NO VALUE in response"))
                }
            } else {
                Result.failure(Exception("Lookup failed: $response"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun generateDestination(): Result<Pair<String, String>> = withContext(Dispatchers.IO) {
        try {
            sendCommand("DEST GENERATE SIGNATURE_TYPE=$SIGNATURE_TYPE")
            val response = readResponse()

            if (response.startsWith("DEST REPLY")) {
                val pub = extractValue(response, "PUB")
                val priv = extractValue(response, "PRIV")

                if (pub != null && priv != null) {
                    Result.success(Pair(pub, priv))
                } else {
                    Result.failure(Exception("Incomplete DEST REPLY"))
                }
            } else {
                Result.failure(Exception("DEST GENERATE failed: $response"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun removeSession(sessionId: String) {
        activeSessions.remove(sessionId)
    }

    fun getActiveSessions(): List<SAMSession> = activeSessions.values.toList()

    fun disconnect() {
        try {
            controlSocket?.close()
            activeSessions.clear()
            _isConnected.value = false
            Log.i(TAG, "Disconnected from SAM")
        } catch (e: Exception) {
            Log.e(TAG, "Error disconnecting", e)
        }
    }

    fun cleanup() {
        disconnect()
        scope.cancel()
    }

    private fun sendCommand(command: String) {
        controlWriter?.println(command)
        Log.d(TAG, "SAM -> $command")
    }

    private fun readResponse(): String {
        val response = controlReader?.readLine() ?: throw IOException("Connection closed")
        Log.d(TAG, "SAM <- $response")
        return response
    }

    private fun extractValue(response: String, key: String): String? {
        val regex = "$key=(\\S+)".toRegex()
        return regex.find(response)?.groupValues?.get(1)
    }

    private fun destinationToB32(destination: String): String {
        val standardBase64 = destination.replace('-', '+').replace('~', '/')
        val decoded = Base64.decode(standardBase64, Base64.DEFAULT)
        val md = MessageDigest.getInstance("SHA-256")
        val hash = md.digest(decoded)
        val b32 = encodeBase32(hash)
        return "$b32.b32.i2p"
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