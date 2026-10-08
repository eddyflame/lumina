package org.lumina.reader.core.annotation

import androidx.compose.ui.graphics.BlendMode
import org.junit.Assert.*
import org.junit.Test
import org.lumina.reader.core.crop.PageCropper2
import org.lumina.reader.core.model.ReadingColorMode

class AnnotationTest {

    @Test
    fun testCoordinateTransformerRoundTrip() {
        val viewW = 1080f
        val viewH = 1920f
        val canvasX = 540f
        val canvasY = 960f

        val norm = PageCoordinateTransformer.canvasToNormalized(canvasX, canvasY, viewW, viewH)
        assertEquals(0.5f, norm.x, 0.001f)
        assertEquals(0.5f, norm.y, 0.001f)

        val (backX, backY) = PageCoordinateTransformer.normalizedToCanvas(norm, viewW, viewH)
        assertEquals(canvasX, backX, 0.001f)
        assertEquals(canvasY, backY, 0.001f)
    }

    @Test
    fun testPdfPointInversion() {
        val pageW = 595f // A4 width pt
        val pageH = 842f // A4 height pt

        // 归一化顶部左侧 (0, 0)
        val normTopLeft = NormalizedPoint(0f, 0f)
        val (pdfX1, pdfY1) = PageCoordinateTransformer.normalizedToPdfPoint(normTopLeft, pageW, pageH)
        // PDF 坐标系原点在左下角，所以顶部对应 Y = pageH
        assertEquals(0f, pdfX1, 0.001f)
        assertEquals(pageH, pdfY1, 0.001f)

        // 逆向变换应准确还原
        val recovered = PageCoordinateTransformer.pdfPointToNormalized(pdfX1, pdfY1, pageW, pageH)
        assertEquals(0f, recovered.x, 0.001f)
        assertEquals(0f, recovered.y, 0.001f)

        // 归一化底部右侧 (1, 1) -> PDF (pageW, 0)
        val normBottomRight = NormalizedPoint(1f, 1f)
        val (pdfX2, pdfY2) = PageCoordinateTransformer.normalizedToPdfPoint(normBottomRight, pageW, pageH)
        assertEquals(pageW, pdfX2, 0.001f)
        assertEquals(0f, pdfY2, 0.001f)
    }

    @Test
    fun testPdfPointInversionWithRotations() {
        val pageW = 595f
        val pageH = 842f
        val testPoints = listOf(
            NormalizedPoint(0f, 0f),
            NormalizedPoint(1f, 0f),
            NormalizedPoint(0f, 1f),
            NormalizedPoint(1f, 1f),
            NormalizedPoint(0.35f, 0.65f)
        )
        val rotations = listOf(0, 90, 180, 270, 360, 450, -90)

        for (rot in rotations) {
            for (pt in testPoints) {
                val (pdfX, pdfY) = PageCoordinateTransformer.normalizedToPdfPoint(pt, pageW, pageH, rot)
                val recovered = PageCoordinateTransformer.pdfPointToNormalized(pdfX, pdfY, pageW, pageH, rot)
                assertEquals("Round trip failed for x at rotation $rot", pt.x, recovered.x, 0.001f)
                assertEquals("Round trip failed for y at rotation $rot", pt.y, recovered.y, 0.001f)
            }
        }

        // 针对 90° 的物理点特性校验：屏幕左上角 (0, 0) 映射到未旋转用户空间的 (0, 0)
        val (rot90X, rot90Y) = PageCoordinateTransformer.normalizedToPdfPoint(NormalizedPoint(0f, 0f), pageW, pageH, 90)
        assertEquals(0f, rot90X, 0.001f)
        assertEquals(0f, rot90Y, 0.001f)

        // 针对 90° 的物理点特性校验：屏幕右上角 (1, 0) 映射到用户空间 (0, pageH)
        val (rot90TopRightX, rot90TopRightY) = PageCoordinateTransformer.normalizedToPdfPoint(NormalizedPoint(1f, 0f), pageW, pageH, 90)
        assertEquals(0f, rot90TopRightX, 0.001f)
        assertEquals(pageH, rot90TopRightY, 0.001f)
    }

