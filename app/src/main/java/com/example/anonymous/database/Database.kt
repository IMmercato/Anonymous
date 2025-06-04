package com.example.anonymous.database

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource

object Database {
    private val config = HikariConfig().apply {
        jdbcUrl = System.getenv("DATABASE_URL") ?: "jdbc:postgresql://hackclub.app:5432/immercato_immercato"
        username = System.getenv("DB_USER") ?: "immercato"
        password = System.getenv("DB_PASSWORD") ?: "G27K8zNvBA3RALEiadxBG5gJvCQ"
        driverClassName = "org.postgresql.Driver"
        maximumPoolSize = 10
        idleTimeout = 60000 // Close idle connections after 60 seconds
        connectionTimeout = 30000 // Timeout if connection takes longer than 30 seconds
        isAutoCommit = false // Prevent unintended commits
    }

    val dataSource: HikariDataSource by lazy {
        HikariDataSource(config)
    }
}