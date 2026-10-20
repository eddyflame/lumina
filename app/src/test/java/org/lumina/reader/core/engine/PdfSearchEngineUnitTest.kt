package org.lumina.reader.core.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.lumina.reader.core.model.PageEditSpec
import org.lumina.reader.core.model.SearchMatch
import org.lumina.reader.core.model.SearchState

class PdfSearchEngineUnitTest {

    @Test
    fun testExtractSnippetShortText() {
        val engine = PdfSearchEngine()
        val text = "Short text with target keyword."
        val matchIndex = text.indexOf("target")
        // 当 contextRadius 足够大时，包含整段且首尾不带省略号
        val fullSnippet = engine.extractSnippet(text, matchIndex, "target".length, contextRadius = 50)
        assertEquals("Short text with target keyword.", fullSnippet)

        // 当 contextRadius 较小时，开头被截断应带有省略号
        val truncatedSnippet = engine.extractSnippet(text, matchIndex, "target".length, contextRadius = 8)
        assertTrue(truncatedSnippet.startsWith("…"))
        assertTrue(truncatedSnippet.contains("target"))
    }

    @Test
    fun testExtractSnippetLongTextTruncation() {
        val engine = PdfSearchEngine()
        val text = "Lorem ipsum dolor sit amet, consectetur adipiscing elit. Sed do eiusmod tempor incididunt ut labore et dolore magna aliqua. Ut enim ad minim veniam, quis nostrud exercitation ullamco laboris nisi ut aliquip ex ea commodo consequat."
        val matchIndex = text.indexOf("tempor")
        val snippet = engine.extractSnippet(text, matchIndex, "tempor".length, contextRadius = 20)

        assertTrue(snippet.contains("tempor"))
        assertTrue(snippet.startsWith("…"))
        assertTrue(snippet.endsWith("…"))
        assertFalse(snippet.contains("\n"))
    }

    @Test
    fun testExtractSnippetWhitespaceSanitization() {
        val engine = PdfSearchEngine()
        val text = "First line\r\n\tSecond line with\n\n\n\t\t\ttarget\nkeyword\rhere."
        val matchIndex = text.indexOf("target")
        val snippet = engine.extractSnippet(text, matchIndex, "target".length, contextRadius = 30)

        assertTrue(snippet.contains("target"))
        assertFalse(snippet.contains("\n"))
        assertFalse(snippet.contains("\r"))
        assertFalse(snippet.contains("\t"))
        assertFalse(snippet.contains("  ")) // No double spaces
    }

    @Test
    fun testSearchStateProperties() {
        val emptyState = SearchState()
        assertFalse(emptyState.isOpen)
        assertEquals("", emptyState.query)
        assertFalse(emptyState.isSearching)
        assertEquals(0, emptyState.totalMatches)
        assertEquals(0, emptyState.displayMatchIndex)
        assertNull(emptyState.currentMatch)

        val match1 = SearchMatch(
            pageIndex = 0,
            originalPageIndex = 0,
            matchIndexInPage = 0,
            globalMatchIndex = 0,
            snippet = "First snippet with keyword",
            query = "keyword"
        )
        val match2 = SearchMatch(
            pageIndex = 2,
            originalPageIndex = 2,
            matchIndexInPage = 0,
            globalMatchIndex = 1,
            snippet = "Second snippet with keyword",
            query = "keyword"
        )

        val activeState = SearchState(
            isOpen = true,
            query = "keyword",
            matches = listOf(match1, match2),
            currentMatchIndex = 0,
            scannedPageCount = 3,
            totalPageCount = 3
        )

        assertEquals(2, activeState.totalMatches)
        assertEquals(1, activeState.displayMatchIndex)
        assertEquals(match1, activeState.currentMatch)

        val navigatedState = activeState.copy(currentMatchIndex = 1)
        assertEquals(2, navigatedState.displayMatchIndex)
        assertEquals(match2, navigatedState.currentMatch)
    }

    @Test
    fun testBuildSearchRegexCjkWhitespaceTolerant() {
        val regex = PdfSearchEngine.buildSearchRegex("离职证明")
        assertNotNull(regex)

        // Exact match
        assertTrue(regex!!.containsMatchIn("特此开具离职证明。"))
        // Match with spaces inserted between characters
        assertTrue(regex.containsMatchIn("特此开具 离 职 证 明 。"))
        // Match with multiple spaces
        assertTrue(regex.containsMatchIn("特此开具 离   职   证   明 。"))
        // Match with newlines
        assertTrue(regex.containsMatchIn("特此开具\n离\n职\n证\n明\n。"))
    }

