package org.lumina.reader.core.export

import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationMarkup
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.lumina.reader.core.annotation.NormalizedPoint
import org.lumina.reader.core.annotation.PdfAnnotation
import org.lumina.reader.core.model.PageEditSpec
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class PdfDocumentExporterTest {

    private fun createSamplePdf(pageCount: Int): ByteArray {
        val doc = PDDocument()
        for (i in 0 until pageCount) {
            val page = PDPage(PDRectangle.A4)
            doc.addPage(page)
        }
        val out = ByteArrayOutputStream()
        doc.save(out)
        doc.close()
        return out.toByteArray()
    }

    @Test
    fun testExportPdfWithInkAnnotations() = runBlocking {
        val samplePdfBytes = createSamplePdf(2)

        val inkAnnot = PdfAnnotation.Ink(
            pageIndex = 0,
            color = 0xFFFF0000, // 红色
            strokeWidthDp = 4f,
            isHighlighter = false,
            strokes = listOf(
                listOf(
                    NormalizedPoint(0.1f, 0.1f),
                    NormalizedPoint(0.5f, 0.5f),
                    NormalizedPoint(0.9f, 0.9f)
                )
            )
        )

        val annotations = mapOf(0 to listOf(inkAnnot))
        val pageSpecs = listOf(PageEditSpec(0, 0), PageEditSpec(1, 0))

        val output = ByteArrayOutputStream()
        PdfDocumentExporter.exportPdf(
            inputStream = ByteArrayInputStream(samplePdfBytes),
            outputStream = output,
            annotations = annotations,
            pageSpecs = pageSpecs
        )

        val exportedBytes = output.toByteArray()
        assertTrue("Exported PDF should not be empty", exportedBytes.isNotEmpty())

        val verifyDoc = PDDocument.load(ByteArrayInputStream(exportedBytes))
        try {
            assertEquals(2, verifyDoc.numberOfPages)
            val page0 = verifyDoc.getPage(0)
            val annots = page0.annotations
            assertEquals(1, annots.size)
            val exportedAnnot = annots[0]
            assertEquals(PDAnnotationMarkup.SUB_TYPE_INK, exportedAnnot.subtype)
            val markup = exportedAnnot as PDAnnotationMarkup
            assertNotNull(markup.inkList)
            assertEquals(1, markup.inkList.size)
            assertEquals(6, markup.inkList[0].size) // 3 points * 2 coords = 6
        } finally {
            verifyDoc.close()
        }
    }

    @Test
    fun testExportPdfWithHighlighterOpacity() = runBlocking {
        val samplePdfBytes = createSamplePdf(1)

        val highlighterAnnot = PdfAnnotation.Ink(
            pageIndex = 0,
            color = 0xFFFFFF00, // 黄色
            strokeWidthDp = 12f,
            isHighlighter = true,
            strokes = listOf(
                listOf(
                    NormalizedPoint(0.2f, 0.3f),
                    NormalizedPoint(0.8f, 0.3f)
                )
            )
        )

        val output = ByteArrayOutputStream()
        PdfDocumentExporter.exportPdf(
            inputStream = ByteArrayInputStream(samplePdfBytes),
            outputStream = output,
            annotations = mapOf(0 to listOf(highlighterAnnot)),
            pageSpecs = emptyList()
        )

        val verifyDoc = PDDocument.load(ByteArrayInputStream(output.toByteArray()))
        try {
            val page = verifyDoc.getPage(0)
            val annot = page.annotations[0] as PDAnnotationMarkup
            assertEquals(0.45f, annot.constantOpacity, 0.01f)
        } finally {
            verifyDoc.close()
        }
    }

    @Test
    fun testExportPdfWithPageReorderingAndRotation() = runBlocking {
        // 创建 3 页具有不同尺寸的文档
        val doc = PDDocument()
        doc.addPage(PDPage(PDRectangle(300f, 400f)))
        doc.addPage(PDPage(PDRectangle(500f, 600f)))
        doc.addPage(PDPage(PDRectangle(700f, 800f)))
        val out = ByteArrayOutputStream()
        doc.save(out)
        doc.close()
        val samplePdfBytes = out.toByteArray()

        // 规范：删除第 1 页 (500x600)，保留第 2 页 (700x800, 旋转90度) 排第一，保留第 0 页 (300x400) 排第二
        val pageSpecs = listOf(
            PageEditSpec(originalPageIndex = 2, rotationDegrees = 90),
            PageEditSpec(originalPageIndex = 0, rotationDegrees = 0)
        )

        val exportOut = ByteArrayOutputStream()
        PdfDocumentExporter.exportPdf(
            inputStream = ByteArrayInputStream(samplePdfBytes),
            outputStream = exportOut,
            annotations = emptyMap(),
            pageSpecs = pageSpecs
        )

        val verifyDoc = PDDocument.load(ByteArrayInputStream(exportOut.toByteArray()))
        try {
            assertEquals(2, verifyDoc.numberOfPages)

            val newPage0 = verifyDoc.getPage(0)
            assertEquals(700f, newPage0.mediaBox.width, 0.1f)
            assertEquals(800f, newPage0.mediaBox.height, 0.1f)
            assertEquals(90, newPage0.rotation)

            val newPage1 = verifyDoc.getPage(1)
            assertEquals(300f, newPage1.mediaBox.width, 0.1f)
            assertEquals(400f, newPage1.mediaBox.height, 0.1f)
            assertEquals(0, newPage1.rotation)
        } finally {
            verifyDoc.close()
        }
    }

    @Test
    fun testExportPdfWithCustomTempDirAndScratchFile() = runBlocking {
        val samplePdfBytes = createSamplePdf(5)
        val tempFolder = java.io.File(System.getProperty("java.io.tmpdir"), "lumina_test_scratch_${System.currentTimeMillis()}")
        tempFolder.mkdirs()

        try {
            val pageSpecs = listOf(
                PageEditSpec(originalPageIndex = 4, rotationDegrees = 180),
                PageEditSpec(originalPageIndex = 2, rotationDegrees = 0),
                PageEditSpec(originalPageIndex = 0, rotationDegrees = 90)
            )

            val output = ByteArrayOutputStream()
            PdfDocumentExporter.exportPdf(
                inputStream = ByteArrayInputStream(samplePdfBytes),
                outputStream = output,
                annotations = emptyMap(),
                pageSpecs = pageSpecs,
                customTempDir = tempFolder
            )

            val exportedBytes = output.toByteArray()
            assertTrue(exportedBytes.isNotEmpty())

            val verifyDoc = PDDocument.load(ByteArrayInputStream(exportedBytes))
            try {
                assertEquals(3, verifyDoc.numberOfPages)
                assertEquals(180, verifyDoc.getPage(0).rotation)
                assertEquals(0, verifyDoc.getPage(1).rotation)
                assertEquals(90, verifyDoc.getPage(2).rotation)
            } finally {
                verifyDoc.close()
            }
        } finally {
            tempFolder.deleteRecursively()
        }
    }

    @Test
    fun testExportPdfWithRotatedPageAnnotations() = runBlocking {
        val samplePdfBytes = createSamplePdf(1)

        val inkAnnot = PdfAnnotation.Ink(
            pageIndex = 0,
            color = 0xFF0000FF, // 蓝色
            strokeWidthDp = 3f,
            isHighlighter = false,
            strokes = listOf(
                listOf(
                    NormalizedPoint(0.2f, 0.3f),
                    NormalizedPoint(0.8f, 0.7f)
                )
            )
        )

        // 旋转 90 度导出
        val pageSpecs = listOf(
            PageEditSpec(originalPageIndex = 0, rotationDegrees = 90)
        )

        val output = ByteArrayOutputStream()
        PdfDocumentExporter.exportPdf(
            inputStream = ByteArrayInputStream(samplePdfBytes),
            outputStream = output,
            annotations = mapOf(0 to listOf(inkAnnot)),
            pageSpecs = pageSpecs
        )

        val exportedBytes = output.toByteArray()
        assertTrue(exportedBytes.isNotEmpty())

        val verifyDoc = PDDocument.load(ByteArrayInputStream(exportedBytes))
        try {
            assertEquals(1, verifyDoc.numberOfPages)
            val page = verifyDoc.getPage(0)
            assertEquals(90, page.rotation)
            val annots = page.annotations
            assertEquals(1, annots.size)
            val markup = annots[0] as PDAnnotationMarkup
            assertEquals(PDAnnotationMarkup.SUB_TYPE_INK, markup.subtype)
            val coords = markup.inkList[0]
            assertEquals(4, coords.size) // 2 points * 2 coords = 4
            // 验证 90 度旋转后的坐标映射：xPdf = yNorm * width, yPdf = xNorm * height
            val expectedX0 = 0.3f * page.mediaBox.width
            val expectedY0 = 0.2f * page.mediaBox.height
            assertEquals(expectedX0, coords[0], 0.5f)
            assertEquals(expectedY0, coords[1], 0.5f)
        } finally {
            verifyDoc.close()
        }
    }
}
