package com.techwombat.liberates

import android.util.Base64
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Splits text into paragraph-aligned chunks small enough for a correction model. */
object TextChunker {
    const val CHUNK_CHAR_LIMIT = 2_500

    fun chunk(text: String, limit: Int = CHUNK_CHAR_LIMIT): List<String> {
        val paragraphs = text.split(Regex("\\n\\s*\\n")).map(String::trim).filter(String::isNotBlank)
        val chunks = mutableListOf<String>()
        var chunk = ""
        paragraphs.forEach { paragraph ->
            if (paragraph.length > limit) {
                if (chunk.isNotBlank()) {
                    chunks += chunk
                    chunk = ""
                }
                paragraph.chunked(limit).forEach(chunks::add)
            } else if (chunk.isBlank()) {
                chunk = paragraph
            } else if (chunk.length + paragraph.length + 2 <= limit) {
                chunk += "\n\n$paragraph"
            } else {
                chunks += chunk
                chunk = paragraph
            }
        }
        if (chunk.isNotBlank()) chunks += chunk
        return chunks
    }
}

/** Conservative OCR proofreading prompt shared by on-device and Ollama correction. */
object CorrectionPrompt {
    fun build(chunk: String): String = """
        You are a conservative OCR proofreader.

        Correct only unmistakable OCR mistakes: confused characters, capitalization errors, broken words, stray punctuation, and word-break errors.
        Preserve the author's exact wording, sentence order, paragraph breaks, dialogue, chapter headings, names, formatting, and meaning.
        Do not rewrite for style. Do not summarize. Do not explain your edits. Do not add or remove sentences.
        If a possible correction is uncertain, leave it unchanged.
        Return only the corrected text, with no heading, commentary, Markdown, or quotes around the response.

        OCR TEXT START
        $chunk
        OCR TEXT END
    """.trimIndent()
}

/** Blocking Ollama /api/generate client; call from a background dispatcher. */
object OllamaClient {
    private const val CONNECT_TIMEOUT_MS = 15_000
    private const val READ_TIMEOUT_MS = 120_000

    fun correct(credentials: OllamaCredentialsStore.Credentials, chunk: String): String =
        generate(credentials, CorrectionPrompt.build(chunk))

    fun generate(credentials: OllamaCredentialsStore.Credentials, prompt: String): String {
        val connection = (URL("${credentials.baseUrl}/api/generate").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            val auth = "${credentials.username}:${credentials.password}".toByteArray(Charsets.UTF_8)
            setRequestProperty("Authorization", "Basic ${Base64.encodeToString(auth, Base64.NO_WRAP)}")
        }
        return try {
            val body = JSONObject()
                .put("model", credentials.model)
                .put("prompt", prompt)
                .put("stream", false)
                .put("options", JSONObject().put("temperature", 0.1))
                .toString()
            connection.outputStream.bufferedWriter(Charsets.UTF_8).use { it.write(body) }
            val responseBody = if (connection.responseCode in 200..299) {
                connection.inputStream.bufferedReader().use { it.readText() }
            } else {
                connection.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
            }
            if (connection.responseCode !in 200..299) error("Ollama HTTP ${connection.responseCode}: ${responseBody.take(300)}")
            JSONObject(responseBody).optString("response").trim()
        } finally {
            connection.disconnect()
        }
    }
}
