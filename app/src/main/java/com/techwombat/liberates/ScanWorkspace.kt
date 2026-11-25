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
    SERVER("Server AI"),
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
    /** Typical body-text line height on the screenshots, in pixels, for font-size advice (see [TextSize]). */
    val lineHeightPx: Int? = null,
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

    val isTest: Boolean get() = key == Workspaces.TEST

    /** Pictures saved from the scan, next to the versions folder. */
    private val imagesDirectory = File(directory.parentFile ?: directory, "images")

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
        val spelling = SpellingDictionary.get()
        val signature = "$DEVICE_CLEANUP_VERSION:${rawSignature()}:${rules.hashCode()}:spelling=${spelling != null}"
        versions().firstOrNull { it.kind == VersionKind.DEVICE }?.let { existing ->
            if (existing.complete && existing.model == null && deviceRulesSignature(existing) == signature) return existing
            delete(existing)
        }
        val cleaned = rawChunks().map { OcrCleanup.clean(it, rules) }
        // Spelling fixes use word counts from the whole book, so names it spells consistently are kept.
        val corrector = spelling?.let { OcrSpelling(it, cleaned.joinToString("\n\n")) }
        val chunks = if (corrector == null) cleaned else cleaned.map(corrector::correct)
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

    /** Saves a finished set of chunks (e.g. from the cleanup server) as a complete version. */
    @Synchronized
    fun saveCompletedVersion(kind: VersionKind, model: String, chunks: List<String>, seconds: Long, rawSignature: String): TextVersion {
        val now = System.currentTimeMillis()
        val version = TextVersion(
            id = "${kind.name.lowercase()}-$now",
            kind = kind,
            model = model,
            createdAtMillis = now,
            seconds = seconds,
            rawSignature = rawSignature,
            expectedChunks = chunks.size,
            complete = true,
        )
        chunksFile(version.id).writeText(chunks.joinToString("") { JSONArray().put(it).toString() + "\n" })
        writeMeta(version)
        return version
    }

    /** A cleanup job running on the Wombat Cleanup server for this scan. */
    data class ServerJob(val jobId: String, val model: String, val submittedAtMillis: Long, val rawSignature: String, val total: Int)

    var serverJob: ServerJob?
        get() = readMeta().optJSONObject("serverJob")?.let {
            ServerJob(it.optString("jobId"), it.optString("model"), it.optLong("submittedAtMillis"), it.optString("rawSignature"), it.optInt("total"))
        }?.takeIf { it.jobId.isNotBlank() }
        set(value) = updateMeta { meta ->
            if (value == null) {
                meta.remove("serverJob")
            } else {
                meta.put(
                    "serverJob",
                    JSONObject()
                        .put("jobId", value.jobId)
                        .put("model", value.model)
                        .put("submittedAtMillis", value.submittedAtMillis)
                        .put("rawSignature", value.rawSignature)
                        .put("total", value.total),
                )
            }
        }

    /** The sentences sent for a server job (so the corrected ones can be put back into the text). */
    fun saveServerPieces(jobId: String, json: String) = File(directory, "server-pieces-$jobId.json").writeText(json)

    fun serverPieces(jobId: String): String? = File(directory, "server-pieces-$jobId.json").takeIf { it.exists() }?.readText()

    fun deleteServerPieces(jobId: String) {
        File(directory, "server-pieces-$jobId.json").delete()
    }

    /** Why the last server cleanup stopped, shown in Review until the next attempt. */
    var serverJobError: String?
        get() = readMeta().optString("serverJobError").ifBlank { null }
        set(value) = updateMeta { if (value == null) it.remove("serverJobError") else it.put("serverJobError", value) }

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
        imagesDirectory.deleteRecursively()
        updateMeta { it.remove("scanSummary"); it.remove("preferredVersionId"); it.remove("coverImageId") }
    }

    // ---- Illustrations ----

    /** Saves a picture and returns its id for a text marker. Ids are never reused, even after a delete. */
    @Synchronized
    fun saveIllustration(jpeg: ByteArray): String {
        imagesDirectory.mkdirs()
        val number = readMeta().optInt("nextImageNumber", 1)
        val id = "img-${number.toString().padStart(4, '0')}.jpg"
        File(imagesDirectory, id).writeBytes(jpeg)
        updateMeta { it.put("nextImageNumber", number + 1).put("imagesChangedAt", System.currentTimeMillis()) }
        return id
    }

    fun illustrationIds(): List<String> =
        imagesDirectory.listFiles { file -> file.isFile && file.extension.lowercase() in IMAGE_EXTENSIONS }.orEmpty().map { it.name }.sorted()

    fun illustrationFile(id: String): File? =
        File(imagesDirectory, id).takeIf { !id.contains('/') && it.isFile }

    /** All saved pictures, for building an EPUB. */
    fun illustrationImages(): Map<String, ByteArray> = illustrationIds().associateWith { File(imagesDirectory, it).readBytes() }

    @Synchronized
    fun deleteIllustration(id: String) {
        illustrationFile(id)?.delete()
        updateMeta { meta ->
            if (meta.optString("coverImageId") == id) meta.remove("coverImageId")
            meta.put("imagesChangedAt", System.currentTimeMillis())
        }
    }

    /** The picture used as the EPUB cover (not repeated inside the book), if one was chosen. */
    var coverImageId: String?
        get() = readMeta().optString("coverImageId").ifBlank { null }?.takeIf { illustrationFile(it) != null }
        set(value) = updateMeta { meta ->
            if (value == null) meta.remove("coverImageId") else meta.put("coverImageId", value)
            meta.put("imagesChangedAt", System.currentTimeMillis())
        }

    private fun imagesChangedAt(): Long = readMeta().optLong("imagesChangedAt")

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

    var createdAtMillis: Long
        get() = readMeta().optLong("createdAtMillis").takeIf { it > 0 } ?: raw.rawFile().lastModified()
        set(value) = updateMeta { it.put("createdAtMillis", value) }

    /** Most recent scan, import or change, for sorting the library. */
    fun lastActivityMillis(): Long = maxOf(
        createdAtMillis,
        if (hasText()) raw.rawFile().lastModified() else 0L,
        scanSummary?.finishedAtMillis ?: 0L,
    )

    /** The EPUB last saved to Documents/Wombat Liberates, if any. */
    data class SavedEpub(val uri: String, val fileName: String, val versionId: String, val versionLabel: String, val builtAtMillis: Long)

    var savedEpub: SavedEpub?
        get() = readMeta().optJSONObject("savedEpub")?.let {
            SavedEpub(it.optString("uri"), it.optString("fileName"), it.optString("versionId"), it.optString("versionLabel"), it.optLong("builtAtMillis"))
        }
        set(value) = updateMeta { meta ->
            if (value == null) {
                meta.remove("savedEpub")
            } else {
                meta.put(
                    "savedEpub",
                    JSONObject()
                        .put("uri", value.uri)
                        .put("fileName", value.fileName)
                        .put("versionId", value.versionId)
                        .put("versionLabel", value.versionLabel)
                        .put("builtAtMillis", value.builtAtMillis),
                )
            }
        }

    /** True when the saved EPUB no longer matches the version chosen for it, or that version changed since. */
    fun isSavedEpubOutOfDate(rules: List<OcrLiteralRule>): Boolean {
        val saved = savedEpub ?: return false
        val preferred = preferredVersion(rules) ?: return false
        val preferredChanged = if (preferred.id == TextVersion.RAW_ID) raw.rawFile().lastModified() else preferred.createdAtMillis
        return saved.versionId != preferred.id || preferredChanged > saved.builtAtMillis || imagesChangedAt() > saved.builtAtMillis
    }

    /** One-line quality summary of the device-cleaned text, cached until that version changes. */
    fun qualitySummary(rules: List<OcrLiteralRule>): String? {
        val device = ensureDeviceVersion(rules) ?: return null
        val cached = readMeta().optJSONObject("quality")
        if (cached != null && cached.optLong("deviceCreatedAt") == device.createdAtMillis) return cached.optString("summary")
        val summary = OcrQuality.describe(OcrQuality.assess(text(device))).lineSequence().first()
        updateMeta { it.put("quality", JSONObject().put("deviceCreatedAt", device.createdAtMillis).put("summary", summary)) }
        return summary
    }

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
                lineHeightPx = it.optInt("lineHeightPx").takeIf { px -> px > 0 },
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
                        .put("finishedAtMillis", value.finishedAtMillis)
                        .apply { value.lineHeightPx?.let { put("lineHeightPx", it) } },
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
        val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp")
        // Bump when OcrCleanup's built-in fixes change, so device versions are regenerated.
        const val DEVICE_CLEANUP_VERSION = 5
    }
}

