package com.hoshino.wenku8reader.data.local.webdav

import java.io.IOException
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WebDavWorkerRetryPolicyTest {

    @Test
    fun `retries network and server failures but not permanent auth or config errors`() {
        assertTrue(WebDavWorkerRetryPolicy.shouldRetry(IOException("network timeout")))
        assertTrue(WebDavWorkerRetryPolicy.shouldRetry(WebDavException("HTTP 503", retryable = true)))
        assertFalse(WebDavWorkerRetryPolicy.shouldRetry(WebDavException("HTTP 401", retryable = false)))
        assertFalse(WebDavWorkerRetryPolicy.shouldRetry(IllegalArgumentException("invalid URL")))
    }
}