    @Test
    fun testInkIntersectionForEraser() {
        // 创建一条从 (0.2, 0.2) 到 (0.8, 0.8) 的对角线笔迹
        val stroke = listOf(
            NormalizedPoint(0.2f, 0.2f),
            NormalizedPoint(0.5f, 0.5f),
            NormalizedPoint(0.8f, 0.8f)
        )
        val ink = PdfAnnotation.Ink(
            pageIndex = 0,
            strokes = listOf(stroke)
        )

        // 点击正中心 (0.5, 0.5) 应该命中
        assertTrue(ink.intersects(NormalizedPoint(0.5f, 0.5f)))

        // 点击靠近线段点 (0.51, 0.50) 应该命中
        assertTrue(ink.intersects(NormalizedPoint(0.51f, 0.50f), threshold = 0.02f))

        // 点击远离线段点 (0.1, 0.9) 不应命中
        assertFalse(ink.intersects(NormalizedPoint(0.1f, 0.9f)))
    }

    @Test
    fun testSinglePointDotEraserIntersection() {
        val singlePointInk = PdfAnnotation.Ink(
            pageIndex = 0,
            strokes = listOf(listOf(NormalizedPoint(0.3f, 0.4f)))
        )
        // 靠近单点应该命中
        assertTrue(singlePointInk.intersects(NormalizedPoint(0.31f, 0.41f), threshold = 0.05f))
        // 远离单点不应命中
        assertFalse(singlePointInk.intersects(NormalizedPoint(0.8f, 0.8f), threshold = 0.05f))
    }

    @Test
    fun testUndoRedoStackFlow() {
        val undoManager = UndoRedoManager()

        // 模拟内存暂存仓库
        val storedList = mutableListOf<PdfAnnotation>()
        val mockStore = object : AnnotationStore {
            override fun addAnnotation(annotation: PdfAnnotation) {
                storedList.add(annotation)
            }
            override fun removeAnnotation(annotationId: String) {
                storedList.removeAll { it.id == annotationId }
            }
            override fun getAnnotationsForPage(pageIndex: Int): List<PdfAnnotation> {
                return storedList.filter { it.pageIndex == pageIndex }
            }
        }

        assertFalse(undoManager.canUndoFlow.value)
        assertFalse(undoManager.canRedoFlow.value)

        val ink1 = PdfAnnotation.Ink(pageIndex = 0, strokes = emptyList())
        val cmd1 = AddAnnotationCommand(mockStore, ink1)

        undoManager.execute(cmd1)
        assertEquals(1, storedList.size)
        assertTrue(undoManager.canUndoFlow.value)
        assertFalse(undoManager.canRedoFlow.value)

        // 撤销
        val undone = undoManager.undo()
        assertTrue(undone)
        assertEquals(0, storedList.size)
        assertFalse(undoManager.canUndoFlow.value)
        assertTrue(undoManager.canRedoFlow.value)

        // 重做
        val redone = undoManager.redo()
        assertTrue(redone)
        assertEquals(1, storedList.size)
        assertTrue(undoManager.canUndoFlow.value)
        assertFalse(undoManager.canRedoFlow.value)
    }

    @Test
    fun testCompoundAnnotationCommandAtomicUndoRedo() {
        val undoManager = UndoRedoManager()
        val storedList = mutableListOf<PdfAnnotation>()
        val mockStore = object : AnnotationStore {
            override fun addAnnotation(annotation: PdfAnnotation) {
                storedList.add(annotation)
            }
            override fun removeAnnotation(annotationId: String) {
                storedList.removeAll { it.id == annotationId }
            }
            override fun getAnnotationsForPage(pageIndex: Int): List<PdfAnnotation> {
                return storedList.filter { it.pageIndex == pageIndex }
            }
        }

        val inkPage0 = PdfAnnotation.Ink(pageIndex = 0, strokes = listOf(listOf(NormalizedPoint(0.5f, 0.9f))))
        val inkPage1 = PdfAnnotation.Ink(pageIndex = 1, strokes = listOf(listOf(NormalizedPoint(0.5f, 0.1f))))
        val compoundCommand = CompoundAnnotationCommand(
            listOf(
                AddAnnotationCommand(mockStore, inkPage0),
                AddAnnotationCommand(mockStore, inkPage1)
            )
        )

        undoManager.execute(compoundCommand)
        assertEquals(2, storedList.size)
        assertEquals(1, mockStore.getAnnotationsForPage(0).size)
        assertEquals(1, mockStore.getAnnotationsForPage(1).size)

        // 原子撤销：跨页笔迹两页均被一次性撤销
        val undone = undoManager.undo()
        assertTrue(undone)
        assertEquals(0, storedList.size)

        // 原子重做：两页笔画同时恢复
        val redone = undoManager.redo()
        assertTrue(redone)
        assertEquals(2, storedList.size)
        assertEquals(1, mockStore.getAnnotationsForPage(0).size)
        assertEquals(1, mockStore.getAnnotationsForPage(1).size)
    }

