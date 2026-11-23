package com.uoai.providerapi

/** Loopback-only API server (127.0.0.1:8765). */
interface LocalHttpServer {
    val host: String get() = "127.0.0.1"
    val port: Int get() = 8765
    suspend fun start()
    suspend fun stop()
}
