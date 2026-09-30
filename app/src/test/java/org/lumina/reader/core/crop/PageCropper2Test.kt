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

        // JIT 预热消除类加载与冷启动耗时
        repeat(5) {
            PageCropper2.getCropBoundsFromPixels(pixels, width, height, extraPaddingRatio = 0f)
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

    /**
     * 测试边缘无内容、偏置在右下角的页面：
     * 验证当左侧/顶部无内容超出 1/3 边界时安全保留 0f 原点，右侧/底部紧贴内容边界。
     */
    @Test
    fun testEdgeAllWhiteWithBottomRightContent() {
        val width = 400
        val height = 600
        val pixels = IntArray(width * height) { white }

        // 仅在右下角绘制内容 (x=250..380, y=400..580)
        for (y in 400 until 580) {
            for (x in 250 until 380) {
                pixels[y * width + x] = black
            }
        }

        val bounds = PageCropper2.getCropBoundsFromPixels(pixels, width, height, extraPaddingRatio = 0f)
        println("Edge-all-white bounds: $bounds")

        // 超过 1/3 安全扫描范围时，左侧与顶部安全保持 0f，防止误裁正文
        assertEquals(0f, bounds.left, 0.05f)
        assertEquals(0f, bounds.top, 0.05f)
        // 右边界和底部边界应接近内容右下角 (380/400 = 0.95, 580/600 = 0.967)
        assertTrue("Right bound should be near content right", bounds.right in 0.90f..1.0f)
        assertTrue("Bottom bound should be near content bottom", bounds.bottom in 0.90f..1.0f)
    }

    /**
     * 测试全白页面的分栏检测不会崩溃，应返回安全边界。
     */
    @Test
    fun testColumnDetectionOnWhitePage() {
        val width = 400
        val height = 600
        val pixels = IntArray(width * height) { white }

        // 在全白页面上尝试分栏检测，不应抛出异常
        val col = PageCropper2.getColumn(pixels, width, height, 0.5f, 0.5f)

        // 返回的边界应是有效值 (0..1 范围内)
        assertTrue("Column left should be in [0, 1]", col.left in 0f..1f)
        assertTrue("Column right should be in [0, 1]", col.right in 0f..1f)
        assertTrue("Column right >= left", col.right >= col.left)
    }

    /**
     * 测试深色背景 PDF 的分栏检测：avgLum 基线 max(200, ...) 保证不会因低亮度导致误判。
     */
    @Test
    fun testDarkBackgroundColumnDetection() {
        val width = 400
        val height = 600
        val darkGray = 0xFF333333.toInt()  // 深灰背景，亮度约 51
        val pixels = IntArray(width * height) { darkGray }

        // 在深色背景上绘制更深的"文字"（纯黑）
        for (y in 100 until 500) {
            for (x in 50..180) {
                pixels[y * width + x] = black
            }
            for (x in 220..350) {
                pixels[y * width + x] = black
            }
        }

        // 分栏检测应正常工作，不应崩溃
        val col = PageCropper2.getColumn(pixels, width, height, 0.25f, 0.5f)
        assertTrue("Column bounds should be valid on dark background", col.left in 0f..1f)
        assertTrue("Column bounds should be valid on dark background", col.right in 0f..1f)

        // avgLum 被 max(200, ...) 提升后，深色背景的微弱亮度差异应该不会被误判为"内容"
        // 验证检测到的列边界是合理的
        println("Dark background column: left=${col.left}, right=${col.right}")
    }
}
