package com.techwombat.liberates

import org.junit.Assert.assertEquals
import org.junit.Test

class ServerUploaderTest {

    @Test
    fun testUrlFormatting() {
        var rawUrl = "192.168.1.100:8000"
        var formatted = if (!rawUrl.startsWith("http://") && !rawUrl.startsWith("https://")) "http://$rawUrl" else rawUrl
        if (!formatted.endsWith("/jobs")) formatted = "$formatted/jobs"
        assertEquals("http://192.168.1.100:8000/jobs", formatted)
    }
}
