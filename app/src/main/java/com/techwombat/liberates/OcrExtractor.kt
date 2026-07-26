package com.techwombat.liberates

import android.graphics.Bitmap
import android.util.Log
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions

object OcrExtractor {

    private const val TAG = "WOMBAT_OCR"
    private val recognizer by lazy {
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    }

    fun cropBitmapHeaderFooter(bitmap: Bitmap, topPercentage: Float = 0.08f, bottomPercentage: Float = 0.08f): Bitmap {
        val width = bitmap.width
        val height = bitmap.height

        val cropTop = (height * topPercentage).toInt().coerceIn(0, height - 1)
        val cropHeight = (height * (1.0f - topPercentage - bottomPercentage)).toInt().coerceIn(1, height - cropTop)

        return Bitmap.createBitmap(bitmap, 0, cropTop, width, cropHeight)
    }

    fun extractTextFromBitmap(bitmap: Bitmap, callback: (List<String>) -> Unit) {
        try {
            val cropped = cropBitmapHeaderFooter(bitmap)
            val image = InputImage.fromBitmap(cropped, 0)

            recognizer.process(image)
                .addOnSuccessListener { visionText ->
                    val lines = mutableListOf<String>()
                    for (block in visionText.textBlocks) {
                        for (line in block.lines) {
                            val text = line.text.trim()
                            if (text.isNotEmpty()) {
                                lines.add(text)
                            }
                        }
                    }
                    val cleanLines = TextCleaner.cleanPageLines(lines)
                    callback(cleanLines)
                }
                .addOnFailureListener { e ->
                    Log.e(TAG, "ML Kit OCR Processing Error", e)
                    callback(emptyList())
                }
        } catch (e: Exception) {
            Log.e(TAG, "OcrExtractor exception", e)
            callback(emptyList())
        }
    }
}
