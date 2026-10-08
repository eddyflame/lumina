package org.lumina.reader.core.engine

import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageFitWidthDestination
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDDocumentOutline
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class PdfOutlineExtractorTest {

    @Test
    fun testExtractEmptyOutlines() {
        val doc = PDDocument()
        doc.addPage(PDPage())
        val out = ByteArrayOutputStream()
        doc.save(out)
        doc.close()

        val outlines = PdfOutlineExtractor.extractOutlines(ByteArrayInputStream(out.toByteArray()))
        assertTrue("Document without outlines should return empty list", outlines.isEmpty())
    }

    @Test
    fun testExtractNestedOutlines() {
        val doc = PDDocument()
        val page0 = PDPage()
        val page1 = PDPage()
        val page2 = PDPage()
        doc.addPage(page0)
        doc.addPage(page1)
        doc.addPage(page2)

        val outline = PDDocumentOutline()
        doc.documentCatalog.documentOutline = outline

        // 顶层书签 Chapter 1 -> Page 0
        val chapter1 = PDOutlineItem().apply {
            title = "Chapter 1: Getting Started"
            destination = PDPageFitWidthDestination().apply { page = page0 }
        }
        outline.addLast(chapter1)

        // 二级书签 Section 1.1 -> Page 1
        val section11 = PDOutlineItem().apply {
            title = "Section 1.1: Installation"
            destination = PDPageFitWidthDestination().apply { page = page1 }
        }
        chapter1.addLast(section11)

        // 顶层书签 Chapter 2 -> Page 2
        val chapter2 = PDOutlineItem().apply {
            title = "Chapter 2: Advanced Topics"
            destination = PDPageFitWidthDestination().apply { page = page2 }
        }
        outline.addLast(chapter2)

        val out = ByteArrayOutputStream()
        doc.save(out)
        doc.close()

        val outlines = PdfOutlineExtractor.extractOutlines(ByteArrayInputStream(out.toByteArray()))
        assertEquals(3, outlines.size)

        assertEquals("Chapter 1: Getting Started", outlines[0].title)
        assertEquals(0, outlines[0].pageIndex)
        assertEquals(0, outlines[0].level)

        assertEquals("Section 1.1: Installation", outlines[1].title)
        assertEquals(1, outlines[1].pageIndex)
        assertEquals(1, outlines[1].level)

        assertEquals("Chapter 2: Advanced Topics", outlines[2].title)
        assertEquals(2, outlines[2].pageIndex)
        assertEquals(0, outlines[2].level)
    }
}
