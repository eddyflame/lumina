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
        repeat(20) {
            PageCropper2.getCropBoundsFromPixels(pixels, width, height, extraPaddingRatio = 0f)
        }

        val startTime = System.nanoTime()
        val bounds = PageCropper2.getCropBoundsFromPixels(pixels, width, height, extraPaddingRatio = 0f)
        val elapsedMs = (System.nanoTime() - startTime) / 1_000_000.0

        println("Crop computation elapsed: ${elapsedMs}ms, bounds: $bounds")

        // 验证计算耗时远低于 50ms (通常在 0.5ms~2ms)
        assertTrue("Crop calculation should be faster than 50ms", elapsedMs < 50.0)

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

    /**
     * 测试白底红字 (标题/印章/批注) 能够被正确识别为有效内容，不会被当做白边误裁
     */
    @Test
    fun testWhiteBackgroundWithRedTextNotCropped() {
        val width = 400
        val height = 600
        val pixels = IntArray(width * height) { white }

        val red = 0xFFDC2626.toInt() // 典型印刷红 (220, 38, 38)
        // 顶部绘制白底红字标题 (x=100..300, y=50..90)
        for (y in 50 until 90) {
            for (x in 100 until 300) {
                pixels[y * width + x] = red
            }
        }
        // 下方绘制常规黑色正文 (x=80..320, y=200..480)
        for (y in 200 until 480) {
            for (x in 80 until 320) {
                pixels[y * width + x] = black
            }
        }

        val bounds = PageCropper2.getCropBoundsFromPixels(pixels, width, height, extraPaddingRatio = 0f)
        println("White background red text bounds: $bounds")

        // 顶部的红字绝不能被裁掉！top 必须在红字之前 (<= 50/600 = 0.083f)
        assertTrue("Top bound must preserve red text at y=50 (was ${bounds.top})", bounds.top <= 0.09f)
        // 底部正文不能被裁掉
        assertTrue("Bottom bound must preserve content at y=480 (was ${bounds.bottom})", bounds.bottom >= 0.79f)
    }

    /**
     * 测试书籍封面典型版式：
     * 1. 顶部白底红字 (丛书名/版头)
     * 2. 中间红底白字条幅 (主书名色块)
     * 3. 底部黑色出版信息
     * 验证整页白边裁切保留全页结构，白底红字不被裁掉，红底白字不被截断压缩。
     */
    @Test
    fun testRedBannerWithWhiteTextAndRedHeader() {
        val width = 400
        val height = 600
        val pixels = IntArray(width * height) { white }

        val red = 0xFFCC2222.toInt()
        // 1. 顶部白底红字 (x=100..300, y=40..70)
        for (y in 40 until 70) {
            for (x in 100 until 300) {
                pixels[y * width + x] = red
            }
        }
        // 2. 中间红底白字大标题条幅 (x=50..350, y=140..220)
        for (y in 140 until 220) {
            for (x in 50 until 350) {
                pixels[y * width + x] = red
            }
        }
        // 红底内的反白文字 (x=100..300, y=160..200)
        for (y in 160 until 200) {
            for (x in 100 until 300) {
                pixels[y * width + x] = white
            }
        }
        // 3. 底部黑色正文/出版社信息 (x=80..320, y=350..520)
        for (y in 350 until 520) {
            for (x in 80 until 320) {
                pixels[y * width + x] = black
            }
        }

        val bounds = PageCropper2.getCropBoundsFromPixels(pixels, width, height, extraPaddingRatio = 0f)
        println("Red banner + red header bounds: $bounds")

        // 验证整体裁边：顶部白底红字不能被裁掉，顶部边界在红字之上 (<= 40/600 = 0.067f)
        assertTrue("Top bound must preserve top red text (was ${bounds.top})", bounds.top <= 0.08f)
        // 底部边界必须覆盖到底部内容 (>= 520/600 = 0.867f)
        assertTrue("Bottom bound must cover bottom content (was ${bounds.bottom})", bounds.bottom >= 0.85f)
        // 左右边界必须覆盖中间红底条幅 (left <= 50/400 = 0.125f, right >= 350/400 = 0.875f)
        assertTrue("Left bound must cover red banner left (was ${bounds.left})", bounds.left <= 0.14f)
        assertTrue("Right bound must cover red banner right (was ${bounds.right})", bounds.right >= 0.86f)
    }

    /**
     * 测试整页纯红底色封面：
     * 全幅红色封面不应被当成白边裁切或切成碎片，应完整保留全图。
     */
    @Test
    fun testFullBleedRedCoverNotOverCropped() {
        val width = 400
        val height = 600
        val red = 0xFFCC2222.toInt()
        val pixels = IntArray(width * height) { red }

        // 中间有反白文字
        for (y in 200 until 280) {
            for (x in 100 until 300) {
                pixels[y * width + x] = white
            }
        }

        val bounds = PageCropper2.getCropBoundsFromPixels(pixels, width, height, extraPaddingRatio = 0f)
        println("Full red cover bounds: $bounds")

        assertEquals("Red cover left should remain near 0", 0f, bounds.left, 0.05f)
        assertEquals("Red cover top should remain near 0", 0f, bounds.top, 0.05f)
        assertEquals("Red cover right should remain near 1", 1f, bounds.right, 0.05f)
        assertEquals("Red cover bottom should remain near 1", 1f, bounds.bottom, 0.05f)
    }

    /**
     * 测试国标红 (237, 43, 36) 印章/印记的探测
     */
    @Test
    fun testRedSealStampDetection() {
        val width = 400
        val height = 600
        val pixels = IntArray(width * height) { white }

        val sealRed = 0xFFED2B24.toInt()
        for (y in 50 until 90) {
            for (x in 180 until 220) {
                pixels[y * width + x] = sealRed
            }
        }
        for (y in 150 until 450) {
            for (x in 100 until 300) {
                pixels[y * width + x] = black
            }
        }

        val bounds = PageCropper2.getCropBoundsFromPixels(pixels, width, height, extraPaddingRatio = 0f)
        assertTrue("Top bound must include the red seal at y=50 (was ${bounds.top})", bounds.top <= 0.09f)
    }

    /**
     * 测试贴顶页眉/红条 (y=0..20)，与正文之间有大片留白 (y=21..80)：
     * 绝不能跳过贴顶页眉并内缩到正文！
     */
    @Test
    fun testTopHeaderTouchingEdgeNotSkipped() {
        val width = 400
        val height = 600
        val pixels = IntArray(width * height) { white }

        val red = 0xFFCC2222.toInt()
        // 贴顶红色页眉 (y = 0..20, x = 50..350)
        for (y in 0 until 20) {
            for (x in 50 until 350) {
                pixels[y * width + x] = red
            }
        }
        // 正文在 y = 80..450
        for (y in 80 until 450) {
            for (x in 80 until 320) {
                pixels[y * width + x] = black
            }
        }

        val bounds = PageCropper2.getCropBoundsFromPixels(pixels, width, height, extraPaddingRatio = 0f)
        println("Top header touching edge bounds: $bounds")
        assertTrue("Top bound must be 0f or near 0f, not skipping edge header (was ${bounds.top})", bounds.top <= 0.02f)
    }

    /**
     * 测试贴底出版信息/条形码 (y=580..600)，与正文之间有留白 (y=450..579)：
     * 绝不能跳过贴底信息内缩到正文！
     */
    @Test
    fun testBottomPublisherTouchingEdgeNotSkipped() {
        val width = 400
        val height = 600
        val pixels = IntArray(width * height) { white }

        // 正文在 y = 100..450
        for (y in 100 until 450) {
            for (x in 80 until 320) {
                pixels[y * width + x] = black
            }
        }
        // 贴底出版信息 (y = 580..600, x = 60..340)
        for (y in 580 until 600) {
            for (x in 60 until 340) {
                pixels[y * width + x] = black
            }
        }

        val bounds = PageCropper2.getCropBoundsFromPixels(pixels, width, height, extraPaddingRatio = 0f)
        println("Bottom publisher touching edge bounds: $bounds")
        assertTrue("Bottom bound must be 1f or near 1f, not skipping edge publisher text (was ${bounds.bottom})", bounds.bottom >= 0.98f)
    }

    /**
     * 测试贴左边缘书脊装饰/通栏，与正文之间有留白：
     * 绝不能跳过边缘内容内缩！
     */
    @Test
    fun testLeftBorderTouchingEdgeNotSkipped() {
        val width = 400
        val height = 600
        val pixels = IntArray(width * height) { white }

        // 贴左边缘装饰条 (x = 0..10, y = 100..500)
        for (y in 100 until 500) {
            for (x in 0 until 10) {
                pixels[y * width + x] = black
            }
        }
        // 正文在 x = 80..320
        for (y in 150 until 450) {
            for (x in 80 until 320) {
                pixels[y * width + x] = black
            }
        }

        val bounds = PageCropper2.getCropBoundsFromPixels(pixels, width, height, extraPaddingRatio = 0f)
        println("Left border touching edge bounds: $bounds")
        assertTrue("Left bound must be 0f or near 0f, not skipping edge content (was ${bounds.left})", bounds.left <= 0.02f)
    }
}
