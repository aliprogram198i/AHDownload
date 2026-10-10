package com.ahdownload.domain.resolver

interface HttpTextClient {
    suspend fun get(url: String): String
    suspend fun get(url: String, headers: Map<String, String>): String = get(url)
    suspend fun get(
        url: String,
        headers: Map<String, String>,
        diagnosticContext: Map<String, String>,
    ): String = get(url, headers)
    suspend fun postJson(url: String, body: String): String =
        throw UnsupportedOperationException("POST JSON is not supported by this client")
    suspend fun postJson(url: String, body: String, headers: Map<String, String>): String =
        postJson(url, body)
    suspend fun postJson(
        url: String,
        body: String,
        headers: Map<String, String>,
        diagnosticContext: Map<String, String>,
    ): String = postJson(url, body, headers)
}
