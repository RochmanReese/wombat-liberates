package com.techwombat.liberates

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CleanupServerTest {
    @Test
    fun `server status and result JSON are read`() {
        val status = CleanupClient.parseStatus(
            """{"job_id":"a","status":"running","done":34,"total":220,"queue_position":0,"eta_seconds":1500,"model":"gemma3:12b","error":null}""",
        )
        assertEquals(CleanupClient.JobStatus("running", 34, 220, 0, 1500L, "gemma3:12b", null), status)
        val queued = CleanupClient.parseStatus("""{"status":"queued","done":0,"total":5,"queue_position":null,"eta_seconds":null,"model":"m","error":null}""")
        assertNull(queued.etaSeconds)
        val result = CleanupClient.parseResult("""{"chunks":["One.","Two\nlines."],"model":"gemma3:12b","seconds":32}""")
        assertEquals(listOf("One.", "Two\nlines."), result.chunks)
        assertEquals(32L, result.seconds)
        assertEquals("gemma3:12b", CleanupClient.parseModels("""{"default":"gemma3:12b","models":["gemma3:12b"]}"""))
    }

    @Test
    fun `a server job is remembered and its result becomes a comparable version`() {
        val dir = Files.createTempDirectory("server-job").toFile()
        val workspace = ScanWorkspace("book-1", "Book", OcrTextFiles(dir, "kindle-ocr"), File(dir, "versions"))
        workspace.raw.appendPage("She took er bag.")
        val device = workspace.ensureDeviceVersion(emptyList())!!
        workspace.serverJob = ScanWorkspace.ServerJob("abc", "gemma3:12b", 1L, device.rawSignature, 1)
        assertEquals("abc", workspace.serverJob?.jobId)

        val version = workspace.saveCompletedVersion(VersionKind.SERVER, "gemma3:12b", listOf("She took her bag."), 16, device.rawSignature)
        workspace.serverJob = null
        assertNull(workspace.serverJob)
        assertEquals("Server AI (gemma3:12b)", version.label)
        assertTrue(version.comparableWith(workspace.rawSignature()))
        assertEquals("She took her bag.", workspace.text(version))
        assertEquals(listOf(VersionKind.DEVICE, VersionKind.SERVER), workspace.versions().map { it.kind })
    }
}
