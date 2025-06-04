package com.example.anonymous.database

import org.jetbrains.exposed.sql.Table

object Users : Table("users") {
    val id = integer("id").autoIncrement()
    val uuid = varchar("UUID", 30)
}