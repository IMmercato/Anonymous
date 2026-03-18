package com.example.anonymous.messaging

import android.content.Context
import android.net.Uri
import android.util.Base64
import android.util.Log
import com.example.anonymous.crypto.CryptoManager
import com.example.anonymous.i2p.SAMClient
import com.example.anonymous.media.MediaChunkManager
import com.example.anonymous.media.MediaMeta
import com.example.anonymous.network.model.Message
import com.example.anonymous.repository.ContactRepository
import com.example.anonymous.repository.MessageRepository
import com.google.gson.Gson
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.io.PrintWriter
import java.net.Socket
import java.security.KeyFactory
import java.security.KeyPair
import java.security.PublicKey
import java.security.SecureRandom
import java.security.spec.X509EncodedKeySpec
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.SecretKey

class MessageManager private constructor(context: Context) {
    companion object {
        private const val TAG = "MessageManager"
        private const val PROTOCOL_VERSION = 1
        private const val AES_IV_SIZE = 12
        private const val AES_TAG_SIZE = 16

        private const val PREFS_NAME = "i2p_identity"
        private const val KEY_PRIV = "sam_priv_key"
        private const val KEY_PUB = "sam_pub_key"

        @Volatile
        private var instance: MessageManager? = null

        fun getInstance(context: Context): MessageManager {
            return instance ?: synchronized(this) {
                instance ?: MessageManager(context.applicationContext).also {
                    instance = it
                }
            }
        }
    }

    data class PeerConnection (
        val contactB32: String,
        val socket: Socket,
        val reader: BufferedReader,
        val writer: PrintWriter,
        val sessionKey: SecretKey? = null,
        val isConnected: Boolean = false,
        val lastActivity: Long = System.currentTimeMillis()
    )

    data class MessageStatus (
        val messageId: String,
        val status: Status,
        val timestamp: Long = System.currentTimeMillis()
    )

    data class NetworkMessage (
        val v: Int = PROTOCOL_VERSION,
        val type: String = "text",
        val senderB32: String,
        val recipientB32: String,
        val encryptedPayload: String,       // Base64 ciphertext
        val iv: String,                     // Base64 IV
        val authTag: String,                // Base64 auth tag
        val dhPublicKey: String,            // Base64
        val salt: String,                   // Base64 salt for HKDF
        val timestamp: Long = System.currentTimeMillis(),
        val replyToId: String? = null,
        val messageId: String = UUID.randomUUID().toString(),
        val mediaId: String? = null,
        val chunkIndex: Int = -1
    )

    enum class ConnectionState {
        Disconnected, Connecting, Connected, Error
    }

    enum class Status {
        SENDING, SENT, DELIVERED, READ, FAILED
    }

    private val context = context.applicationContext
    private val contactRepository = ContactRepository.getInstance(context)
    private val messageRepository = MessageRepository(context)
    private val samClient = SAMClient.getInstance()
    private val cryptoManager = CryptoManager
    private val gson = Gson()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // Active I2P connections to peers
    private val activeConnections = ConcurrentHashMap<String, PeerConnection>()

    // Session ID
    private var serverSessionId: String? = null

    private val _incomingMessages = MutableSharedFlow<Message>(extraBufferCapacity = 100)
    val incomingMessages: SharedFlow<Message> = _incomingMessages.asSharedFlow()

    private val _messageStatus = MutableSharedFlow<MessageStatus>(extraBufferCapacity = 100)
    val messageStatus: SharedFlow<MessageStatus> = _messageStatus.asSharedFlow()

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    /**
     * Persistent I2P identity helpers
     */
    private suspend fun getPersistentDestination(): String? {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val savedPriv = prefs.getString(KEY_PRIV, null)

        if (savedPriv != null) {
            Log.i(TAG, "Reusing persisted I2P destination")
            return savedPriv
        }

        Log.e(TAG, "SAM PRIVATE key missing from SharedPreferences - identity not yet registered or data was cleared")
        return null
    }

