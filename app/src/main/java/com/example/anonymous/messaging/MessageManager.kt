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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.OutputStreamWriter
import java.io.PrintWriter
import java.net.Socket
import java.security.PublicKey
import java.security.SecureRandom
import java.security.interfaces.ECPublicKey
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.SecretKey

class MessageManager private constructor(context: Context) {
    companion object {
        private const val TAG = "MessageManager"
        private const val PROTOCOL_VERSION = 1

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
        val socket: Socket? = null,
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
        val encryptedPayload: String,
        val iv: String,
        val authTag: String,
        val dhPublicKey: String,
        val timestamp: Long = System.currentTimeMillis(),
        val replyToId: String? = null
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

    private val _incomingMessages = MutableSharedFlow<Message>(extraBufferCapacity = 100)
    val incomingMessages: SharedFlow<Message> = _incomingMessages.asSharedFlow()

    private val _messageStatus = MutableSharedFlow<MessageStatus>(extraBufferCapacity = 100)
    val messageStatus: SharedFlow<MessageStatus> = _messageStatus.asSharedFlow()

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    fun initialize(sessionId: String) {
        scope.launch {
            _connectionState.value = ConnectionState.Connected
            Log.i(TAG, "MessageManager initialized with I2P")
        }
    }

    suspend fun sendMessage(contactB32: String, text: String, replyToId: String? = null): Result<Message> {
        return try {
            _messageStatus.emit(MessageStatus(UUID.randomUUID().toString(), Status.SENDING))

            // I2P connection
            val connection = getOrCreateConnection(contactB32) ?: return Result.failure(Exception("Could not establish I2P connection to $contactB32"))

            // I2P myidentity
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

            // Network message
            val networkMessage = NetworkMessage(
                senderB32 = myB32,
                recipientB32 = contactB32,
                encryptedPayload = encryptedData.ct,
                iv = encryptedData.iv,
                authTag = encryptedData.authTag,
                dhPublicKey = Base64.encodeToString((ephemeralKeyPair.public as ECPublicKey).q.getEncoded(true), Base64.NO_WRAP),
                replyToId = replyToId
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
    private suspend fun getOrCreateConnection(contactB32: String): PeerConnection? {
        // Check existing connection
        activeConnections[contactB32]?.let { connection ->
            if (connection.isConnected && (System.currentTimeMillis() - connection.lastActivity) > 300000) {
                return connection
            }
        }

        // Create new I2P connection via SAM
        val session = samClient.getActiveSessions().firstOrNull() ?: samClient.createStreamSession().getOrNull() ?: return null

        val socketResult = samClient.connectToPeer(session.id, contactB32)
        if (socketResult.isFailure) {
            Log.e(TAG, "Failed to connect to $contactB32 via I2P")
            return null
        }

        val socket = socketResult.getOrThrow()

        val connection = PeerConnection(
            contactB32 = contactB32,
            socket = socket,
            isConnected = true
        )

        activeConnections[contactB32] = connection

        // Start listening for incoming messages on this connection
        startConnectionListener(connection)

        return connection
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
     * Listen for messages on existing connection
     */
    private fun startConnectionListener(connection: PeerConnection)  {
    }

    private suspend fun getMyB32Address(): String? {
        return samClient.getActiveSessions().firstOrNull()?.b32Address ?: contactRepository.getMyIdentity()?.b32Address
    }

    private fun getRecipientPublicKey(b32Address: String): PublicKey? {
        return cryptoManager.getContactPublicKey(context, b32Address)
    }

    private fun generateSalt(): ByteArray {
        val salt = ByteArray(16)
        SecureRandom().nextBytes(salt)
        return salt
    }
}