/**
 * All saved book scans (one folder each under files/books) plus the separate test scan.
 * Instances are cached so the scanning service and screens share one object per book.
 */
class Library(private val filesDir: File) {
    private val booksDirectory = File(filesDir, "books")
    private val cache = mutableMapOf<String, ScanWorkspace>()
    private var migrated = false

    @Synchronized
    fun books(): List<ScanWorkspace> {
        migrateSingleBook()
        return booksDirectory.listFiles { file -> file.isDirectory }.orEmpty()
            .mapNotNull { open(it.name) }
            .sortedByDescending { it.lastActivityMillis() }
    }

    @Synchronized
    fun open(id: String): ScanWorkspace? {
        if (id == Workspaces.TEST) return test()
        cache[id]?.let { return it }
        val directory = File(booksDirectory, id)
        if (!directory.isDirectory) return null
        return ScanWorkspace(id, "Book", OcrTextFiles(directory, RAW_BASE_NAME), File(directory, VERSIONS)).also { cache[id] = it }
    }

    @Synchronized
    fun create(title: String, author: String): ScanWorkspace {
        val now = System.currentTimeMillis()
        var id = "book-$now"
        while (File(booksDirectory, id).exists()) id += "x"
        File(booksDirectory, id).mkdirs()
        return open(id)!!.also {
            it.title = title
            it.author = author
            it.createdAtMillis = now
        }
    }