    /**
     * Initialize MessageManager and start listening for incoming I2P connections (After I2pdDaemon is READY)
     */
    suspend fun initialize(): Boolean = withContext(Dispatchers.IO) {
        try {
            _connectionState.value = ConnectionState.Connecting

            // SAM Connection check
            if (!samClient.connect()) {
                throw IllegalStateException("SAM bridge not reachable on 127.0.0.1:7656")
            }

            // Wait until I2P has built enough tunnels to register a LeaSet
            val tunnelsReady = waitForTunnelsReady(timeoutMs = 300_000L)    // 5 min
            if (!tunnelsReady) {
                Log.e(TAG, "Timed out waiting for I2P tunnels - check internet connectivity")
                _connectionState.value = ConnectionState.Error
                return@withContext false
            }

            // Load saved PRIV key so the same b32 address is restored every launch
            val savedPriv = getPersistentDestination()
            if (savedPriv == null) {
                Log.w(TAG, "No persistent SAM key found — has Registration been completed?")
            }

            // Create main STREAM session (stable identity when savedPrivate != null)
            val sessionResult = samClient.createStreamSession(savedPrivateKey = savedPriv)
            if (sessionResult.isFailure) {
                throw IllegalStateException("Failed to create SAM session: ${sessionResult.exceptionOrNull()?.message}")
            }

            val session = sessionResult.getOrThrow()
            serverSessionId = session.id

            // Wait for LeaseSet
            Log.i(TAG, "Session created (${session.b32Address}), waiting for LeaseSet to propagate...")
            val leaseSetReady = waitForLeaseSetPropagation(session.b32Address, timeoutMs = 120_000L)
            if (!leaseSetReady) {
                Log.w(TAG, "LeaseSet may not be visible yet - peers might not reach you for a few minutes")
            }

            // Save my I2P identity if not already saved
            saveMyI2PIdentity(session.b32Address, session.destination)

            // Start connection in background
            startAcceptingConnections(session.id)

            _connectionState.value = ConnectionState.Connected
            Log.i(TAG, "MessageManger initialized. Listening on ${session.b32Address}")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize MessageManager", e)
            _connectionState.value = ConnectionState.Error
            false
        }
    }

    private suspend fun waitForTunnelsReady(timeoutMs: Long = 300_000L): Boolean = withContext(Dispatchers.IO) {
        val deadline = System.currentTimeMillis() + timeoutMs
        var attempt = 0

        Log.i(TAG, "Waiting for I2P tunnels to be ready (timeout ${timeoutMs / 1000}s)...")

        while (System.currentTimeMillis() < deadline) {
            attempt++
            try {
                val result = samClient.createStreamSession(savedPrivateKey = null)

                if (result.isSuccess) {
                    val probeSession = result.getOrThrow()

                    samClient.removeSession(probeSession.id)

                    Log.i(TAG, "I2P tunnels ready after $attempt attempts " + "(~${(System.currentTimeMillis() - (deadline - timeoutMs)) / 1000}s)")
                    return@withContext true
                } else {
                    Log.d(TAG, "Tunnel probe attempt $attempt failed: " + "${result.exceptionOrNull()?.message} - retrying in 10s")
                }
            } catch (e : Exception) {
                Log.d(TAG, "Tunnel probe attempt $attempt exception: ${e.message} - retrying in 10s")
            }

            delay(10_000L)  // 10s
        }

        Log.e(TAG, "waitForTunnelsReady timed out after ${timeoutMs / 1000}s")
        false
    }

    private suspend fun waitForLeaseSetPropagation(b32: String, timeoutMs: Long): Boolean = withContext(Dispatchers.IO) {
        val deadline = System.currentTimeMillis() + timeoutMs
        var attempt = 0
        while (System.currentTimeMillis() < deadline) {
            attempt++
            val result = samClient.namingLookup(b32)
            if (result.isSuccess) {
                Log.i(TAG, "LeaseSet confirmed visible after $attempt attempts")
                return@withContext true
            }
            Log.d(TAG, "LeaseSet not yet visible (attempt $attempt), retrying in 10s...")
            delay(10_000)   // 10s
        }
        false
    }

