package com.techwombat.liberates

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScanWorkspaceTest {
    private val directory: File = Files.createTempDirectory("workspace-test").toFile()
    private val raw = OcrTextFiles(directory, "kindle-ocr")
    private fun workspace() = ScanWorkspace("book", "Book scan", raw, File(directory, "versions"))
    private val rules = listOf(OcrLiteralRule("teh", "the"))

    @Test
    fun `device version is cleaned, aligned with raw chunks, and reused until raw changes`() {
        raw.appendPage("As | told teh Councilman.")
        val workspace = workspace()
        val device = workspace.ensureDeviceVersion(rules)!!
        assertEquals("As I told the Councilman.", workspace.text(device))
        assertEquals(workspace.rawChunks().size, workspace.chunks(device).size)
        assertEquals(device.createdAtMillis, workspace.ensureDeviceVersion(rules)!!.createdAtMillis)

        Thread.sleep(1_100) // lastModified has one-second resolution on some file systems
        raw.appendPage("Then teh end.")
        val regenerated = workspace.ensureDeviceVersion(rules)!!
        assertTrue(workspace.text(regenerated).endsWith("Then the end."))
        assertEquals(1, workspace.versions().count { it.kind == VersionKind.DEVICE })
    }

    @Test
    fun `device version is regenerated when rules change`() {
        raw.appendPage("teh cat")
        val workspace = workspace()
        workspace.ensureDeviceVersion(rules)
        val updated = workspace.ensureDeviceVersion(rules + OcrLiteralRule("cat", "dog"))!!
        assertEquals("the dog", workspace.text(updated))
    }

    @Test
    fun `ai version saves chunks as it goes and can be resumed`() {
        raw.appendPage("One.")
        val workspace = workspace()
        val version = workspace.startAiVersion(VersionKind.OLLAMA, "mistral", expectedChunks = 2)
        workspace.appendChunk(version, "First\nchunk")
        val partial = workspace.updateProgress(version, 10)
        assertFalse(partial.complete)
        assertEquals(partial.id, workspace.resumableVersion(VersionKind.OLLAMA, "mistral")?.id)
        assertNull(workspace.resumableVersion(VersionKind.OLLAMA, "other-model"))

        workspace.appendChunk(partial, "Second")
        val finished = workspace.updateProgress(partial, 5)
        assertTrue(finished.complete)
        assertEquals(15, finished.seconds)
        assertEquals(listOf("First\nchunk", "Second"), workspace.chunks(finished))
        assertNull(workspace.resumableVersion(VersionKind.OLLAMA, "mistral"))
        assertEquals("Ollama (mistral)", finished.label)
    }

    @Test
    fun `preferred version falls back to device cleanup when deleted`() {
        raw.appendPage("teh text")
        val workspace = workspace()
        val ai = workspace.startAiVersion(VersionKind.OLLAMA, "m", 1)
        workspace.appendChunk(ai, "AI text")
        val done = workspace.updateProgress(ai, 1)
        workspace.preferredVersionId = done.id
        assertEquals(done.id, workspace.preferredVersion(rules)!!.id)
        workspace.delete(done)
        assertEquals(VersionKind.DEVICE, workspace.preferredVersion(rules)!!.kind)
    }

    @Test
    fun `title, author and scan summary persist across instances`() {
        workspace().apply {
            title = "My Book"
            author = "A. Writer"
            scanSummary = ScanSummary(0, 812, 3, "Batch complete", 1234L)
        }
        val reloaded = workspace()
        assertEquals("My Book", reloaded.title)
        assertEquals("A. Writer", reloaded.author)
        assertEquals(812, reloaded.scanSummary?.screensCaptured)
    }

    @Test
    fun `cleanup from older app versions is kept as an earlier version`() {
        raw.appendPage("raw text")
        raw.correctedFile().writeText("Old cleaned text.\n")
        val workspace = workspace()
        val earlier = workspace.versions().single { it.kind == VersionKind.EARLIER }
        assertEquals("Old cleaned text.", workspace.text(earlier))
        assertFalse(earlier.comparableWith(workspace.rawSignature()))
        assertEquals(0L, raw.correctedFile().length())
    }

    @Test
    fun `clear all removes text and versions but keeps the title`() {
        raw.appendPage("text")
        val workspace = workspace()
        workspace.title = "Kept"
        workspace.ensureDeviceVersion(rules)
        workspace.clearAll()
        assertFalse(workspace.hasText())
        assertTrue(workspace.versions().isEmpty())
        assertNull(workspace.ensureDeviceVersion(rules))
        assertEquals("Kept", workspace.title)
        assertNotNull(workspace.rawVersion())
        assertNotEquals("", workspace.rawSignature())
    }
}
