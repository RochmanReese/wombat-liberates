package com.techwombat.liberates

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Where a version of the text came from. */
enum class VersionKind(val shortLabel: String) {
    RAW("Raw OCR"),
    DEVICE("Device cleanup"),
    OLLAMA("Ollama"),
    GEMINI("Gemini Nano"),
    EARLIER("Earlier cleanup"),
}

/**
 * One saved version of a scan's text. Versions are stored as chunks aligned with the raw OCR chunks,
 * so any two current versions can be compared chunk by chunk.
 */
data class TextVersion(
    val id: String,
    val kind: VersionKind,
    val model: String?,
    val createdAtMillis: Long,
    val seconds: Long,
    val rawSignature: String,
    val expectedChunks: Int,
    val complete: Boolean,
) {
    val label: String get() = if (model.isNullOrBlank()) kind.shortLabel else "${kind.shortLabel} ($model)"

    /** Comparable only if made from the current raw text with the same chunking. */
    fun comparableWith(currentSignature: String): Boolean =
        complete && kind != VersionKind.EARLIER && rawSignature == currentSignature

    companion object {
        const val RAW_ID = "raw"
    }
}

/** Summary of how the last scan into a workspace ended. */
data class ScanSummary(
    val screensRequested: Int,
    val screensCaptured: Int,
    val blankPagesSkipped: Int,
    val stopReason: String,
    val finishedAtMillis: Long,
)

/**
 * A scan (the book or the test scan): its immutable raw OCR plus every cleaned version made from it,
 * the EPUB title/author, and which version the EPUB should use.
 */