    @Test
    fun testPointRotationInverses() {
        val width = 1080f
        val height = 1920f
        val testPoints = listOf(
            Pair(100f, 200f),
            Pair(540f, 960f),
            Pair(1000f, 1800f),
            Pair(0f, 0f),
            Pair(width, height)
        )
        val rotations = listOf(0, 90, 180, 270, 360, -90)

        for (deg in rotations) {
            for ((x, y) in testPoints) {
                val (unrotX, unrotY) = PageCoordinateTransformer.unrotatePoint(x, y, width, height, deg)
                val (rotX, rotY) = PageCoordinateTransformer.rotatePoint(unrotX, unrotY, width, height, deg)
                assertEquals("Rotation $deg at ($x, $y) X", x, rotX, 0.01f)
                assertEquals("Rotation $deg at ($x, $y) Y", y, rotY, 0.01f)
            }
        }
    }

    @Test
    fun testContinuousStrokeCoordinateZeroDrift() {
        val viewportWidth = 1280f
        val contentHeightPx = 1750f

        // 模拟非对称裁切边界 (如论文排版奇偶页装订边)
        val crop = PageCropper2.CropBounds(left = 0.06f, top = 0.04f, right = 0.94f, bottom = 0.92f)

        // 模拟手绘竖线、圆点与圆形笔画点序列
        val touchPoints = listOf(
            Pair(300f, 150f),
            Pair(300f, 500f),
            Pair(300f, 900f),
            Pair(640f, 875f),
            Pair(1000f, 200f)
        )

        for ((touchX, touchY) in touchPoints) {
            // 1. Overlay 触控转换为归一化坐标 (commitContinuousStroke 逻辑)
            val (unrotX, unrotY) = PageCoordinateTransformer.unrotatePoint(touchX, touchY, viewportWidth, contentHeightPx, 0)
            val normX = (crop.left + (unrotX / viewportWidth) * crop.width).coerceIn(0f, 1f)
            val normY = (crop.top + (unrotY / contentHeightPx) * crop.height).coerceIn(0f, 1f)

            // 2. 页面视图 Canvas 映射回屏幕物理坐标 (buildContinuousPagePath 逻辑)
            val u = (normX - crop.left) / crop.width
            val v = (normY - crop.top) / crop.height
            val mappedX = u * viewportWidth
            val mappedY = v * contentHeightPx
            val (renderX, renderY) = PageCoordinateTransformer.rotatePoint(mappedX, mappedY, viewportWidth, contentHeightPx, 0)

        }
    }

    @Test
    fun testAdjacentPagesContinuousStrokeZeroDrift() {
        val viewportWidth = 1080f
        val page0Height = 1600f
        val page1Height = 1650f
        val dividerHeight = 45f

        // 上页与下页具有各自独立的裁切参数
        val crop0 = PageCropper2.CropBounds(left = 0.05f, top = 0.03f, right = 0.95f, bottom = 0.97f)
        val crop1 = PageCropper2.CropBounds(left = 0.08f, top = 0.06f, right = 0.92f, bottom = 0.94f)

        // 上页可见区域：offset = -400 (部分滚出屏幕顶部)
        val item0Offset = -400f
        val item0ContentTop = item0Offset
        val item0ContentBottom = item0Offset + page0Height

        // 下页可见区域：紧随上页及分割条下方
        val item1Offset = item0ContentBottom + dividerHeight
        val item1ContentTop = item1Offset
        val item1ContentBottom = item1Offset + page1Height

        // 测试在下页上绘制笔画 (绝对视口 Y 坐标在下页范围内)
        val bottomPageTouches = listOf(
            Pair(200f, item1ContentTop + 100f),
            Pair(540f, item1ContentTop + 500f),
            Pair(800f, item1ContentTop + 1200f)
        )

        for ((touchX, touchY) in bottomPageTouches) {
            // 1. Overlay 下页坐标归一化
            val rawLocalX = touchX.coerceIn(0f, viewportWidth)
            val rawLocalY = (touchY - item1ContentTop).coerceIn(0f, page1Height)
            val normX = (crop1.left + (rawLocalX / viewportWidth) * crop1.width).coerceIn(0f, 1f)
            val normY = (crop1.top + (rawLocalY / page1Height) * crop1.height).coerceIn(0f, 1f)

            // 2. PdfPageView 下页 Canvas 本地坐标反解映射
            val u = (normX - crop1.left) / crop1.width
            val v = (normY - crop1.top) / crop1.height
            val localX = u * viewportWidth
            val localY = v * page1Height

            // 3. 计算在当前屏幕视口内的实际渲染绝对位置 (item1Offset + localY)
            val renderedScreenX = localX
            val renderedScreenY = item1ContentTop + localY

            assertEquals("下页 X 坐标完全对齐零漂移", touchX, renderedScreenX, 0.001f)
            assertEquals("下页 Y 坐标完全对齐零漂移", touchY, renderedScreenY, 0.001f)
        }
    }