    @Test
    fun testBuildSearchRegexUserPastedSpaces() {
        // When user types or pastes "离 职 证 明"
        val regex = PdfSearchEngine.buildSearchRegex("离 职 证 明")
        assertNotNull(regex)

        // Matches continuous text
        assertTrue(regex!!.containsMatchIn("特此开具离职证明。"))
        // Matches text with spaces
        assertTrue(regex.containsMatchIn("特此开具离 职 证 明。"))
    }

    @Test
    fun testBuildSearchRegexEnglishAndSpecialChars() {
        val regex = PdfSearchEngine.buildSearchRegex("SO hook")
        assertNotNull(regex)
        assertTrue(regex!!.containsMatchIn("Native SO hook implementation"))
        assertTrue(regex.containsMatchIn("Native SO\nhook implementation"))
        assertTrue(regex.containsMatchIn("Native SO   hook implementation"))

        val regexCpp = PdfSearchEngine.buildSearchRegex("C++")
        assertNotNull(regexCpp)
        assertTrue(regexCpp!!.containsMatchIn("Modern C++20 standard"))
    }

    @Test
    fun testBuildSearchRegexCjkMixedWithNumbers() {
        val regex = PdfSearchEngine.buildSearchRegex("第1章")
        assertNotNull(regex)
        assertTrue(regex!!.containsMatchIn("阅读第1章内容"))
        assertTrue(regex.containsMatchIn("阅读第 1 章内容"))
    }

    @Test
    fun testExtractHighlightRectsCoordinateAccuracy() {
        val engine = PdfSearchEngine()
        val pageWidth = 600f
        val pageHeight = 800f

        // 模拟页面第 1 行的字符: "target" (x=120..180, baseline y=100, height=12)
        // PDF 坐标系下，原点在左下角，y=700 对应屏幕顶部向下 100
        fun makeTp(x: Float, yPdf: Float, w: Float, h: Float, ch: String): com.tom_roush.pdfbox.text.TextPosition {
            val matrix = com.tom_roush.pdfbox.util.Matrix.getTranslateInstance(x, yPdf)
            return com.tom_roush.pdfbox.text.TextPosition(
                0, pageWidth, pageHeight, matrix, x + w, yPdf, h, w, 5f, ch, intArrayOf(ch[0].code), null, h, h.toInt()
            )
        }

        val chars = listOf(
            PositionedChar('t', makeTp(120f, 700f, 10f, 12f, "t")),
            PositionedChar('a', makeTp(130f, 700f, 10f, 12f, "a")),
            PositionedChar('r', makeTp(140f, 700f, 10f, 12f, "r")),
            PositionedChar('g', makeTp(150f, 700f, 10f, 12f, "g")),
            PositionedChar('e', makeTp(160f, 700f, 10f, 12f, "e")),
            PositionedChar('t', makeTp(170f, 700f, 10f, 12f, "t")),
            PositionedChar(' ', null),
            PositionedChar('\n', null)
        )

        val rects = engine.extractHighlightRects(
            chars = chars,
            startCharIndex = 0,
            endCharIndex = 6,
            pageWidth = pageWidth,
            pageHeight = pageHeight
        )

        assertEquals(1, rects.size)
        val rect = rects[0]

        // 验证屏幕坐标：
        // 包含少许水平留白 hPad (约 1.3pt)
        assertTrue("Left should be <= 0.20, actual: ${rect.left}", rect.left <= 0.20f)
        assertTrue("Right should be >= 0.30, actual: ${rect.right}", rect.right >= 0.30f)

        // 顶部 (baseline 100 - effectiveFontHeight 16.2 * 0.88) ≈ 85.7 / 800 ≈ 0.107
        // 底部 (baseline 100 + effectiveFontHeight 16.2 * 0.20) ≈ 103.2 / 800 ≈ 0.129
        assertTrue("Top should be around 0.10..0.12, actual: ${rect.top}", rect.top in 0.09f..0.12f)
        assertTrue("Bottom should be around 0.12..0.14, actual: ${rect.bottom}", rect.bottom in 0.12f..0.14f)
        assertTrue("Rect should be valid", rect.isValid)
    }