class ScanWorkspace(
    val key: String,
    val displayName: String,
    val raw: OcrTextFiles,
    private val directory: File,
) {
    private val metaFile = File(directory, "workspace.json")

    init {
        directory.mkdirs()
        importEarlierCleanup()
    }

    // ---- Raw text ----

    fun rawSignature(): String = raw.rawFile().let { "${it.length()}:${it.lastModified()}" }

    fun rawChunks(): List<String> = TextChunker.chunk(raw.rawText())

    fun hasText(): Boolean = raw.hasRawText()

    fun rawVersion(): TextVersion = TextVersion(
        id = TextVersion.RAW_ID,
        kind = VersionKind.RAW,
        model = null,
        createdAtMillis = raw.rawFile().lastModified(),
        seconds = 0,
        rawSignature = rawSignature(),
        expectedChunks = 0,
        complete = true,
    )

    // ---- Versions ----

    /** Saved versions (not including raw), oldest first. */
    @Synchronized
    fun versions(): List<TextVersion> =
        directory.listFiles { file -> file.name.endsWith(META_SUFFIX) }
            .orEmpty()
            .mapNotNull { runCatching { parseVersion(JSONObject(it.readText())) }.getOrNull() }
            .sortedBy { it.createdAtMillis }

    @Synchronized
    fun chunks(version: TextVersion): List<String> {
        if (version.id == TextVersion.RAW_ID) return rawChunks()
        val file = chunksFile(version.id)
        if (!file.exists()) return emptyList()
        return file.readLines().filter { it.isNotBlank() }.map { JSONArray(it).getString(0) }
    }

    fun text(version: TextVersion): String =
        if (version.id == TextVersion.RAW_ID) raw.rawText() else chunks(version).joinToString("\n\n")

    @Synchronized
    fun chunkCount(version: TextVersion): Int =
        chunksFile(version.id).takeIf { it.exists() }?.readLines()?.count { it.isNotBlank() } ?: 0

    /** Returns the device-cleaned version, regenerating it if the raw text or rules changed. */
    @Synchronized
    fun ensureDeviceVersion(rules: List<OcrLiteralRule>): TextVersion? {
        if (!hasText()) return null
        val signature = "$DEVICE_CLEANUP_VERSION:${rawSignature()}:${rules.hashCode()}"
        versions().firstOrNull { it.kind == VersionKind.DEVICE }?.let { existing ->
            if (existing.complete && existing.model == null && deviceRulesSignature(existing) == signature) return existing
            delete(existing)
        }
        val chunks = rawChunks().map { OcrCleanup.clean(it, rules) }
        val version = TextVersion(
            id = DEVICE_ID,
            kind = VersionKind.DEVICE,
            model = null,
            createdAtMillis = System.currentTimeMillis(),
            seconds = 0,
            rawSignature = rawSignature(),
            expectedChunks = chunks.size,
            complete = true,
        )
        chunksFile(version.id).writeText(chunks.joinToString("") { JSONArray().put(it).toString() + "\n" })
        writeMeta(version, extra = JSONObject().put("rulesSignature", signature))
        return version
    }

    private fun deviceRulesSignature(version: TextVersion): String? =
        runCatching { JSONObject(metaFile(version.id).readText()).optString("rulesSignature") }.getOrNull()

    /** Starts an empty AI version; chunks are appended as they finish so a run can be resumed. */
    @Synchronized
    fun startAiVersion(kind: VersionKind, model: String, expectedChunks: Int): TextVersion {
        val now = System.currentTimeMillis()
        val version = TextVersion(
            id = "${kind.name.lowercase()}-$now",
            kind = kind,
            model = model,
            createdAtMillis = now,
            seconds = 0,
            rawSignature = rawSignature(),
            expectedChunks = expectedChunks,
            complete = false,
        )
        chunksFile(version.id).writeText("")
        writeMeta(version)
        return version
    }

    @Synchronized
    fun appendChunk(version: TextVersion, text: String) {
        chunksFile(version.id).appendText(JSONArray().put(text).toString() + "\n")
    }

    /** Records time spent and marks the version complete once every chunk is present. */
    @Synchronized
    fun updateProgress(version: TextVersion, addedSeconds: Long): TextVersion {
        val updated = version.copy(
            seconds = version.seconds + addedSeconds,
            complete = chunkCount(version) >= version.expectedChunks,
        )
        writeMeta(updated)
        return updated
    }

    /** An unfinished AI run for the current raw text that can be continued. */
    fun resumableVersion(kind: VersionKind, model: String): TextVersion? =
        versions().lastOrNull { it.kind == kind && it.model == model && !it.complete && it.rawSignature == rawSignature() }

    @Synchronized
    fun delete(version: TextVersion) {
        if (version.id == TextVersion.RAW_ID) return
        metaFile(version.id).delete()
        chunksFile(version.id).delete()
        if (preferredVersionId == version.id) preferredVersionId = null
    }

    /** Clears raw text, every version and the scan summary (title and author are kept). */
    @Synchronized
    fun clearAll() {
        raw.clear()
        versions().forEach(::delete)
        updateMeta { it.remove("scanSummary"); it.remove("preferredVersionId") }
    }

    // ---- Workspace settings ----

    var title: String
        get() = readMeta().optString("title")
        set(value) = updateMeta { it.put("title", value) }

    var author: String
        get() = readMeta().optString("author")
        set(value) = updateMeta { it.put("author", value) }

    var preferredVersionId: String?
        get() = readMeta().optString("preferredVersionId").ifBlank { null }
        set(value) = updateMeta { if (value == null) it.remove("preferredVersionId") else it.put("preferredVersionId", value) }

    /** The version the EPUB should use: the user's choice if still valid, otherwise device cleanup. */
    fun preferredVersion(rules: List<OcrLiteralRule>): TextVersion? {
        val device = ensureDeviceVersion(rules) ?: return null
        val preferred = preferredVersionId ?: return device
        if (preferred == TextVersion.RAW_ID) return rawVersion()
        return versions().firstOrNull { it.id == preferred && it.complete } ?: device
    }

    var scanSummary: ScanSummary?
        get() = readMeta().optJSONObject("scanSummary")?.let {
            ScanSummary(
                screensRequested = it.optInt("screensRequested"),
                screensCaptured = it.optInt("screensCaptured"),
                blankPagesSkipped = it.optInt("blankPagesSkipped"),
                stopReason = it.optString("stopReason"),
                finishedAtMillis = it.optLong("finishedAtMillis"),
            )
        }
        set(value) = updateMeta { meta ->
            if (value == null) {
                meta.remove("scanSummary")
            } else {
                meta.put(
                    "scanSummary",
                    JSONObject()
                        .put("screensRequested", value.screensRequested)
                        .put("screensCaptured", value.screensCaptured)
                        .put("blankPagesSkipped", value.blankPagesSkipped)
                        .put("stopReason", value.stopReason)
                        .put("finishedAtMillis", value.finishedAtMillis),
                )
            }
        }

    // ---- Storage helpers ----

    private fun metaFile(id: String) = File(directory, "$id$META_SUFFIX")
    private fun chunksFile(id: String) = File(directory, "$id.chunks.jsonl")

    private fun writeMeta(version: TextVersion, extra: JSONObject? = null) {
        val json = (extra ?: JSONObject())
            .put("id", version.id)
            .put("kind", version.kind.name)
            .put("model", version.model ?: "")
            .put("createdAtMillis", version.createdAtMillis)
            .put("seconds", version.seconds)
            .put("rawSignature", version.rawSignature)
            .put("expectedChunks", version.expectedChunks)
            .put("complete", version.complete)
        if (extra == null && version.kind == VersionKind.DEVICE) {
            deviceRulesSignature(version)?.let { json.put("rulesSignature", it) }
        }
        metaFile(version.id).writeText(json.toString())
    }

    private fun parseVersion(json: JSONObject) = TextVersion(
        id = json.getString("id"),
        kind = VersionKind.valueOf(json.getString("kind")),
        model = json.optString("model").ifBlank { null },
        createdAtMillis = json.optLong("createdAtMillis"),
        seconds = json.optLong("seconds"),
        rawSignature = json.optString("rawSignature"),
        expectedChunks = json.optInt("expectedChunks"),
        complete = json.optBoolean("complete"),
    )

    @Synchronized
    private fun readMeta(): JSONObject =
        runCatching { JSONObject(metaFile.readText()) }.getOrElse { JSONObject() }

    @Synchronized
    private fun updateMeta(change: (JSONObject) -> Unit) {
        val meta = readMeta()
        change(meta)
        metaFile.writeText(meta.toString())
    }

    /** Keeps text cleaned by older app versions (single corrected file) as an "Earlier cleanup" version. */
    private fun importEarlierCleanup() {
        if (!raw.hasCorrectedText()) return
        val text = raw.correctedFile().readText().trim()
        if (text.isNotBlank()) {
            val chunks = TextChunker.chunk(text)
            val version = TextVersion(
                id = "earlier-${System.currentTimeMillis()}",
                kind = VersionKind.EARLIER,
                model = null,
                createdAtMillis = raw.correctedFile().lastModified(),
                seconds = 0,
                rawSignature = "",
                expectedChunks = chunks.size,
                complete = true,
            )
            chunksFile(version.id).writeText(chunks.joinToString("") { JSONArray().put(it).toString() + "\n" })
            writeMeta(version)
        }
        raw.correctedFile().writeText("")
    }

    private companion object {
        const val META_SUFFIX = ".meta.json"
        const val DEVICE_ID = "device"
        // Bump when OcrCleanup's built-in fixes change, so device versions are regenerated.
        const val DEVICE_CLEANUP_VERSION = 2
    }
}

/** The two workspaces the app uses. */
object Workspaces {
    private var book: ScanWorkspace? = null
    private var test: ScanWorkspace? = null

    const val BOOK = "book"
    const val TEST = "test"

    @Synchronized
    fun book(context: Context): ScanWorkspace {
        OcrTextStore.initialize(context.applicationContext)
        return book ?: ScanWorkspace(BOOK, "Book scan", OcrTextStore.main, File(context.filesDir, "book-versions")).also { book = it }
    }

    @Synchronized
    fun test(context: Context): ScanWorkspace {
        OcrTextStore.initialize(context.applicationContext)
        return test ?: ScanWorkspace(TEST, "Test scan", OcrTextStore.test, File(context.filesDir, "test-scan/versions")).also { test = it }
    }

    fun byKey(context: Context, key: String?): ScanWorkspace = if (key == TEST) test(context) else book(context)
}
