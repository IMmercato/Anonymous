package com.example.anonymous.database

import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.transactions.transaction

class UserRepository {
    fun getAllUsers(): List<UserData> = transaction {
        Users.selectAll().map { UserData(
            id = it[Users.id],
            uuid = it[Users.uuid]
        )}
    }

    fun getUser(id: Int): UserData? = transaction {
        Users.select { Users.id eq id }.firstOrNull()?.let { UserData(
            id = it[Users.id],
            uuid = it[Users.uuid]
        ) }
    }

    fun createUser(user: UserData) = transaction {
        Users.insert {
            it[uuid] = user.uuid // Corrected insertion syntax
        }
    }

    fun deleteUser(id: Int) = transaction {
        Users.deleteWhere { Users.id eq id }
    }
}

data class UserData(
    val id: Int,
    val uuid: String // Corrected type
)