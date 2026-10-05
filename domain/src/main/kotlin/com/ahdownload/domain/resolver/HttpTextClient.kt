package com.ahdownload.domain.resolver

interface HttpTextClient {
    suspend fun get(url: String): String
}