    fun startListening(sessionId: String) {
        if (serverSessionId == sessionId && _connectionState.value == ConnectionState.Connected) {
            Log.i(TAG, "Already listening on $sessionId - skipping duplicate")
            return
        }
        this.serverSessionId = sessionId
        _connectionState.value = ConnectionState.Connected

        scope.launch {
            Log.i(TAG, "Starting to listen for incoming I2P connections on session: $sessionId")

            var backoffMs = 2_000L
            var consecutiveFailures = 0
            val maxConsecutiveFailures = 5

            while (isActive) {
                try {
                    val result = samClient.acceptConnection(sessionId)
                    if (result.isSuccess) {
                        backoffMs = 2_000L
                        consecutiveFailures = 0
                        val (socket, peerDest) = result.getOrThrow()
                        Log.i(TAG, "Accepted incoming connection from: $peerDest")
                        handleIncomingConnection(socket, peerDest)
                    } else {
                        consecutiveFailures++
                        Log.w(TAG, "acceptConnection failed ($consecutiveFailures/$maxConsecutiveFailures, backoff ${backoffMs}ms): ${result.exceptionOrNull()?.message}")

                        if (consecutiveFailures >= maxConsecutiveFailures) {
                            Log.e(TAG, "Session $sessionId dead after $consecutiveFailures failures — i2pd restarted. Triggering recovery.")
                            samClient.removeSession(sessionId)
                            _connectionState.value = ConnectionState.Error
                            break
                        }

                        delay(backoffMs)
                        backoffMs = minOf(backoffMs * 2, 30_000L)
                    }
                } catch (e: Exception) {
                    if (e !is CancellationException) {
                        consecutiveFailures++
                        Log.e(TAG, "Error accepting connection ($consecutiveFailures/$maxConsecutiveFailures)", e)

                        if (consecutiveFailures >= maxConsecutiveFailures) {
                            Log.e(TAG, "Session $sessionId dead after $consecutiveFailures failures. Triggering recovery.")
                            samClient.removeSession(sessionId)
                            _connectionState.value = ConnectionState.Error
                            break
                        }

                        delay(backoffMs)
                        backoffMs = minOf(backoffMs * 2, 30_000L)
                    }
                }
            }
        }
    }

    suspend fun sendMessage(contactB32: String, text: String, replyToId: String? = null): Result<Message> = withContext(Dispatchers.IO) {
        return@withContext try {
            val messageId = UUID.randomUUID().toString()
            _messageStatus.emit(MessageStatus(messageId, Status.SENDING))

            // I2P connection
            val connection = getOrCreateConnection(contactB32) ?: return@withContext Result.failure(Exception("Could not establish I2P connection to $contactB32"))

            // I2P my identity
            val myB32 = getMyB32Address() ?: return@withContext Result.failure(Exception("No I2P identity"))

            // ECDH key pair
            val ephemeralKeyPair = cryptoManager.generateECDHKeyPair()

            // recipient's public key
            val recipientPublicKey = getRecipientPublicKey(contactB32) ?: return@withContext Result.failure(Exception("No public key for contact $contactB32"))

            // Perform ECDH
            val sharedSecret = cryptoManager.performECDH(
                ephemeralKeyPair.private,
                recipientPublicKey
            )

            // Derive AES key
            val salt = generateSalt()
            val info = "AnonymousMessage".toByteArray()
            val aesKey = cryptoManager.deriveAESKey(sharedSecret, salt, info)

            // Encrypt message
            val encryptedData = cryptoManager.encryptMessage(text, aesKey, null)

            // Encode ephemeral public key (P-256 point)
            val dhPublicKeyBytes = ephemeralKeyPair.public.encoded
            val dhPublicKey = Base64.encodeToString(dhPublicKeyBytes, Base64.NO_WRAP)

            // Network message
            val networkMessage = NetworkMessage(
                v = PROTOCOL_VERSION,
                type = "text",
                senderB32 = myB32,
                recipientB32 = contactB32,
                encryptedPayload = encryptedData.ct,
                iv = encryptedData.iv,
                authTag = encryptedData.authTag,
                dhPublicKey = dhPublicKey,
                salt = Base64.encodeToString(salt, Base64.NO_WRAP),
                timestamp = System.currentTimeMillis(),
                replyToId = replyToId,
                messageId = messageId
            )

            // Send over I2P
            sendOverI2P(connection, networkMessage)

            // Message local storage
            val message = Message(
                id = UUID.randomUUID().toString(),
                content = text,     // Plain for Local
                encryptedContent = encryptedData.ct,
                senderId = myB32,
                receiverId = contactB32,
                timestamp = System.currentTimeMillis(),
                isRead = true,      // Outgoing
                iv = encryptedData.iv,
                authTag = encryptedData.authTag,
                version = encryptedData.v,
                dhPublicKey = networkMessage.dhPublicKey,
            )

            // Store
            messageRepository.addMessage(message)

            _messageStatus.emit(MessageStatus(message.id, Status.SENT))

            Result.success(message)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send message", e)
            _messageStatus.emit(MessageStatus(UUID.randomUUID().toString(), Status.FAILED))
            Result.failure(e)
        }
    }

