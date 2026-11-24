package com.techwombat.liberates

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.widget.Toast
import androidx.core.content.FileProvider
import java.io.File

/** Builds EPUBs for a scan, saves them to Documents/Wombat Liberates, and opens them in the user's reader. */
object BookExport {
    const val FOLDER_NAME = "Wombat Liberates"
    private const val EPUB_MIME = "application/epub+zip"

    fun title(workspace: ScanWorkspace): String =
        workspace.title.ifBlank { if (workspace.isTest) "Test scan" else "Untitled" }

    fun author(workspace: ScanWorkspace): String = workspace.author.ifBlank { "Unknown author" }

    /** Title shown inside the EPUB: plain for the book's chosen version, labelled for any other version. */
    fun epubTitle(workspace: ScanWorkspace, version: TextVersion, preferredId: String?): String =
        if (!workspace.isTest && version.id == preferredId) title(workspace) else "${title(workspace)} (${version.label})"

    fun safeFileName(name: String, extension: String): String {
        val safe = name.map { if (it.isLetterOrDigit() || it == '-' || it == '_' || it == ' ') it else '_' }
            .joinToString("").trim().replace(Regex("[ _]+"), " ").ifBlank { "book" }
        return "$safe.$extension"
    }

    /**
     * Writes the book's chosen version as an EPUB to Documents/Wombat Liberates, replacing the file saved
     * last time when it still exists. Blocking: call from a background dispatcher.
     */
    fun saveToDocuments(context: Context, workspace: ScanWorkspace, rules: List<OcrLiteralRule>): ScanWorkspace.SavedEpub {
        check(Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) { "Saving to Documents needs Android 10 or later." }
        val version = workspace.preferredVersion(rules) ?: error("This book has no text yet.")
        val fileName = safeFileName(title(workspace), "epub")
        val resolver = context.contentResolver
        val text = workspace.text(version)

        fun write(uri: Uri) {
            resolver.openOutputStream(uri, "wt")?.use { writeEpub(it, workspace, text, title(workspace)) }
                ?: error("Could not write the EPUB file.")
        }

        // Reuse the previous file (same name, same place) if it is still there and still ours.
        val previous = workspace.savedEpub
        val reused = previous?.takeIf { it.fileName == fileName }?.let { saved ->
            runCatching { Uri.parse(saved.uri).also(::write) }.getOrNull()
        }
        val uri = reused ?: run {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                put(MediaStore.MediaColumns.MIME_TYPE, EPUB_MIME)
                put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DOCUMENTS}/$FOLDER_NAME")
            }
            val created = resolver.insert(MediaStore.Files.getContentUri("external"), values)
                ?: error("Android would not create the EPUB in Documents.")
            runCatching { write(created) }.onFailure {
                resolver.delete(created, null, null)
                throw it
            }
            created
        }
        return ScanWorkspace.SavedEpub(uri.toString(), fileName, version.id, version.label, System.currentTimeMillis())
            .also { workspace.savedEpub = it }
    }

    /** Builds one version as an EPUB in a private shared folder. Blocking: call from a background dispatcher. */
    fun buildShareableEpub(context: Context, workspace: ScanWorkspace, version: TextVersion, preferredId: String?): File {
        val directory = File(context.cacheDir, "shared-epubs").apply { mkdirs() }
        directory.listFiles()?.forEach { it.delete() }
        val name = if (version.id == preferredId) title(workspace) else "${title(workspace)} ${version.label}"
        return File(directory, safeFileName(name, "epub")).also { file ->
            file.outputStream().use { writeEpub(it, workspace, workspace.text(version), epubTitle(workspace, version, preferredId)) }
        }
    }

    /** Writes an EPUB with the book's saved pictures and chosen cover. */
    fun writeEpub(output: java.io.OutputStream, workspace: ScanWorkspace, text: String, epubTitle: String) {
        EpubWriter.write(output, text, epubTitle, author(workspace), workspace.illustrationImages(), workspace.coverImageId)
    }

    /** Opens an EPUB in the default reader (Android offers "Always" the first time). */
    fun openInReader(activity: Activity, file: File) {
        val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.fileprovider", file)
        val view = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, EPUB_MIME)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try {
            activity.startActivity(view)
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(activity, "No installed app opens EPUB files.", Toast.LENGTH_LONG).show()
        }
    }
}
