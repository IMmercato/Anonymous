package com.example.anonymous.network

object QueryBuilder {
    fun createUser(pubKey : String) = """
        mutation{
            createUser(publicKey: "$pubKey") {
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
}