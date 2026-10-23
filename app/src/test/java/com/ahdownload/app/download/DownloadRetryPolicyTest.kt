package com.ahdownload.app.download

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadRetryPolicyTest {
    @Test
    fun retriesOnlyTransientHttpCodes() {
        assertTrue(DownloadRetryPolicy.isRetryableHttp("408"))
        assertTrue(DownloadRetryPolicy.isRetryableHttp("429"))
        assertTrue(DownloadRetryPolicy.isRetryableHttp("500"))
        assertTrue(DownloadRetryPolicy.isRetryableHttp("599"))

        assertFalse(DownloadRetryPolicy.isRetryableHttp(null))
        assertFalse(DownloadRetryPolicy.isRetryableHttp("not-a-code"))
        assertFalse(DownloadRetryPolicy.isRetryableHttp("200"))
        assertFalse(DownloadRetryPolicy.isRetryableHttp("403"))
        assertFalse(DownloadRetryPolicy.isRetryableHttp("600"))
    }
}
