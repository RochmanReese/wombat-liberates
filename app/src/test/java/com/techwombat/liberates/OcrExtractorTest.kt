package com.techwombat.liberates

import org.junit.Assert.assertEquals
import org.junit.Test

class OcrExtractorTest {

    @Test
    fun testCropPercentageCalculations() {
        val totalHeight = 1000
        val topPercentage = 0.08f
        val bottomPercentage = 0.08f

        val expectedCropTop = (totalHeight * topPercentage).toInt() // 80
        val expectedCropHeight = (totalHeight * (1.0f - topPercentage - bottomPercentage)).toInt() // 840

        assertEquals(80, expectedCropTop)
        assertEquals(840, expectedCropHeight)
    }
}
