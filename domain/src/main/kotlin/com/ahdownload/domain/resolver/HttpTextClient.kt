package com.ahdownload.domain.resolver

interface HttpTextClient {
    suspend fun get(url: String): String

    suspend fun postJson(url: String, body: String): String =
        throw UnsupportedOperationException("POST JSON is not supported by this client")
}