    @Test
    fun testAnnotationColorThemeAdapterNormalMode() {
        val originalColor = 0xFF212121L
        val resolved = AnnotationColorThemeAdapter.resolveDisplayColorLong(originalColor, ReadingColorMode.NORMAL)
        assertEquals("日间模式下色值保持完全一致", originalColor, resolved)
        assertEquals("日间模式下荧光笔采用 Multiply", BlendMode.Multiply, AnnotationColorThemeAdapter.resolveHighlighterBlendMode(ReadingColorMode.NORMAL))
    }

    @Test
    fun testAnnotationColorThemeAdapterNightModeInversion() {
        // 1. 碳素黑 (33, 33, 33) 在 AMOLED_DARK 下反转为明亮银白 (178, 178, 178)
        val blackColor = 0xFF212121L
        val amoledInverted = AnnotationColorThemeAdapter.resolveDisplayColorLong(blackColor, ReadingColorMode.AMOLED_DARK)
        val rAmoled = ((amoledInverted shr 16) and 0xFF).toInt()
        val gAmoled = ((amoledInverted shr 8) and 0xFF).toInt()
        val bAmoled = (amoledInverted and 0xFF).toInt()
        assertEquals(178, rAmoled)
        assertEquals(178, gAmoled)
        assertEquals(178, bAmoled)

        // 2. 碳素黑 (33, 33, 33) 在 SOFT_DARK 下反转为温润白 (190, 196, 205)
        val softInverted = AnnotationColorThemeAdapter.resolveDisplayColorLong(blackColor, ReadingColorMode.SOFT_DARK)
        val rSoft = ((softInverted shr 16) and 0xFF).toInt()
        val gSoft = ((softInverted shr 8) and 0xFF).toInt()
        val bSoft = (softInverted and 0xFF).toInt()
        assertEquals(190, rSoft)
        assertEquals(196, gSoft)
        assertEquals(205, bSoft)

        // 3. 纯黑 (0, 0, 0) 在 AMOLED_DARK 下反转为 (204, 204, 204)
        val pureBlack = 0xFF000000L
        val pureBlackInverted = AnnotationColorThemeAdapter.resolveDisplayColorLong(pureBlack, ReadingColorMode.AMOLED_DARK)
        assertEquals(204, ((pureBlackInverted shr 16) and 0xFF).toInt())
        assertEquals(204, ((pureBlackInverted shr 8) and 0xFF).toInt())
        assertEquals(204, (pureBlackInverted and 0xFF).toInt())

        // 4. 夜间模式高亮笔混合模式应为 Screen
        assertEquals(BlendMode.Screen, AnnotationColorThemeAdapter.resolveHighlighterBlendMode(ReadingColorMode.SOFT_DARK))
        assertEquals(BlendMode.Screen, AnnotationColorThemeAdapter.resolveHighlighterBlendMode(ReadingColorMode.AMOLED_DARK))
    }

    @Test
    fun testAnnotationPaletteContrastInNightMode() {
        val palette = listOf(
            0xFF0066FFL, // 品牌天蓝
            0xFFE53935L, // 醒目烈红
            0xFFFFB300L, // 荧光琥珀
            0xFF43A047L, // 护眼清绿
            0xFF8E24AAL, // 典雅紫罗兰
            0xFF212121L  // 纯黑碳素
        )

        for (color in palette) {
            val colorSoft = AnnotationColorThemeAdapter.resolveDisplayColor(color, ReadingColorMode.SOFT_DARK)
            val maxSoft = maxOf(colorSoft.red, colorSoft.green, colorSoft.blue)
            assertTrue("柔和夜间模式下各色块均具备明亮视觉通道 (>=0.55): $color", maxSoft >= 0.55f)

            val colorAmoled = AnnotationColorThemeAdapter.resolveDisplayColor(color, ReadingColorMode.AMOLED_DARK)
            val maxAmoled = maxOf(colorAmoled.red, colorAmoled.green, colorAmoled.blue)
            assertTrue("AMOLED夜间模式下各色块均具备明亮视觉通道 (>=0.55): $color", maxAmoled >= 0.55f)
        }
    }
}

