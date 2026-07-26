package com.techwombat.liberates

import android.os.Handler
import android.os.Looper
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.net.HttpURLConnection
import java.net.URL

object ServerUploader {

    private val mainHandler = Handler(Looper.getMainLooper())

    fun uploadEpub(
        serverUrl: String,
        epubFile: File,
        title: String,
        author: String,
        callback: (Boolean, String) -> Unit
    ) {
        Thread {
            try {
                var targetUrl = serverUrl.trim()
                if (!targetUrl.startsWith("http://") && !targetUrl.startsWith("https://")) {
                    targetUrl = "http://$targetUrl"
                }
                if (!targetUrl.endsWith("/jobs") && !targetUrl.endsWith("/upload")) {
                    targetUrl = if (targetUrl.endsWith("/")) "${targetUrl}jobs" else "$targetUrl/jobs"
                }

                val boundary = "===WombatBoundary${System.currentTimeMillis()}==="
                val lineEnd = "\r\n"
                val twoHyphens = "--"

                val url = URL(targetUrl)
                val conn = url.openConnection() as HttpURLConnection
                conn.doInput = true
                conn.doOutput = true
                conn.useCaches = false
                conn.requestMethod = "POST"
                conn.connectTimeout = 10000
                conn.readTimeout = 15000
                conn.setRequestProperty("Connection", "Keep-Alive")
                conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")

                DataOutputStream(conn.outputStream).use { dos ->
                    // Title parameter
                    dos.writeBytes("$twoHyphens$boundary$lineEnd")
                    dos.writeBytes("Content-Disposition: form-data; name=\"title\"$lineEnd$lineEnd")
                    dos.writeBytes("$title$lineEnd")

                    // Author parameter
                    dos.writeBytes("$twoHyphens$boundary$lineEnd")
                    dos.writeBytes("Content-Disposition: form-data; name=\"author\"$lineEnd$lineEnd")
                    dos.writeBytes("$author$lineEnd")

                    // EPUB File parameter
                    dos.writeBytes("$twoHyphens$boundary$lineEnd")
                    dos.writeBytes("Content-Disposition: form-data; name=\"file\"; filename=\"${epubFile.name}\"$lineEnd")
                    dos.writeBytes("Content-Type: application/epub+zip$lineEnd$lineEnd")

                    FileInputStream(epubFile).use { fis ->
                        val buffer = ByteArray(4096)
                        var bytesRead: Int
                        while (fis.read(buffer).also { bytesRead = it } != -1) {
                            dos.write(buffer, 0, bytesRead)
                        }
                    }
                    dos.writeBytes(lineEnd)

                    // End boundary
                    dos.writeBytes("$twoHyphens$boundary$twoHyphens$lineEnd")
                    dos.flush()
                }

                val responseCode = conn.responseCode
                val responseMsg = if (responseCode in 200..299) {
                    conn.inputStream.bufferedReader().use { it.readText() }
                } else {
                    conn.errorStream?.bufferedReader()?.use { it.readText() } ?: "HTTP $responseCode"
                }

                mainHandler.post {
                    if (responseCode in 200..299) {
                        callback(true, "Upload Successful (HTTP $responseCode):\n$responseMsg")
                    } else {
                        callback(false, "Upload Failed (HTTP $responseCode):\n$responseMsg")
                    }
                }
            } catch (e: Exception) {
                mainHandler.post {
                    callback(false, "Upload Error: ${e.localizedMessage ?: e.message}")
                }
            }
        }.start()
    }
}
