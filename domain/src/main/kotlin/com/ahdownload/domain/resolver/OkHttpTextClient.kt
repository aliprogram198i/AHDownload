package com.ahdownload.domain.resolver

import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class OkHttpTextClient(
    private val client: OkHttpClient = OkHttpClient(),
) : HttpTextClient {
    override suspend fun get(url: String): String = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Accept-Language", "en-US,en;q=0.9")
            .get()
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("HTTP " + response.code)
            }
            response.body.string()
        }
    }

    private companion object {
        const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 10; Mobile) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/131.0 Mobile Safari/537.36"
    }
}
