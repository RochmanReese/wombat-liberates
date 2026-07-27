package com.techwombat.liberates

import android.content.Context
import java.io.File
import java.nio.charset.StandardCharsets.UTF_8

/** Stores editable OCR rules separately from captured and cleaned OCR text. */
object OcrRulesStore {
    private const val RULES_FILE_NAME = "ocr-rules.txt"
    private const val DEFAULT_RULES_ASSET_NAME = "ocr-rules.txt"

    @Synchronized
    fun initialize(context: Context) {
        seedDefaultsIfMissing(rulesFile(context), defaultRules(context))
    }

    @Synchronized
    fun readRules(context: Context): String {
        initialize(context)
        return rulesFile(context).readText(UTF_8)
    }

    @Synchronized
    fun customRules(context: Context): List<OcrLiteralRule> = customRules(
        rulesFile(context),
        defaultRules(context),
    )

    @Synchronized
    fun addCustomRule(context: Context, rule: OcrLiteralRule) {
        val defaults = defaultRules(context)
        val rulesFile = rulesFile(context)
        val updated = customRules(rulesFile, defaults) + rule
        writeCustomRules(rulesFile, defaults, updated)
    }

    @Synchronized
    fun removeCustomRule(context: Context, index: Int) {
        val defaults = defaultRules(context)
        val rulesFile = rulesFile(context)
        val rules = customRules(rulesFile, defaults).toMutableList()
        if (index !in rules.indices) return
        rules.removeAt(index)
        writeCustomRules(rulesFile, defaults, rules)
    }

    @Synchronized
    fun resetToDefaults(context: Context) {
        resetToDefaults(rulesFile(context), defaultRules(context))
    }

    internal fun seedDefaultsIfMissing(rulesFile: File, defaults: String) {
        if (!rulesFile.exists()) rulesFile.writeText(defaults, UTF_8)
    }

    internal fun resetToDefaults(rulesFile: File, defaults: String) {
        rulesFile.writeText(defaults, UTF_8)
    }

    internal fun customRules(rulesFile: File, defaults: String): List<OcrLiteralRule> {
        seedDefaultsIfMissing(rulesFile, defaults)
        val customText = rulesFile.readText(UTF_8).removePrefix(defaults).trim()
        return OcrRulesParser.parse(customText).rules
    }

    internal fun writeCustomRules(rulesFile: File, defaults: String, customRules: List<OcrLiteralRule>) {
        val customText = customRules.joinToString("\n") { "${it.find} => ${it.replace}" }
        val document = buildString {
            append(defaults.trimEnd())
            if (customText.isNotBlank()) append("\n\n").append(customText)
            append('\n')
        }
        rulesFile.writeText(document, UTF_8)
    }

    fun rulesFile(context: Context): File = File(context.filesDir, RULES_FILE_NAME)

    private fun defaultRules(context: Context): String = context.assets.open(DEFAULT_RULES_ASSET_NAME)
        .bufferedReader(UTF_8)
        .use { it.readText() }
}
