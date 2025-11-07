package com.example.anonymous.network

import com.google.ai.client.generativeai.type.Content
import io.ktor.http.content.Version

object QueryBuilder {
    fun registerUser(publicKey: String) = """
        mutation {
            registerUser(publicKey: "$publicKey") {
                jwt
            }
        }
    """.trimIndent()

    fun createUser(publicKey: String) = """
        mutation{
            createUser(publicKey: "$publicKey") {
                id
                publicKey
                createdAt
            }
        }
    """.trimIndent()

    fun deleteUser(id: String) = """
        mutation{
            deleteUser(id: "$id")
        }
    """.trimIndent()

    fun checkUser(id: String) = """
        query{
            getUser(id: "$id")
        }
    """.trimIndent()

    fun loginWithJwt(jwt: String) = """
        mutation {
            loginWithJwt(jwt: "$jwt") {
                nonce
            }
        }
    """.trimIndent()

    fun completeLogin(uuid: String, signature: String) = """
        mutation {
            completeLogin(uuid: "$uuid", signature: "$signature") {
                token
                qrToken
            }
        }
    """.trimIndent()

    fun sendEncryptedMessage(
        receiverId: String,
        encryptedContent: String,
        iv: String,
        authTag: String,
        version: Int,
        dhPublicKey: String
    ) = """
    mutation {
        sendMessage(
            receiverId: "$receiverId",
            encryptedContent: "$encryptedContent",
            iv: "$iv",
            authTag: "$authTag",
            version: $version,
            dhPublicKey: "$dhPublicKey"
        ) {
            id
            encryptedContent
            iv
            authTag
            version
            dhPublicKey
            sender {
                id
            }
            receiver {
                id
            }
            isRead
            createdAt
        }
    }
""".trimIndent()

    fun getEncryptedMessages(receiverId: String) = """
        query GetMessages {
            getMessages(receiverId: "$receiverId") {
                id
                encryptedContent
                iv
                authTag
                version
                dhPublicKey
                sender {
                    id
                }
                receiver {
                    id
                }
                isRead
                createdAt
            }
        }
    """.trimIndent()

    fun getUnreadEncryptedMessages() = """
        query GetUnreadMessages {
            getUnreadMessages {
                id
                encryptedContent
                iv
                authTag
                version
                dhPublicKey
                sender {
                    id
                }
                receiver {
                    id
                }
                isRead
                createdAt
            }
        }
    """.trimIndent()

    fun markMessageAsRead(messageId: String) = """
        mutation MarkMessageAsRead {
            markMessageAsRead(messageId: "$messageId") {
                id
                isRead
            }
        }
    """.trimIndent()
}