package org.lumina.reader.core.crop

import org.junit.Assert.*
import org.junit.Test

class PageCropper2Test {

    private val white = 0xFFFFFFFF.toInt()
    private val black = 0xFF000000.toInt()

    @Test
    fun testEmptyWhitePage() {
        val width = 400
        val height = 600
        val pixels = IntArray(width * height) { white }

        val bounds = PageCropper2.getCropBoundsFromPixels(pixels, width, height)
        // 全白页面应返回全图或者安全边界
        assertEquals(0f, bounds.left, 0.05f)
        assertEquals(0f, bounds.top, 0.05f)
        assertEquals(1f, bounds.right, 0.05f)
        assertEquals(1f, bounds.bottom, 0.05f)
    }

    @Test
    fun testCenteredContentCropping() {
        val width = 400
        val height = 600
        val pixels = IntArray(width * height) { white }

        // 在页面中间 (left=100..300, top=150..450) 绘制模拟正文黑色色块
        for (y in 150 until 450) {
            for (x in 100 until 300) {
                pixels[y * width + x] = black
            }
        }

        val startTime = System.nanoTime()
        val bounds = PageCropper2.getCropBoundsFromPixels(pixels, width, height, extraPaddingRatio = 0f)
        val elapsedMs = (System.nanoTime() - startTime) / 1_000_000.0

        println("Crop computation elapsed: ${elapsedMs}ms, bounds: $bounds")

        // 验证计算耗时远低于 5ms (通常在 0.5ms~1.5ms)
        assertTrue("Crop calculation should be faster than 10ms", elapsedMs < 10.0)

        // 验证裁切边界是否精确锁定了内容区域 (100/400 = 0.25, 150/600 = 0.25, 300/400 = 0.75, 450/600 = 0.75)
        assertTrue("Left bound should be close to 0.25", bounds.left in 0.20f..0.26f)
        assertTrue("Top bound should be close to 0.25", bounds.top in 0.20f..0.26f)
        assertTrue("Right bound should be close to 0.75", bounds.right in 0.74f..0.80f)
        assertTrue("Bottom bound should be close to 0.75", bounds.bottom in 0.74f..0.80f)
    }

    @Test
    fun testDoubleColumnDetection() {
        val width = 400
        val height = 600
        val pixels = IntArray(width * height) { white }

        // 绘制双栏论文：
        // 左栏：x = 50..180
        // 栏间空白：x = 181..219
        // 右栏：x = 220..350
        for (y in 100 until 500) {
            for (x in 50..180) {
                pixels[y * width + x] = black
            }
            for (x in 220..350) {
                pixels[y * width + x] = black
            }
        }

        // 点击左栏 (x = 100/400 = 0.25, y = 300/600 = 0.5)
        val leftCol = PageCropper2.getColumn(pixels, width, height, 0.25f, 0.5f)
        assertTrue("Left column right bound should be near gap (180/400=0.45)", leftCol.right in 0.44f..0.52f)

        // 点击右栏 (x = 280/400 = 0.7, y = 300/600 = 0.5)
        val rightCol = PageCropper2.getColumn(pixels, width, height, 0.7f, 0.5f)
        assertTrue("Right column left bound should be near gap (220/400=0.55)", rightCol.left in 0.50f..0.58f)
    }
}