    suspend fun sendMedia(contactB32: String, uri: Uri, caption: String = "", replyToId: String? = null): Result<Message> = withContext(Dispatchers.IO) {
        return@withContext try {
            val mediaManager = MediaChunkManager.getInstance(context)

            val (meta, chunks) = mediaManager.prepareMedia(uri) ?: return@withContext Result.failure(Exception("Could not establish I2P connection to $contactB32"))

            val connection = getOrCreateConnection(contactB32) ?: return@withContext Result.failure(Exception("Could not establish I2P connection to $contactB32"))

            val myB32 = getMyB32Address() ?: return@withContext Result.failure(Exception("No I2P identity"))

            fun emptyNetwork(type: String, mediaId: String? = null, chunkIndex: Int = -1, payload: String = "") = NetworkMessage(
                v = PROTOCOL_VERSION,
                type = type,
                senderB32 = myB32,
                recipientB32 = contactB32,
                encryptedPayload = payload,
                iv = "",
                authTag = "",
                dhPublicKey = "",
                salt = "",
                timestamp = System.currentTimeMillis(),
                mediaId = mediaId,
                chunkIndex = chunkIndex
            )

            // 1. Send MEDIA_META
            val metaPacket = emptyNetwork(type = "media_meta", mediaId = meta.mediaId, payload = gson.toJson(meta))
            sendOverI2P(connection, metaPacket)

            // 2. Send chunks
            chunks.forEachIndexed { index, chunkB64 ->
                val chunkPacket = emptyNetwork(type = "media_chunk", mediaId = meta.mediaId, chunkIndex = index, payload = chunkB64)
                sendOverI2P(connection, chunkPacket)
            }
            Log.i(TAG, "Sent ${chunks.size} chunks for media ${meta.mediaId} to $contactB32")

            // 3. Send messageId's text message
            val messageId = UUID.randomUUID().toString()
            _messageStatus.emit(MessageStatus(messageId, Status.SENDING))

            val displayText = caption.ifBlank { "media" }

            val ephemeralKeyPair = cryptoManager.generateECDHKeyPair()
            val recipientPublicKey = getRecipientPublicKey(contactB32) ?: return@withContext Result.failure(Exception("No public key for contact $contactB32"))
            val sharedSecret = cryptoManager.performECDH(ephemeralKeyPair.private, recipientPublicKey)
            val salt = generateSalt()
            val aesKey = cryptoManager.deriveAESKey(sharedSecret, salt, "AnonymousMessage".toByteArray())
            val encryptedData = cryptoManager.encryptMessage(displayText, aesKey, null)
            val dhPublicKey = Base64.encodeToString(ephemeralKeyPair.public.encoded, Base64.NO_WRAP)

            val networkMessage = NetworkMessage(
                v = PROTOCOL_VERSION,
                type = "text",
                senderB32 = myB32,
                recipientB32 = contactB32,
                encryptedPayload = encryptedData.ct,
                iv = encryptedData.iv,
                authTag = encryptedData.authTag,
                dhPublicKey = dhPublicKey,
                salt = Base64.encodeToString(salt, Base64.NO_WRAP),
                timestamp = System.currentTimeMillis(),
                replyToId = replyToId,
                messageId = messageId,
                mediaId = meta.mediaId
            )
            sendOverI2P(connection, networkMessage)

            // 4. Store locally
            val message = Message(
                id = messageId,
                content = displayText,
                encryptedContent = encryptedData.ct,
                senderId = myB32,
                receiverId = contactB32,
                timestamp = System.currentTimeMillis(),
                isRead = true,
                iv = encryptedData.iv,
                authTag = encryptedData.authTag,
                version = encryptedData.v,
                dhPublicKey = dhPublicKey,
                mediaId = meta.mediaId
            )
            messageRepository.addMessage(message)
            _messageStatus.emit(MessageStatus(message.id, Status.SENT))

            Result.success(message)
        } catch (e : Exception) {
            Log.e(TAG, "sendMedia failed", e)
            _messageStatus.emit(MessageStatus(UUID.randomUUID().toString(), Status.FAILED))
            Result.failure(e)
        }
    }