    @Test
    fun testExtractHighlightRectsMultiLineGrouping() {
        val engine = PdfSearchEngine()
        val pageWidth = 500f
        val pageHeight = 1000f

        fun makeTp(x: Float, yPdf: Float, w: Float, h: Float, ch: String): com.tom_roush.pdfbox.text.TextPosition {
            val matrix = com.tom_roush.pdfbox.util.Matrix.getTranslateInstance(x, yPdf)
            return com.tom_roush.pdfbox.text.TextPosition(
                0, pageWidth, pageHeight, matrix, x + w, yPdf, h, w, 5f, ch, intArrayOf(ch[0].code), null, h, h.toInt()
            )
        }

        // 跨行搜索命中项："离职" (行 1) + "\n" + "证明" (行 2)
        val chars = listOf(
            PositionedChar('离', makeTp(400f, 800f, 20f, 16f, "离")), // 行 1 屏幕 baseline = 200
            PositionedChar('职', makeTp(420f, 800f, 20f, 16f, "职")),
            PositionedChar('\n', null),
            PositionedChar('证', makeTp(50f, 750f, 20f, 16f, "证")),  // 行 2 屏幕 baseline = 250
            PositionedChar('明', makeTp(70f, 750f, 20f, 16f, "明"))
        )

        val rects = engine.extractHighlightRects(
            chars = chars,
            startCharIndex = 0,
            endCharIndex = 5,
            pageWidth = pageWidth,
            pageHeight = pageHeight
        )

        // 应智能拆分为两个不同行的矩形
        assertEquals(2, rects.size)

        // 第 1 行矩形在页面右上方
        val rect1 = rects[0]
        assertTrue(rect1.left <= 400f / 500f)
        assertTrue(rect1.right >= 440f / 500f)

        // 第 2 行矩形在页面左下方
        val rect2 = rects[1]
        assertTrue(rect2.left <= 50f / 500f)
        assertTrue(rect2.right >= 90f / 500f)

        // 第 2 行 top 应该大于第 1 行 bottom
        assertTrue(rect2.top > rect1.bottom)
    }

    @Test
    fun testExtractHighlightRectsTitleVsBodyFontSize() {
        val engine = PdfSearchEngine()
        val pageWidth = 600f
        val pageHeight = 1000f

        fun makeTp(x: Float, yPdf: Float, w: Float, fontSize: Float, ch: String): com.tom_roush.pdfbox.text.TextPosition {
            val matrix = com.tom_roush.pdfbox.util.Matrix.getTranslateInstance(x, yPdf)
            return com.tom_roush.pdfbox.text.TextPosition(
                0, pageWidth, pageHeight, matrix, x + w, yPdf, fontSize * 0.5f, w, 5f, ch,
                intArrayOf(ch[0].code), null, fontSize, fontSize.toInt()
            )
        }

        // 大标题 (28pt)
        val titleChars = listOf(
            PositionedChar('T', makeTp(100f, 800f, 20f, 28f, "T")),
            PositionedChar('i', makeTp(120f, 800f, 10f, 28f, "i")),
            PositionedChar('t', makeTp(130f, 800f, 15f, 28f, "t")),
            PositionedChar('l', makeTp(145f, 800f, 10f, 28f, "l")),
            PositionedChar('e', makeTp(155f, 800f, 15f, 28f, "e"))
        )

        // 正文小字 (12pt)
        val bodyChars = listOf(
            PositionedChar('B', makeTp(100f, 600f, 8f, 12f, "B")),
            PositionedChar('o', makeTp(108f, 600f, 8f, 12f, "o")),
            PositionedChar('d', makeTp(116f, 600f, 8f, 12f, "d")),
            PositionedChar('y', makeTp(124f, 600f, 8f, 12f, "y"))
        )

        val titleRects = engine.extractHighlightRects(titleChars, 0, 5, pageWidth, pageHeight)
        val bodyRects = engine.extractHighlightRects(bodyChars, 0, 4, pageWidth, pageHeight)

        assertEquals(1, titleRects.size)
        assertEquals(1, bodyRects.size)

        val titleHeight = titleRects[0].height * pageHeight
        val bodyHeight = bodyRects[0].height * pageHeight

        // 大标题的高亮高度应该显著大于正文高亮高度 (28pt vs 12pt, 比例约为 2.33 倍)
        assertTrue(
            "Title highlight height ($titleHeight) must be significantly larger than body height ($bodyHeight)",
            titleHeight > bodyHeight * 2.0f
        )
    }

