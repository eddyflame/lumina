package org.lumina.reader.core.annotation

import org.junit.Assert.*
import org.junit.Test

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
}
