package com.example.anonymous.messaging

import android.content.Context
import android.util.Base64
import android.util.Log
import com.example.anonymous.crypto.CryptoManager
import com.example.anonymous.i2p.SAMClient
import com.example.anonymous.network.model.Message
import com.example.anonymous.repository.ContactRepository
import com.example.anonymous.repository.MessageRepository
import com.google.gson.Gson
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
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
        val messageId: String = UUID.randomUUID().toString()
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
     * Initialize MessageManager and start listening for incoming I2P connections (After I2pdDaemon is READY)
     */
    suspend fun initialize(): Boolean = withContext(Dispatchers.IO) {
        try {
            _connectionState.value = ConnectionState.Connecting

            // SAM Connection check
            if (!samClient.connect()) {
                throw IllegalStateException("Failed to connect to SAM bridge")
            }

            // Create main STREAM session
            val sessionResult = samClient.createStreamSession()
            if (sessionResult.isFailure) {
                throw IllegalStateException("Failed to create SAM session: ${sessionResult.exceptionOrNull()?.message}")
            }

            val session = sessionResult.getOrThrow()
            serverSessionId = session.id

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

    suspend fun sendMessage(contactB32: String, text: String, replyToId: String? = null): Result<Message> {
        return try {
            val messageId = UUID.randomUUID().toString()
            _messageStatus.emit(MessageStatus(messageId, Status.SENDING))

            // I2P connection
            val connection = getOrCreateConnection(contactB32) ?: return Result.failure(Exception("Could not establish I2P connection to $contactB32"))

            // I2P my identity
            val myB32 = getMyB32Address() ?: return Result.failure(Exception("No I2P identity"))

            // ECDH key pair
            val ephemeralKeyPair = cryptoManager.generateECDHKeyPair()

            // recipient's public key
            val recipientPublicKey = getRecipientPublicKey(contactB32) ?: return Result.failure(Exception("No public key for contact $contactB32"))

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
            // Create new I2P connection via SAM
            val session = samClient.getActiveSessions().firstOrNull() ?: samClient.createStreamSession().getOrNull() ?: return@withContext null

            val socketResult = samClient.connectToPeer(session.id, contactB32)
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
    private fun sendOverI2P(connection: PeerConnection, message: NetworkMessage) {
        val socket = connection.socket ?: throw IllegalStateException("No socket")
        val writer = PrintWriter(OutputStreamWriter(socket.getOutputStream()), true)

        val json = gson.toJson(message)
    }

    /**
     * Listen for incoming connections
     */
    private fun startAcceptingConnections(sessionId: String) {
        scope.launch {
            while (isActive) {
                try {
                    val result = samClient.acceptConnection(sessionId)
                    if (result.isSuccess) {
                        val (socket, peerDest) = result.getOrThrow()
                        handleIncomingConnection(socket, peerDest)
                    }
                } catch (e: Exception) {
                    if (e !is CancellationException) {
                        Log.e(TAG, "Error accepting connection", e)
                        delay(1000)
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
    }

    /**
     * Process incoming I2P message
     */
    private suspend fun processIncomingMessage(json: String, senderB32: String) {
        try {
            val networkMsg =gson.fromJson(json, NetworkMessage::class.java)

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
                dhPublicKey = networkMsg.dhPublicKey
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
    private suspend fun sendReadReceipt(contactB32: String, messageId: String) {
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

    private fun closeConnection(connection: PeerConnection) {
        try {
            connection.writer.close()
            connection.reader.close()
            connection.socket.close()
        } catch (e: Exception) {

        }
    }

    private suspend fun getMyB32Address(): String? {
        return samClient.getActiveSessions().firstOrNull()?.b32Address ?: contactRepository.getMyIdentity()?.b32Address
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
        return cryptoManager.getContactPublicKey(context, b32Address)
    }

    private fun getMyKeyPair(): KeyPair? {
        return cryptoManager.getUserKeyPair(context)
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
}