    /**
     * Get or Create I2P connection to peer
     */
    private suspend fun getOrCreateConnection(contactB32: String): PeerConnection? = withContext(Dispatchers.IO) {
        // Check existing connection
        activeConnections[contactB32]?.let { connection ->
            if (connection.isConnected && !connection.socket.isClosed && (System.currentTimeMillis() - connection.lastActivity) < 300000) {
                return@withContext connection
            } else {
                closeConnection(connection)
                activeConnections.remove(contactB32)
            }
        }

        try {
            // Reuse the existing persistent session opened in initialize().
            val sessionId = serverSessionId ?: return@withContext null.also { Log.e(TAG, "getOrCreateConnection: serverSessionId is null - MessageManager not initialized") }

            val socketResult = samClient.connectToPeer(sessionId, contactB32)
            if (socketResult.isFailure) {
                Log.e(TAG, "Failed to connect to $contactB32 via I2P")
                return@withContext null
            }

            val socket = socketResult.getOrThrow()
            val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
            val writer = PrintWriter(OutputStreamWriter(socket.getOutputStream()), true)

            val connection = PeerConnection(
                contactB32 = contactB32,
                socket = socket,
                reader = reader,
                writer = writer,
                isConnected = true
            )

            activeConnections[contactB32] = connection

            // Start listening for incoming messages on this connection
            startConnectionListener(connection)

            Log.i(TAG, "Established new connection to $contactB32")
            connection
        } catch (e: Exception) {
            Log.e(TAG, "Error creating connection to $contactB32", e)
            null
        }
    }

    /**
     * Send message over I2P connection
     */
    private fun sendOverI2P(connection: PeerConnection, message: NetworkMessage): Boolean {
        return try {
            val json = gson.toJson(message)
            connection.writer.println(json)
            connection.writer.flush()

            activeConnections[connection.contactB32] = connection.copy(
                lastActivity = System.currentTimeMillis()
            )

            Log.d(TAG, "Sent message ${message.messageId} to ${connection.contactB32}")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send over I2P", e)
            false
        }
    }

    /**
     * Listen for incoming connections
     */
    private fun startAcceptingConnections(sessionId: String) {
        scope.launch {
            var backoffMs = 2_000L
            var consecutiveFailures = 0
            val maxConsecutiveFailures = 5

            while (isActive) {
                try {
                    val result = samClient.acceptConnection(sessionId)
                    if (result.isSuccess) {
                        backoffMs = 2_000L
                        consecutiveFailures = 0
                        val (socket, peerDest) = result.getOrThrow()
                        handleIncomingConnection(socket, peerDest)
                    } else {
                        consecutiveFailures++
                        Log.w(TAG, "acceptConnection failed ($consecutiveFailures/$maxConsecutiveFailures, backoff ${backoffMs}ms): ${result.exceptionOrNull()?.message}")

                        if (consecutiveFailures >= maxConsecutiveFailures) {
                            Log.e(TAG, "Session $sessionId dead after $consecutiveFailures failures. Triggering recovery.")
                            samClient.removeSession(sessionId)
                            _connectionState.value = ConnectionState.Error
                            break
                        }

                        delay(backoffMs)
                        backoffMs = minOf(backoffMs * 2, 30_000L)
                    }
                } catch (e: Exception) {
                    if (e !is CancellationException) {
                        consecutiveFailures++
                        Log.e(TAG, "Error accepting connection ($consecutiveFailures/$maxConsecutiveFailures)", e)

                        if (consecutiveFailures >= maxConsecutiveFailures) {
                            Log.e(TAG, "Session $sessionId dead. Triggering recovery.")
                            samClient.removeSession(sessionId)
                            _connectionState.value = ConnectionState.Error
                            break
                        }

                        delay(backoffMs)
                        backoffMs = minOf(backoffMs * 2, 30_000L)
                    }
                }
            }
        }
    }