    @Test
    fun testSearchExamplePdf() = kotlinx.coroutines.runBlocking {
        val pdfFile = java.io.File("../example/search.pdf").canonicalFile
        org.junit.Assume.assumeTrue("example/search.pdf not present, skipping integration test", pdfFile.exists())

        val engine = PdfSearchEngine()
        val doc = com.tom_roush.pdfbox.pdmodel.PDDocument.load(pdfFile)
        val pageCount = doc.numberOfPages
        doc.close()

        val matches = engine.search(
            inputStreamProvider = { pdfFile.inputStream() },
            query = "梅苑",
            pageSpecs = emptyList(),
            pageCount = pageCount
        )

        assertEquals(3, matches.size)

        // 验证第 1 个匹配项 (页码 26 / 第 27 页：红梅苑)
        val m1 = matches[0]
        assertEquals(26, m1.pageIndex)
        assertTrue("Snippet must contain 红梅苑: ${m1.snippet}", m1.snippet.contains("红梅苑"))
        assertFalse("Snippet must not contain previous line 恒盛居: ${m1.snippet}", m1.snippet.contains("恒盛居"))
        assertEquals(1, m1.highlightRects.size)
        val r1 = m1.highlightRects[0]
        assertTrue("Highlight height (${r1.height}) must be a single-line height (< 0.02)", r1.height < 0.02f)
        assertTrue("Highlight top (${r1.top}) must accurately wrap row 1123 around 0.35-0.37", r1.top in 0.35f..0.37f)

        // 验证第 2 个匹配项 (页码 27 / 第 28 页：振业梅苑)
        val m2 = matches[1]
        assertEquals(27, m2.pageIndex)
        assertTrue("Snippet must contain 振业梅苑: ${m2.snippet}", m2.snippet.contains("振业梅苑"))
        assertEquals(1, m2.highlightRects.size)
        val r2 = m2.highlightRects[0]
        assertTrue("Highlight height (${r2.height}) must be a single-line height (< 0.02)", r2.height < 0.02f)
        assertTrue("Highlight top (${r2.top}) must accurately wrap row 1190 around 0.80-0.83", r2.top in 0.80f..0.83f)

        // 验证第 3 个匹配项 (页码 49 / 第 50 页：松坪村梅苑)
        val m3 = matches[2]
        assertEquals(49, m3.pageIndex)
        assertTrue("Snippet must contain 松坪村梅苑: ${m3.snippet}", m3.snippet.contains("松坪村梅苑"))
        assertEquals(1, m3.highlightRects.size)
        val r3 = m3.highlightRects[0]
        assertTrue("Highlight height (${r3.height}) must be a single-line height (< 0.02)", r3.height < 0.02f)
        assertTrue("Highlight top (${r3.top}) must accurately wrap row 2115 around 0.40-0.43", r3.top in 0.40f..0.43f)
    }

    @Test
    fun testAllPagesTextAndPositionsAlignment() = kotlinx.coroutines.runBlocking {
        val pdfFile = java.io.File("../example/search.pdf").canonicalFile
        org.junit.Assume.assumeTrue(pdfFile.exists())

        val pdDoc = com.tom_roush.pdfbox.pdmodel.PDDocument.load(pdfFile)
        val engine = PdfSearchEngine()

        val matches = engine.search(
            inputStreamProvider = { pdfFile.inputStream() },
            query = "梅苑",
            pageSpecs = emptyList(),
            pageCount = pdDoc.numberOfPages
        )

        var totalDiscrepancies = 0
        for (m in matches) {
            println("Page ${m.pageIndex} found match: snippet=${m.snippet}, rects=${m.highlightRects.size}")
            for (r in m.highlightRects) {
                println("  Rect: $r, width=${r.width}")
                if (r.width > 0.1f) {
                    println("  WARNING: unusually wide rect on page ${m.pageIndex}! width=${r.width}")
                    totalDiscrepancies++
                }
            }
        }
        pdDoc.close()
        assertEquals(0, totalDiscrepancies)
    }

