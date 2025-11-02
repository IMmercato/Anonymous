package com.example.anonymous.network

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
}