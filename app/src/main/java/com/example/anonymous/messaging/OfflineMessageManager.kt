package com.example.anonymous.messaging

import android.content.Context
import android.util.Log
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import com.example.anonymous.i2p.SAMClient
import io.ktor.utils.io.InternalAPI
import io.ktor.utils.io.locks.synchronized
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.UUID


@Entity(tableName = "queued_messages")
data class QueuedMessage(
    @PrimaryKey val id: String,
    val recipientB32: String,
    val content: String,
    val replyToId: String? = null,
    val status: Status,
    val createdAt: Long,
    val retryCount: Int = 0,
    val nextRetryAt: Long = 0,
    val deliveredAt: Long? = null,
    val errorMessage: String? = null
) {
    enum class Status {
        PENDING, DELIVERED, FAILED, CANCELED
    }
}

@Dao
interface MessageDao {
    @Insert
    suspend fun insert(message: QueuedMessage)

    @Query("SELECT * FROM queued_messages WHERE id = :id")
    suspend fun getById(id: String): QueuedMessage?

    @Query("SELECT * FROM queued_messages WHERE status = 'PENDING' AND nextRetryAt <= :now")
    suspend fun getDueForRetry(now: Long): List<QueuedMessage>

    @Query("UPDATE queued_messages SET status = :status WHERE id = :id")
    suspend fun updateStatus(id: String, status: QueuedMessage.Status)

    @Query("UPDATE queued_messages SET retryCount = retryCount + 1, nextRetryAt = :nextRetryAt, errorMessage = :error WHERE id = :id")
    suspend fun incrementRetry(id: String, nextRetryAt: Long, error: String)

    @Query("UPDATE queued_messages SET errorMessage = :error WHERE id = :id")
    suspend fun updateErrorMessage(id: String, error: String)
}

@Database(entities = [QueuedMessage::class], version = 1)
abstract class OfflineMessageDatabase : RoomDatabase() {
    abstract fun messageDao(): MessageDao

    companion object {
        @Volatile
        private var instance: OfflineMessageDatabase? = null

        @OptIn(InternalAPI::class)
        fun getInstance(context: Context): OfflineMessageDatabase {
            return instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    OfflineMessageDatabase::class.java,
                    "offline_messages.db"
                ).build().also { instance = it }
            }
        }
    }
}

class OfflineMessageManager private constructor(context: Context) {
    companion object {
        private const val TAG = "OfflineMessageManager"
        private const val MAX_RETRIES = 5
        private val RETRY_DELAY = listOf(30_000L, 60_000L, 120_000L, 300_000L, 600_000L)        // 30s-10min

        @Volatile
        private var instance: OfflineMessageManager? = null

        @OptIn(InternalAPI::class)
        fun getInstance(context: Context): OfflineMessageManager {
            return instance ?: synchronized(this) {
                instance ?: OfflineMessageManager(context.applicationContext).also {
                    instance = it
                }
            }
        }
    }

    private val context = context.applicationContext
    private val db = OfflineMessageDatabase.getInstance(context)
    private val messageDao = db.messageDao()
    private val samClient = SAMClient.getInstance()
    private val messageManager = MessageManager.getInstance(context)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var retryJob: Job? = null

    init {
        startRetryWorker()
    }

    /**
     * Queue message for offline delivery
     */
    suspend fun queueMessage(
        recipientB32: String,
        content: String,
        replyToId: String? = null
    ): String {
        val queuedId = UUID.randomUUID().toString()
        val queuedMessage = QueuedMessage(
            id = queuedId,
            recipientB32 = recipientB32,
            content = content,
            replyToId = replyToId,
            status = QueuedMessage.Status.PENDING,
            createdAt = System.currentTimeMillis(),
            retryCount = 0,
            nextRetryAt = System.currentTimeMillis()
        )

        messageDao.insert(queuedMessage)
        Log.d(TAG, "Queued message $queuedId for $recipientB32")

        scope.launch { attemptDelivery(queuedMessage) }

        return queuedId
    }

    /**
     * Start background retry worker
     */
    private fun startRetryWorker() {
        retryJob = scope.launch {
            while (isActive) {
                try {
                    processPendingMessages()
                } catch (e: Exception) {
                    Log.e(TAG, "Error in retry worker", e)
                }
                delay(30_000)       // 30s
            }
        }
    }

    private suspend fun processPendingMessages() {
        val now = System.currentTimeMillis()
        val dueMessages = messageDao.getDueForRetry(now)

        dueMessages.forEach { message ->
            attemptDelivery(message)
        }
    }

    private suspend fun attemptDelivery(queuedMessage: QueuedMessage) {
        // Check if recipient is online
        val isOnline = checkRecipientOnline(queuedMessage.recipientB32)

        if (!isOnline) {
            handleRetryFailure(queuedMessage, "Recipient offline")
            return
        }
    }

    private suspend fun checkRecipientOnline(b32Address: String): Boolean {
        return try {
            val session = samClient.getActiveSessions().firstOrNull() ?: samClient.createStreamSession().getOrNull() ?: return false

            // Try to connect
            val result = samClient.connectToPeer(session.id, b32Address)

            if (result.isSuccess) {
                // Close test connection
                result.getOrThrow().close()
                true
            } else {
                false
            }
        } catch (e: Exception) {
            false
        }
    }

    private suspend fun handleRetryFailure(message: QueuedMessage, reason: String) {
        val newRetryCount = message.retryCount + 1

        if (newRetryCount >= MAX_RETRIES) {
            messageDao.updateStatus(message.id, QueuedMessage.Status.FAILED)
            messageDao.updateErrorMessage(message.id, "Max retries reached: $reason")
            Log.w(TAG, "Message ${message.id} failed permanently after $MAX_RETRIES retries")
        } else {
            val delayIndex = minOf(newRetryCount, RETRY_DELAY.size - 1)
            val nextRetryDelay = RETRY_DELAY[delayIndex]
            val nextRetryAt = System.currentTimeMillis() + nextRetryDelay

            messageDao.incrementRetry(message.id, nextRetryAt, reason)
            Log.d(TAG, "Schedule retry ${newRetryCount + 1}/$MAX_RETRIES for ${message.id} in ${nextRetryDelay}ms")
        }
    }
}