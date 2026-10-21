package org.lumina.reader.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.lumina.reader.data.db.RecentDocument

class RecentDocumentTest {

    @Test
    fun testDefaultDocumentIsValid() {
        val doc = RecentDocument(
            uriString = "content://com.android.providers.media.documents/document/123",
            title = "Test.pdf",
            pageCount = 10,
            lastReadPage = 2,
            progressPercent = 0.3f,
            lastReadTime = System.currentTimeMillis(),
            isPinned = false
        )

        assertTrue("Document should default to valid", doc.isValid)
        assertFalse("Document should not be pinned by default", doc.isPinned)
        assertEquals("Test.pdf", doc.title)
    }

    @Test
    fun testExplicitInvalidDocument() {
        val doc = RecentDocument(
            uriString = "content://com.android.providers.media.documents/document/456",
            title = "Deleted.pdf",
            pageCount = 5,
            lastReadPage = 0,
            progressPercent = 0.2f,
            lastReadTime = System.currentTimeMillis(),
            isPinned = true,
            isValid = false
        )

        assertFalse("Document should be marked invalid", doc.isValid)
        assertTrue("Pinned state should be retained even if invalid", doc.isPinned)
    }

    @Test
    fun testCopyValidity() {
        val validDoc = RecentDocument(
            uriString = "file:///storage/emulated/0/Download/sample.pdf",
            title = "sample.pdf",
            pageCount = 20,
            lastReadPage = 5,
            progressPercent = 0.3f,
            lastReadTime = 1000L,
            isValid = true
        )

        val invalidatedDoc = validDoc.copy(isValid = false)
        assertFalse(invalidatedDoc.isValid)
        assertEquals(validDoc.uriString, invalidatedDoc.uriString)
        assertEquals(validDoc.title, invalidatedDoc.title)

        val restoredDoc = invalidatedDoc.copy(isValid = true)
        assertTrue(restoredDoc.isValid)
    }

    @Test
    fun testProgressAndFormatOnInvalidDoc() {
        val doc = RecentDocument(
            uriString = "content://dummy",
            title = "dummy.pdf",
            pageCount = 10,
            lastReadPage = 4,
            progressPercent = 0.5f,
            lastReadTime = System.currentTimeMillis(),
            isValid = false
        )

        assertTrue(doc.progressFormatted.contains("50%"))
        assertFalse(doc.isValid)
    }
}