    /** Deletes the scan and its versions. EPUBs already saved to Documents are kept. */
    @Synchronized
    fun delete(id: String) {
        if (id == Workspaces.TEST || id.isBlank() || id.contains('/')) return
        cache.remove(id)
        File(booksDirectory, id).deleteRecursively()
    }

    @Synchronized
    fun test(): ScanWorkspace = cache.getOrPut(Workspaces.TEST) {
        val directory = File(filesDir, "test-scan")
        ScanWorkspace(Workspaces.TEST, "Test scan", OcrTextFiles(directory, RAW_BASE_NAME), File(directory, VERSIONS))
    }

    /** Older app versions kept one book in files/ directly; move it into the library as its own book. */
    private fun migrateSingleBook() {
        if (migrated) return
        migrated = true
        val oldRaw = File(filesDir, "$RAW_BASE_NAME.txt")
        val oldVersions = File(filesDir, "book-versions")
        val hasOldBook = (oldRaw.exists() && oldRaw.length() > 0) || oldVersions.isDirectory
        if (!hasOldBook) {
            oldRaw.delete()
            return
        }
        val directory = File(booksDirectory, "book-${oldRaw.takeIf { it.exists() }?.lastModified() ?: System.currentTimeMillis()}")
        directory.mkdirs()
        listOf("", "-corrected", "-last-page").forEach { suffix ->
            val file = File(filesDir, "$RAW_BASE_NAME$suffix.txt")
            if (file.exists()) file.renameTo(File(directory, file.name))
        }
        if (oldVersions.isDirectory) oldVersions.renameTo(File(directory, VERSIONS))
    }

    private companion object {
        const val RAW_BASE_NAME = "kindle-ocr"
        const val VERSIONS = "versions"
    }
}

/** App-wide access to the library and the test scan. */
object Workspaces {
    const val TEST = "test"
    private var library: Library? = null

    @Synchronized
    fun library(context: Context): Library {
        SpellingDictionary.useAssets(context)
        return library ?: Library(context.applicationContext.filesDir).also { library = it }
    }

    fun test(context: Context): ScanWorkspace = library(context).test()

    /** A book by id, or the test scan; null if the book was deleted. */
    fun byKey(context: Context, key: String?): ScanWorkspace? = key?.let { library(context).open(it) }
}