    /**
     * Handle incoming I2P connection
     */
    private fun handleIncomingConnection(socket: Socket, peerDest: String) {
        scope.launch {
            try {
                val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
                val writer = PrintWriter(OutputStreamWriter(socket.getOutputStream()), true)

                val connection = PeerConnection(
                    contactB32 = peerDest,
                    socket = socket,
                    reader = reader,
                    writer = writer,
                    isConnected = true
                )

                activeConnections[peerDest] = connection
                Log.i(TAG, "Accepted connection from $peerDest")

                // Read messages from this connection
                while (isActive && !socket.isClosed) {
                    try {
                        val line = reader.readLine() ?: break
                        if (line.isNotBlank()) {
                            processIncomingMessage(line, peerDest)
                        }
                    } catch (e: Exception) {
                        if (e !is CancellationException) {
                            Log.e(TAG, "Error reading from $peerDest", e)
                        }
                        break
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error handling connection from $peerDest", e)
            } finally {
                socket.close()
                activeConnections.remove(peerDest)
                Log.d(TAG, "Closed connection from $peerDest")
            }
        }
    }

    /**
     * Listen for messages on outgoing connection (bidirectional)
     */
    private fun startConnectionListener(connection: PeerConnection)  {
        scope.launch {
            try {
                while (isActive && !connection.socket.isClosed) {
                    val line = connection.reader.readLine() ?: break
                    if (line.isNotBlank()) {
                        processIncomingMessage(line, connection.contactB32)
                    }
                }
            } catch (e: Exception) {
                if (e !is CancellationException) {
                    Log.e(TAG, "Listener error fro ${connection.contactB32}", e)
                }
            } finally {
                closeConnection(connection)
                activeConnections.remove(connection.contactB32)
            }
        }
    }

    /**
     * Process incoming I2P message
     */
    private suspend fun processIncomingMessage(json: String, senderB32: String) {
        try {
            val networkMsg =gson.fromJson(json, NetworkMessage::class.java)

            // Handle message type
            when (networkMsg.type) {
                "receipt" -> {
                    Log.d(TAG, "Received read receipt for ${networkMsg.messageId} from $senderB32")
                    _messageStatus.emit(MessageStatus(networkMsg.messageId, Status.READ))
                    return
                }
                "media_meta" -> {
                    val metaJson = networkMsg.encryptedPayload
                    val meta = gson.fromJson(metaJson, MediaMeta::class.java)
                    MediaChunkManager.getInstance(context).registerMeta(meta)
                    Log.d(TAG, "Received media_meta for ${meta.mediaId} (${meta.chunkCount} chunks) from $senderB32")
                    return
                }
                "media_chunk" -> {
                    val mediaId = networkMsg.mediaId ?: run {
                        Log.w(TAG, "media_chunk with no mediaId from $senderB32")
                        return
                    }
                    MediaChunkManager.getInstance(context).onChunkReceived(mediaId, networkMsg.chunkIndex, networkMsg.encryptedPayload)
                    return
                }
                "text" -> {}
                else -> {
                    Log.w(TAG, "Unknown message type '${networkMsg.type}' - ignoring")
                    return
                }
            }

            // Protocol Version
            if (networkMsg.v != PROTOCOL_VERSION) {
                Log.w(TAG, "Unsupported protocol version: ${networkMsg.v}")
                return
            }

            // Verify
            val myB32 = getMyB32Address()
            if (myB32 != null && networkMsg.recipientB32 != myB32) {
                Log.w(TAG, "Message not intended for us (recipient: ${networkMsg.recipientB32})")
                return
            }

            // Get my key pair for decryption
            val myKeyPair = getMyKeyPair() ?: run {
                Log.e(TAG, "No key pair available for decryption")
                return
            }

            // Decode sender's publickey
            val senderPubKeyBytes = Base64.decode(networkMsg.dhPublicKey, Base64.NO_WRAP)
            val senderPubKey = decodeECPublicKey(senderPubKeyBytes)

            // Perform ECDH
            val sharedSecret = cryptoManager.performECDH(myKeyPair.private, senderPubKey)

            // Derive same AES key
            val salt = Base64.decode(networkMsg.salt, Base64.NO_WRAP)
            val info = "AnonymousMessage".toByteArray()
            val aesKey = cryptoManager.deriveAESKey(sharedSecret, salt, info)

            // Decrypt
            val encryptedData = CryptoManager.EncryptedMessage(
                v = networkMsg.v,
                iv = networkMsg.iv,
                ct = networkMsg.encryptedPayload,
                authTag = networkMsg.authTag
            )

            val decryptedContent = try {
                cryptoManager.decryptMessage(encryptedData, aesKey)
            } catch (e: Exception) {
                Log.e(TAG, "Decryption failed...", e)
                return
            }

            // Create Message object
            val message = Message(
                id = networkMsg.messageId,
                content = decryptedContent,
                encryptedContent = networkMsg.encryptedPayload,
                senderId = networkMsg.senderB32,
                receiverId = networkMsg.recipientB32,
                timestamp = networkMsg.timestamp,
                isRead = false,
                iv = networkMsg.iv,
                authTag = networkMsg.authTag,
                version = networkMsg.v,
                dhPublicKey = networkMsg.dhPublicKey,
                mediaId = networkMsg.mediaId
            )

            // Store in repository
            messageRepository.addMessage(message)
            _incomingMessages.emit(message)

            // Send read receipt asynchronously
            sendReadReceipt(networkMsg.senderB32, networkMsg.messageId)

            Log.d(TAG, "Received and decrypted message ${message.id} from $senderB32")
        } catch (e: Exception) {
            Log.e(TAG, "Failed tp process incoming message from $senderB32", e)
        }
    }

    /**
     * Send Read Receipt
     */
    private fun sendReadReceipt(contactB32: String, messageId: String) {
        try {
            val connection = activeConnections[contactB32] ?: return
            val myB32 = getMyB32Address() ?: return

            val receipt = NetworkMessage(
                type = "receipt",
                senderB32 = myB32,
                recipientB32 = contactB32,
                encryptedPayload = "",
                iv = "",
                authTag = "",
                dhPublicKey = "",
                salt = "",
                messageId = messageId
            )

            sendOverI2P(connection, receipt)
            Log.d(TAG, "Sent read receipt for $messageId to $contactB32")
        } catch (e: Exception) {
            Log.w(TAG, "Failed too send read receipt", e)
        }
    }

    private fun getMyB32Address(): String? {
        val sessionId = serverSessionId
        if (sessionId != null) {
            val session = samClient.getActiveSessions().find { it.id == sessionId }
            if (session != null) return session.b32Address
        }
        return contactRepository.getMyIdentity()?.b32Address
    }

    private fun saveMyI2PIdentity(b32Address: String, destination: String) {
        scope.launch {
            val existing = contactRepository.getMyIdentity()
            if (existing == null) {
                val identity = ContactRepository.MyIdentity(
                    b32Address = b32Address,
                    publicKey = destination,
                    privateKeyEncrypted = ""
                )

                contactRepository.saveMyIdentity(identity)
                Log.i(TAG, "Saved I2P identity: $b32Address")
            }
        }
    }

    private fun getRecipientPublicKey(b32Address: String): PublicKey? {
        val key = cryptoManager.getContactPublicKey(context, b32Address)
        if (key == null) {
            Log.e(TAG, "No EC public key for $b32Address - contact must reshare QR")
        }
        return key
    }

    private fun getMyKeyPair(): KeyPair? {
        return cryptoManager.getOrCreateOwnKeyPair(context)
    }

    private fun decodeECPublicKey(bytes: ByteArray): PublicKey {
        val keyFactory = KeyFactory.getInstance("EC")
        val spec = X509EncodedKeySpec(bytes)
        return keyFactory.generatePublic(spec)
    }

    private fun generateSalt(): ByteArray {
        val salt = ByteArray(16)
        SecureRandom().nextBytes(salt)
        return salt
    }

    private val connectionLock = Any()

    private fun closeConnection(connection: PeerConnection) {
        synchronized(connectionLock) {
            try {
                connection.writer.close()
                connection.reader.close()
                connection.socket.close()
            } catch (e: Exception) {
                Log.w(TAG, "Error closing connection", e)
            }
        }
    }

    fun cleanup() {
        scope.cancel()
        synchronized(connectionLock) {
            activeConnections.values.forEach { closeConnection(it) }
            activeConnections.clear()
        }
        samClient.disconnect()
        Log.i(TAG, "MessageManager cleaned up")
    }
}