    @Test
    fun testSearchJinXiu() = kotlinx.coroutines.runBlocking {
        val pdfFile = java.io.File("../example/search.pdf").canonicalFile
        org.junit.Assume.assumeTrue(pdfFile.exists())

        val pdDoc = com.tom_roush.pdfbox.pdmodel.PDDocument.load(pdfFile)
        val engine = PdfSearchEngine()

        val matches = engine.search(
            inputStreamProvider = { pdfFile.inputStream() },
            query = "锦绣",
            pageSpecs = emptyList(),
            pageCount = pdDoc.numberOfPages
        )

        println("==== Total matches found for 锦绣: ${matches.size} ====")
        for (m in matches) {
            println("Page ${m.pageIndex} (PDF page ${m.pageIndex + 1}): snippet='${m.snippet}', start=${m.startCharIndex}, end=${m.endCharIndex}, rects=${m.highlightRects.size}")
            for (r in m.highlightRects) {
                println("   Rect: left=${r.left}, top=${r.top}, right=${r.right}, bottom=${r.bottom}, w=${r.width}, h=${r.height}")
            }
        }

        pdDoc.close()
    }

    @Test
    fun testSecondSearchHasHighlights() = kotlinx.coroutines.runBlocking {
        val pdfFile = java.io.File("../example/search.pdf").canonicalFile
        org.junit.Assume.assumeTrue(pdfFile.exists())

        val pdDoc = com.tom_roush.pdfbox.pdmodel.PDDocument.load(pdfFile)
        val engine = PdfSearchEngine()

        // 首次搜索
        val firstMatches = engine.search(
            inputStreamProvider = { pdfFile.inputStream() },
            query = "梅苑",
            pageSpecs = emptyList(),
            pageCount = pdDoc.numberOfPages
        )
        println("After first search:")
        val cache = engine.getPageDataCache()
        for (i in 0 until pdDoc.numberOfPages) {
            val data = cache[i]
            if (data != null) {
                if (data.chars.isEmpty()) {
                    println("  Page $i chars is EMPTY!")
                } else {
                    println("  Page $i chars size: ${data.chars.size}")
                }
            } else {
                println("  Page $i not in cache!")
            }
        }

        // 第二次搜索（不同词语）
        val secondMatches = engine.search(
            inputStreamProvider = { pdfFile.inputStream() },
            query = "锦绣",
            pageSpecs = emptyList(),
            pageCount = pdDoc.numberOfPages
        )
        println("Second search matches count: ${secondMatches.size}")
        assertTrue("Second search should find matches", secondMatches.isNotEmpty())
        for (m in secondMatches) {
            println("Second match page ${m.pageIndex} rects count: ${m.highlightRects.size}")
            assertTrue("Second search match on page ${m.pageIndex} must have highlight rects!", m.highlightRects.isNotEmpty())
        }

        // 第三次搜索（大词汇量）
        val thirdMatches = engine.search(
            inputStreamProvider = { pdfFile.inputStream() },
            query = "花园",
            pageSpecs = emptyList(),
            pageCount = pdDoc.numberOfPages
        )
        println("Third search (花园) matches count: ${thirdMatches.size}")
        assertTrue("Third search should find many matches", thirdMatches.size > 50)
        for (m in thirdMatches) {
            assertTrue("Match on page ${m.pageIndex} must have rects", m.highlightRects.isNotEmpty())
        }

        // 第四次搜索（重新搜第一个词，对比坐标完全一致）
        val fourthMatches = engine.search(
            inputStreamProvider = { pdfFile.inputStream() },
            query = "梅苑",
            pageSpecs = emptyList(),
            pageCount = pdDoc.numberOfPages
        )
        assertEquals("Fourth search matches count must equal first search", firstMatches.size, fourthMatches.size)
        for (i in firstMatches.indices) {
            assertEquals("Page index must match", firstMatches[i].pageIndex, fourthMatches[i].pageIndex)
            assertEquals("Rects count must match", firstMatches[i].highlightRects.size, fourthMatches[i].highlightRects.size)
            for (j in firstMatches[i].highlightRects.indices) {
                val r1 = firstMatches[i].highlightRects[j]
                val r4 = fourthMatches[i].highlightRects[j]
                assertEquals("Rect left must match", r1.left, r4.left, 0.0001f)
                assertEquals("Rect top must match", r1.top, r4.top, 0.0001f)
                assertEquals("Rect right must match", r1.right, r4.right, 0.0001f)
                assertEquals("Rect bottom must match", r1.bottom, r4.bottom, 0.0001f)
            }
        }
        println("Fourth search verified 100% identical to first search!")

        pdDoc.close()
    }
}





